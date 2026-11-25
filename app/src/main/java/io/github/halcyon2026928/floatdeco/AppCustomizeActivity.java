package io.github.halcyon2026928.floatdeco;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 应用自定义：给单个应用指定「上滑关闭后最近任务卡片」显示的图片。
 *
 * <p>列表包含全部已安装应用（含系统应用）——需要清单里的 QUERY_ALL_PACKAGES，
 * 否则 Android 11+ 只能看到一部分包。点某一行调系统图片选择器，图片压好存进
 * {@link CustomCovers}，Launcher 侧 2 秒内自动同步。</p>
 */
public class AppCustomizeActivity extends Activity {

    private static final int REQ_PICK = 1001;
    private static final String UI_PREFS = "floatdeco_ui";
    private static final String KEY_THEME_MODE = "theme_mode";
    /** 列表偏好：是否显示系统应用、排序方式，都记在 ui_prefs 里，下次进来还保持。 */
    private static final String KEY_SHOW_SYSTEM = "app_list_show_system";
    private static final String KEY_SORT_MODE = "app_list_sort";

    private static final int SORT_NAME = 0;
    private static final int SORT_TIME = 1;
    private static final int SORT_PKG = 2;

    private final List<AppEntry> all = new ArrayList<>();
    private final List<AppEntry> shown = new ArrayList<>();
    private final Set<String> customPkgs = new HashSet<>();

    private AppAdapter adapter;
    private EditText search;
    private TextView filterAll;
    private TextView filterCustom;
    private TextView empty;
    private TextView optCover;
    private TextView optSplash;
    private TextView optOfficial;

    private boolean onlyCustom;
    private boolean light;
    /** 是否把系统应用也列出来。默认 false：只显示用户自己装的应用。 */
    private boolean showSystem;
    /** 排序方式：0 名称 / 1 安装时间（新→旧）/ 2 包名。 */
    private int sortMode = SORT_NAME;
    private PopupWindow menuPopup;
    private PopupWindow sortPopup;
    private volatile boolean loaded;
    /** 当前显示方式：0 裁剪 / 1 启动页 / 2 官方（只作用于没单独设图的应用）。 */
    private int mode;
    /** 正在等图片选择结果的那个应用。 */
    private String pendingPkg;

    static final class AppEntry {
        String pkg;
        String label;
        boolean system;
        long installTime;
        Drawable icon;
        boolean custom;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean lightTheme = resolveLight();
        light = lightTheme;
        setTheme(lightTheme ? R.style.LightTheme : R.style.AppTheme);
        setContentView(R.layout.activity_app_customize);
        applyStatusBar(lightTheme);

        View back = findViewById(R.id.btn_back);
        if (back != null) back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // 列表偏好：默认只显示用户应用，排序默认按名称。
        SharedPreferences ui = getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE);
        showSystem = ui.getBoolean(KEY_SHOW_SYSTEM, false);
        sortMode = ui.getInt(KEY_SORT_MODE, SORT_NAME);
        setupOverflow();

        search = findViewById(R.id.et_search);
        filterAll = findViewById(R.id.tv_filter_all);
        filterCustom = findViewById(R.id.tv_filter_custom);
        empty = findViewById(R.id.tv_empty);

