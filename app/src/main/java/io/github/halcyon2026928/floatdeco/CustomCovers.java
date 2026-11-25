package io.github.halcyon2026928.floatdeco;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用自定义卡片图片：用户给某个应用指定一张图，该应用被上滑关闭后，
 * 最近任务卡片就显示这张图，而不是启动页 / 内容快照。
 *
 * <p><b>为什么要走 ContentProvider</b>：图片存在模块 app 的私有目录里，Launcher 是另一个
 * uid，{@code canRead=false}（跟 prefs 0660 是同一个坑）。所以图片必须由模块进程打开成
 * FD 交给 Launcher：{@code content://…/custom_cover/<包名>} → {@link ConfigProvider#openFile}。
 * 列表同理，走 {@code content://…/custom}，返回「包名 + 版本」。</p>
 *
 * <p><b>两个进程的角色</b>：模块进程负责写入 / 枚举（{@link #saveFromUri} /
 * {@link #listLocal}）；Launcher 进程负责同步与解码（{@link #sync} / {@link #bitmapFor}）。
 * Launcher 侧的解码全部发生在配置轮询线程上，绘制线程只查内存。</p>
 */
public final class CustomCovers {

    /** 图片目录（模块私有）：files/app_cover/&lt;包名&gt;.jpg */
    private static final String DIR = "app_cover";

    /** 存图宽度上限。卡片约 936px 宽，800 已经够清晰，再大只是白占内存。 */
    private static final int MAX_WIDTH = 800;
    private static final int JPEG_QUALITY = 88;

    /** Launcher 侧最多缓存几张解码后的图（每张约 5MB，控制总内存）。 */
    private static final int MAX_BITMAPS = 3;

    static final String PATH_CUSTOM = "custom";
    static final String PATH_COVER = "custom_cover";

    /** 列表：包名 + 版本号。 */
    public static final Uri LIST_URI =
            Uri.parse("content://" + ConfigProvider.AUTHORITY + "/" + PATH_CUSTOM);

    public static Uri coverUri(String pkg) {
        return Uri.parse("content://" + ConfigProvider.AUTHORITY + "/" + PATH_COVER + "/" + pkg);
    }

    private CustomCovers() {
    }

    // ==================== 模块进程：写入 / 枚举 ====================

    public static File dirOf(Context ctx) {
        return new File(ctx.getFilesDir(), DIR);
    }

    public static File fileOf(Context ctx, String pkg) {
        return new File(dirOf(ctx), sanitize(pkg) + ".jpg");
    }

    public static boolean has(Context ctx, String pkg) {
        return pkg != null && fileOf(ctx, pkg).isFile();
    }

    /** 当前已自定义的「包名 → 版本」。版本用 mtime^length，文件一换值就变。 */
    public static Map<String, Long> listLocal(Context ctx) {
        Map<String, Long> out = new LinkedHashMap<>();
        File[] files = dirOf(ctx).listFiles();
        if (files == null) return out;
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".jpg")) continue;
            out.put(name.substring(0, name.length() - 4), f.lastModified() ^ f.length());
        }
        return out;
    }

    public static void delete(Context ctx, String pkg) {
        if (pkg == null) return;
        try {
            fileOf(ctx, pkg).delete();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把用户选的图片压好存下来。
     *
     * <p>三步：按 EXIF 摆正 → 缩到 {@link #MAX_WIDTH} → 存 JPEG。EXIF 必须自己处理，
     * BitmapFactory 不解旋转，相机拍的照片直接存会躺倒。</p>
     */
    public static boolean saveFromUri(Context ctx, String pkg, Uri src) {
        if (ctx == null || pkg == null || src == null) return false;
        List<Bitmap> made = new ArrayList<>();
        InputStream in = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            in = ctx.getContentResolver().openInputStream(src);
            BitmapFactory.decodeStream(in, null, bounds);
            closeQuietly(in);
            in = null;
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;

            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            in = ctx.getContentResolver().openInputStream(src);
            Bitmap decoded = BitmapFactory.decodeStream(in, null, opts);
            closeQuietly(in);
            in = null;
            if (decoded == null) return false;
            made.add(decoded);

            Bitmap current = decoded;
            int degrees = exifRotation(ctx, src);
            if (degrees != 0) {
                Matrix m = new Matrix();
                m.postRotate(degrees);
                Bitmap rotated = Bitmap.createBitmap(current, 0, 0,
                        current.getWidth(), current.getHeight(), m, true);
                made.add(rotated);
                current = rotated;
            }
            if (current.getWidth() > MAX_WIDTH) {
                int h = Math.max(1, Math.round(
                        current.getHeight() * (MAX_WIDTH / (float) current.getWidth())));
                Bitmap scaled = Bitmap.createScaledBitmap(current, MAX_WIDTH, h, true);
                made.add(scaled);
                current = scaled;
            }

            File dir = dirOf(ctx);
            if (!dir.exists() && !dir.mkdirs()) return false;
            // 先写临时文件再改名：避免 Launcher 刚好读到写了一半的图。
            File tmp = new File(dir, sanitize(pkg) + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            boolean ok;
            try {
                ok = current.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
                out.flush();
            } finally {
                out.close();
            }
            if (!ok) {
                tmp.delete();
                return false;
            }
            File dst = fileOf(ctx, pkg);
            if (!tmp.renameTo(dst)) {
                dst.delete();
                if (!tmp.renameTo(dst)) {
                    tmp.delete();
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            closeQuietly(in);
            for (Bitmap b : made) {
                if (!b.isRecycled()) b.recycle();
            }
        }
    }

    private static int exifRotation(Context ctx, Uri src) {
        InputStream in = null;
        try {
            in = ctx.getContentResolver().openInputStream(src);
            if (in == null) return 0;
            android.media.ExifInterface exif = new android.media.ExifInterface(in);
            int o = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
            switch (o) {
                case android.media.ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (Throwable t) {
            return 0;
        } finally {
            closeQuietly(in);
        }
    }

    // ==================== Launcher 进程：同步 / 取图 ====================

    private static final Object LOCK = new Object();
    /** 包名 → 解码后的图（LRU，超出上限自动丢最久未用的引用，不 recycle）。 */
    private static final LinkedHashMap<String, Bitmap> sBitmaps =
            new LinkedHashMap<String, Bitmap>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > MAX_BITMAPS;
                }
            };
    private static final Map<String, Long> sVersions = new HashMap<>();
    /** 绘制线程的快速判空：没有任何自定义图时完全不进 infoOf。 */
    private static volatile boolean sAny;

    public static boolean hasAny() {
        return sAny;
    }

    /** 绘制线程调用：纯内存查表，无 IO。 */
    public static Bitmap bitmapFor(String pkg) {
        if (!sAny || pkg == null) return null;
        synchronized (LOCK) {
            Bitmap b = sBitmaps.get(pkg);
            return b != null && !b.isRecycled() ? b : null;
        }
    }

    /**
     * 配置轮询线程调用：对齐 provider 里的列表，把变化的图解码进缓存。
     *
     * @return 缓存是否发生变化（调用方据此决定要不要让卡片重绘）
     */
    public static boolean sync(Context ctx) {
        if (ctx == null) return false;
        Map<String, Long> remote = queryList(ctx);
        if (remote == null) return false;

        boolean changed = false;
        List<String> toLoad = new ArrayList<>();
        synchronized (LOCK) {
            Iterator<Map.Entry<String, Long>> it = sVersions.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Long> e = it.next();
                if (!remote.containsKey(e.getKey())) {
                    it.remove();
                    sBitmaps.remove(e.getKey());
                    changed = true;
                }
            }
            for (Map.Entry<String, Long> e : remote.entrySet()) {
                Long old = sVersions.get(e.getKey());
                if (old == null || !old.equals(e.getValue())) {
                    toLoad.add(e.getKey());
                }
            }
        }

        // 解码放在锁外：一张 800px 的 JPEG 解码要几毫秒，别卡住绘制线程的查询。
        for (String pkg : toLoad) {
            Bitmap b = decode(ctx, pkg);
            if (b == null) continue;
            Long v = remote.get(pkg);
            synchronized (LOCK) {
                sBitmaps.put(pkg, b);
                if (v != null) sVersions.put(pkg, v);
                sAny = !sBitmaps.isEmpty();
            }
            changed = true;
        }
        synchronized (LOCK) {
            sAny = !sBitmaps.isEmpty() || !sVersions.isEmpty();
        }
        return changed;
    }

    private static Map<String, Long> queryList(Context ctx) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(LIST_URI, null, null, null, null);
            if (c == null) return null;
            Map<String, Long> out = new LinkedHashMap<>();
            while (c.moveToNext()) {
                out.put(c.getString(0), c.getLong(1));
            }
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    private static Bitmap decode(Context ctx, String pkg) {
        InputStream in = null;
        try {
            in = ctx.getContentResolver().openInputStream(coverUri(pkg));
            if (in == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeStream(in, null, o);
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    // ==================== 工具 ====================

    private static String sanitize(String pkg) {
        if (pkg == null) return "";
        StringBuilder sb = new StringBuilder(pkg.length());
        for (int i = 0; i < pkg.length(); i++) {
            char ch = pkg.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '.' || ch == '_' || ch == '-';
            sb.append(ok ? ch : '_');
        }
        return sb.toString();
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (Throwable ignored) {
        }
    }
}
