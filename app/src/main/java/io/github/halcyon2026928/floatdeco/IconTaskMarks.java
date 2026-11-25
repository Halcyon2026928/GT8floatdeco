package io.github.halcyon2026928.floatdeco;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「上滑关闭的自由小窗 task」标记：system_server 侧写入，Launcher 侧读取。
 *
 * <p>主通道用 SystemProperties（system_server 有 set 权限、Launcher 读 get 无障碍，
 * 无 SELinux 文件权限问题）；文件通道作为开机兜底与可观测性（方便用 adb 查）。</p>
 *
 * <p>格式：{@code "taskId,timestamp"}。上滑关闭发生时记录 taskId + 时间戳；
 * 最近任务卡片绘制时若在有效期内命中，则该卡片显示图标页而非内容快照。
 * 有效期用于避免 taskId 复用导致的误判。</p>
 */
final class IconTaskMarks {

    private static final String TAG = "[图标页]";
    /** SystemProperties 键；同时只能标记最近一个上滑关闭的 task。
     *  不用 persist. 前缀：重启自动清空，避免残留导致旧 taskId 误命中。 */
    private static final String PROP = "sys.floatdeco.icon_task";
    /** 文件兜底（可观测）。 */
    private static final String FILE = "/data/system/floatdeco_icon_tasks.txt";
    /** 标记有效期：30 分钟内该 task 的最近任务卡片显示图标页。
     *  取值较长是因为用户可能上滑关闭后过一阵才打开最近任务；
     *  即使 taskId 被复用也无害——图标按该 task 自己的包名解析，不会串应用。 */
    private static final long TTL_MS = 30 * 60_000L;

    private IconTaskMarks() {
    }

    /** system_server 侧调用：标记一个 task 为上滑关闭。 */
    static void mark(int taskId) {
        if (taskId < 0) return;
        long now = System.currentTimeMillis();
        String value = taskId + "," + now;
        boolean propOk = false;
        try {
            // 主通道：SystemProperties，跨进程最稳。
            setProp(PROP, value);
            // 回读校验：SELinux 拒绝写入时 set 不抛异常但值不会变，必须回读确认。
            propOk = value.equals(getProp(PROP));
        } catch (Throwable t) {
            XposedBridge.log(TAG + " setProp failed: " + t);
        }
        try {
            // 兜底文件，便于 adb 观测与多 task 记录。
            File f = new File(FILE);
            Map<Integer, Long> marks = readAll(f);
            marks.put(taskId, now);
            for (Map.Entry<Integer, Long> e : marks.entrySet()) {
                if (now - e.getValue() > TTL_MS) marks.remove(e.getKey());
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Integer, Long> e : marks.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            FileWriter w = new FileWriter(f, false);
            try {
                w.write(sb.toString());
            } finally {
                w.close();
            }
            f.setReadable(true, false);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " mark file failed: " + t);
        }
        XposedBridge.log(TAG + " mark taskId=" + taskId + " propOk=" + propOk
                + " readback=" + getProp(PROP));
    }

    /** 是否存在仍在有效期内的标记（任意 task）。 */
    static boolean hasActiveMark() {
        return activeTaskId() >= 0;
    }

    /** 当前有效标记的 taskId，没有则 -1。 */
    static int activeTaskId() {
        try {
            String v = getProp(PROP);
            if (v == null || v.isEmpty()) return -1;
            int comma = v.indexOf(',');
            if (comma <= 0) return -1;
            int id = Integer.parseInt(v.substring(0, comma).trim());
            long ts = Long.parseLong(v.substring(comma + 1).trim());
            return (System.currentTimeMillis() - ts <= TTL_MS) ? id : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 诊断用：打印当前标记状态。 */
    static String describe() {
        String v = getProp(PROP);
        return "prop=" + (v == null ? "null" : v) + " active=" + activeTaskId();
    }

    /** Launcher 侧调用：该 task 是否处于「上滑关闭」标记有效期内。
     *
     *  <p><b>性能约束</b>：本方法在卡片 onDraw 里被调用，横向滑动时每帧每张卡片都会走一次。
     *  所以这里做了两层缓存，绝不能每次真读：</p>
     *  <ul>
     *   <li>SystemProperties 的方法句柄静态缓存，不再每帧反射查找；</li>
     *   <li>属性值 400ms 内复用，避免每帧 native get + 字符串解析；</li>
     *   <li><b>不读文件</b>——文件通道只用于 adb 观测和 describe()，放到绘制热路径上是致命卡顿源。</li>
     *  </ul>
     */
    static boolean isMarked(int taskId) {
        if (taskId < 0) return false;
        long now = System.currentTimeMillis();
        if (now - sPropAt > 400L) {
            sPropAt = now;
            sPropTaskId = -1;
            sPropTs = 0L;
            try {
                String v = getProp(PROP);
                if (v != null && !v.isEmpty()) {
                    int comma = v.indexOf(',');
                    if (comma > 0) {
                        sPropTaskId = Integer.parseInt(v.substring(0, comma).trim());
                        sPropTs = Long.parseLong(v.substring(comma + 1).trim());
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return sPropTaskId == taskId && now - sPropTs <= TTL_MS;
    }

    /** 属性缓存：避免绘制热路径每帧 native 调用 + 反射。 */
    private static volatile long sPropAt;
    private static volatile int sPropTaskId = -1;
    private static volatile long sPropTs;

    /** SystemProperties 方法句柄缓存：反射查找只做一次。 */
    private static Method sSetMethod;
    private static Method sGetMethod;
    private static boolean sMethodsResolved;

    private static void setProp(String key, String value) {
        try {
            Method set = systemPropertiesSet();
            if (set != null) set.invoke(null, key, value);
        } catch (Throwable ignored) {
        }
    }

    private static String getProp(String key) {
        try {
            Method get = systemPropertiesGet();
            if (get == null) return null;
            Object v = get.invoke(null, key);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static synchronized Method systemPropertiesSet() {
        if (!sMethodsResolved) resolveSystemProperties();
        return sSetMethod;
    }

    private static synchronized Method systemPropertiesGet() {
        if (!sMethodsResolved) resolveSystemProperties();
        return sGetMethod;
    }

    private static void resolveSystemProperties() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sSetMethod = sp.getMethod("set", String.class, String.class);
            sGetMethod = sp.getMethod("get", String.class);
        } catch (Throwable ignored) {
        }
        sMethodsResolved = true;
    }

    private static Map<Integer, Long> readAll(File f) {
        Map<Integer, Long> out = new LinkedHashMap<>();
        if (f == null || !f.exists()) return out;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                try {
                    int id = Integer.parseInt(line.substring(0, eq).trim());
                    long ts = Long.parseLong(line.substring(eq + 1).trim());
                    out.put(id, ts);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }
}
