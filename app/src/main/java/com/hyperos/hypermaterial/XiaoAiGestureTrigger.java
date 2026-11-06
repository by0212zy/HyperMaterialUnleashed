package com.hyperos.hypermaterial;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * 把「长按手势提示线 → 超级小爱识屏」暴露成一条可被其它 App 调用的 shell 命令。
 *
 * <p>反编译 com.miui.home 3.0.3 得到的真实链路：</p>
 * <pre>
 * NavStubGestureEventManager.onLongPress()
 *   → handleLongPressEvent() → doAction(2)
 *   → findGestureTargetConfig(2)：从 NavShortcutConfigManager.INSTANCE 读系统设置
 *   → doActionInternal(2) → launchService(...)
 *   → buildIntent()：new Intent("android.intent.action.ASSIST")
 *       .setClassName("com.miui.voiceassist", "com.xiaomi.voiceassistant.VoiceService")
 *       + triggerType / triggerFrom / voice_assist_start_from_key / function 参数
 *   → Application.getInstance().startForegroundService(intent)
 * </pre>
 *
 * <p>因此这里不硬编码任何 Intent 参数：收到广播直接反射调用桌面的
 * handleLongPressEvent()，等价于用户长按了一次手势提示线，天然跟随系统里
 * 「长按手势提示线 = 超级小爱识屏」的设置，也不会随 ROM 版本改动而失效。</p>
 *
 * <p>所有反射都只在广播到达时执行（此时 Application / 全局 Context 早已就绪），
 * 绝不在 onPackageReady 阶段触发宿主类的 &lt;clinit&gt; —— 这是 v1.2 桌面
 * ExceptionInInitializerError 无限闪退的根因，必须避免。</p>
 *
 * <p>Android 13+ 动态注册的接收器默认 NOT_EXPORTED，跨应用广播收不到，
 * 所以必须显式传 {@link Context#RECEIVER_EXPORTED}。</p>
 */
final class XiaoAiGestureTrigger {

    /** 主 action：完全等价长按手势提示线（跟随系统设置）。 */
    static final String ACTION_LONG_PRESS =
            "com.hyperos.hypermaterial.action.GESTURE_LINE_LONG_PRESS";

    /** 别名 action：强制走「超级小爱识屏」，不依赖系统里长按手势提示线的设置。 */
    static final String ACTION_SCREEN_RECOGNIZE =
            "com.hyperos.hypermaterial.action.XIAOAI_SCREEN_RECOGNIZE";

    /** 可选 extra：来源标记，仅写进日志，便于排查是哪个 App 触发的。 */
    static final String EXTRA_FROM = "from";

    /** 可选 extra：值为 {@link #TARGET_SCREEN_RECOGNIZE} 时强制识屏。 */
    static final String EXTRA_TARGET = "target";
    static final String TARGET_SCREEN_RECOGNIZE = "screen_recognize";

    private static final String CLS_NAV_MANAGER =
            "com.miui.home.recents.gesture.NavStubGestureEventManager";
    private static final String CLS_CONFIG_MANAGER =
            "com.miui.home.recents.gesture.NavShortcutConfigManager";

    private static final String VOICE_PKG = "com.miui.voiceassist";
    private static final String VOICE_SERVICE = "com.xiaomi.voiceassistant.VoiceService";

    private static volatile Object navManager;
    private static volatile boolean registered;

    private XiaoAiGestureTrigger() {
    }

    /** 由 {@link Deferred} 在应用就绪后调用；仅桌面主进程注册一次。 */
    static void install(final XposedModule module, final ClassLoader loader, String pkg) {
        if (registered) {
            return;
        }
        final Context app = application();
        if (app == null) {
            Diag.w(module, "识屏广播：拿不到 Application Context，入口未注册", null);
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_LONG_PRESS);
        filter.addAction(ACTION_SCREEN_RECOGNIZE);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                // 广播回调必须完全隔离异常，绝不能把异常抛回桌面主线程
                try {
                    handle(module, loader, i);
                } catch (Throwable t) {
                    Diag.e(module, "识屏广播处理异常（已隔离）", t);
                }
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
            registered = true;
            Diag.i(module, "★ 识屏广播接收器已注册（exported，pkg=" + pkg
                    + "，action=" + ACTION_LONG_PRESS + " / " + ACTION_SCREEN_RECOGNIZE + "）");
        } catch (Throwable t) {
            Diag.w(module, "识屏广播接收器注册失败", t);
        }
    }

    private static void handle(XposedModule module, ClassLoader loader, Intent i) {
        String action = i == null ? "null" : i.getAction();
        String from = i == null ? null : i.getStringExtra(EXTRA_FROM);
        String target = i == null ? null : i.getStringExtra(EXTRA_TARGET);
        Diag.i(module, "★ 收到识屏广播 action=" + action
                + " from=" + from + " target=" + target);

        boolean force = ACTION_SCREEN_RECOGNIZE.equals(action)
                || TARGET_SCREEN_RECOGNIZE.equals(target);

        // 1) 默认路径：完全等价长按手势提示线，跟随系统设置
        if (!force && longPress(module, loader)) {
            return;
        }
        // 2) 强制识屏：从系统配置里取「识屏」项，让桌面自己构造 Intent
        if (force && screenRecognize(module, loader)) {
            return;
        }
        // 3) 前两步都失败时再走兜底
        if (force && longPress(module, loader)) {
            return;
        }
        fallback(module);
    }

    /** 反射调用 NavStubGestureEventManager.handleLongPressEvent()。 */
    private static boolean longPress(XposedModule module, ClassLoader loader) {
        try {
            Object m = manager(loader);
            if (m == null) {
                Diag.w(module, "长按入口：找不到 NavStubGestureEventManager", null);
                return false;
            }
            Method h = Host.method(m.getClass(), "handleLongPressEvent");
            if (h == null) {
                Diag.w(module, "长按入口：找不到 handleLongPressEvent()", null);
                return false;
            }
            h.setAccessible(true);
            h.invoke(m);
            Diag.i(module, "★ 已调用 handleLongPressEvent()（等价长按手势提示线）");
            return true;
        } catch (Throwable t) {
            Diag.w(module, "长按入口调用失败，转下一级", t);
            return false;
        }
    }

    /**
     * 强制识屏：从 NavShortcutConfigManager 的配置列表里找 entityType 为
     * VoiceAssistantScreenRecognizer 的项，用桌面的 buildIntent() 构造 Intent 后启动服务。
     * 参数全部来自系统配置，不硬编码，兼容不同 ROM 的 function key。
     */
    private static boolean screenRecognize(XposedModule module, ClassLoader loader) {
        try {
            Class<?> cm = Host.find(loader, CLS_CONFIG_MANAGER);
            Object m = manager(loader);
            if (cm == null || m == null) {
                return false;
            }
            Field singletonField = cm.getDeclaredField("INSTANCE");
            singletonField.setAccessible(true);
            Object singleton = singletonField.get(null);
            Field listField = Host.field(cm, "gestureConfigItemList");
            if (listField == null) {
                return false;
            }
            listField.setAccessible(true);
            Object list = listField.get(singleton);
            if (!(list instanceof List)) {
                return false;
            }
            Object item = null;
            for (Object o : (List<?>) list) {
                if (o == null) {
                    continue;
                }
                Method et = Host.method(o.getClass(), "getEntityType");
                if (et == null) {
                    continue;
                }
                et.setAccessible(true);
                Object v = et.invoke(o);
                if (v != null && String.valueOf(v).contains("ScreenRecognizer")) {
                    item = o;
                    break;
                }
            }
            if (item == null) {
                Diag.w(module, "强制识屏：系统配置里没有 ScreenRecognizer 项", null);
                return false;
            }
            Method build = findBuildIntent(m.getClass());
            if (build == null) {
                return false;
            }
            build.setAccessible(true);
            Intent intent = (Intent) build.invoke(m, item, "NavLongPress");
            if (intent == null) {
                return false;
            }
            Context app = application();
            if (app == null) {
                return false;
            }
            app.startForegroundService(intent);
            Diag.i(module, "★ 强制识屏：已启动 " + intent.getComponent());
            return true;
        } catch (Throwable t) {
            Diag.w(module, "强制识屏失败，转下一级", t);
            return false;
        }
    }

    /** 最后兜底：直接按桌面 DeviceConfigs.launchXiaoAi() 的形态拉起小爱服务。 */
    private static void fallback(XposedModule module) {
        try {
            Context app = application();
            if (app == null) {
                Diag.w(module, "识屏兜底：拿不到 Context", null);
                return;
            }
            Intent intent = new Intent("android.intent.action.ASSIST");
            intent.setClassName(VOICE_PKG, VOICE_SERVICE);
            intent.putExtra("voice_assist_start_from_key", "long_press_fullscreen_gesture_line");
            intent.putExtra("triggerType", "NavLongPress");
            intent.putExtra("triggerFrom", "MiuiHome");
            intent.putExtra("voice_assist_function_key", "start_screen_recognition");
            app.startForegroundService(intent);
            Diag.i(module, "★ 识屏兜底：已直接启动 " + VOICE_SERVICE
                    + "（参数不全时可能只唤醒小爱而不识屏）");
        } catch (Throwable t) {
            Diag.e(module, "识屏兜底启动失败", t);
        }
    }

    private static Method findBuildIntent(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if ("buildIntent".equals(m.getName()) && p.length == 2
                    && p[1] == String.class && m.getReturnType() == Intent.class) {
                return m;
            }
        }
        return null;
    }

    /** 懒加载并缓存一个 NavStubGestureEventManager 实例，避免重复注册 ContentObserver。 */
    private static Object manager(ClassLoader loader) throws ReflectiveOperationException {
        Object m = navManager;
        if (m != null) {
            return m;
        }
        synchronized (XiaoAiGestureTrigger.class) {
            if (navManager != null) {
                return navManager;
            }
            Class<?> c = Host.find(loader, CLS_NAV_MANAGER);
            if (c == null) {
                return null;
            }
            Constructor<?> ctor = c.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object created = ctor.newInstance();
            navManager = created;
            return created;
        }
    }

    private static Context application() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread", false, null);
            Method m = at.getDeclaredMethod("currentApplication");
            m.setAccessible(true);
            Object app = m.invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
