package com.hyperos.hypermaterial;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 系统桌面（com.miui.home）专属放行 —— 实测生效的核心。
 *
 * 桌面不走 miuix 的 HyperMaterialUtils，而是自己的门控链：
 *
 * <pre>
 * MiuixMaterialBlurUtilities.isSupportHyperMaterialBlur()
 *   ├─ BlurUtilities.isBlurSupported()
 *   │    ├─ IS_BACKGROUND_BLUR_SUPPORTED   ← 系统属性（本机 true）
 *   │    └─ IS_BACKGROUND_BLUR_ENABLED     ← Settings.Secure.background_blur_enable
 *   │                                       关总开关时为 false ★元凶
 *   └─ HyperMaterialUtils.getVersion() &gt;= 4 （本机 3）
 * shouldApplyBlur(View) → isBlurSupported()
 * </pre>
 *
 * v1.3：早期绝不触发宿主 &lt;clinit&gt;（会打崩桌面），静态字段写入全部交给 Deferred。
 * v2.0：删除日志中从未命中的 isNotSupportBlurDevice。
 */
final class LauncherHooks {

    private static final String BU = "com.miui.home.common.utils.BlurUtilities";
    private static final String MM = "com.miui.home.common.utils.MiuixMaterialBlurUtilities";

    private LauncherHooks() {
    }

    static void install(XposedModule module, ClassLoader loader, String pkg) {
        // 只加载，不初始化
        final Class<?> bu = Host.find(loader, BU);
        final Class<?> mm = Host.find(loader, MM);

        if (bu == null && mm == null) {
            Diag.i(module, pkg + ": 非系统桌面模糊实现，跳过桌面专属放行");
            return;
        }

        Diag.dumpStaticFields(module, pkg + ":BU", bu);   // 只读名字，安全
        Diag.dumpMethods(module, pkg + ":BU", bu);
        Diag.dumpStaticFields(module, pkg + ":MM", mm);
        Diag.dumpMethods(module, pkg + ":MM", mm);

        if (bu != null) {
            hookBoolean(module, pkg, bu, "isBlurSupported", Boolean.TRUE);
            hookBoolean(module, pkg, bu, "isBackgroundBlurSupported", Boolean.TRUE);
            hookBoolean(module, pkg, bu, "isThreeLayerBlurSupported", Boolean.TRUE);
            hookBoolean(module, pkg, bu, "isWidgetBlurSupported", Boolean.TRUE);
            hookBoolean(module, pkg, bu, "isSupportSetMiBlurList", Boolean.TRUE);

            // 设置变化 / 主题切换后桌面会重读 Settings 并把字段改回 false，执行后压回 true
            hookReassert(module, pkg, bu, "setBackgroundBlurEnabled", bu);
        }

        if (mm != null) {
            // 直接返回 true，绕过 getVersion() >= 4 这道检查
            hookBoolean(module, pkg, mm, "isSupportHyperMaterialBlur", Boolean.TRUE);
            hookBoolean(module, pkg, mm, "shouldApplyBlur", Boolean.TRUE);
        }

        if (Config.AGGRESSIVE_DEOPTIMIZE) {
            deoptimizeAll(module, bu);
            deoptimizeAll(module, mm);
        }

        // 静态字段注入会触发 <clinit>，延后到应用就绪后
        Deferred.post(module, pkg, () -> injectFields(module, pkg, bu, mm));

        Diag.i(module, pkg + ": 桌面高级材质放行安装完成");
    }

    /** 只在应用就绪后执行，此时 ContextProvider / DimenUtils 已可用。 */
    private static void injectFields(XposedModule module, String pkg, Class<?> bu, Class<?> mm) {
        if (bu == null) {
            return;
        }
        Diag.dumpStaticFieldValues(module, pkg + ":BU", bu);
        Diag.dumpStaticFieldValues(module, pkg + ":MM", mm);

        Diag.i(module, pkg + ": 桌面开关原值 IS_BACKGROUND_BLUR_ENABLED="
                + readStatic(bu, "IS_BACKGROUND_BLUR_ENABLED"));

        // 免疫 ART 内联（30+ 调用点里不少会把 isBlurSupported 内联进去）
        Host.setStatic(bu, "IS_BACKGROUND_BLUR_ENABLED", Boolean.TRUE);
        Host.setStatic(bu, "IS_BACKGROUND_BLUR_SUPPORTED", Boolean.TRUE);
        Host.setStatic(bu, "mIsSupportMiBlurList", Boolean.TRUE);
        Host.setStatic(bu, "sThreeLayerBlurSupportedCache", Boolean.TRUE);
        forceBlurFields(module, pkg, bu);

        Diag.i(module, pkg + ": 桌面开关注入后 IS_BACKGROUND_BLUR_ENABLED="
                + readStatic(bu, "IS_BACKGROUND_BLUR_ENABLED"));
    }

