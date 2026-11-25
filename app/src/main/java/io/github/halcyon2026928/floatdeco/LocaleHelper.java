package io.github.halcyon2026928.floatdeco;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

/**
 * 应用内语言：只改模块自己的界面，不动系统语言，也不影响 LSPosed 注入的桌面进程。
 *
 * <p>做法是给每个 Activity 的 {@code attachBaseContext} 套一个改了 locale 的
 * Configuration，切换时 {@code recreate()} 让资源重新解析。没选过语言时按系统语言决定：
 * 中文系统用中文，其余一律英文——本地只有中英两套资源，别让法语用户掉回中文。</p>
 */
public final class LocaleHelper {

    private static final String PREFS = "floatdeco_ui";
    private static final String KEY_LANG = "app_language";

    public static final String ZH = "zh";
    public static final String EN = "en";

    private LocaleHelper() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 当前生效的语言：选过就按选的，没选过跟随系统。 */
    public static String current(Context c) {
        String v;
        try {
            v = prefs(c).getString(KEY_LANG, null);
        } catch (Throwable t) {
            v = null;
        }
        if (EN.equals(v) || ZH.equals(v)) return v;
        return systemIsChinese() ? ZH : EN;
    }

    public static boolean isChinese(Context c) {
        return ZH.equals(current(c));
    }

    private static boolean systemIsChinese() {
        Locale l = Locale.getDefault();
        return l != null && "zh".equals(l.getLanguage());
    }

    public static void set(Context c, String lang) {
        prefs(c).edit().putString(KEY_LANG, lang).apply();
    }

    public static void toggle(Context c) {
        set(c, isChinese(c) ? EN : ZH);
    }

    /**
     * 给 Activity 换一套 locale 的 Context。
     *
     * <p>{@code Locale.setDefault} 必须跟着改：应用列表排序用的是
     * {@code Collator.getInstance(Locale.getDefault())}，不设的话英文界面仍按中文规则排序。</p>
     */
    public static Context wrap(Context base) {
        Locale locale = new Locale(current(base));
        Locale.setDefault(locale);
        Configuration conf = new Configuration(base.getResources().getConfiguration());
        conf.setLocale(locale);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            conf.setLocales(new LocaleList(locale));
        }
        conf.setLayoutDirection(locale);
        return base.createConfigurationContext(conf);
    }
}
