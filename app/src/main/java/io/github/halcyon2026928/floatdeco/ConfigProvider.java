package io.github.halcyon2026928.floatdeco;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.Map;

/**
 * 给 Launcher / system_server 的跨进程通道。重启后的权威数据在 XSharedPreferences。
 *
 * <p>三类路径：</p>
 * <ul>
 *   <li>{@code /config}：开关值（原有）</li>
 *   <li>{@code /custom}：已自定义卡片图片的「包名 + 版本」列表</li>
 *   <li>{@code /custom_cover/<包名>}：图片本体，通过 openFile 交出 FD</li>
 * </ul>
 *
 * <p>图片必须走 provider 而不是直接给文件路径：模块私有目录是 0700/0660，Launcher 是
 * 另一个 uid 读不了。由本进程打开文件、把 FD 传出去，权限问题就绕开了。</p>
 */
public class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.halcyon2026928.floatdeco.config";
    public static final Uri CONFIG_URI = Uri.parse("content://" + AUTHORITY + "/config");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        String path = firstSegment(uri);
        // 自定义图片列表：只枚举目录，不做任何开关推送（这条会被每 2 秒轮询一次）。
        if (CustomCovers.PATH_CUSTOM.equals(path)) {
            MatrixCursor c = new MatrixCursor(new String[]{"pkg", "version"});
            for (Map.Entry<String, Long> e : CustomCovers.listLocal(getCtx()).entrySet()) {
                c.addRow(new Object[]{e.getKey(), e.getValue()});
            }
            return c;
        }

        // 每次被查询时顺手把 prefs 设成可读，保证 XSharedPreferences 兜底通道有效。
        ModulePrefs.makePrefsReadable(getCtx());
        SharedPreferences prefs = ModulePrefs.get(getCtx());
        MatrixCursor c = new MatrixCursor(new String[]{"key", "value"});
        c.addRow(new Object[]{SettingsActivity.KEY_HIDE_BOTTOM_BAR,
                prefs.getBoolean(SettingsActivity.KEY_HIDE_BOTTOM_BAR, true) ? 1 : 0});
        c.addRow(new Object[]{SettingsActivity.KEY_HIDE_THREE_DOT,
                prefs.getBoolean(SettingsActivity.KEY_HIDE_THREE_DOT, true) ? 1 : 0});
        c.addRow(new Object[]{SettingsActivity.KEY_FIRST_DRAG_DOCK,
                prefs.getBoolean(SettingsActivity.KEY_FIRST_DRAG_DOCK, true) ? 1 : 0});
        c.addRow(new Object[]{SettingsActivity.KEY_LIVELY_HOURS,
                prefs.getInt(SettingsActivity.KEY_LIVELY_HOURS, 1)});
        c.addRow(new Object[]{SettingsActivity.KEY_LIVELY_UNLOCK_COUNT,
                prefs.getBoolean(SettingsActivity.KEY_LIVELY_UNLOCK_COUNT, true) ? 1 : 0});
        c.addRow(new Object[]{SettingsActivity.KEY_CARD_COVER,
                prefs.getBoolean(SettingsActivity.KEY_CARD_COVER, true) ? 1 : 0});
        // 卡片显示方式（0 裁剪 / 1 启动页 / 2 官方）。Launcher 侧真正读的是这一行；
        // 下面那行布尔是给 system_server 的旧通道做兼容。
        int displayMode;
        if (prefs.contains(CardCoverHook.KEY_DISPLAY_MODE)) {
            displayMode = prefs.getInt(CardCoverHook.KEY_DISPLAY_MODE, CardCoverHook.MODE_COVER);
            if (displayMode != CardCoverHook.MODE_SPLASH
                    && displayMode != CardCoverHook.MODE_OFFICIAL) {
                displayMode = CardCoverHook.MODE_COVER;
            }
        } else {
            displayMode = prefs.getBoolean(SettingsActivity.KEY_ICON_PAGE, false)
                    ? CardCoverHook.MODE_SPLASH : CardCoverHook.MODE_COVER;
        }
        c.addRow(new Object[]{CardCoverHook.KEY_DISPLAY_MODE, displayMode});
        c.addRow(new Object[]{SettingsActivity.KEY_ICON_PAGE,
                displayMode == CardCoverHook.MODE_SPLASH ? 1 : 0});
        // 被查询即顺手推一份落盘：system_server 里那份文件是开机恢复的唯一来源，
        // 这样即便用户改完设置就直接关掉 app，下次开机也能拿到最新的值。
        SettingsActivity.pushSettings(getCtx());
        return c;
    }

    @Override
    public String getType(Uri uri) {
        String path = firstSegment(uri);
        if (CustomCovers.PATH_CUSTOM.equals(path)) {
            return "vnd.android.cursor.dir/vnd.floatdeco.custom";
        }
        if (CustomCovers.PATH_COVER.equals(path)) {
            return "image/jpeg";
        }
        return "vnd.android.cursor.dir/vnd.floatdeco.config";
    }

    /** 交出图片 FD：文件由本进程打开，调用方不需要有读私有目录的权限。 */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        List<String> segs = uri.getPathSegments();
        if (segs.size() >= 2 && CustomCovers.PATH_COVER.equals(segs.get(0))) {
            File f = CustomCovers.fileOf(getCtx(), segs.get(1));
            if (f.isFile()) {
                return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            }
        }
        throw new FileNotFoundException(uri.toString());
    }

    private static String firstSegment(Uri uri) {
        List<String> segs = uri == null ? null : uri.getPathSegments();
        return segs == null || segs.isEmpty() ? "" : segs.get(0);
    }

    private Context getCtx() {
        Context ctx = getContext();
        if (ctx == null) throw new IllegalStateException("provider detached");
        return ctx;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
