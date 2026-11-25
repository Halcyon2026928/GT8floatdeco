package io.github.halcyon2026928.floatdeco;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 现代 Xposed API（libxposed API 102）入口。
 *
 * <p>本模块只作用于系统框架（system_server），因此真正的初始化放在
 * {@link #onSystemServerStarting}；其余进程不做事。</p>
 */
public class DecoModule extends XposedModule {

    private volatile boolean installed;
    private volatile boolean launcherInstalled;

    /** OPlus/ColorOS 的默认桌面（OplusLauncher）包名；最近任务卡片由它绘制。 */
    private static final String LAUNCHER_PKG = "com.android.launcher";

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        XposedBridge.attach(this, DecoModule.class.getClassLoader());
        XposedBridge.log("module loaded: process=" + param.getProcessName()
                + " systemServer=" + param.isSystemServer()
                + " framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + " api=" + getApiVersion());
    }

    /**
     * Launcher 进程加载时注入后台卡片封面 hook。
     * system_server 的第一个包由 onSystemServerStarting 接管，所以这里不会与之冲突。
     */
    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (launcherInstalled) return;
        if (!LAUNCHER_PKG.equals(param.getPackageName())) return;
        launcherInstalled = true;
        XposedBridge.log("launcher package loaded: " + param.getPackageName()
                + " first=" + param.isFirstPackage());
        try {
            new CardCoverHook().install(param.getDefaultClassLoader());
            XposedBridge.log("CardCoverHook installed on launcher");
        } catch (Throwable t) {
            XposedBridge.log("CardCoverHook install failed: " + t);
        }
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        if (installed) {
            return;
        }
        installed = true;
        XposedBridge.attach(this, param.getClassLoader());
        try {
            new DecoHook().install(param.getClassLoader());
            XposedBridge.log("DecoHook installed on system_server");
        } catch (Throwable t) {
            XposedBridge.log("DecoHook install failed: " + t);
        }
    }
}

