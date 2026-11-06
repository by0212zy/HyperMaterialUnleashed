package com.hyperos.hypermaterial;

/** 编译期开关。「对哪些应用生效」由 LSPosed 作用域决定，默认只勾选系统桌面。 */
final class Config {
    static final String TAG = "HM-Unleash";

    static final String LAUNCHER_PKG = "com.miui.home";

    /** 在本进程内把 background_blur_enable 的读取伪造成 1（不改系统数据库）。 */
    static final boolean HOOK_GLOBAL_SWITCH = true;

    /** 放行桌面 BlurUtilities / MiuixMaterialBlurUtilities 门控链。 */
    static final boolean PATCH_LAUNCHER = true;

    /** 暴露「长按手势提示线 → 超级小爱识屏」的广播触发入口（供其它 App 用 shell 调用）。 */
    static final boolean ENABLE_XIAOAI_TRIGGER = true;

    private Config() {
    }
}
