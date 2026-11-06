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
 * 这是「不开启总开关，只让部分应用内生效」最根本的做法：
 * 反编译系统桌面 3.0.3 发现，桌面根本不经过 miuix 的 HyperMaterialUtils，
 * 而是 com.miui.home.common.utils.BlurUtilities.setBackgroundBlurEnabled() 直接读
 *   Settings.Secure.getInt(resolver, "background_blur_enable", 0)
 * 写进静态字段 IS_BACKGROUND_BLUR_ENABLED；关了总开关它就是 false，
 * isBlurSupported() 直接返回 false，桌面所有材质/模糊全被掐掉。
 *
 * 这里 Hook framework 的 Settings 读取方法（不会改系统数据库、不影响其它进程），
 * 只在「作用域内、且 key 是高级材质开关」时返回 1，其余一律 chain.proceed()。
 * 同时覆盖 miuix 的 HyperMaterialUtils.isFeatureEnable()、其它应用里同样的读取点。
 */
final class GlobalSwitchHooks {

    private static final String[] SETTINGS_CLASSES = {
            "android.provider.Settings$Secure",
            "android.provider.Settings$System",
            "android.provider.Settings$Global",
    };

    private static final Set<String> KEYS = new HashSet<>(Arrays.asList(
            "background_blur_enable",
            "enable_background_blur",
            "background_blur_mode",
            "background_blur_setting"
    ));

    private static final Set<String> GETTERS = new HashSet<>(Arrays.asList(
            "getInt", "getString", "getLong", "getFloat", "getIntForUser", "getStringForUser"
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
                        if (key != null && KEYS.contains(key)) {
                            Diag.once(module, "fire:gs:" + pkg + ":" + key,
                                    "★ 全局开关 " + label + " 读取 " + key + " -> 伪造成已开启(1)");
                            return fakeValue(chain.getExecutable());
                        }
                        return chain.proceed();
                    });
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
        if (ret == long.class || ret == Long.class) {
            return 1L;
        }
        if (ret == float.class || ret == Float.class) {
            return 1.0f;
        }
        if (ret == boolean.class || ret == Boolean.class) {
            return Boolean.TRUE;
        }
        return 1;
    }
}
