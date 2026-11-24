package io.github.halcyon2026928.floatdeco;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Rect;
import android.graphics.Region;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自由小窗标题栏装饰：隐藏底部白条、移除顶部三点菜单栏。
 *
 * <p>只做两件事：hook {@code FlexibleTaskCaptionView.initCaptionView} 按开关直接改视图树，
 * 再挂两条输入侧的 hook（三点热区点击拦截、热区挖空让触摸穿透）。</p>
 *
 * <p>开关的重启记忆分三层：{@link DecoStore}（system_server 自己落盘，开机最先可用）→
 * XSharedPreferences（LSPosed 提供的可读 prefs）→ {@link ConfigProvider}（app 起来后的即时值）。</p>
 */
final class DecoHook {

    private static final String TAG = "[自由小窗OS]";

    static final String KEY_HIDE_BOTTOM_BAR = "hide_bottom_bar";
    static final String KEY_HIDE_THREE_DOT = "hide_three_dot_menu";
    static final String KEY_FIRST_DRAG_DOCK = "first_drag_to_dock";

    private static final boolean DUMP_CAPTION_TREE = false;

    private static volatile boolean hideBottomBar = true;
    private static volatile boolean hideThreeDot = true;
    private static volatile boolean firstDragToDock = true;
    private static volatile boolean configLoaded;
    private static volatile boolean sStoreBootstrapped;

    private static String sRomDisplayId;

