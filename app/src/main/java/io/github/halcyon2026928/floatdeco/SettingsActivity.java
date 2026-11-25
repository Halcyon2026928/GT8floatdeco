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
    /** 后台卡片封面铺满：自由小窗快照在最近任务卡片里按 cover 铺满，消除上下黑边。 */
    static final String KEY_CARD_COVER = CardCoverHook.KEY_CARD_COVER;
    /** 上滑关闭自由小窗后，最近任务卡片显示图标页而非内容快照。 */
    static final String KEY_ICON_PAGE = DecoHook.KEY_ICON_PAGE;

    static final String KEY_THEME_MODE = "theme_mode";
    static final int THEME_FOLLOW_SYSTEM = 0;
    static final int THEME_LIGHT = 1;
    static final int THEME_DARK = 2;

    private static final String ACTION_PUSH = "io.github.halcyon2026928.floatdeco.SETTINGS_PUSHED";
    private static final String UI_PREFS = "floatdeco_ui";
    private static final String KEY_GROUP_FREEWINDOW_EXPANDED = "group_free_window_expanded";
    private static final String KEY_GROUP_BACKGROUND_EXPANDED = "group_background_expanded";
    private static final String KEY_GROUP_TASK_CARD_EXPANDED = "group_task_card_expanded";

    /** 拖动条档位：1、2、4、8、12 小时，最后一档 0 表示不限。 */
    private static final int[] HOUR_STEPS = {1, 2, 4, 8, 12, 0};

    private Switch bottomBarSwitch;
    private Switch threeDotSwitch;
    private Switch firstDragSwitch;
    private Switch livelyCountSwitch;
    private Switch cardCoverSwitch;
    private SeekBar livelySeek;
    private TextView livelyValue;

    private boolean syncing;
    private boolean light;

    /** 界面语言在这里生效：换一套 locale 的 Context，切换语言时 recreate 即可。 */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final int themeMode = resolveThemeMode();
        light = isLightOf(themeMode);

        setTheme(light ? R.style.LightTheme : R.style.AppTheme);
        setContentView(R.layout.activity_settings);
        applyStatusBar(light);
        // 打开即同步一次：把当前开关写成快照并推送，避免用户改完没触发写入。
        pushSettings(this);

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

        TextView langBtn = findViewById(R.id.btn_lang);
        if (langBtn != null) {
            // 文案本身就是「另一种语言」的名字：中文界面显示 English，英文界面显示 中文。
            langBtn.setText(R.string.lang_switch_label);
            langBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    LocaleHelper.toggle(SettingsActivity.this);
                    recreate();
                }
            });
        }

        settleGroup(R.id.header_free_window, R.id.items_free_window,
                R.id.arrow_free_window, KEY_GROUP_FREEWINDOW_EXPANDED);
        settleGroup(R.id.header_background, R.id.items_background,
                R.id.arrow_background, KEY_GROUP_BACKGROUND_EXPANDED);
        // 任务卡片是新增分组：第一次进设置页默认展开，之后跟随用户的展开/收起。
        SharedPreferences uiPrefs = getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE);
        if (!uiPrefs.contains(KEY_GROUP_TASK_CARD_EXPANDED)) {
            uiPrefs.edit().putBoolean(KEY_GROUP_TASK_CARD_EXPANDED, true).commit();
        }
        settleGroup(R.id.header_task_card, R.id.items_task_card,
                R.id.arrow_task_card, KEY_GROUP_TASK_CARD_EXPANDED);

        bottomBarSwitch = findViewById(R.id.switch_bottom_bar);
        threeDotSwitch = findViewById(R.id.switch_three_dot);
        firstDragSwitch = findViewById(R.id.switch_first_drag);
        livelyCountSwitch = findViewById(R.id.switch_lively_count);
        cardCoverSwitch = findViewById(R.id.switch_card_cover);
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
        if (cardCoverSwitch != null) {
            cardCoverSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (syncing) return;
                    writePref(KEY_CARD_COVER, isChecked);
                }
            });
        }
        setupAppCustomize();

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
        if (cardCoverSwitch != null) cardCoverSwitch.setChecked(readPref(KEY_CARD_COVER, true));
        int hours = readLivelyHours();
        if (livelySeek != null) livelySeek.setProgress(stepOfHours(hours));
        if (livelyValue != null) livelyValue.setText(labelOfLively(hours));
        syncing = false;
    }

    private boolean readPref(String key, boolean def) {
        return ModulePrefs.get(this).getBoolean(key, def);
    }

    // ==================== 上滑关闭后的卡片显示方式 ====================

    /**
     * 「应用自定义」入口：点开一个独立页面，给单个应用指定卡片图片。
     *
     * <p>显示方式（裁剪页 / 启动页 / 官方）的选择器已经移到那个页面的顶部——
     * 两处都放一个选择器会互相打架，这里只留入口。</p>
     */
    private void setupAppCustomize() {
        View row = findViewById(R.id.row_app_customize);
        if (row != null) {
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        startActivity(new Intent(SettingsActivity.this,
                                AppCustomizeActivity.class));
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
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

    private String labelOfLively(int hours) {
        if (hours <= 0) return getString(R.string.lively_unlimited);
        return getString(R.string.lively_hours, hours);
    }

    /**
     * 读当前显示方式（0 裁剪 / 1 启动页 / 2 官方）。
     *
     * <p>兼容旧数据：只有布尔 {@code slide_up_icon_page} 时，true 视为启动页。</p>
     */
    static int readDisplayMode(SharedPreferences prefs) {
        if (prefs.contains(CardCoverHook.KEY_DISPLAY_MODE)) {
            int m = prefs.getInt(CardCoverHook.KEY_DISPLAY_MODE, CardCoverHook.MODE_COVER);
            if (m == CardCoverHook.MODE_SPLASH || m == CardCoverHook.MODE_OFFICIAL) return m;
        }
        return prefs.getBoolean(KEY_ICON_PAGE, false)
                ? CardCoverHook.MODE_SPLASH : CardCoverHook.MODE_COVER;
    }

    /**
     * 把当前开关整份推给 system_server（写进 /data/system 下的文件，开机时先用它）。
     */
    static void pushSettings(Context ctx) {
        if (ctx == null) return;
        SharedPreferences prefs = ModulePrefs.get(ctx);
        int displayMode = readDisplayMode(prefs);
        // 兜底通道：写一份 key=value 到外部私有目录，system_server 直读文件即可拿到最新开关。
        writeConfigSnapshot(ctx, prefs, displayMode);
        try {
            Intent intent = new Intent(ACTION_PUSH);
            intent.putExtra(KEY_HIDE_BOTTOM_BAR, prefs.getBoolean(KEY_HIDE_BOTTOM_BAR, true));
            intent.putExtra(KEY_HIDE_THREE_DOT, prefs.getBoolean(KEY_HIDE_THREE_DOT, true));
            intent.putExtra(KEY_FIRST_DRAG_DOCK, prefs.getBoolean(KEY_FIRST_DRAG_DOCK, true));
            intent.putExtra(KEY_LIVELY_HOURS, prefs.getInt(KEY_LIVELY_HOURS, 1));
            intent.putExtra(KEY_LIVELY_UNLOCK_COUNT, prefs.getBoolean(KEY_LIVELY_UNLOCK_COUNT, true));
            intent.putExtra(KEY_CARD_COVER, prefs.getBoolean(KEY_CARD_COVER, true));
            // 旧字段保留（system_server 侧日志与旧逻辑还认它），新字段给 Launcher 侧用。
            intent.putExtra(KEY_ICON_PAGE, displayMode == CardCoverHook.MODE_SPLASH);
            intent.putExtra(CardCoverHook.KEY_DISPLAY_MODE, displayMode);
            ctx.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    /** 把当前开关写成文本快照，供 system_server 直读（广播/provider 不通时唯一的可靠通道）。 */
    private static void writeConfigSnapshot(Context ctx, SharedPreferences prefs, int displayMode) {
        StringBuilder sb = new StringBuilder();
        sb.append(KEY_HIDE_BOTTOM_BAR).append('=').append(prefs.getBoolean(KEY_HIDE_BOTTOM_BAR, true)).append('\n');
        sb.append(KEY_HIDE_THREE_DOT).append('=').append(prefs.getBoolean(KEY_HIDE_THREE_DOT, true)).append('\n');
        sb.append(KEY_FIRST_DRAG_DOCK).append('=').append(prefs.getBoolean(KEY_FIRST_DRAG_DOCK, true)).append('\n');
        sb.append(KEY_LIVELY_HOURS).append('=').append(prefs.getInt(KEY_LIVELY_HOURS, 1)).append('\n');
        sb.append(KEY_LIVELY_UNLOCK_COUNT).append('=').append(prefs.getBoolean(KEY_LIVELY_UNLOCK_COUNT, true)).append('\n');
        sb.append(KEY_CARD_COVER).append('=').append(prefs.getBoolean(KEY_CARD_COVER, true)).append('\n');
        sb.append(KEY_ICON_PAGE).append('=').append(displayMode == CardCoverHook.MODE_SPLASH).append('\n');
        sb.append(CardCoverHook.KEY_DISPLAY_MODE).append('=').append(displayMode).append('\n');
        String content = sb.toString();
        // 两处都写：外部私有目录（system_server 走 FUSE 可读）与内部 files 目录。
        writeSnapshotTo(ctx.getExternalFilesDir(null), content);
        writeSnapshotTo(ctx.getFilesDir(), content);
    }

    private static void writeSnapshotTo(java.io.File dir, String content) {
        if (dir == null) return;
        try {
            if (!dir.exists() && !dir.mkdirs()) return;
            java.io.File f = new java.io.File(dir, "floatdeco_config.txt");
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, false);
            try {
                out.write(content.getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
            f.setReadable(true, false);
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
