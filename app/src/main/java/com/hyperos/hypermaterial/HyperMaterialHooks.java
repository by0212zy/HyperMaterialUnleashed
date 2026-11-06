package com.hyperos.hypermaterial;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 通用层：放行本进程内 miuix 的高级材质检查。
 *
 * 反编译验证：MiuiBlurUtils.setBackgroundBlur() 开头就调用
 * HyperMaterialUtils.isEnable() / isFeatureEnable()，返回 false 直接 return false。
 *
 * v2.0 精简：只保留日志中在桌面真实命中的 isEnable / clearFeatureEnable，
 * 加上配套的 isFeatureEnable；删除从未命中的 isDefaultFeatureEnable、
 * MiuiBlurUtils 系列、LiteUtils / DrawableUtils、设置专属的低端机判断。
 */
final class HyperMaterialHooks {
    static final String HM_CLASS = "miuix.core.util.HyperMaterialUtils";

    private static final Set<String> KNOWN_FORCE_TRUE = new HashSet<>(Arrays.asList(
            "isForceEnable", "SUPPORT_HYPER_MATERIAL", "DEFAULT_FEATURE_ENABLE",
            "ENABLE_HYPER_MATERIAL", "sEnable", "sIsEnable"));

    private HyperMaterialHooks() {
    }

    static boolean install(XposedModule module, ClassLoader loader, String pkg) {
        // 只加载不初始化：<clinit> 必须等应用就绪（见 Deferred）
        final Class<?> hm = Host.find(loader, HM_CLASS);
        if (hm == null) {
            Diag.i(module, pkg + ": 无 " + HM_CLASS + "，跳过通用放行");
            return false;
        }

        Diag.dumpStaticFields(module, pkg + ":HMU", hm);  // 只读名字，不触发 <clinit>
        Diag.dumpMethods(module, pkg + ":HMU", hm);

        hookReturn(module, pkg, Host.method(hm, "isEnable"), Boolean.TRUE,
                "HyperMaterialUtils.isEnable");
        if (Host.method(hm, "isEnable") == null) {
            hookReturn(module, pkg, staticBooleanNoArg(hm), Boolean.TRUE,
                    "HyperMaterialUtils.<isEnable 兜底>");
        }

        Method feature = Host.method(hm, "isFeatureEnable", Context.class);
        if (feature == null) {
            feature = Host.firstMethod(hm, "isFeatureEnable");
        }
        hookReturn(module, pkg, feature, Boolean.TRUE, "HyperMaterialUtils.isFeatureEnable");

        // 切换系统开关时宿主会清空缓存，拦掉即可保持强制开启
        hookReturn(module, pkg, Host.method(hm, "clearFeatureEnable"), null,
                "HyperMaterialUtils.clearFeatureEnable");

        if (Config.AGGRESSIVE_DEOPTIMIZE) {
            deoptimizeAll(module, hm, pkg + ":HMU");
        }

        // 静态字段写入会触发 <clinit>，延后到应用就绪后
        Deferred.post(module, pkg, () -> injectFields(module, pkg, hm));

        Diag.i(module, pkg + ": 通用放行安装完成");
        return true;
    }

    /** 只在应用就绪后执行：putstatic 会触发宿主 &lt;clinit&gt;。 */
    private static void injectFields(XposedModule module, String pkg, Class<?> hm) {
        try {
            int ok = 0;
            for (Field f : hm.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Class<?> t = f.getType();
                if (t != boolean.class && t != Boolean.class) {
                    continue;
                }
                String n = f.getName().toLowerCase(Locale.ROOT);
                boolean positive = n.contains("enable") || n.contains("support")
                        || n.contains("force") || n.contains("feature")
                        || n.contains("available") || n.contains("blur");
                boolean negative = n.contains("disable") || n.contains("lite")
                        || n.contains("low") || n.contains("not") || n.contains("off")
                        || n.contains("debug") || n.contains("log");
                if (!(KNOWN_FORCE_TRUE.contains(f.getName()) || (positive && !negative))) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    f.set(null, Boolean.TRUE);
                    ok++;
                    Diag.once(module, "setf:" + pkg + ":" + f.getName(),
                            pkg + ": 静态字段 " + f.getName() + " -> true ✓");
                } catch (Throwable ignored) {
                    // static final / 类型异常
                }
            }
            Diag.i(module, pkg + ": HyperMaterialUtils 静态字段注入 ok=" + ok);
            Diag.dumpStaticFieldValues(module, pkg + ":HMU", hm);
        } catch (Throwable t) {
            Diag.w(module, pkg + ": 延迟字段注入异常（已隔离）", t);
        }
    }

    private static Method staticBooleanNoArg(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getParameterTypes().length == 0
                    && m.getReturnType() == boolean.class && !m.getName().equals("isFeatureEnable")) {
                return m;
            }
        }
        return null;
    }

    /** 不调用 chain.proceed() == 完全替换方法体。 */
    private static void hookReturn(XposedModule module, String pkg, Method method,
                                   Object value, String label) {
        if (method == null) {
            return;
        }
        Class<?> ret = method.getReturnType();
        if (ret != boolean.class && ret != void.class) {
            return;
        }
        try {
            final String fireKey = "fire:" + pkg + ":" + label;
            module.hook(method)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Diag.once(module, fireKey, "★ 命中 " + label + " -> " + value);
                        return value;
                    });
            Diag.i(module, "hooked " + label);
        } catch (Throwable t) {
            Diag.w(module, "hook 失败 " + label, t);
        }
    }

    private static void deoptimizeAll(XposedModule module, Class<?> c, String label) {
        int ok = 0;
        for (Method m : c.getDeclaredMethods()) {
            try {
                module.deoptimize(m);
                ok++;
            } catch (Throwable ignored) {
                // 个别方法不支持反优化
            }
        }
        Diag.i(module, label + " deoptimize " + ok + "/" + c.getDeclaredMethods().length);
    }
}
