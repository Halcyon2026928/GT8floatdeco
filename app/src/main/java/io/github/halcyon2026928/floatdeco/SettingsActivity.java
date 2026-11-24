package io.github.halcyon2026928.floatdeco;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 模块设置页：两个开关 + 右上角主题按钮（跟随系统 / 浅色 / 深色 三态循环）。
 * 改完开关立即推给 system_server 落盘（重启记忆）。
 */
public class SettingsActivity extends Activity {

    static final String KEY_HIDE_BOTTOM_BAR = "hide_bottom_bar";
    static final String KEY_HIDE_THREE_DOT = "hide_three_dot_menu";
    static final String KEY_FIRST_DRAG_DOCK = "first_drag_to_dock";

    static final String KEY_THEME_MODE = "theme_mode";
    static final int THEME_FOLLOW_SYSTEM = 0;
    static final int THEME_LIGHT = 1;
    static final int THEME_DARK = 2;

    private static final String ACTION_PUSH = "io.github.halcyon2026928.floatdeco.SETTINGS_PUSHED";
    private static final String UI_PREFS = "floatdeco_ui";

    private Switch bottomBarSwitch;
    private Switch threeDotSwitch;
    private Switch firstDragSwitch;
    private boolean syncing;

    private boolean light;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final int themeMode = resolveThemeMode();
        light = isLightOf(themeMode);

        getWindow().setStatusBarColor(light ? 0xFFF3F1FA : 0xFF0B1020);
        getWindow().setNavigationBarColor(light ? 0xFFF3F1FA : 0xFF0B1020);
        setLightStatusBar(light);

