package com.hyperos.hypermaterial;

import android.content.ContentResolver;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 通用层：在本进程内把「高级材质总开关」的读取结果伪造成已开启。
 *
 * 反编译系统桌面 3.0.3：桌面不走 miuix 的 HyperMaterialUtils，而是
 * BlurUtilities.setBackgroundBlurEnabled() 直接读
 * {@code Settings.Secure.getInt(resolver, "background_blur_enable", 0)}
 * 写进静态字段 IS_BACKGROUND_BLUR_ENABLED。关了总开关它就是 false，
 * isBlurSupported() 直接返回 false，桌面所有材质/模糊全被掐掉。
 *
 * 这里 Hook framework 的 Settings 读取方法（不改系统数据库、不影响其它进程），
 * 只在「作用域内、且 key 是 background_blur_enable」时返回 1，其余一律 chain.proceed()。
 *
 * v3.0 精简：只保留反编译确认的唯一 key 与 int/String 两类 getter；
 * 删除三个猜测别名（enable_background_blur / background_blur_mode /
 * background_blur_setting）和 getLong / getFloat —— 日志中从未命中，只会多挂 Hook。
 */
final class GlobalSwitchHooks {

    private static final String[] SETTINGS_CLASSES = {
            "android.provider.Settings$Secure",
            "android.provider.Settings$System",
            "android.provider.Settings$Global",
    };

    /** 桌面实际读取的唯一 key。 */
    private static final String KEY = "background_blur_enable";

    private static final Set<String> GETTERS = new HashSet<>(Arrays.asList(
            "getInt", "getString", "getIntForUser", "getStringForUser"
    ));

    private GlobalSwitchHooks() {
    }

    static void install(XposedModule module, ClassLoader loader, String pkg) {
        int n = 0;
        for (String cn : SETTINGS_CLASSES) {
            Class<?> c = Host.find(loader, cn);
            if (c == null) {
                Diag.once(module, "gs:miss:" + cn, pkg + ": 无 " + cn);
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) || !GETTERS.contains(m.getName())) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (p.length < 2 || p[0] != ContentResolver.class || p[1] != String.class) {
                    continue;
                }
                if (hookGetter(module, pkg, m, cn)) {
                    n++;
                }
            }
        }
        Diag.i(module, pkg + ": 全局开关拦截已装 " + n + " 个 getter");
    }

    private static boolean hookGetter(XposedModule module, String pkg, Method m, String cn) {
        try {
            final String label = cn.substring(cn.lastIndexOf('$') + 1) + "." + m.getName();
            module.hook(m)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        String key = keyOf(chain);
                        if (KEY.equals(key)) {
                            Diag.once(module, "fire:gs:" + pkg + ":" + key,
                                    "★ 全局开关 " + label + " 读取 " + key + " -> 伪造成已开启(1)");
                            return fakeValue(chain.getExecutable());
                        }
                        return chain.proceed();
                    });
            // 只反优化真正被 Hook 的方法，防止 ART 内联绕过（不再整类 deoptimize）
            try {
                module.deoptimize(m);
            } catch (Throwable ignored) {
                // 个别方法不支持反优化
            }
            return true;
        } catch (Throwable t) {
            Diag.w(module, "hook " + cn + "." + m.getName() + " 失败", t);
            return false;
        }
    }

    private static String keyOf(XposedInterface.Chain chain) {
        try {
            Object k = chain.getArg(1);
            return k instanceof String ? (String) k : null;
        } catch (Throwable t) {
            try {
                List<Object> args = chain.getArgs();
                return args.size() > 1 && args.get(1) instanceof String
                        ? (String) args.get(1) : null;
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    private static Object fakeValue(Executable e) {
        Class<?> ret = e instanceof Method ? ((Method) e).getReturnType() : int.class;
        if (ret == String.class) {
            return "1";
        }
        if (ret == boolean.class || ret == Boolean.class) {
            return Boolean.TRUE;
        }
        return 1;
    }
}
