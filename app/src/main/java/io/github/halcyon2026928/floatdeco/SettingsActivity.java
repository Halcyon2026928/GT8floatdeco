package io.github.halcyon2026928.floatdeco;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 设置页：与灵动坞OS 同一套视觉（渐变背景 + 卡片分组 + 大标题点开二级项）。
 * 右上角主题按钮三态循环（跟随系统 / 浅色 / 深色），改完立即推给 system_server 落盘。
 */
public class SettingsActivity extends Activity {

    static final String KEY_HIDE_BOTTOM_BAR = "hide_bottom_bar";
    static final String KEY_HIDE_THREE_DOT = "hide_three_dot_menu";
    static final String KEY_FIRST_DRAG_DOCK = "first_drag_to_dock";
    static final String KEY_LIVELY_HOURS = "lively_run_hours";
    static final String KEY_LIVELY_UNLOCK_COUNT = "lively_unlock_count";

    static final String KEY_THEME_MODE = "theme_mode";
    static final int THEME_FOLLOW_SYSTEM = 0;
    static final int THEME_LIGHT = 1;
    static final int THEME_DARK = 2;

    private static final String ACTION_PUSH = "io.github.halcyon2026928.floatdeco.SETTINGS_PUSHED";
    private static final String UI_PREFS = "floatdeco_ui";
    private static final String KEY_GROUP_FREEWINDOW_EXPANDED = "group_free_window_expanded";
    private static final String KEY_GROUP_BACKGROUND_EXPANDED = "group_background_expanded";

    /** 拖动条档位：1、2、4、8、12 小时，最后一档 0 表示不限。 */
    private static final int[] HOUR_STEPS = {1, 2, 4, 8, 12, 0};

    private Switch bottomBarSwitch;
    private Switch threeDotSwitch;
    private Switch firstDragSwitch;
    private Switch livelyCountSwitch;
    private SeekBar livelySeek;
    private TextView livelyValue;

    private boolean syncing;
    private boolean light;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final int themeMode = resolveThemeMode();
        light = isLightOf(themeMode);

        setTheme(light ? R.style.LightTheme : R.style.AppTheme);
        setContentView(R.layout.activity_settings);
        applyStatusBar(light);

        ImageView themeIcon = findViewById(R.id.ivThemeIcon);
        if (themeIcon != null) themeIcon.setImageResource(iconOf(themeMode));