    void install(ClassLoader cl) {
        try {
            DecoStore.install(this, cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " store install failed: " + t.getMessage());
        }
        try {
            loadFromXSharedPrefs();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " load config failed: " + t.getMessage());
        }
        try {
            Class<?> captionViewClass = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleTaskCaptionView", cl);

            XposedHelpers.findAndHookMethod(captionViewClass, "initCaptionView",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                applyCaptionDecor(param.thisObject);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " initCaptionView hook error: " + t.getMessage());
                            }
                        }
                    });
            XposedBridge.log(TAG + " FlexibleTaskCaptionView.initCaptionView hook success");

            // 第二层：拦截三点按钮区域的点击功能
            hookSingleTapUpMenuBlock(captionViewClass);

            // 第三层：让三点区域触摸穿透到应用
            hookTouchThroughCarve(captionViewClass);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook FlexibleTaskCaptionView failed: " + t.getMessage());
        }

        // 第四层：拖动收起时第一次也直接进坞窗（官方默认第一次走最小化）
        hookFirstDragToDock(cl);
    }

    /**
     * 官方拖动收起的去向由 {@code LivelyDefaultStatePolicy.needDefaultRun(taskId)} 决定：
     * 只有 taskId 已经进过坞窗（被 onLivelyChanged 记进 mLivelyList）才返回 true，
     * 所以第一次拖动总是走普通最小化。这里让它恒为 true，
     * 外层 {@code isLivelySupportAndEnable()} 仍由官方判断，坞窗没开时不受影响。
     */
    private void hookFirstDragToDock(ClassLoader cl) {
        try {
            Class<?> policyClass = XposedHelpers.findClass(
                    "com.android.server.wm.floathandle.LivelyDefaultStatePolicy", cl);
            XposedHelpers.findAndHookMethod(policyClass, "needDefaultRun", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!firstDragToDock) return;
                            try {
                                int taskId = (int) (Integer) param.args[0];
                                param.setResult(Boolean.TRUE);
                                XposedBridge.log(TAG + " needDefaultRun forced true taskId=" + taskId);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " needDefaultRun hook error: " + t.getMessage());
                            }
                        }
                    });
            XposedBridge.log(TAG + " LivelyDefaultStatePolicy.needDefaultRun hook success");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook needDefaultRun failed: " + t.getMessage());
        }
    }

    /** 每次自由小窗创建时按当前开关改视图树。 */
    private void applyCaptionDecor(Object captionView) {
        loadConfig(captionView);

        // ---- 隐藏底部手势小横条（开关：hide_bottom_bar） ----
        if (hideBottomBar) {
            View bottomFrame = (View) XposedHelpers.getObjectField(captionView, "mBottomFrame");
            View bottomHandleView = (View) XposedHelpers.getObjectField(captionView, "mBottomHandleView");
            View bottomHandleLongView = (View) XposedHelpers.getObjectField(captionView, "mBottomHandleLongView");

            if (bottomFrame != null) bottomFrame.setVisibility(View.GONE);
            if (bottomHandleView != null) bottomHandleView.setVisibility(View.GONE);
            if (bottomHandleLongView != null) bottomHandleLongView.setVisibility(View.GONE);
            XposedBridge.log(TAG + " hidden freeform bottom handle bar");
        }

        // ---- 移除顶部三点菜单按钮（开关：hide_three_dot_menu） ----
        if (hideThreeDot && captionView instanceof View) {
            View captionRoot = (View) captionView;
            if (DUMP_CAPTION_TREE) {
                dumpViewTree(captionRoot, 0);
            }
            View menuButton = removeThreeDotMenuButton(captionRoot);
            if (menuButton != null) {
                XposedBridge.log(TAG + " removed freeform three-dot menu button: " + describe(menuButton));
            } else {
                XposedBridge.log(TAG + " three-dot menu button not found");
            }
        }
    }

    // ==================== 配置（含重启记忆） ====================

    private void loadConfig(Object captionView) {
        try {
            Context ctx = (Context) XposedHelpers.getObjectField(captionView, "mContext");
            loadConfigFromContext(ctx);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " loadConfig error, keep cached config: " + t.getMessage());
        }
    }

    private void loadConfigFromContext(Context ctx) {
        loadFromXSharedPrefs();
        if (ctx == null) return;
        try {
            ContentResolver cr = ctx.getContentResolver();
            Cursor c = cr.query(ConfigProvider.CONFIG_URI, null, null, null, null);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        String key = c.getString(0);
                        int value = c.getInt(1);
                        if (KEY_HIDE_BOTTOM_BAR.equals(key)) {
                            hideBottomBar = value == 1;
                        } else if (KEY_HIDE_THREE_DOT.equals(key)) {
                            hideThreeDot = value == 1;
                        } else if (KEY_FIRST_DRAG_DOCK.equals(key)) {
                            firstDragToDock = value == 1;
                        }
                    }
                    configLoaded = true;
                    bootstrapStoreOnce();
                    XposedBridge.log(TAG + " config(provider) -> hideBottomBar=" + hideBottomBar
                            + " hideThreeDot=" + hideThreeDot);
                } finally {
                    c.close();
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " loadConfig provider fail, use XSharedPreferences: " + t.getMessage());
        }
    }

    private void loadFromXSharedPrefs() {
        try {
            XSharedPreferences prefs = new XSharedPreferences(ModulePrefs.PKG, ModulePrefs.NAME);
            prefs.makeWorldReadable();
            // 没解析到任何键时保持上次读到的值，不能用默认值覆盖。
            if (!prefs.hasData()) {
                XposedBridge.log(TAG + " config(xprefs empty, keep cached) -> hideBottomBar="
                        + hideBottomBar + " hideThreeDot=" + hideThreeDot);
                return;
            }
            hideBottomBar = prefs.getBoolean(KEY_HIDE_BOTTOM_BAR, true);
            hideThreeDot = prefs.getBoolean(KEY_HIDE_THREE_DOT, true);
            firstDragToDock = prefs.getBoolean(KEY_FIRST_DRAG_DOCK, true);
            configLoaded = true;
            bootstrapStoreOnce();
            XposedBridge.log(TAG + " config(xprefs) -> hideBottomBar=" + hideBottomBar
                    + " hideThreeDot=" + hideThreeDot + " firstDragToDock=" + firstDragToDock);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " XSharedPreferences error: " + t.getMessage());
        }
    }

    /** 应用 system_server 落盘里的那份设置。开机 restore 和 app 推送都走这里。 */
    void applyConfigFromStore(Map<String, String> map) {
        if (map == null || map.isEmpty()) return;
        hideBottomBar = boolOf(map, KEY_HIDE_BOTTOM_BAR, hideBottomBar);
        hideThreeDot = boolOf(map, KEY_HIDE_THREE_DOT, hideThreeDot);
        firstDragToDock = boolOf(map, KEY_FIRST_DRAG_DOCK, firstDragToDock);
        configLoaded = true;
        XposedBridge.log(TAG + " config(store) -> hideBottomBar=" + hideBottomBar
                + " hideThreeDot=" + hideThreeDot + " firstDragToDock=" + firstDragToDock);
    }

    Map<String, String> configToMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(KEY_HIDE_BOTTOM_BAR, String.valueOf(hideBottomBar));
        map.put(KEY_HIDE_THREE_DOT, String.valueOf(hideThreeDot));
        map.put(KEY_FIRST_DRAG_DOCK, String.valueOf(firstDragToDock));
        return map;
    }

    /** 首次自举：第一次读到有效值时立刻落盘，下次开机就有得读。只做一次。 */
    private static void bootstrapStoreOnce() {
        if (sStoreBootstrapped) return;
        sStoreBootstrapped = true;
        try {
            DecoStore.saveCurrent();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " bootstrap store failed: " + t.getMessage());
        }
    }

    private static boolean boolOf(Map<String, String> map, String key, boolean def) {
        String value = map.get(key);
        if (value == null) return def;
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    // ==================== 三点按钮：视图移除 ====================

    /**
     * 移除顶部“三点菜单按钮”（自由小窗的“浮窗控制器”）。
     *
     * 主路径：按资源名精确移除 flexible_simple_mode 整个容器；
     * 若新版布局改名，则退化为移除 control_flexible，再退化为语义/兜底查找。
     */
    private View removeThreeDotMenuButton(View root) {
        if (root == null) return null;

        View simpleMode = findViewByResName(root, "flexible_simple_mode");
        if (simpleMode != null) {
            removeViewCleanly(simpleMode);
            return simpleMode;
        }
        View control = findViewByResName(root, "control_flexible");
        if (control != null) {
            removeViewCleanly(control);
            return control;
        }

        View semantic = findMenuButtonBySemantics(root);
        if (semantic != null) {
            removeViewCleanly(semantic);
            return semantic;
        }
        return null;
    }

    private View findViewByResName(View view, String simpleResName) {
        if (view == null) return null;
        try {
            int id = view.getId();
            if (id != View.NO_ID) {
                String resName = view.getResources().getResourceName(id);
                if (resName != null && resName.endsWith("/" + simpleResName)) {
                    return view;
                }
            }
        } catch (Throwable ignored) {
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View hit = findViewByResName(group.getChildAt(i), simpleResName);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private View findMenuButtonBySemantics(View view) {
        if (view == null) return null;
        if (isSemanticMenuButton(view)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View hit = findMenuButtonBySemantics(group.getChildAt(i));
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private boolean isSemanticMenuButton(View v) {
        if (isHandleView(v)) return false;
        try {
            int id = v.getId();
            if (id != View.NO_ID) {
                String resName = v.getResources().getResourceName(id).toLowerCase();
                if (resName.contains("menu") || resName.contains("more")
                        || resName.contains("overflow")) {
                    if (isButtonLike(v)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        CharSequence cd = v.getContentDescription();
        if (cd != null) {
            String s = cd.toString().toLowerCase();
            if (s.contains("更多") || s.contains("菜单") || s.contains("menu") || s.contains("more")
                    || s.contains("overflow")) {
                if (isButtonLike(v)) return true;
            }
        }
        return false;
    }

    private boolean isHandleView(View v) {
        try {
            int id = v.getId();
            if (id != View.NO_ID) {
                String resName = v.getResources().getResourceName(id).toLowerCase();
                if (resName.contains("handle")) return true;
            }
        } catch (Throwable ignored) {
        }
        CharSequence cd = v.getContentDescription();
        if (cd != null) {
            String s = cd.toString();
            if (s.contains("把手") || s.contains("handle")) return true;
        }
        return false;
    }

    private boolean isButtonLike(View v) {
        return v.isClickable() || v instanceof ImageView
                || v.getClass().getSimpleName().toLowerCase().contains("imagebutton")
                || v.getClass().getSimpleName().toLowerCase().contains("button");
    }

    private void removeViewCleanly(View v) {
        if (v == null) return;
        ViewGroup parent = (v.getParent() instanceof ViewGroup) ? (ViewGroup) v.getParent() : null;
        if (parent != null) {
            try {
                parent.removeView(v);
                return;
            } catch (Throwable ignored) {
            }
        }
        v.setVisibility(View.GONE);
    }

    private String describe(View v) {
        StringBuilder sb = new StringBuilder(v.getClass().getSimpleName());
        sb.append('@').append(Integer.toHexString(v.hashCode()));
        try {
            int id = v.getId();
            if (id != View.NO_ID) {
                sb.append(" id=").append(v.getResources().getResourceName(id));
            }
        } catch (Throwable ignored) {
        }
        CharSequence cd = v.getContentDescription();
        if (cd != null && cd.length() > 0) sb.append(" desc=").append(cd);
        return sb.toString();
    }

    private void dumpViewTree(View v, int depth) {
        if (v == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(describe(v));
        XposedBridge.log(TAG + " tree: " + sb);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dumpViewTree(g.getChildAt(i), depth + 1);
        }
    }

    // ==================== 三点按钮：输入侧 ====================

    /**
     * 拦截 FlexibleCaptionView.onSingleTapUp(MotionEvent)：
     * 触摸点位于三点按钮热区（mHandleButtonRegion）时直接返回 false，阻止弹出自由小窗菜单。
     */
    private void hookSingleTapUpMenuBlock(Class<?> captionViewClass) {
        try {
            Class<?> baseClass = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleCaptionView", captionViewClass.getClassLoader());

            XposedHelpers.findAndHookMethod(baseClass, "onSingleTapUp", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!hideThreeDot) return;
                                Object thisObj = param.thisObject;
                                // 迷你浮窗的单击是「恢复成自由小窗」，吞掉会把恢复打死。
                                if (isMiniZoomState(thisObj)) return;

                                Rect region = handleButtonRegion(thisObj);
                                if (region == null || region.isEmpty()) return;

                                MotionEvent ev = (MotionEvent) param.args[0];
                                int x = (int) ev.getX();
                                int y = (int) ev.getY();
                                if (!region.contains(x, y)) return;

                                int actionFlag = (int) XposedHelpers.getIntField(thisObj, "mActionFlag");

                                // ColorOS 16 上 mActionFlag 有时来不及更新，纯坐标判定；
                                // 若该 ROM 把矩形放大到接近整个 caption，则退回 flag 判定。
                                if (isColorOs16Rom() && !isRegionTooLarge(thisObj, region)) {
                                    XposedBridge.log(TAG + " blocked three-dot menu tap (c16, by rect) at "
                                            + x + "," + y);
                                    param.setResult(false);
                                    return;
                                }

                                if (actionFlag != 1) return; // HANDLE_EVENT

                                XposedBridge.log(TAG + " blocked three-dot menu tap at " + x + "," + y);
                                param.setResult(false);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " onSingleTapUp block error: " + t.getMessage());
                            }
                        }
                    });
            XposedBridge.log(TAG + " FlexibleCaptionView.onSingleTapUp menu block hook success");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook onSingleTapUp failed: " + t.getMessage());
        }
    }

    /**
     * 把三点按钮热区从标题栏的触摸区域里挖掉，触摸直接穿透到下面的应用。
     */
    private void hookTouchThroughCarve(Class<?> captionViewClass) {
        try {
            Class<?> baseClass = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleCaptionView", captionViewClass.getClassLoader());

            XposedHelpers.findAndHookMethod(baseClass, "updateTouchableRegion", Region.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!hideThreeDot) return;

                                /*
                                 * ColorOS 16 上 mThreeButtonFrame / mHandleButtonRegion 都铺满整个
                                 * caption，挖任何一块都会让标题栏拿不到 HANDLE_EVENT、小窗拖不动。
                                 */
                                if (isColorOs16Rom()) {
                                    XposedBridge.log(TAG + " skip carve on coloros16 rom: " + romDisplayId());
                                    return;
                                }

                                /*
                                 * 迷你浮窗：系统把整个迷你矩形并进 caption 触摸区，靠它接手势和
                                 * 「单击恢复小窗」；挖任何一块都会让迷你浮窗点不动。
                                 */
                                if (isMiniZoomState(param.thisObject)) {
                                    XposedBridge.log(TAG + " skip carve in mini state");
                                    return;
                                }

                                Region region = (Region) param.args[0];
                                if (region == null || region.isEmpty()) return;

                                Rect hot = handleButtonRegion(param.thisObject);
                                if (hot == null || hot.isEmpty()) return;

                                if (isRegionTooLarge(param.thisObject, hot)) {
                                    XposedBridge.log(TAG + " skip carve: three-dot hot region too large "
                                            + hot.toShortString());
                                    return;
                                }

                                // 在副本上挖，且挖完必须还剩得下一块像样的触摸区，否则宁可放弃。
                                Region carved = new Region(region);
                                carved.op(hot, Region.Op.DIFFERENCE);
                                if (carved.isEmpty()) {
                                    XposedBridge.log(TAG + " skip carve: region would become empty");
                                    return;
                                }
                                param.args[0] = carved;
                                XposedBridge.log(TAG + " carved three-dot touch region: "
                                        + hot.toShortString() + " remain=" + carved.getBounds().toShortString());
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " updateTouchableRegion carve error: " + t.getMessage());
                            }
                        }
                    });
            XposedBridge.log(TAG + " touch-through carve hook success");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook updateTouchableRegion failed: " + t.getMessage());
        }
    }

    /** 迷你（含 ready）态：该状态下标题栏要独占整块区域做手势与单点恢复。 */
    private static boolean isMiniZoomState(Object captionView) {
        if (!(captionView instanceof View)) return false;
        try {
            Object v = XposedHelpers.callMethod(captionView, "isMiniZoomState");
            return v instanceof Boolean && (Boolean) v;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 系统真正的三点按钮热区（caption 局部坐标）。 */
    private static Rect handleButtonRegion(Object captionView) {
        try {
            Object mgr = XposedHelpers.getObjectField(captionView, "mFlexibleCaptionViewRegionMgr");
            if (mgr == null) return null;
            Object r = XposedHelpers.getObjectField(mgr, "mHandleButtonRegion");
            return (r instanceof Rect) ? new Rect((Rect) r) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String romDisplayId() {
        if (sRomDisplayId != null) return sRomDisplayId;
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class).invoke(null, "ro.build.display.id");
            sRomDisplayId = (v instanceof String) ? (String) v : "";
        } catch (Throwable t) {
            sRomDisplayId = "";
        }
        return sRomDisplayId;
    }

    /**
     * 是否为 ColorOS 16（OPPO / 一加上一代系统）。realme（RMX 前缀）不算。
     */
    private static boolean isColorOs16Rom() {
        String id = romDisplayId();
        if (id.isEmpty()) return false;
        int us = id.indexOf('_');
        if (us < 0 || us + 3 > id.length()) return false;
        String model = id.substring(0, us);
        if (model.startsWith("RMX")) return false;
        String ver = id.substring(us + 1);
        return ver.startsWith("16.");
    }

    /** 触摸矩形是否大得不像「三点按钮热区」（面积 ≥ caption 的 40%）。 */
    private static boolean isRegionTooLarge(Object captionView, Rect rect) {
        if (!(captionView instanceof View) || rect == null) return true;
        View v = (View) captionView;
        int w = v.getWidth();
        int h = v.getHeight();
        if (w <= 0 || h <= 0) return true;
        long all = (long) w * h;
        long area = (long) rect.width() * rect.height();
        return area * 10 >= all * 4;
    }
}

