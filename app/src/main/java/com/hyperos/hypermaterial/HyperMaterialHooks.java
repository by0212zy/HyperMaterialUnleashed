package com.hyperos.hypermaterial;

import android.content.Context;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 通用层：放行本进程内 miuix 的高级材质检查。
 *
 * 反编译验证：MiuiBlurUtils.setBackgroundBlur() 开头就调用
 * HyperMaterialUtils.isEnable() / isFeatureEnable()，返回 false 直接 return false。
 *
 * v3.0 精简：只保留反编译确认、日志中真实命中的三个入口。删除了
 * 「无参 boolean 静态方法」兜底 Hook（会误 hook 任意同签名方法）、
 * HyperMaterialUtils 静态字段全量扫描注入（猜测性 putstatic，有触发 &lt;clinit&gt;
 * 与误伤风险）、以及整类 deoptimize —— 这些在桌面上从未命中，只增加开销。
 */
final class HyperMaterialHooks {
    static final String HM_CLASS = "miuix.core.util.HyperMaterialUtils";

    private HyperMaterialHooks() {
    }

    static boolean install(XposedModule module, ClassLoader loader, String pkg) {
        // 只加载不初始化：<clinit> 必须等应用就绪（见 Deferred）
        final Class<?> hm = Host.find(loader, HM_CLASS);
        if (hm == null) {
            Diag.i(module, pkg + ": 无 " + HM_CLASS + "，跳过通用放行");
            return false;
        }

        hookReturn(module, pkg, Host.method(hm, "isEnable"), Boolean.TRUE,
                "HyperMaterialUtils.isEnable");

        Method feature = Host.method(hm, "isFeatureEnable", Context.class);
        if (feature == null) {
            feature = Host.firstMethod(hm, "isFeatureEnable");
        }
        hookReturn(module, pkg, feature, Boolean.TRUE,
                "HyperMaterialUtils.isFeatureEnable");

        // 切换系统开关时宿主会清空缓存，拦掉即可保持强制开启
        hookReturn(module, pkg, Host.method(hm, "clearFeatureEnable"), null,
                "HyperMaterialUtils.clearFeatureEnable");

        Diag.i(module, pkg + ": 通用放行安装完成（3 个入口）");
        return true;
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
            // 精确反优化这一个方法，防止短方法被 ART 内联绕过 Hook
            try {
                module.deoptimize(method);
            } catch (Throwable ignored) {
                // 个别方法不支持反优化
            }
            Diag.i(module, "hooked " + label);
        } catch (Throwable t) {
            Diag.w(module, "hook 失败 " + label, t);
        }
    }
}
