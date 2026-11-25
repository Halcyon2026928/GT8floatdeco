package io.github.halcyon2026928.floatdeco;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Rect;
import android.graphics.Region;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.os.Handler;
import android.widget.ImageView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
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
    /** 后台运行时长，单位小时：0=不限，1=官方默认，2/4/8/12 为延长。 */
    static final String KEY_LIVELY_HOURS = "lively_run_hours";
    /** 是否解除官方「最多 2 个应用后台运行」，开启时放到 {@link #LIVELY_COUNT_LIMIT}。 */
    static final String KEY_LIVELY_UNLOCK_COUNT = "lively_unlock_count";

    /** 官方「最多 2 个应用后台运行」是硬编码，这里放到 12（与自由浮窗总数上限一致）。 */
    private static final int LIVELY_COUNT_LIMIT = 12;

    private static final boolean DUMP_CAPTION_TREE = false;

    private static volatile boolean hideBottomBar = true;
    private static volatile boolean hideThreeDot = true;
    private static volatile boolean firstDragToDock = true;
    private static volatile int livelyHours = 1;
    private static volatile boolean livelyUnlockCount = true;
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

        // 第五层：官方「后台运行 1 小时」改成可选的时长
        hookLivelyTimeout(cl);

        // 第六层：官方「最多 2 个应用后台运行」放到 12 个
        hookLivelyCount(cl);

        // 第七层：新窗口进坞窗时那处 livelySize == 2 的降级
        hookJoinLivelyGuard(cl);

        // 第八层：兜底掐掉「弹菜单」这个动作本身（notifySystemEvent 1001）
        hookMenuNotifyGuard(cl);
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

    // ==================== 后台运行：时长 ====================

    /**
     * 官方「后台运行 1 小时」的判点在
     * {@code LivelyFloatHandleViewManager$TimeOutPolicy} 里的那个无参 lambda
     * （{@code lambda$new$0}，原 {@code checkTimeout}）：遍历 {@code mRunningInfos}，
     * {@code now - mStartTime >= SystemProperties.getLong("debug.livelytimeout", 3600000L)}
     * 就 {@code exitLively(taskId)}。
     *
     * <p>这里不改方法体，而是接管这一次判定：用同一张表、同一个 Handler、同一个
     * {@code mCheckTimeout} Runnable，按用户选的时长重新算，算完把原方法整段跳过。
     * 当选「1 小时（官方默认）」时完全不碰，走官方原逻辑。</p>
     */
    private void hookLivelyTimeout(ClassLoader cl) {
        try {
            Class<?> policyClass = XposedHelpers.findClass(
                    "com.android.server.wm.floathandle.LivelyFloatHandleViewManager$TimeOutPolicy", cl);
            Method check = findTimeoutCheck(policyClass);
            if (check == null) {
                XposedBridge.log(TAG + " lively timeout: check method not found");
                return;
            }
            XposedBridge.hookMethod(check, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (livelyHours == 1) return;
                    try {
                        if (recalcLivelyTimeout(param.thisObject)) {
                            param.setResult(null);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " lively timeout hook error: " + t.getMessage());
                    }
                }
            });
            XposedBridge.log(TAG + " LivelyFloatHandleViewManager.TimeOutPolicy hook success: "
                    + check.getName() + " hours=" + livelyHours);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook lively timeout failed: " + t.getMessage());
        }
    }

    /** TimeOutPolicy 里那个无参 void 的超时判定方法（真名 lambda$new$0，备用名判定避开 refresh）。 */
    private static Method findTimeoutCheck(Class<?> policyClass) {
        Method fallback = null;
        for (Method m : policyClass.getDeclaredMethods()) {
            if (m.getParameterCount() != 0 || m.getReturnType() != void.class) continue;
            if (m.getName().startsWith("lambda$new")) {
                m.setAccessible(true);
                return m;
            }
            if (!"refresh".equals(m.getName()) && fallback == null) fallback = m;
        }
        if (fallback != null) fallback.setAccessible(true);
        return fallback;
    }

    /**
     * 按用户选的时长重算一遍。返回 true 表示已经处理完、让原方法跳过。
     *
     * <p>时长 0 = 不限：把在跑的检查撤掉，永不退出（不额外排期，不引入任何自建定时）。</p>
     */
    private boolean recalcLivelyTimeout(Object policy) {
        Object manager = XposedHelpers.getObjectField(policy, "this$0");
        Object mapObj = XposedHelpers.getObjectField(policy, "mRunningInfos");
        if (manager == null || !(mapObj instanceof Map)) return false;
        Map<?, ?> running = (Map<?, ?>) mapObj;

        Handler handler = (Handler) XposedHelpers.getObjectField(manager, "mHandler");
        Runnable check = (Runnable) XposedHelpers.getObjectField(policy, "mCheckTimeout");
        if (handler == null || check == null) return false;

        long cfgMs = livelyHours <= 0 ? 0 : livelyHours * 3600000L;
        long now = System.currentTimeMillis();
        long delay = -1;
        boolean fieldOk = true;

        Iterator<? extends Map.Entry<?, ?>> it = running.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<?, ?> entry = it.next();
            Object info = entry.getValue();
            if (info == null) continue;
            long start = startTimeOf(info);
            if (start < 0) {
                fieldOk = false;
                break;
            }
            long elapsed = now - start;
            if (cfgMs > 0 && elapsed >= cfgMs) {
                it.remove();
                int taskId = taskIdOf(info);
                XposedBridge.log(TAG + " lively run timeout(" + livelyHours + "h) exit taskId=" + taskId);
                exitLively(manager, taskId);
                continue;
            }
            if (cfgMs > 0) {
                long wait = cfgMs - elapsed;
                if (delay < 0 || wait < delay) delay = wait;
            }
        }
        if (!fieldOk) return false;

        handler.removeCallbacks(check);
        if (cfgMs > 0 && delay > 0) {
            handler.postDelayed(check, delay);
        }
        return true;
    }

    private static void exitLively(Object manager, int taskId) {
        try {
            Class<?> controllerClass = XposedHelpers.findClass(
                    "com.android.server.wm.FloatHandleController", manager.getClass().getClassLoader());
            Object controller = XposedHelpers.callStaticMethod(controllerClass, "getInstance");
            if (controller != null) {
                XposedHelpers.callMethod(controller, "exitLively", taskId);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " exitLively failed: " + t.getMessage());
        }
    }

    private static volatile Field sStartTimeField;
    private static volatile boolean sStartTimeFieldResolved;

    /** FloatTimeInfo 里的开始时间（long）。读不到返回 -1，上层就交回官方逻辑。 */
    private static long startTimeOf(Object info) {
        Field field = sStartTimeField;
        if (!sStartTimeFieldResolved) {
            Class<?> clazz = info.getClass();
            try {
                field = XposedHelpers.findField(clazz, "mStartTime");
            } catch (Throwable ignored) {
                field = null;
            }
            if (field == null) {
                for (Field candidate : clazz.getDeclaredFields()) {
                    if (candidate.getType() == long.class) {
                        field = candidate;
                        break;
                    }
                }
            }
            if (field != null) {
                try {
                    field.setAccessible(true);
                } catch (Throwable ignored) {
                }
            }
            sStartTimeField = field;
            sStartTimeFieldResolved = true;
        }
        if (field == null) return -1;
        try {
            return field.getLong(info);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int taskIdOf(Object info) {
        try {
            return XposedHelpers.getIntField(info, "mTaskId");
        } catch (Throwable ignored) {
            try {
                return XposedHelpers.getIntField(info, "taskId");
            } catch (Throwable ignored2) {
                return -1;
            }
        }
    }

    // ==================== 后台运行：数量 ====================

    /**
     * 官方「最多支持 2 个应用后台运行」落在
     * {@code FloatHandleController.checkLivelySizeMaybeChangeToStatic(boolean)}：
     * {@code if (getLivelySize() == 2)} 就把列表里第二个降级成静态。
     *
     * <p>这里把阈值挪到 {@link #LIVELY_COUNT_LIMIT}：12 个以内一律跳过官方这段，
     * 到 12 个时由我们按同样的方式降级一个，其余行为（列表顺序、静态数清理）都不变。</p>
     */
    private void hookLivelyCount(ClassLoader cl) {
        try {
            Class<?> controllerClass = XposedHelpers.findClass(
                    "com.android.server.wm.FloatHandleController", cl);
            XposedHelpers.findAndHookMethod(controllerClass, "checkLivelySizeMaybeChangeToStatic",
                    boolean.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!livelyUnlockCount) return;
                            try {
                                Object controller = param.thisObject;
                                int size = (Integer) XposedHelpers.callMethod(controller, "getLivelySize");
                                if (size < LIVELY_COUNT_LIMIT) {
                                    // 官方只会对 size == 2 动手，12 以内直接不让它介入
                                    param.setResult(null);
                                    return;
                                }
                                Object listObj = XposedHelpers.getObjectField(
                                        controller, "mFloatHandleInfoList");
                                if (!(listObj instanceof List)) return;
                                List<?> list = (List<?>) listObj;
                                if (list.size() < 2) return;
                                Object victim = list.get(1);
                                boolean pendingAdd = (Boolean) param.args[0];
                                XposedHelpers.callMethod(controller, "changeToStatic", victim, pendingAdd);
                                param.setResult(null);
                                XposedBridge.log(TAG + " lively count limit " + LIVELY_COUNT_LIMIT
                                        + " reached, demote taskId=" + taskIdOf(victim));
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " lively count hook error: " + t.getMessage());
                            }
                        }
                    });
            XposedBridge.log(TAG + " FloatHandleController.lively count limit -> "
                    + LIVELY_COUNT_LIMIT);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook lively count failed: " + t.getMessage());
        }
    }

    /**
     * 新窗口进坞窗时官方还有第二处硬编码，光改上面那个方法不够：
     * {@code FloatHandleController.updateInfoMapForAdd()} 里
     * {@code if (livelySize == 2) changeToStatic(mFloatHandleInfoList.get(livelySize - 1), true)}，
     * 也就是后台运行已有 2 个时，新来的那个会把前一个踢成静态。
     *
     * <p>这里按调用来源把这次降级拦掉（只有从 updateInfoMapForAdd 调进来的改动），
     * 12 个满员时反过来把新窗口本身按静态加入，保证总数不超。</p>
     */
    private void hookJoinLivelyGuard(ClassLoader cl) {
        try {
            Class<?> controllerClass = XposedHelpers.findClass(
                    "com.android.server.wm.FloatHandleController", cl);

            // 参数类型是 com.android.server.wm.floathandle.FloatHandleInfo，直接按类名取容易
            // 因包名变化失败，这里按方法名 + 参数个数从控制器里捞，不依赖外部类名。
            for (Method method : controllerClass.getDeclaredMethods()) {
                if (!"updateInfoMapForAdd".equals(method.getName())) continue;
                if (method.getParameterCount() != 1) continue;
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!livelyUnlockCount) return;
                        try {
                            Object info = param.args[0];
                            if (info == null) return;
                            if (XposedHelpers.getIntField(info, "type") != 1) return;
                            int lively = (Integer) XposedHelpers.callMethod(
                                    param.thisObject, "getLivelySize");
                            if (lively < LIVELY_COUNT_LIMIT) return;
                            XposedHelpers.setIntField(info, "type", 0);
                            XposedBridge.log(TAG + " lively full(" + LIVELY_COUNT_LIMIT
                                    + "), add new window as static taskId=" + taskIdOf(info));
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " updateInfoMapForAdd guard error: " + t.getMessage());
                        }
                    }
                });
            }

            for (Method method : controllerClass.getDeclaredMethods()) {
                if (!"changeToStatic".equals(method.getName())) continue;
                if (method.getReturnType() != int.class || method.getParameterCount() != 2) continue;
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!livelyUnlockCount) return;
                        try {
                            if (!calledFrom("updateInfoMapForAdd")) return;
                            Object controller = param.thisObject;
                            int lively = (Integer) XposedHelpers.callMethod(controller, "getLivelySize");
                            if (lively >= LIVELY_COUNT_LIMIT) return;
                            param.setResult(0);
                            XposedBridge.log(TAG + " skip updateInfoMap lively demote, lively=" + lively);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " changeToStatic guard error: " + t.getMessage());
                        }
                    }
                });
            }
            XposedBridge.log(TAG + " updateInfoMapForAdd lively demote guard installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook updateInfoMap guard failed: " + t.getMessage());
        }
    }

    /** 当前调用栈里是否出现过指定方法名（用来区分 changeToStatic 的调用来源）。 */
    private static boolean calledFrom(String... methodNames) {
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            String name = e.getMethodName();
            for (String target : methodNames) {
                if (target.equals(name)) return true;
            }
        }
        return false;
    }

    /** 每次自由小窗创建时按当前开关改视图树。 */
    private void applyCaptionDecor(Object captionView) {        loadConfig(captionView);

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
                        } else if (KEY_LIVELY_HOURS.equals(key)) {
                            livelyHours = clampHours(value);
                        } else if (KEY_LIVELY_UNLOCK_COUNT.equals(key)) {
                            livelyUnlockCount = value == 1;
                        }
                    }
                    configLoaded = true;
                    bootstrapStoreOnce();
                    XposedBridge.log(TAG + " config(provider) -> hideBottomBar=" + hideBottomBar
                            + " hideThreeDot=" + hideThreeDot + " livelyHours=" + livelyHours
                            + " livelyUnlockCount=" + livelyUnlockCount);
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
            livelyHours = clampHours(prefs.getInt(KEY_LIVELY_HOURS, 1));
            livelyUnlockCount = prefs.getBoolean(KEY_LIVELY_UNLOCK_COUNT, true);
            configLoaded = true;
            bootstrapStoreOnce();
            XposedBridge.log(TAG + " config(xprefs) -> hideBottomBar=" + hideBottomBar
                    + " hideThreeDot=" + hideThreeDot + " firstDragToDock=" + firstDragToDock
                    + " livelyHours=" + livelyHours + " livelyUnlockCount=" + livelyUnlockCount);
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
        livelyHours = clampHours(intOf(map, KEY_LIVELY_HOURS, livelyHours));
        livelyUnlockCount = boolOf(map, KEY_LIVELY_UNLOCK_COUNT, livelyUnlockCount);
        configLoaded = true;
        XposedBridge.log(TAG + " config(store) -> hideBottomBar=" + hideBottomBar
                + " hideThreeDot=" + hideThreeDot + " firstDragToDock=" + firstDragToDock
                + " livelyHours=" + livelyHours + " livelyUnlockCount=" + livelyUnlockCount);
    }

    Map<String, String> configToMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(KEY_HIDE_BOTTOM_BAR, String.valueOf(hideBottomBar));
        map.put(KEY_HIDE_THREE_DOT, String.valueOf(hideThreeDot));
        map.put(KEY_FIRST_DRAG_DOCK, String.valueOf(firstDragToDock));
        map.put(KEY_LIVELY_HOURS, String.valueOf(livelyHours));
        map.put(KEY_LIVELY_UNLOCK_COUNT, String.valueOf(livelyUnlockCount));
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

    private static int intOf(Map<String, String> map, String key, int def) {
        String value = map.get(key);
        if (value == null) return def;
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    /** 只认 0（不限）、1（官方默认）、2/4/8/12，别的值当默认 1 小时。 */
    private static int clampHours(int hours) {
        if (hours == 0 || hours == 1 || hours == 2 || hours == 4 || hours == 8 || hours == 12) {
            return hours;
        }
        return 1;
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

                                MotionEvent ev = (MotionEvent) param.args[0];
                                int x = (int) ev.getX();
                                int y = (int) ev.getY();

                                /*
                                 * 主判定：mActionFlag == 1 就是「按在标题栏按钮区」。
                                 * 官方弹菜单（actionFlag 分支里的 notifySystemEvent 1001）只走这里，
                                 * 所以不看坐标、不看矩形大小，flag 命中就直接吃掉。
                                 * 之前按矩形判定会漏：系统算按钮区的范围比自己 ulimit 的那块大，
                                 * 点偏一点就不再 contains，菜单照样弹出来。
                                 */
                                int actionFlag = XposedHelpers.getIntField(thisObj, "mActionFlag");
                                if (actionFlag == 1) {
                                    XposedBridge.log(TAG + " blocked menu tap by flag at " + x + "," + y);
                                    param.setResult(false);
                                    return;
                                }

                                // 兜底：部分 ROM 的 mActionFlag 来不及更新，再按热区矩形判一次。
                                Rect region = handleButtonRegion(thisObj);
                                if (region == null || region.isEmpty()) return;
                                if (!region.contains(x, y)) return;

                                XposedBridge.log(TAG + " blocked menu tap by rect at " + x + "," + y);
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
     * 兜底：直接把「弹菜单」这个动作本身掐掉。
     *
     * <p>官方链路是 {@code FlexibleCaptionView.onSingleTapUp(...)} 里
     * {@code setFlexibleMenuShowing(true)} 之后调
     * {@code mFlexibleTaskController.notifySystemEvent(task, 1001)}，
     * SystemUI 收到 1001 才显示 {@code FlexibleTaskMenu}。整个 wm 只有这一处发 1001，
     * 所以在这里截断就不会再弹，而且不影响全屏 / 关闭 / 最小化这些其它按钮。</p>
     */
    private void hookMenuNotifyGuard(ClassLoader cl) {
        try {
            Class<?> controllerClass = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleTaskController", cl);
            for (Method method : controllerClass.getDeclaredMethods()) {
                if (!"notifySystemEvent".equals(method.getName())) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length != 2 || types[1] != int.class) continue;
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!hideThreeDot) return;
                        try {
                            int event = (Integer) param.args[1];
                            if (event != 1001) return;
                            // 官方在调用前已把「菜单正在显示」置位，这里复位避免状态卡住。
                            try {
                                XposedHelpers.callMethod(param.thisObject, "setFlexibleMenuShowing", false);
                            } catch (Throwable ignored) {
                            }
                            param.setResult(null);
                            XposedBridge.log(TAG + " blocked menu notify(event=1001)");
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " menu notify guard error: " + t.getMessage());
                        }
                    }
                });
                XposedBridge.log(TAG + " menu notify(event=1001) guard installed");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook menu notify guard failed: " + t.getMessage());
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

