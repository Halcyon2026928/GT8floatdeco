package io.github.halcyon2026928.floatdeco;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * LSPosed 官方开关记忆方式（New XSharedPreferences）：
 * 模块进程用 MODE_WORLD_READABLE 写 prefs；LSPosed 把它放到可读目录。
 * 系统框架用 XSharedPreferences(包名, 文件名) 读取，重启后仍能拿到上次状态。
 */
public final class ModulePrefs {

    public static final String PKG = "io.github.halcyon2026928.floatdeco";
    public static final String NAME = "floatdeco_prefs";

    private ModulePrefs() {
    }

    public static SharedPreferences get(Context ctx) {
        SharedPreferences prefs;
        try {
            prefs = ctx.getSharedPreferences(NAME, Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            prefs = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        }
        makePrefsReadable(ctx);
        return prefs;
    }

    /**
     * 把 prefs 文件设成 world-readable，让 system_server 侧的 XSharedPreferences 能直接读
     * （legacy 的 makeWorldReadable 在 modern API 下是空实现）。
     *
     * <p>三个条件缺一不可：数据目录 o+x（1000 才进得去）、shared_prefs 目录 o+x、xml o+r。
     */
    public static void makePrefsReadable(Context ctx) {
        try {
            java.io.File dataDir = ctx.getFilesDir().getParentFile();
            if (dataDir == null) return;
            dataDir.setExecutable(true, false);
            java.io.File prefsDir = new java.io.File(dataDir, "shared_prefs");
            prefsDir.setExecutable(true, false);
            java.io.File xml = new java.io.File(prefsDir, NAME + ".xml");
            if (xml.exists()) {
                xml.setReadable(true, false);
            }
        } catch (Throwable ignored) {
        }
    }
}

