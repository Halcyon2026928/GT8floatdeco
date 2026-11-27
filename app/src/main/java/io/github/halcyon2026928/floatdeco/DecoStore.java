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
 *
 * <p>文件一旦有内容就是唯一权威：xprefs / provider 只在文件还空着时用来自举，读到值就落盘，
 * 不再参与后续读取。</p>
 */
final class DecoStore {

    private static final String TAG = "[自由小窗OS-config]";
    /** 系统数据目录，system_server 可写；app 私有目录在系统进程里拿不到。 */
    private static final String STORE_DIR = "/data/system";
    private static final String STORE_NAME = "floatdeco_settings.txt";

    static final String ACTION_PUSH = "io.github.halcyon2026928.floatdeco.SETTINGS_PUSHED";

    private static final String MODULE_PKG = "io.github.halcyon2026928.floatdeco";
    /** app 每次保存设置都会写一份 key=value 快照，只有它自己能写，系统进程读它比读广播 extras 可信。 */
    private static final String SNAP_NAME = "floatdeco_config.txt";
    private static final String[] SNAP_PATHS = {
            "/data/media/0/Android/data/" + MODULE_PKG + "/files/" + SNAP_NAME,
            "/storage/emulated/0/Android/data/" + MODULE_PKG + "/files/" + SNAP_NAME,
            "/data/user/0/" + MODULE_PKG + "/files/" + SNAP_NAME,
            "/data/data/" + MODULE_PKG + "/files/" + SNAP_NAME,
    };

    private static volatile DecoHook sHook;
    private static volatile Context sContext;
    private static volatile ClassLoader sCl;
    private static volatile boolean sInstalled;
    /** 推送接收器是否已注册成功；只在 AMS.systemReady 之后注册一次。 */
    private static volatile boolean sReceiverRegistered;
    /** 落盘文件是否已有内容（或本进程写过）。为 true 时它就是唯一权威，不再查 provider/xprefs。 */
    private static volatile boolean sAuthoritative;
    /** 上一次落盘的内容，用来避免每次查询都重写同一个文件。 */
    private static volatile Map<String, String> sPersisted;
    /** 模块 app 的 uid 缓存，-2 表示还没查。 */
    private static volatile int sModuleUid = -2;

    private DecoStore() {
    }

    /** 装钩子时调一次：先读文件把开关恢复成用户上次的选择。推送接收器等 AMS 就绪后再注册。 */
    static void install(DecoHook hook, ClassLoader cl) {
        if (sInstalled) return;
        sInstalled = true;
        sHook = hook;
        sCl = cl;
        sContext = systemContext(cl);
        restore();
    }

    /** 开机恢复：系统进程比模块 app 早，必须用上次落盘的值先跑起来。 */
    private static void restore() {
        Map<String, String> map = readFile();
        // app 快照比落盘文件新时以快照为准：用户最后一次在 app 里改的开关才算数
        File snapFile = readableSnapshot();
        if (snapFile != null) {
            Map<String, String> snap = parse(snapFile);
            if (!snap.isEmpty() && snapFile.lastModified() >= storeFile().lastModified()) {
                map = snap;
                persist(map);
                log("restored from app snapshot " + map.size() + " keys");
            }
        } else {
            log("app snapshot not readable, store only");
        }
        if (map.isEmpty()) {
            log("no stored config, wait for app push");
            return;
        }
        sPersisted = map;
        sAuthoritative = true;
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
        onPushFromApp(extrasToMap(extras));
    }

