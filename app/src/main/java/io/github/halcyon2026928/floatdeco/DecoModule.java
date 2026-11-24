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

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        XposedBridge.attach(this, DecoModule.class.getClassLoader());
        XposedBridge.log("module loaded: process=" + param.getProcessName()
                + " systemServer=" + param.isSystemServer()
                + " framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + " api=" + getApiVersion());
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

