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
 * v2.0：移除设置（com.android.settings）相关的全部 Hook 与「我的设备」版本卡片补丁——
 * 实测只放行系统桌面即可获得高级材质，设置侧 Hook 从未在桌面命中。
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
                + " isFirstPackage=" + param.isFirstPackage()
                + " loader=" + (loader == null ? "null" : loader.getClass().getName()));

        try {
            // 武装延迟执行器（幂等）：静态字段注入必须等应用就绪后才能触发 <clinit>
            Deferred.arm(this, pkg);
            installOnce(loader, pkg, proc);

            // 少数 ROM 用自定义 AppComponentFactory 换了 classloader，兜底再试一次
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && Host.find(loader, HyperMaterialHooks.HM_CLASS) == null) {
                ClassLoader def = param.getDefaultClassLoader();
                Diag.i(this, "主 loader 无 HyperMaterialUtils，尝试 defaultClassLoader="
                        + (def == null ? "null" : def.getClass().getName()));
                if (def != null && def != loader) {
                    installOnce(def, pkg, proc);
                }
            }
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
        }
    }
}