    static void onPushFromApp(Map<String, String> map) {
        if (map == null || map.isEmpty()) return;
        boolean same = map.equals(sPersisted);
        if (!same) {
            boolean written = persist(map);
            sPersisted = map;
            if (written) sAuthoritative = true;
        }
        apply(map);
        refreshWindows();
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
            boolean written = persist(map);
            sPersisted = map;
            if (written) sAuthoritative = true;
            log("saved current " + map.size() + " keys");
        } catch (Throwable t) {
            log("saveCurrent failed: " + t.getMessage());
        }
    }

    /** 值变化后，把已经打开的标题栏视图也按新开关重刷一遍，不必重开窗口。 */
    private static void refreshWindows() {
        DecoHook hook = sHook;
        if (hook == null) return;
        try {
            hook.refreshAfterConfigChanged();
        } catch (Throwable t) {
            log("refresh windows failed: " + t.getMessage());
        }
    }

    private static boolean persist(Map<String, String> map) {
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
            return false;
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
                return false;
            }
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
        return true;
    }

    private static Map<String, String> readFile() {
        return parse(storeFile());
    }

    /** 按 key=value 解析；文件不存在或读不到就返回空表。 */
    private static Map<String, String> parse(File file) {
        Map<String, String> map = new LinkedHashMap<>();
        if (file == null || !file.exists()) return map;
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

    /**
     * 找到第一个可读的 app 快照文件。系统进程对 /data/data 与 FUSE 路径的读权限不一样，
     * 逐个试，能读到哪个就用哪个。
     */
    private static File readableSnapshot() {
        for (String path : SNAP_PATHS) {
            File f = new File(path);
            if (f.exists() && f.canRead() && f.length() > 0) return f;
        }
        return null;
    }

    /** 读 app 落盘的配置快照；这个文件只有本模块 app 有写权限。 */
    private static Map<String, String> readSnapshot() {
        File f = readableSnapshot();
        return f == null ? new LinkedHashMap<>() : parse(f);
    }

    private static Map<String, String> extrasToMap(Bundle extras) {
        Map<String, String> map = new LinkedHashMap<>();
        if (extras == null) return map;
        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            if (value != null) map.put(key, String.valueOf(value));
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

    /** 落盘文件是否已是权威来源：是的话 DecoHook 不再去查 provider / xprefs。 */
    static boolean isAuthoritative() {
        return sAuthoritative;
    }

    /**
     * AMS.systemReady 之后由 DecoHook 调一次。
     *
     * <p>开机早期 registerReceiver 会抛 NPE（IActivityManager 还是 null），所以注册必须等到
     * 系统真正 ready，而不是在装钩子入口先试再靠定时重试。</p>
     */
    static void onAmsReady() {
        tryRegisterReceiver();
    }

    /** 注册推送接收器：只试一次，成功即置位；失败只留一条日志，不再重试。 */
    private static void tryRegisterReceiver() {
        if (sReceiverRegistered) return;
        Context ctx = sContext;
        if (ctx == null) {
            ctx = systemContext(sCl);
            sContext = ctx;
        }
        if (ctx == null) {
            log("push channel register failed: system context unavailable");
            return;
        }
        try {
            IntentFilter filter = new IntentFilter(ACTION_PUSH);
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || !ACTION_PUSH.equals(intent.getAction())) return;
                    int uid = senderUid(intent);
                    // 只有“明确查到第三方 uid”才拒绝：普通 app 发广播时 getSentFromUid 恒为 -1，
                    // 把“查不到”当成“不可信”会把用户自己的开关全部挡掉。
                    if (uid > 0 && uid != moduleUid() && uid != Process.SYSTEM_UID) {
                        log("rejected foreign push from uid=" + uid);
                        return;
                    }
                    // 优先用 app 落盘的快照（只有本模块 app 能写），读不到才退回 extras
                    Map<String, String> snapshot = readSnapshot();
                    Map<String, String> data = snapshot.isEmpty()
                            ? extrasToMap(intent.getExtras()) : snapshot;
                    log("push accepted uid=" + uid + " snapshot=" + !snapshot.isEmpty()
                            + " keys=" + data.size());
                    onPushFromApp(data);
                }
            }, filter, Context.RECEIVER_EXPORTED);
            sReceiverRegistered = true;
            log("push channel ready");
        } catch (Throwable t) {
            log("push channel register failed: " + t.getMessage());
        }
    }

    /**
     * 发送方 uid。普通 app 发的广播里 {@code getSentFromUid()} 恒为 -1（那个字段只给 shell 广播填），
     * 真正可用的是 {@code getSendingUid()}（AMS 分发时写进去的调用者 uid）。两个都取不到才返回 -1。
     */
    private static int senderUid(Intent intent) {
        int uid = callInt(intent, "getSendingUid");
        if (uid > 0) return uid;
        return callInt(intent, "getSentFromUid");
    }

    private static int callInt(Intent intent, String method) {
        if (intent == null) return -1;
        try {
            Object v = XposedHelpers.callMethod(intent, method);
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** 模块 app 的 uid，查一次缓存住。 */
    private static int moduleUid() {
        int cached = sModuleUid;
        if (cached != -2) return cached;
        Context ctx = sContext;
        if (ctx == null) return -1;
        try {
            int uid = ctx.getPackageManager().getApplicationInfo(MODULE_PKG, 0).uid;
            sModuleUid = uid;
            return uid;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 只认模块 app 自己或系统进程：这份配置决定模块行为，不能由第三方写。 */
    private static boolean trusted(int uid) {
        if (uid == Process.SYSTEM_UID) return true;
        if (uid < 0) return false;
        return moduleUid() == uid;
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