        if (filterAll != null) filterAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onlyCustom = false;
                refreshChips();
                applyFilter();
            }
        });
        if (filterCustom != null) filterCustom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onlyCustom = true;
                refreshChips();
                applyFilter();
            }
        });

        if (search != null) {
            search.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    applyFilter();
                }
            });
        }

        ListView list = findViewById(R.id.list_apps);
        adapter = new AppAdapter();
        if (list != null) {
            list.setAdapter(adapter);
            list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    if (position < 0 || position >= shown.size()) return;
                    pickImage(shown.get(position).pkg);
                }
            });
        }

        refreshChips();
        setupDisplayMode();
        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从别的页面回来时（比如刚设过图）重新对齐一次「已自定义」标记。
        if (loaded) reloadCustomFlags();
        if (optCover != null) {
            applyDisplayMode(SettingsActivity.readDisplayMode(ModulePrefs.get(this)));
        }
    }

    // ==================== 右上角溢出菜单 ====================

    private void setupOverflow() {
        View more = findViewById(R.id.btn_more);
        if (more == null) return;
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showOverflow(v);
            }
        });
    }

    /**
     * 溢出菜单：显示系统应用（开关）+ 排序（二级）。
     *
     * <p>背景直接用 {@code ?attr/fwCardBackground}，圆角、描边、深浅色都与列表卡片一致，
     * 不另造一套弹窗视觉。</p>
     */
    private void showOverflow(final View anchor) {
        dismissMenus();
        View content = LayoutInflater.from(this).inflate(R.layout.popup_app_menu, null, false);

        final CheckBox cb = content.findViewById(R.id.cb_show_system);
        if (cb != null) cb.setChecked(showSystem);

        View rowShow = content.findViewById(R.id.row_show_system);
        if (rowShow != null) {
            rowShow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showSystem = !showSystem;
                    if (cb != null) cb.setChecked(showSystem);
                    uiPrefs().edit().putBoolean(KEY_SHOW_SYSTEM, showSystem).apply();
                    applyFilter();
                }
            });
        }

        final TextView sortValue = content.findViewById(R.id.tv_sort_value);
        if (sortValue != null) sortValue.setText(sortLabel());

        View rowSort = content.findViewById(R.id.row_sort);
        if (rowSort != null) {
            rowSort.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showSortMenu(v, sortValue);
                }
            });
        }

        PopupWindow pop = new PopupWindow(content, dp(212),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pop.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pop.setOutsideTouchable(true);
        pop.setElevation(dp(10));
        pop.setOnDismissListener(new PopupWindow.OnDismissListener() {
            @Override
            public void onDismiss() {
                menuPopup = null;
            }
        });
        menuPopup = pop;
        // 右对齐到 ⋮ 按钮：菜单向左展开，不会顶出屏幕。
        pop.showAsDropDown(anchor, 0, dp(6), Gravity.END);
    }

    /** 排序二级菜单。已经打开时再点一次「排序」就收起。 */
    private void showSortMenu(View anchor, final TextView value) {
        if (sortPopup != null) {
            sortPopup.dismiss();
            sortPopup = null;
            return;
        }
        View content = LayoutInflater.from(this).inflate(R.layout.popup_sort_menu, null, false);
        bindSortItem(content, R.id.sort_name, SORT_NAME, value);
        bindSortItem(content, R.id.sort_time, SORT_TIME, value);
        bindSortItem(content, R.id.sort_pkg, SORT_PKG, value);

        PopupWindow pop = new PopupWindow(content, dp(190),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pop.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pop.setOutsideTouchable(true);
        pop.setElevation(dp(10));
        pop.setOnDismissListener(new PopupWindow.OnDismissListener() {
            @Override
            public void onDismiss() {
                sortPopup = null;
            }
        });
        sortPopup = pop;
        pop.showAsDropDown(anchor, 0, dp(4), Gravity.END);
    }

    private void bindSortItem(View root, int id, final int m, final TextView value) {
        TextView tv = root.findViewById(id);
        if (tv == null) return;
        final boolean selected = m == sortMode;
        tv.setTextColor(selected ? getColor(R.color.accent_1) : primaryTextColor());
        tv.getPaint().setFakeBoldText(selected);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sortMode = m;
                uiPrefs().edit().putInt(KEY_SORT_MODE, m).apply();
                sortAll();
                applyFilter();
                if (value != null) value.setText(sortLabel());
                dismissMenus();
            }
        });
    }

    private String sortLabel() {
        if (sortMode == SORT_TIME) return getString(R.string.sort_value_time);
        if (sortMode == SORT_PKG) return getString(R.string.sort_value_pkg);
        return getString(R.string.sort_value_name);
    }

    private void dismissMenus() {
        if (sortPopup != null) {
            sortPopup.dismiss();
            sortPopup = null;
        }
        if (menuPopup != null) {
            menuPopup.dismiss();
            menuPopup = null;
        }
    }

    private SharedPreferences uiPrefs() {
        return getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE);
    }

    private int primaryTextColor() {
        return light ? getColor(R.color.light_text_primary) : getColor(R.color.text_primary);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ==================== 应用列表 ====================

    private void loadApps() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppEntry> result = new ArrayList<>();
                final Set<String> seen = new HashSet<>();
                try {
                    PackageManager pm = getPackageManager();
                    List<ApplicationInfo> infos = pm.getInstalledApplications(0);
                    for (ApplicationInfo ai : infos) {
                        if (ai == null || ai.packageName == null) continue;
                        if (!seen.add(ai.packageName)) continue;
                        AppEntry e = new AppEntry();
                        e.pkg = ai.packageName;
                        e.label = String.valueOf(ai.loadLabel(pm));
                        e.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                        e.installTime = installTimeOf(ai);
                        try {
                            e.icon = ai.loadIcon(pm);
                        } catch (Throwable ignored) {
                        }
                        result.add(e);
                    }
                } catch (Throwable ignored) {
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        all.clear();
                        all.addAll(result);
                        loaded = true;
                        sortAll();
                        reloadCustomFlags();
                    }
                });
            }
        }, "floatdeco-applist").start();
    }

    /**
     * 取安装时间。必须走反射：这个项目的 SDK stub 里 {@code ApplicationInfo.firstInstallTime}
     * 不存在（和之前的 {@code Intent.getSentFromUid()} 一样），直接访问会编译失败。
     */
    private static long installTimeOf(ApplicationInfo ai) {
        try {
            java.lang.reflect.Field f = ApplicationInfo.class.getField("firstInstallTime");
            return f.getLong(ai);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 按当前 sortMode 重排全集（列表只显示 shown，重排后要再 applyFilter）。 */
    private void sortAll() {
        final Collator collator = Collator.getInstance(Locale.getDefault());
        Collections.sort(all, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                if (sortMode == SORT_TIME) {
                    // 新装的在前，和系统「应用管理」的默认观感一致。
                    int c = Long.compare(b.installTime, a.installTime);
                    if (c != 0) return c;
                } else if (sortMode == SORT_PKG) {
                    int c = a.pkg.compareTo(b.pkg);
                    if (c != 0) return c;
                }
                int c = collator.compare(a.label, b.label);
                return c != 0 ? c : a.pkg.compareTo(b.pkg);
            }
        });
    }

    private void reloadCustomFlags() {
        customPkgs.clear();
        for (Map.Entry<String, Long> e : CustomCovers.listLocal(this).entrySet()) {
            customPkgs.add(e.getKey());
        }
        for (AppEntry e : all) {
            e.custom = customPkgs.contains(e.pkg);
        }
        applyFilter();
    }

    private void applyFilter() {
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (AppEntry e : all) {
            // 默认隐藏系统应用：几百个 com.android.* 混进来，用户根本找不到自己想设的那个。
            if (!showSystem && e.system) continue;
            if (onlyCustom && !e.custom) continue;
            if (!q.isEmpty()
                    && !e.label.toLowerCase(Locale.ROOT).contains(q)
                    && !e.pkg.toLowerCase(Locale.ROOT).contains(q)) continue;
            shown.add(e);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (empty != null) {
            if (!loaded) {
                empty.setVisibility(View.VISIBLE);
                empty.setText(R.string.ac_loading);
            } else if (shown.isEmpty()) {
                empty.setVisibility(View.VISIBLE);
                if (onlyCustom) {
                    empty.setText(R.string.ac_empty_custom);
                } else if (!showSystem) {
                    empty.setText(R.string.ac_empty_user);
                } else {
                    empty.setText(R.string.ac_empty_any);
                }
            } else {
                empty.setVisibility(View.GONE);
            }
        }
    }

    private void refreshChips() {
        styleChip(filterAll, !onlyCustom);
        styleChip(filterCustom, onlyCustom);
    }

    private void styleChip(TextView tv, boolean selected) {
        if (tv == null) return;
        if (selected) {
            tv.setBackgroundResource(R.drawable.bg_theme_chip);
            tv.setTextColor(getColor(R.color.accent_1));
        } else {
            tv.setBackgroundResource(0);
            tv.setTextColor(light ? getColor(R.color.light_text_secondary)
                    : getColor(R.color.text_secondary));
        }
    }

    // ==================== 未自定义应用的显示方式 ====================

    /**
     * 顶部三选一：裁剪页 / 启动页 / 官方。
     *
     * <p>它管的是「没有单独设图的应用」显示什么。设过图的应用两种模式（裁剪页、启动页）
     * 下都优先显示自己的图；选「官方」则整条链路让路——不用逐张清除图片就能一键回到
     * 系统原样，图片仍然留在列表里。</p>
     */
    private void setupDisplayMode() {
        optCover = findViewById(R.id.tv_opt_cover);
        optSplash = findViewById(R.id.tv_opt_splash);
        optOfficial = findViewById(R.id.tv_opt_official);
        if (optCover == null || optSplash == null || optOfficial == null) return;

        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int m;
                if (v.getId() == R.id.tv_opt_splash) m = CardCoverHook.MODE_SPLASH;
                else if (v.getId() == R.id.tv_opt_official) m = CardCoverHook.MODE_OFFICIAL;
                else m = CardCoverHook.MODE_COVER;
                writeDisplayMode(m);
            }
        };
        optCover.setOnClickListener(listener);
        optSplash.setOnClickListener(listener);
        optOfficial.setOnClickListener(listener);

        applyDisplayMode(SettingsActivity.readDisplayMode(ModulePrefs.get(this)));
    }

    private void writeDisplayMode(int m) {
        if (m == mode) return;
        mode = m;
        SharedPreferences.Editor e = ModulePrefs.get(this).edit();
        e.putInt(CardCoverHook.KEY_DISPLAY_MODE, m);
        // 旧字段跟着同步：system_server 侧日志和旧逻辑还认这个布尔。
        e.putBoolean(SettingsActivity.KEY_ICON_PAGE, m == CardCoverHook.MODE_SPLASH);
        e.commit();
        ModulePrefs.makePrefsReadable(this);
        // 推一份整份开关（广播 + 文本快照），Launcher 侧 2 秒轮询内拿到新值。
        SettingsActivity.pushSettings(this);
        applyDisplayMode(m);
    }

    private void applyDisplayMode(int m) {
        mode = m;
        styleOption(optCover, m == CardCoverHook.MODE_COVER);
        styleOption(optSplash, m == CardCoverHook.MODE_SPLASH);
        styleOption(optOfficial, m == CardCoverHook.MODE_OFFICIAL);
    }

    private void styleOption(TextView tv, boolean selected) {
        if (tv == null) return;
        if (selected) {
            tv.setBackgroundResource(R.drawable.bg_theme_chip);
            tv.setTextColor(getColor(R.color.accent_1));
        } else {
            tv.setBackgroundResource(0);
            tv.setTextColor(light ? getColor(R.color.light_text_secondary)
                    : getColor(R.color.text_secondary));
        }
    }

    // ==================== 选图 / 清除 ====================

    private void pickImage(String pkg) {
        pendingPkg = pkg;
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, REQ_PICK);
        } catch (Throwable t) {
            Intent fallback = new Intent(Intent.ACTION_PICK,
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            try {
                startActivityForResult(fallback, REQ_PICK);
            } catch (Throwable t2) {
                pendingPkg = null;
                toast(getString(R.string.ac_no_picker));
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK) return;
        final String pkg = pendingPkg;
        pendingPkg = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || pkg == null) return;

        final Uri uri = data.getData();
        toast(getString(R.string.ac_saving));
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = CustomCovers.saveFromUri(
                        AppCustomizeActivity.this, pkg, uri);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast(ok ? getString(R.string.ac_saved)
                                : getString(R.string.ac_save_failed));
                        reloadCustomFlags();
                    }
                });
            }
        }, "floatdeco-save-cover").start();
    }

    private void clearCustom(final String pkg) {
        CustomCovers.delete(this, pkg);
        toast(getString(R.string.ac_cleared));
        reloadCustomFlags();
    }

    private void toast(String msg) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    // ==================== 列表适配 ====================

    private final class AppAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(AppCustomizeActivity.this)
                        .inflate(R.layout.item_app_custom, parent, false);
            }
            AppEntry e = shown.get(position);

            ImageView icon = v.findViewById(R.id.iv_app_icon);
            if (icon != null) {
                if (e.icon != null) icon.setImageDrawable(e.icon);
                else icon.setImageDrawable(null);
            }

            TextView name = v.findViewById(R.id.tv_app_name);
            if (name != null) name.setText(e.label);

            TextView desc = v.findViewById(R.id.tv_app_desc);
            if (desc != null) {
                String tag = e.system ? getString(R.string.ac_system_tag) : "";
                desc.setText(e.custom
                        ? getString(R.string.ac_customized) + tag : e.pkg + tag);
            }

            TextView action = v.findViewById(R.id.tv_app_action);
            if (action != null) {
                final String pkg = e.pkg;
                if (e.custom) {
                    action.setText(R.string.ac_clear);
                    action.setTextColor(light ? getColor(R.color.light_text_secondary)
                            : getColor(R.color.text_secondary));
                    action.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View x) {
                            clearCustom(pkg);
                        }
                    });
                } else {
                    action.setText(R.string.ac_pick);
                    action.setTextColor(getColor(R.color.accent_1));
                    action.setOnClickListener(null);
                }
            }
            return v;
        }
    }

    @Override
    protected void onDestroy() {
        // 弹窗是独立 window，页面销毁时不清掉会漏 window（快速返回时能复现）。
        dismissMenus();
        super.onDestroy();
    }

    // ==================== 主题 ====================

    private boolean resolveLight() {
        try {
            SharedPreferences ui = getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE);
            int mode = ui.getInt(KEY_THEME_MODE, 0);
            if (mode == 1) return true;
            if (mode == 2) return false;
        } catch (Throwable ignored) {
        }
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night != Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyStatusBar(boolean lightIcons) {
        try {
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (lightIcons) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            decor.setSystemUiVisibility(flags);
        } catch (Throwable ignored) {
        }
    }
}
