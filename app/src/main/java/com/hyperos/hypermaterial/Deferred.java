package com.hyperos.hypermaterial;

import android.os.Bundle;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 把「会触发宿主类 &lt;clinit&gt;」的操作推迟到应用完全就绪之后。
 *
 * <p>v1.2 闪退根因：onPackageReady 发生在 ActivityThread.handleBindApplication 的
 * createAppContext 阶段，此时 Application.onCreate 还没跑，桌面的
 * ContextProvider._appContext 仍是未赋值的 lateinit。v1.2 用
 * {@code Class.forName(name, true, loader)} 加载 BlurUtilities，立刻触发它的
 * {@code <clinit>}：</p>
 *
 * <pre>
 * BlurUtilities.&lt;clinit&gt; (BlurUtilities.java:119)
 *   → DimenUtils.&lt;clinit&gt; (DimenUtils.java:11)
 *   → ContextProvider.getAppContext() (ContextProvider.kt:11)
 *   → kotlin.UninitializedPropertyAccessException: lateinit property _appContext has not been initialized
 *   → ExceptionInInitializerError
 * </pre>
 *
 * <p>ART 会把初始化失败的类永久标记为 erroneous，之后任何访问都直接抛
 * {@code NoClassDefFoundError: com.miui.home.common.utils.BlurUtilities}，
 * 桌面因此在 handleBindApplication 里无限重启闪退。</p>
 *
 * <p>v1.3 对策：所有宿主类一律 {@code Class.forName(name, false, loader)} 只加载不初始化；
 * getstatic / putstatic 必然触发 &lt;clinit&gt;，所以静态字段读写统一登记到这里，
 * 等第一个 Activity 起来后再执行。方法 Hook 与 deoptimize 不触发类初始化，
 * 仍在早期安装 —— 即使延迟触发点全部失效，放行逻辑也不会退化成「完全没作用」，
 * 更不会让宿主崩溃。</p>
 *
 * <p>为什么不用 Application.onCreate 作为触发点：桌面的 ContextProvider 有可能在
 * Application.onCreate 之后才赋值，而 &lt;clinit&gt; 失败是<strong>不可逆</strong>的
 * （类会被永久标记 erroneous，重试也没用）。Activity.onCreate 是更晚、更保险的时机：
 * 此时 Application 与全局 Context 必然就绪，宿主自己的 &lt;clinit&gt; 若会被触发也早已
 * 正常完成，我们再去写字段完全安全。字段注入只是防 ART 内联的补充，晚一点不影响效果。</p>
 */
final class Deferred {

    private static final List<Runnable> TASKS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean ARMED = new AtomicBoolean(false);
    private static final AtomicBoolean FIRED = new AtomicBoolean(false);

    private Deferred() {
    }

    /** 登记一个必须在应用就绪后才能跑的任务。 */
    static void post(XposedModule module, String pkg, Runnable task) {
        if (task == null) {
            return;
        }
        if (FIRED.get()) {
            // 已经就绪过，直接跑（此时触发 <clinit> 是安全的）
            runSafely(module, task);
            return;
        }
        TASKS.add(task);
        arm(module, pkg);
    }

    /** 供 ModuleMain 兜底调用：即使所有 install 都跳过，也保证触发点已就位。幂等。 */
    static void arm(XposedModule module, String pkg) {
        if (!ARMED.compareAndSet(false, true)) {
            return;
        }
        // 主触发点：第一个 Activity 创建完成（Application / 全局 Context 必然就绪）
        hookAfter(module, "android.app.Activity", "onCreate", new Class<?>[]{Bundle.class});
        // late inject 兜底：模块载入时 Application 已存在，直接执行
        if (applicationReady()) {
            fire(module);
        }
        // 零依赖兜底：主线程延时触发，完全不依赖上面的 framework Hook 是否成功。
        // 若 Hook 成功，FIRED 会先被置位，定时器再调 fire 会被 compareAndSet 直接挡掉。
        try {
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> {
                        if (applicationReady()) {
                            fire(module);
                        } else {
                            Diag.once(module, "defer:timer:notready",
                                    "延迟兜底定时器：Application 仍未就绪，跳过本轮");
                        }
                    }, 3000);
            Diag.once(module, "defer:timer", "延迟兜底定时器已设置（3 秒）");
        } catch (Throwable t) {
            Diag.w(module, "延迟兜底定时器设置失败", t);
        }
        Diag.i(module, "延迟执行器已武装（pkg=" + pkg + "，待执行 " + TASKS.size()
                + " 项；静态字段注入将在 Activity 起来后执行）");
    }

    private static void hookAfter(XposedModule module, String cls, String name,
                                  Class<?>[] params) {
        try {
            Class<?> c = Class.forName(cls, false, null);
            Method m = c.getDeclaredMethod(name, params);
            module.hook(m)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object r = chain.proceed();   // 先让 Activity 初始化完成
                        fire(module);
                        return r;
                    });
            Diag.once(module, "defer:hook:" + cls + name,
                    "延迟触发点已装 " + cls + "." + name);
        } catch (Throwable t) {
            Diag.w(module, "延迟触发点安装失败 " + cls + "." + name, t);
        }
    }

    private static boolean applicationReady() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread", false, null);
            Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            return m.invoke(null) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void fire(XposedModule module) {
        if (!FIRED.compareAndSet(false, true)) {
            return;
        }
        Diag.i(module, "★ 应用已就绪，执行延迟任务 " + TASKS.size() + " 项（静态字段注入；Application="
                + (applicationReady() ? "已创建" : "未创建") + "）");
        for (Runnable r : TASKS) {
            runSafely(module, r);
        }
        TASKS.clear();
    }

    private static void runSafely(XposedModule module, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            // 单个任务失败必须隔离，绝不能把异常抛回宿主
            Diag.w(module, "延迟任务执行失败（已隔离，不影响宿主）", t);
        }
    }
}