        setContentView(buildUi(themeMode));
        syncFromPrefs();
    }

    private void setLightStatusBar(boolean lightIcons) {
        try {
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (lightIcons) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            decor.setSystemUiVisibility(flags);
        } catch (Throwable ignored) {
        }
    }

    private View buildUi(int themeMode) {
        int bg = light ? 0xFFF3F1FA : 0xFF0B1020;
        int cardBg = light ? 0xFFFFFFFF : 0xFF1A1F2E;
        int titleColor = light ? 0xFF0F172A : 0xFFFFFFFF;
        int subColor = light ? 0xFF64748B : 0xFF94A3B8;
        int hintColor = light ? 0xFF94A3B8 : 0xFF64748B;

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(36), pad, dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView pill = new TextView(this);
        pill.setText("自由小窗OS · 独立模块");
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(14);
        pill.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0xFF6C5CE7);
        pillBg.setCornerRadius(dp(22));
        pill.setBackground(pillBg);
        root.addView(pill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 标题行：大标题 + 右上角圆形主题按钮
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleRowLp.topMargin = dp(18);
        root.addView(titleRow, titleRowLp);

        TextView title = new TextView(this);
        title.setText("自由小窗OS");
        title.setTextColor(titleColor);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView themeBtn = new TextView(this);
        themeBtn.setGravity(Gravity.CENTER);
        themeBtn.setText(themeGlyphOf(themeMode));
        themeBtn.setTextSize(18);
        themeBtn.setTextColor(Color.WHITE);
        int size = dp(44);
        GradientDrawable themeBg = new GradientDrawable(
                GradientDrawable.Orientation.BL_TR,
                new int[]{0xFF8B5CF6, 0xFF22D3EE});
        themeBg.setShape(GradientDrawable.OVAL);
        themeBtn.setBackground(themeBg);
        themeBtn.setContentDescription(labelOf(themeMode));
        themeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int next = nextThemeMode(resolveThemeMode());
                getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
                        .edit().putInt(KEY_THEME_MODE, next).commit();
                try {
                    getContentResolver().notifyChange(ConfigProvider.CONFIG_URI, null);
                } catch (Throwable ignored) {
                }
                recreate();
            }
        });
        titleRow.addView(themeBtn, new LinearLayout.LayoutParams(size, size));

        TextView subtitle = new TextView(this);
        subtitle.setText("自由小窗标题栏装饰项，可独立开关");
        subtitle.setTextColor(subColor);
        subtitle.setTextSize(15);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(6);
        subLp.bottomMargin = dp(18);
        root.addView(subtitle, subLp);

        bottomBarSwitch = buildSwitchRow(root, "隐藏窗口白条",
                "隐藏自由小窗底部的白色手势横条", KEY_HIDE_BOTTOM_BAR,
                cardBg, titleColor, subColor);
        threeDotSwitch = buildSwitchRow(root, "隐藏三点菜单栏",
                "移除顶部三点按钮（视觉、点击、触摸穿透）", KEY_HIDE_THREE_DOT,
                cardBg, titleColor, subColor);
        firstDragSwitch = buildSwitchRow(root, "首次拖动直达坞窗",
                "官方默认第一次拖动收起会最小化，开启后第一次也直接进坞窗", KEY_FIRST_DRAG_DOCK,
                cardBg, titleColor, subColor);

        TextView hint = new TextView(this);
        hint.setText("开关会记住你的选择，重启手机后保持上次状态。改开关后重新打开自由小窗即可生效。");
        hint.setTextColor(hintColor);
        hint.setTextSize(13);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(16);
        root.addView(hint, hintLp);

        return scroll;
    }

    private Switch buildSwitchRow(LinearLayout parent, String name, String desc, final String key,
                                  int cardBg, int titleColor, int subColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(20), dp(18), dp(18), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardBg);
        bg.setCornerRadius(dp(20));
        card.setBackground(bg);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(name);
        title.setTextColor(titleColor);
        title.setTextSize(18);
        texts.addView(title);
        TextView sub = new TextView(this);
        sub.setText(desc);
        sub.setTextColor(subColor);
        sub.setTextSize(13);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        texts.addView(sub, subLp);
        card.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (syncing) return;
                writePref(key, isChecked);
            }
        });
        card.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(14);
        parent.addView(card, cardLp);
        return sw;
    }

    private void syncFromPrefs() {
        syncing = true;
        bottomBarSwitch.setChecked(readPref(KEY_HIDE_BOTTOM_BAR, true));
        threeDotSwitch.setChecked(readPref(KEY_HIDE_THREE_DOT, true));
        firstDragSwitch.setChecked(readPref(KEY_FIRST_DRAG_DOCK, true));
        syncing = false;
    }

    private boolean readPref(String key, boolean def) {
        return ModulePrefs.get(this).getBoolean(key, def);
    }

    private void writePref(String key, boolean value) {
        ModulePrefs.get(this).edit().putBoolean(key, value).commit();
        // commit 会重写 xml（权限回到 0600），必须马上放开成 world-readable，
        // 否则模块 app 进程退出后 system_server 就读不到，开关表现为全部恢复默认。
        ModulePrefs.makePrefsReadable(this);
        // 顺手把整份开关推给 system_server 落盘：它开机先读自己的那份，不依赖 xprefs。
        pushSettings(this);
    }

    /**
     * 把当前开关整份推给 system_server（写进 /data/system 下的文件，开机时先用它）。
     */
    static void pushSettings(Context ctx) {
        if (ctx == null) return;
        try {
            SharedPreferences prefs = ModulePrefs.get(ctx);
            Intent intent = new Intent(ACTION_PUSH);
            intent.putExtra(KEY_HIDE_BOTTOM_BAR, prefs.getBoolean(KEY_HIDE_BOTTOM_BAR, true));
            intent.putExtra(KEY_HIDE_THREE_DOT, prefs.getBoolean(KEY_HIDE_THREE_DOT, true));
            intent.putExtra(KEY_FIRST_DRAG_DOCK, prefs.getBoolean(KEY_FIRST_DRAG_DOCK, true));
            ctx.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    // ==================== 主题 ====================

    private int resolveThemeMode() {
        SharedPreferences ui = getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE);
        if (ui.contains(KEY_THEME_MODE)) {
            int mode = ui.getInt(KEY_THEME_MODE, THEME_FOLLOW_SYSTEM);
            return (mode == THEME_LIGHT || mode == THEME_DARK) ? mode : THEME_FOLLOW_SYSTEM;
        }
        return THEME_FOLLOW_SYSTEM;
    }

    private static int nextThemeMode(int current) {
        if (current == THEME_FOLLOW_SYSTEM) return THEME_LIGHT;
        if (current == THEME_LIGHT) return THEME_DARK;
        return THEME_FOLLOW_SYSTEM;
    }

    private boolean isLightOf(int mode) {
        if (mode == THEME_LIGHT) return true;
        if (mode == THEME_DARK) return false;
        int night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK);
        return night != Configuration.UI_MODE_NIGHT_YES;
    }

    private static String themeGlyphOf(int mode) {
        if (mode == THEME_LIGHT) return "☀";
        if (mode == THEME_DARK) return "☾";
        return "◐";
    }

    private static String labelOf(int mode) {
        if (mode == THEME_LIGHT) return "浅色模式";
        if (mode == THEME_DARK) return "深色模式";
        return "跟随系统";
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
