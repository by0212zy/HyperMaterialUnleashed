package com.hyperos.hypermaterial;

import android.os.Build;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * 模块入口（META-INF/xposed/java_init.list）。
 *
 * 现代 libxposed API 不注入 Zygote，只注入作用域内应用的进程；因此「分应用生效」
 * 天然等于「在 LSPosed 里只勾选这些应用」，系统属性与 Settings 全局开关都不动。
 *
 * v3.0 正式版：移除从未命中的设置应用 Hook、版本卡片补丁、猜测性门控 Hook、
 * 整类 deoptimize、安装期方法清单 dump 与 classloader 猜测兜底，只保留实测生效的最小链路。
 */
public class ModuleMain extends XposedModule {

    private static final Set<String> INSTALLED =
            Collections.synchronizedSet(new HashSet<String>());

    private volatile String processName = "?";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        Diag.i(this, "════ 模块载入 ════ 进程=" + processName
                + " 框架=" + getFrameworkName() + " API=" + getApiVersion()
                + " 设备=" + Build.MANUFACTURER + "/" + Build.MODEL
                + " SDK=" + Build.VERSION.SDK_INT);
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        final String pkg = param.getPackageName();
        final String proc = processName;
        final ClassLoader loader = param.getClassLoader();

        Diag.i(this, "onPackageReady 进程=" + proc + " 包=" + pkg
                + " isFirstPackage=" + param.isFirstPackage());

        try {
            // 武装延迟执行器（幂等）：静态字段注入必须等应用就绪后才能触发 <clinit>
            Deferred.arm(this, pkg);
            installOnce(loader, pkg, proc);
        } catch (Throwable t) {
            Diag.e(this, "安装 Hook 失败: " + pkg + "/" + proc, t);
        }
    }

    private void installOnce(ClassLoader loader, String pkg, String proc) {
        if (loader == null) {
            return;
        }
        String key = proc + "|" + pkg + "|" + System.identityHashCode(loader);
        if (!INSTALLED.add(key)) {
            return;
        }

        // 最根本的一层：本进程内把高级材质总开关的 Settings 读取伪造成已开启
        if (Config.HOOK_GLOBAL_SWITCH) {
            GlobalSwitchHooks.install(this, loader, pkg);
        }

        // 通用：让本进程内的 miuix 认为高级材质已开启
        HyperMaterialHooks.install(this, loader, pkg);

        // 系统桌面专属：放行自己的 BlurUtilities 门控链（不走 miuix）
        if (Config.PATCH_LAUNCHER && Config.LAUNCHER_PKG.equals(pkg)) {
            LauncherHooks.install(this, loader, pkg);

            // 把「长按手势提示线 → 超级小爱识屏」暴露成广播入口。
            // 只在桌面主进程注册，避免一次广播被多个子进程重复触发。
            // 广播接收器必须在应用就绪后注册：早期注册会让外部广播触发宿主类的
            // <clinit>（v1.2 闪退根因），且 Android 13+ 需要显式 exported。
            if (Config.ENABLE_XIAOAI_TRIGGER && !proc.contains(":")) {
                Deferred.post(this, pkg,
                        () -> XiaoAiGestureTrigger.install(this, loader, pkg));
            }
        }
    }
}
