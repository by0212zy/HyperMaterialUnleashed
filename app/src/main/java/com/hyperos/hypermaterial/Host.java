package com.hyperos.hypermaterial;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** 宿主反射工具：只按名字查找，且一律只加载不初始化。 */
final class Host {
    private Host() {
    }

    /**
     * Class.forName(name, <b>false</b>, loader)：绝不触发 &lt;clinit&gt;。
     * onPackageReady 阶段宿主 Application 尚未初始化，触发类初始化会把它永久
     * 标记为 erroneous（详见 Deferred），导致桌面无限闪退。
     */
    static Class<?> find(ClassLoader loader, String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    static Method method(Class<?> c, String name, Class<?>... params) {
        if (c == null) {
            return null;
        }
        try {
            return c.getDeclaredMethod(name, params);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 各应用内嵌的 miuix 版本不同，方法签名会变，优先找 boolean 返回值。 */
    static Method firstMethod(Class<?> c, String name) {
        if (c == null) {
            return null;
        }
        Method[] all = c.getDeclaredMethods();
        for (Method m : all) {
            if (m.getName().equals(name) && m.getReturnType() == boolean.class) {
                return m;
            }
        }
        for (Method m : all) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        return null;
    }

    static Field field(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch (Throwable ignored) {
                // 继续向父类找
            }
        }
        return null;
    }

    /**
     * 写静态字段（putstatic 会触发 &lt;clinit&gt;），只允许在 Deferred 的延迟阶段调用。
     */
    static void setStatic(Class<?> c, String name, Object value) {
        if (c == null) {
            return;
        }
        try {
            Field f = field(c, name);
            if (f == null) {
                return;
            }
            f.setAccessible(true);
            f.set(null, value);
        } catch (Throwable ignored) {
            // 字段不存在或类型变化时忽略，方法 Hook 仍然兜底
        }
    }
}