    /** 按语义扫描静态 boolean 开关，适配不同桌面版本的字段名。只在延迟阶段调用。 */
    private static void forceBlurFields(XposedModule module, String pkg, Class<?> bu) {
        int ok = 0;
        for (Field f : bu.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            Class<?> t = f.getType();
            if (t != boolean.class && t != Boolean.class) {
                continue;
            }
            String n = f.getName().toUpperCase(Locale.ROOT);
            if ((!n.contains("BLUR") && !n.contains("SUPPORT"))
                    || n.contains("NOTSUPPORT") || n.contains("DISABLE")) {
                continue;
            }
            try {
                f.setAccessible(true);
                f.set(null, Boolean.TRUE);
                ok++;
                Diag.once(module, "lh:sf:" + pkg + ":" + f.getName(),
                        pkg + ": 桌面静态字段 " + f.getName() + " -> true ✓");
            } catch (Throwable ignored) {
                // static final / 类型异常
            }
        }
        Diag.i(module, pkg + ": 桌面 blur 静态字段扫描注入 ok=" + ok);
    }

    /** 只在延迟阶段调用；早期读静态字段会触发 &lt;clinit&gt;。 */
    private static Object readStatic(Class<?> c, String name) {
        try {
            Field f = Host.field(c, name);
            if (f == null) {
                return "<无此字段>";
            }
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            return "<不可读>";
        }
    }

    private static void hookBoolean(XposedModule module, String pkg, Class<?> c,
                                    String name, Object value) {
        if (c == null) {
            return;
        }
        Method m = Host.firstMethod(c, name);
        if (m == null || (m.getReturnType() != boolean.class && m.getReturnType() != Boolean.class)) {
            Diag.i(module, pkg + ": 桌面方法 " + name + " 不存在，跳过");
            return;
        }
        try {
            final String fireKey = "fire:lh:" + pkg + ":" + name;
            module.hook(m)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Diag.once(module, fireKey, "★ 桌面命中 " + name + " -> " + value);
                        return value;
                    });
            Diag.i(module, "hooked 桌面 " + name + "()");
        } catch (Throwable t) {
            Diag.w(module, "hook 桌面 " + name + " 失败", t);
        }
    }

    private static void hookReassert(XposedModule module, String pkg, Class<?> c,
                                     String name, Class<?> bu) {
        Method m = Host.firstMethod(c, name);
        if (m == null) {
            return;
        }
        try {
            module.hook(m)
                    .setPriority(XposedInterface.PRIORITY_LOWEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object r = chain.proceed();
                        Host.setStatic(bu, "IS_BACKGROUND_BLUR_ENABLED", Boolean.TRUE);
                        Host.setStatic(bu, "sThreeLayerBlurSupportedCache", Boolean.TRUE);
                        Diag.once(module, "fire:lh:" + pkg + ":reassert",
                                "★ 桌面 " + name + " 执行后已重新压回 true");
                        return r;
                    });
            Diag.i(module, "hooked 桌面 " + name);
        } catch (Throwable t) {
            Diag.w(module, "hook 桌面 " + name + " 失败", t);
        }
    }

    private static void deoptimizeAll(XposedModule module, Class<?> c) {
        if (c == null) {
            return;
        }
        int ok = 0;
        for (Method m : c.getDeclaredMethods()) {
            try {
                module.deoptimize(m);
                ok++;
            } catch (Throwable ignored) {
                // ignore
            }
        }
        Diag.i(module, "桌面 " + c.getSimpleName() + " deoptimize " + ok
                + "/" + c.getDeclaredMethods().length);
    }
}
