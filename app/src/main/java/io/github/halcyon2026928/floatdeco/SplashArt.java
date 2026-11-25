package io.github.halcyon2026928.floatdeco;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;

import java.util.HashMap;
import java.util.Map;

/**
 * 解析每个 app「真实启动页」的视觉资源。
 *
 * <p>启动页（SplashScreen）在 Android 12+ 由三部分组成，全都写死在 app 的主题里：
 * <ul>
 *   <li>{@code android:windowSplashScreenBackground} —— 背景色</li>
 *   <li>{@code android:windowSplashScreenAnimatedIcon} —— 居中图标（品牌 logo 或 app 图标）</li>
 *   <li>{@code android:windowSplashScreenIconBackgroundColor} —— 图标底圈色</li>
 * </ul>
 * Android 12 以下以及大量国产 app 不写这三个属性，但它们会把启动页整张图挂在
 * {@code android:windowBackground} 上（logo + 背景一张图，或者纯色 + logo）。
 *
 * <p>所以按「splashIcon → windowBackground 整图 → windowBackground 纯色 + app 图标」
 * 三级降级，就能还原绝大多数 app 启动页的样子。
 *
 * <p>结果按包名缓存：解析主题要走 PackageManager + Resources，很慢，只在真正要画图标页时做一次。
 */
final class SplashArt {

    /** 主视觉来源：splash-icon / window-bg / app-icon。 */
    final String source;
    /** 主视觉。fullBleed=true 时是整张铺满的启动图；false 时是居中图标。 */
    final Drawable art;
    final boolean fullBleed;
    /** 背景色（art 为空或需要垫底时使用）。 */
    final int bg;
    /** 兜底 app 图标（art 为空时画它）。 */
    final Drawable icon;
    /** 启动图标的圆形底圈色（windowSplashScreenIconBackgroundColor），0 = 没设。 */
    final int iconBg;

    /**
     * 底部署名图（windowSplashScreenBrandingImage）。
     *
     * <p>有些 app（米家就是）把 {@code windowSplashScreenAnimatedIcon} 设成一张
     * <b>全透明的色</b>，真正要显示的 logo 挂在 BrandingImage 上——那是系统画在屏幕底部的小图。
     * 所以主视觉量出来画不出像素时，要退到这张图上，否则卡片就是一片空白。</p>
     */
    final Drawable branding;

    /** 最终真正要画的那个 drawable 与它的内容框（layer-list 拆层 / 退到署名图之后的结果）。 */
    volatile Drawable chosen;
    volatile float[] chosenBox;
    volatile boolean chosenTried;

    private SplashArt(String source, Drawable art, boolean fullBleed, int bg,
                      Drawable icon, int iconBg, Drawable branding) {
        this.source = source;
        this.art = art;
        this.fullBleed = fullBleed;
        this.bg = bg;
        this.icon = icon;
        this.iconBg = iconBg;
        this.branding = branding;
    }

    private static final Map<String, SplashArt> CACHE = new HashMap<>();

    /** 取某个包名的启动页视觉，失败返回 null。结果带缓存。 */
    static SplashArt of(Context ctx, String pkg) {
        if (ctx == null || pkg == null || pkg.isEmpty()) return null;
        synchronized (CACHE) {
            SplashArt cached = CACHE.get(pkg);
            if (cached != null) return cached;
        }
        SplashArt resolved = resolve(ctx, pkg);
        synchronized (CACHE) {
            CACHE.put(pkg, resolved);
        }
        return resolved;
    }

    private static SplashArt resolve(Context ctx, String pkg) {
        Drawable appIcon = null;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            appIcon = pm.getApplicationIcon(ai);

            Resources res = pm.getResourcesForApplication(ai);
            Resources.Theme theme = res.newTheme();
            if (ai.theme != 0) {
                try {
                    theme.applyStyle(ai.theme, true);
                } catch (Throwable ignored) {
                }
            }

            int idSplashIcon = res.getIdentifier("windowSplashScreenAnimatedIcon", "attr", "android");
            int idSplashBg = res.getIdentifier("windowSplashScreenBackground", "attr", "android");
            int idIconBg = res.getIdentifier("windowSplashScreenIconBackgroundColor", "attr", "android");
            int idBranding = res.getIdentifier("windowSplashScreenBrandingImage", "attr", "android");
            int idWindowBg = res.getIdentifier("windowBackground", "attr", "android");
            int idColorBg = res.getIdentifier("colorBackground", "attr", "android");

            Drawable splashIcon = attrDrawable(theme, idSplashIcon);
            Drawable branding = attrDrawable(theme, idBranding);
            int bg = attrColor(theme, idSplashBg, 0);
            if (bg == 0) bg = attrColor(theme, idColorBg, 0);
            int iconBg = attrColor(theme, idIconBg, 0);

            if (splashIcon != null && bg != 0) {
                // 标准 Android 12 SplashScreen：纯色背景 + 居中品牌图标。
                return new SplashArt("splash-icon", splashIcon, false, bg, appIcon, iconBg, branding);
            }

            // 没有 splash 图标，看 windowBackground：整图启动页 or 纯色背景。
            Drawable windowBg = attrDrawable(theme, idWindowBg);
            if (windowBg instanceof ColorDrawable) {
                int c = ((ColorDrawable) windowBg).getColor();
                // 全透明视为「没写」，免得画出空背景。
                if ((c >>> 24) != 0) bg = c;
                windowBg = null;
            }
            if (windowBg != null) {
                // 有真实内容：当作整张启动图铺满（国产 app 最常见的样子）。
                return new SplashArt("window-bg", windowBg, true, bg != 0 ? bg : 0xFF202020,
                        appIcon, iconBg, branding);
            }
            if (splashIcon != null) {
                return new SplashArt("splash-icon-no-bg", splashIcon, false,
                        bg != 0 ? bg : 0xFF202020, appIcon, iconBg, branding);
            }
            if (bg != 0) {
                // 只有背景色，没有图：纯色 + app 图标（这就是系统默认启动页）。
                return new SplashArt("color-app-icon", null, false, bg, appIcon, iconBg, branding);
            }
        } catch (Throwable t) {
            XposedBridge.log("[卡片封面] splash resolve failed pkg=" + pkg + " " + t);
        }
        // 什么都拿不到：深色底 + app 图标。
        return new SplashArt("app-icon", null, false, 0xFF2C2C2A, appIcon, 0, null);
    }

    private static Drawable attrDrawable(Resources.Theme theme, int attrId) {
        if (attrId == 0) return null;
        try {
            android.content.res.TypedArray ta = theme.obtainStyledAttributes(new int[]{attrId});
            try {
                return ta.getDrawable(0);
            } finally {
                ta.recycle();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static int attrColor(Resources.Theme theme, int attrId, int fallback) {
        if (attrId == 0) return fallback;
        try {
            TypedValue tv = new TypedValue();
            if (theme.resolveAttribute(attrId, tv, true)) {
                if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                        && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return tv.data;
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }
}