        View themeBtn = findViewById(R.id.btnTheme);
        if (themeBtn != null) {
            themeBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int next = nextThemeMode(resolveThemeMode());
                    getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
                            .edit().putInt(KEY_THEME_MODE, next).commit();
                    recreate();
                }
            });
        }

        settleGroup(R.id.header_free_window, R.id.items_free_window,
                R.id.arrow_free_window, KEY_GROUP_FREEWINDOW_EXPANDED);
        settleGroup(R.id.header_background, R.id.items_background,
                R.id.arrow_background, KEY_GROUP_BACKGROUND_EXPANDED);

        bottomBarSwitch = findViewById(R.id.switch_bottom_bar);
        threeDotSwitch = findViewById(R.id.switch_three_dot);
        firstDragSwitch = findViewById(R.id.switch_first_drag);
        livelyCountSwitch = findViewById(R.id.switch_lively_count);
        livelySeek = findViewById(R.id.seek_lively_hours);
        livelyValue = findViewById(R.id.tv_lively_value);

        bottomBarSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (syncing) return;
                writePref(KEY_HIDE_BOTTOM_BAR, isChecked);
            }
        });
        threeDotSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (syncing) return;
                writePref(KEY_HIDE_THREE_DOT, isChecked);
            }
        });
        firstDragSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (syncing) return;
                writePref(KEY_FIRST_DRAG_DOCK, isChecked);
            }
        });
        livelyCountSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (syncing) return;
                writePref(KEY_LIVELY_UNLOCK_COUNT, isChecked);
            }
        });

        if (livelySeek != null) {
            livelySeek.setMax(HOUR_STEPS.length - 1);
            livelySeek.setProgressTintList(ColorStateList.valueOf(getColor(R.color.accent_1)));
            livelySeek.setThumbTintList(ColorStateList.valueOf(getColor(R.color.accent_1)));
            livelySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (syncing || livelyValue == null) return;
                    livelyValue.setText(labelOfLively(hoursOfStep(progress)));
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    writeLivelyHours(hoursOfStep(seekBar.getProgress()));
                }
            });
        }

        syncFromPrefs();
    }

    private void applyStatusBar(boolean lightIcons) {
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

    /**
     * 一个大标题 + 它下面那组子项：点标题整行展开/收起。
     * 箭头统一用 "›"，收起时朝右（0°），展开时转 90° 朝下；展开状态记在 ui_prefs 里。
     */
    private void settleGroup(int headerId, int itemsId, int arrowId, final String prefKey) {
        View header = findViewById(headerId);
        final View items = findViewById(itemsId);
        final TextView arrow = findViewById(arrowId);
        if (header == null || items == null || arrow == null) return;

        final boolean[] expanded = {readGroupExpanded(prefKey)};
        items.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
        arrow.setRotation(expanded[0] ? 90f : 0f);
        header.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                expanded[0] = !expanded[0];
                items.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
                arrow.animate().rotation(expanded[0] ? 90f : 0f).setDuration(180).start();
                getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(prefKey, expanded[0]).commit();
            }
        });
    }

    private boolean readGroupExpanded(String prefKey) {
        return getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).getBoolean(prefKey, false);
    }

    private void syncFromPrefs() {
        syncing = true;
        bottomBarSwitch.setChecked(readPref(KEY_HIDE_BOTTOM_BAR, true));
        threeDotSwitch.setChecked(readPref(KEY_HIDE_THREE_DOT, true));
        firstDragSwitch.setChecked(readPref(KEY_FIRST_DRAG_DOCK, true));
        livelyCountSwitch.setChecked(readPref(KEY_LIVELY_UNLOCK_COUNT, true));
        int hours = readLivelyHours();
        if (livelySeek != null) livelySeek.setProgress(stepOfHours(hours));
        if (livelyValue != null) livelyValue.setText(labelOfLively(hours));
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

    // ==================== 后台运行时长 ====================

    private int readLivelyHours() {
        return ModulePrefs.get(this).getInt(KEY_LIVELY_HOURS, 1);
    }

    private void writeLivelyHours(int hours) {
        ModulePrefs.get(this).edit().putInt(KEY_LIVELY_HOURS, hours).commit();
        ModulePrefs.makePrefsReadable(this);
        pushSettings(this);
        if (livelyValue != null) {
            livelyValue.setText(labelOfLively(hours));
        }
    }

    private static int hoursOfStep(int step) {
        if (step < 0) return HOUR_STEPS[0];
        if (step >= HOUR_STEPS.length) return HOUR_STEPS[HOUR_STEPS.length - 1];
        return HOUR_STEPS[step];
    }

    private static int stepOfHours(int hours) {
        for (int i = 0; i < HOUR_STEPS.length; i++) {
            if (HOUR_STEPS[i] == hours) return i;
        }
        return 0;
    }

    private static String labelOfLively(int hours) {
        if (hours <= 0) return "不限";
        return hours + " 小时";
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
            intent.putExtra(KEY_LIVELY_HOURS, prefs.getInt(KEY_LIVELY_HOURS, 1));
            intent.putExtra(KEY_LIVELY_UNLOCK_COUNT, prefs.getBoolean(KEY_LIVELY_UNLOCK_COUNT, true));
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

    private static int iconOf(int mode) {
        if (mode == THEME_LIGHT) return R.drawable.ic_theme_sun;
        if (mode == THEME_DARK) return R.drawable.ic_theme_moon;
        return R.drawable.ic_theme_auto;
    }
}
