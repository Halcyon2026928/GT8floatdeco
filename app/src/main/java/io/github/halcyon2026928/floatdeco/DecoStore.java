package io.github.halcyon2026928.floatdeco;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * system_server 里的设置落盘（重启记忆）。
 *
 * <p>开机那一刻，模块 app 还没起来：XSharedPreferences 读不到（world-readable 已被限制），
 * ConfigProvider 也查不了，所以每次重启开关都会掉回默认值。这里让系统进程自己维护一份设置文件
 * ——进程一起来先读文件，app 改设置再通过广播推过来。这样开关与 app 在不在、能不能读它的
 * prefs 都无关。</p>
 */
final class DecoStore {

    private static final String TAG = "[自由小窗OS-config]";
    /** 系统数据目录，system_server 可写；app 私有目录在系统进程里拿不到。 */
    private static final String STORE_DIR = "/data/system";
    private static final String STORE_NAME = "floatdeco_settings.txt";

    static final String ACTION_PUSH = "io.github.halcyon2026928.floatdeco.SETTINGS_PUSHED";

    /** 开机 AMS 未就绪时注册失败，最多重试 40 次（每次 2s，覆盖约 80s）。 */
    private static final int MAX_REGISTER_ATTEMPTS = 40;
    private static final long REGISTER_RETRY_DELAY_MS = 2000L;

    private static volatile DecoHook sHook;
    private static volatile Context sContext;
    private static volatile boolean sInstalled;
    /** 上一次落盘的内容，用来避免每次查询都重写同一个文件。 */
    private static volatile Map<String, String> sPersisted;

    private DecoStore() {
    }

    /** 装钩子时调一次：先读文件把开关恢复成用户上次的选择，再注册接收 app 的推送。 */
    static void install(DecoHook hook, ClassLoader cl) {
        if (sInstalled) return;
        sInstalled = true;
        sHook = hook;
        sContext = systemContext(cl);
        restore();
        registerReceiver();
    }

    /** 开机恢复：系统进程比模块 app 早，必须用上次落盘的值先跑起来。 */
    private static void restore() {
        Map<String, String> map = readFile();
        if (map.isEmpty()) {
            log("no stored config, wait for app push");
            return;
        }
        sPersisted = map;
        apply(map);
        log("restored " + map.size() + " keys");
    }

    private static void apply(Map<String, String> map) {
        DecoHook hook = sHook;
        if (hook == null) return;
        try {
            hook.applyConfigFromStore(map);
        } catch (Throwable t) {
            log("apply failed: " + t.getMessage());
        }
    }

    /**
     * 把 app 推过来的整份配置落盘并当场生效。
     * 内容没变化就不重复写，避免开小窗时的查询把文件一直刷。
     */
    static void onPushFromApp(Bundle extras) {
        if (extras == null) return;
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            if (value != null) map.put(key, String.valueOf(value));
        }
        if (map.isEmpty()) return;
        boolean same = map.equals(sPersisted);
        if (!same) {
            persist(map);
            sPersisted = map;
        }
        apply(map);
        log("pushed " + map.size() + " keys, written=" + (!same));
    }

    /** 当前值就地落盘：provider / xprefs 读到有效值时用它做首次自举。 */
    static void saveCurrent() {
        DecoHook hook = sHook;
        if (hook == null) return;
        try {
            Map<String, String> map = hook.configToMap();
            if (map == null || map.isEmpty()) return;
            if (map.equals(sPersisted)) return;
            persist(map);
            sPersisted = map;
            log("saved current " + map.size() + " keys");
        } catch (Throwable t) {
            log("saveCurrent failed: " + t.getMessage());
        }
    }

    private static void persist(Map<String, String> map) {
        File target = storeFile();
        File temp = new File(target.getParentFile(), STORE_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : map.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable t) {
            log("persist failed: " + t.getMessage());
            return;
        }
        // 原子替换：写到一半被读到会解析出半个配置
        if (!temp.renameTo(target)) {
            try (FileInputStream in = new FileInputStream(temp);
                 FileOutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (Throwable t) {
                log("persist copy failed: " + t.getMessage());
            }
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private static Map<String, String> readFile() {
        Map<String, String> map = new LinkedHashMap<>();
        File file = storeFile();
        if (!file.exists()) return map;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                map.put(line.substring(0, eq), line.substring(eq + 1));
            }
        } catch (Throwable t) {
            log("read failed: " + t.getMessage());
        }
        return map;
    }

    private static File storeFile() {
        File dir = new File(STORE_DIR);
        if (!dir.canWrite()) {
            Context ctx = sContext;
            if (ctx != null) {
                File dir2 = ctx.getFilesDir();
                if (dir2 != null) return new File(dir2, STORE_NAME);
            }
        }
        return new File(dir, STORE_NAME);
    }

    private static void registerReceiver() {
        registerReceiver(0);
    }

    /**
     * 注册推送接收器。开机早期 AMS 尚未就绪时 registerReceiver 会抛 NPE
     * （IActivityManager 为 null），所以失败要延迟重试，否则推送通道永久失效。
     */
    private static void registerReceiver(final int attempt) {
        Context ctx = sContext;
        if (ctx == null) {
            log("system context unavailable, push channel disabled");
            return;
        }
        try {
            IntentFilter filter = new IntentFilter(ACTION_PUSH);
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || !ACTION_PUSH.equals(intent.getAction())) return;
                    int uid = sentFromUid(intent);
                    if (!trusted(uid)) {
                        log("rejected foreign push from uid=" + uid);
                        return;
                    }
                    onPushFromApp(intent.getExtras());
                }
            }, filter, Context.RECEIVER_EXPORTED);
            log("push channel ready (attempt " + attempt + ")");
        } catch (Throwable t) {
            log("register receiver failed (attempt " + attempt + "): " + t.getMessage());
            if (attempt < MAX_REGISTER_ATTEMPTS) {
                scheduleRetry(attempt + 1);
            }
        }
    }

    private static void scheduleRetry(final int attempt) {
        try {
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    registerReceiver(attempt);
                }
            }, REGISTER_RETRY_DELAY_MS);
        } catch (Throwable t) {
            log("schedule retry failed: " + t.getMessage());
        }
    }

    /**
     * 发送方 uid。{@code Intent.getSentFromUid()} 在 compileSdk 34 的 stub 里没有，
     * 只能反射调；取不到就返回 -1，由 {@link #trusted(int)} 当成不可信丢弃。
     */
    private static int sentFromUid(Intent intent) {
        if (intent == null) return -1;
        try {
            Object v = XposedHelpers.callMethod(intent, "getSentFromUid");
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** 只认模块 app 自己或系统进程：这份配置决定模块行为，不能由第三方写。 */
    private static boolean trusted(int uid) {
        if (uid == Process.SYSTEM_UID) return true;
        if (uid < 0) return false;
        Context ctx = sContext;
        if (ctx == null) return false;
        try {
            return ctx.getPackageManager()
                    .getApplicationInfo(ModulePrefs.PKG, 0).uid == uid;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Context systemContext(ClassLoader cl) {
        try {
            Object at = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", cl),
                    "currentActivityThread");
            Object ctx = at == null ? null : XposedHelpers.callMethod(at, "getSystemContext");
            if (ctx instanceof Context) return (Context) ctx;
        } catch (Throwable t) {
            log("systemContext failed: " + t.getMessage());
        }
        return null;
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + " " + msg);
    }
}

