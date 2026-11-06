package com.hyperos.hypermaterial;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/** 运行时诊断：LSPosed 日志筛 HM-Unleash 即可看到 Hook 是否命中、字段是否写入成功。 */
final class Diag {

    private static final Set<String> ONCE = Collections.synchronizedSet(new HashSet<String>());

    private Diag() {
    }

    static void i(XposedModule m, String msg) {
        try {
            m.log(Log.INFO, Config.TAG, msg);
        } catch (Throwable ignored) {
            // 日志框架异常不能影响 Hook
        }
    }

    static void w(XposedModule m, String msg, Throwable t) {
        try {
            m.log(Log.WARN, Config.TAG, msg, t);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    static void e(XposedModule m, String msg, Throwable t) {
        try {
            m.log(Log.ERROR, Config.TAG, msg, t);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 同一个 key 只打一次，避免高频方法刷爆日志。 */
    static void once(XposedModule m, String key, String msg) {
        if (ONCE.add(key)) {
            i(m, msg);
        }
    }

    /** 读静态字段值：只允许在应用就绪后的延迟阶段调用（getstatic 会触发 &lt;clinit&gt;）。 */
    static void dumpStaticFieldValues(XposedModule m, String label, Class<?> c) {
        if (c == null) {
            return;
        }
        StringBuilder sb = new StringBuilder(label).append(" 静态字段值[");
        for (Field f : c.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            Object v;
            try {
                f.setAccessible(true);
                v = f.get(null);
            } catch (Throwable t) {
                v = "<不可读>";
            }
            sb.append(f.getName()).append('=').append(v).append(' ');
        }
        once(m, "sfv:" + label, sb.append(']').toString());
    }
}
