package io.github.halcyon2026928.floatdeco;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 后台任务卡片「封面铺满」：消除自由小窗快照在竖长卡片里 contain 后的上下黑边。
 *
 * <p>原理（照搬 freewindow 的 LivelyHook.paint / roundCrop）：
 * 系统 {@code TaskThumbnailView.updateThumbnailMatrix()} 默认按 contain（min 缩放）
 * 把快照等比装进卡片，装不下的区域露出黑色 backgroundPaint。这里在该方法之后，
 * 对柔性小窗快照把 BitmapShader 的 localMatrix 改成 cover（max 缩放 + 居中），
 * 同时把 FullscreenDrawParams 的绘制框尺寸钉死为整张卡片，画面即铺满、不留黑边。</p>
 *
 * <p>只对柔性小窗（flexibleWindowMode==2 或 windowingMode==6）生效，
 * 普通全屏应用卡片保持官方行为。开关关闭时完全不干预。</p>
 */
final class CardCoverHook {

    private static final String TAG = "[卡片封面]";

    private static volatile boolean enabled = true;
    private static XSharedPreferences prefs;

    /**
     * 上滑关闭后卡片的显示方式（三选一）：
     * 0 = 裁剪页（快照铺满），1 = 启动页，2 = 官方（完全不接管）。
     *
     * <p><b>自定义图不是模式，是数据</b>：只要某个应用设了图，在 0/1 两种模式下都会优先
     * 显示这张图；只有 2（官方）才整体让路，这样用户不用逐张清除图片就能一键回到官方观感。</p>
     */
    static final String KEY_DISPLAY_MODE = "card_display_mode";
    static final int MODE_COVER = 0;
    static final int MODE_SPLASH = 1;
    static final int MODE_OFFICIAL = 2;

    /** 当前显示方式（Launcher 侧读取）。绘制线程只读这个 volatile。 */
    private static volatile int sMode = MODE_COVER;

    /** 与 SettingsActivity / ConfigProvider 共用的开关键。 */
    static final String KEY_CARD_COVER = "recents_card_cover";

    /**
     * 桌面进程自己的「重启记忆」：显示方式与封面铺满的上一份值。
     *
     * <p>为什么单独存一份：模块 app 的 prefs 是 0660，Launcher 是另一个 uid，读不到；
     * provider 又要等进程里拿到 Context 才能查，而且查询是跨进程的。结果是桌面一重启，
     * 第一帧只能按默认值画，等轮询拿到真值再改一次——观感就是「重启后变回默认」。</p>
     *
     * <p>这份文件写在 <b>Launcher 自己的私有目录</b>里（代码就跑在 Launcher 进程内，
     * uid 就是 Launcher），进程一起来同步读几十字节就能定下第一帧。</p>
     */
    private static final String LOCAL_PREFS = "floatdeco_launcher";
    /** 本地记忆是否已经装进内存（只装一次）。 */
    private static volatile boolean sLocalLoaded;
    /** 是否已经从模块侧拿到过真值；拿到过就不再让本地记忆覆盖。 */
    private static volatile boolean sRemoteSeen;

    /**
     * 进入 onDraw 时的 canvas 变换（= 干净的 view 局部坐标）。
     * 用 ThreadLocal 而不是普通静态字段：绘制链路里可能有嵌套 draw，
     * 同一个 Matrix 被两层同时写入就会互相覆盖。
     */
    private static final ThreadLocal<Matrix> sCanvasEnter = new ThreadLocal<>();

    /**
     * 被我们改过 shader 矩阵 / 绘制参数的卡片。key 是 view（WeakHashMap，随卡片回收失效）。
     * 关闭「封面铺满」时必须逐个还原，否则画面永远停在铺满状态。
     */
    private static final Map<Object, Boolean> sCoverApplied = new WeakHashMap<>();

    /** {@link #sCoverApplied} 的近似大小，只用于热路径快速跳过加锁（可能因 GC 偏大，仅作提示）。 */
    private static volatile int sCoverAppliedCount;

    /** 还原过程中会再次进 applyCover，用这个标记避免自我递归。 */
    private static volatile boolean sRestoringCover;

    void install(ClassLoader cl) {
        // 配置改由独立后台线程轮询：绘制路径上不再做任何文件/provider IO。
        startConfigPoll();
        try {
            Class<?> ttv = XposedHelpers.findClass(
                    "com.android.quickstep.views.TaskThumbnailView", cl);
            XposedHelpers.findAndHookMethod(ttv, "updateThumbnailMatrix",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                applyCover(param.thisObject);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " applyCover error: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + " updateThumbnailMatrix hook success");

            // 真正绘制走子类 OplusTaskThumbnailViewImpl（覆写了 onDraw/drawOnCanvas）。
            //
            // 两种模式分别在不同阶段：
            // - 启动页模式在 before 里把整张卡片画完，然后 setResult 跳过原方法。官方那张
            //   1440x2560 的快照底图会被我们的不透明底色完全盖住，可每帧仍要采样一次这么
            //   大的纹理——进出最近任务的动画里这就是掉帧的大头，索性不画。
            // - 裁剪画面模式仍在 after 里补一层 cover：before 时先记一份进入时的变换
            //   （那才是干净的 view 局部坐标），after 复位回来再画，画面才不会跟着残留
            //   的 scale/translate 漂移。
            try {
                Class<?> impl = XposedHelpers.findClass(
                        "com.android.quickstep.views.OplusTaskThumbnailViewImpl", cl);
                XposedHelpers.findAndHookMethod(impl, "onDraw",
                        android.graphics.Canvas.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                Canvas c = (Canvas) param.args[0];
                                Matrix entered = sCanvasEnter.get();
                                if (entered == null) {
                                    entered = new Matrix();
                                    sCanvasEnter.set(entered);
                                }
                                try {
                                    c.getMatrix(entered);
                                } catch (Throwable t) {
                                    sCanvasEnter.remove();
                                }
                                try {
                                    if (drawIconPageIfActive(param.thisObject, c)) {
                                        // 已经画完，原方法（快照底图）直接跳过。
                                        param.setResult(null);
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + " icon page error: " + t);
                                }
                            }

                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Canvas c = (Canvas) param.args[0];
                                    Matrix m = sCanvasEnter.get();
                                    int sc = c.save();
                                    try {
                                        if (m != null) c.setMatrix(m);
                                        drawCoverOnView(param.thisObject, param.args);
                                    } finally {
                                        c.restoreToCount(sc);
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + " onDraw error: " + t);
                                }
                            }
                        });
                XposedBridge.log(TAG + " OplusTaskThumbnailViewImpl.onDraw hook success");
            } catch (Throwable t) {
                XposedBridge.log(TAG + " onDraw hook failed: " + t);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " install failed: " + t);
        }
    }

    // ---------------- 配置读取：独立后台线程轮询，绘制路径零 IO ----------------

    /** 轮询线程是否已启动。 */
    private static volatile boolean sPollStarted;
    /** 最近一次绘制卡片的时间，用来决定轮询频率。 */
    private static volatile long sLastDrawAt;
    /** Launcher 侧 Context：查 provider 用，第一次绘制卡片时顺手取。 */
    private static volatile android.content.Context sCtx;

    /**
     * 起一条后台轮询线程读开关。
     *
     * <p><b>为什么不能在 onDraw 里读</b>：这个 ROM 上模块的 prefs 文件是 0660，Launcher
     * 是另一个 uid，{@code canRead=false}，所以 xprefs 每次都是「打开失败」。即使失败得快，
     * 那也是每 3 秒一次系统调用 + 一次 provider 查询，全压在绘制线程上；provider 那边还可能
     * 把被回收的模块进程重新拉起来，进出最近任务的动画里就是一下可感知的卡顿。</p>
     *
     * <p>现在改成独立线程每 2 秒读一次，绘制线程只读 volatile 字段。开关的生效延迟仍在
     * 2 秒内，但绘制路径上是纯内存访问。</p>
     */
    /** 轮询线程的休眠锁：第一次拿到 Context 时立刻叫醒它，省掉最多 10 秒的空等。 */
    private static final Object sPollLock = new Object();

    private static void startConfigPoll() {
        if (sPollStarted) return;
        synchronized (CardCoverHook.class) {
            if (sPollStarted) return;
            sPollStarted = true;
        }
        try {
            Thread t = new Thread(() -> {
                while (true) {
                    try {
                        pollConfigOnce();
                    } catch (Throwable ignored) {
                    }
                    // 正在用最近任务时 2s 一次（开关手感快）；长时间没画卡片就退到 10s，
                    // 免得空转去查 provider 把模块进程反复拉起来。
                    long idle = System.currentTimeMillis() - sLastDrawAt;
                    synchronized (sPollLock) {
                        try {
                            sPollLock.wait(idle > 5000L ? 10000L : 2000L);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }, "floatdeco-cfg-poll");
            t.setDaemon(true);
            t.start();
            XposedBridge.log(TAG + " config poll started");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " config poll start failed: " + t);
        }
    }

    /** 读一次配置：先文件（通常读不到），再 provider（本 ROM 上真正生效的通道）。 */
    private static void pollConfigOnce() {
        try {
            if (prefs == null) {
                prefs = new XSharedPreferences(ModulePrefs.PKG, ModulePrefs.NAME);
            } else {
                prefs.reload();
            }
            if (prefs.hasData()) {
                applyCoverFlag(prefs.getBoolean(KEY_CARD_COVER, true));
                applyModeFlag(modeOfPrefs(prefs));
            }
        } catch (Throwable ignored) {
        }

        android.content.Context ctx = sCtx;
        if (ctx == null) return;
        try {
            android.database.Cursor c = ctx.getContentResolver().query(
                    ConfigProvider.CONFIG_URI, null, null, null, null);
            if (c == null) return;
            try {
                while (c.moveToNext()) {
                    String key = c.getString(0);
                    int v = c.getInt(1);
                    if (KEY_CARD_COVER.equals(key)) applyCoverFlag(v == 1);
                    else if (KEY_DISPLAY_MODE.equals(key)) applyModeFlag(v);
                }
            } finally {
                c.close();
            }
        } catch (Throwable ignored) {
        }

        // 自定义卡片图片：对齐 provider 里的列表，变化的图在本线程解码（后台，不占绘制线程）。
        try {
            if (CustomCovers.sync(ctx)) {
                logLine("custom covers changed");
                invalidateCards();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 主线程 Handler，用来把「重绘」请求丢回 UI 线程。 */
    private static volatile android.os.Handler sMainHandler;

    /**
     * 自定义图变化后让已经缓存的卡片重绘。
     * 不然用户得把卡片滑走再滑回才看得到新图（封面是在 onDraw 里贴的）。
     */
    private static void invalidateCards() {
        final java.util.List<Object> views;
        synchronized (CARD_INFO) {
            if (CARD_INFO.isEmpty()) return;
            views = new java.util.ArrayList<>(CARD_INFO.keySet());
        }
        try {
            android.os.Handler h = sMainHandler;
            if (h == null) {
                h = new android.os.Handler(android.os.Looper.getMainLooper());
                sMainHandler = h;
            }
            h.post(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < views.size(); i++) {
                        Object v = views.get(i);
                        if (!(v instanceof android.view.View)) continue;
                        try {
                            ((android.view.View) v).invalidate();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /**
     * 从 prefs 读出显示方式。
     *
     * <p>兼容旧版：只有布尔 {@code slide_up_icon_page} 时，true 视为「启动页」。</p>
     */
    private static int modeOfPrefs(XSharedPreferences p) {
        try {
            // XSharedPreferences 这份 shim 没有 contains()，用 -1 作为「没写过」的哨兵值。
            int raw = p.getInt(KEY_DISPLAY_MODE, -1);
            if (raw >= 0) return normalizeMode(raw);
            return p.getBoolean(DecoHook.KEY_ICON_PAGE, false) ? MODE_SPLASH : MODE_COVER;
        } catch (Throwable t) {
            return MODE_COVER;
        }
    }

    /** 把越界的模式值收回到合法范围。旧版 2 是「自定义」，语义正好等于现在的「官方」。 */
    private static int normalizeMode(int m) {
        return (m == MODE_SPLASH || m == MODE_OFFICIAL) ? m : MODE_COVER;
    }

    private static void applyCoverFlag(boolean v) {
        sRemoteSeen = true;
        if (enabled != v) {
            enabled = v;
            logLine("cover=" + v);
            invalidateCards();
        }
        saveLocalMemory();
    }

    private static void applyModeFlag(int m) {
        sRemoteSeen = true;
        m = normalizeMode(m);
        if (sMode != m) {
            sMode = m;
            logLine("mode=" + m + (m == MODE_SPLASH ? " (splash)"
                    : m == MODE_OFFICIAL ? " (official)" : " (cover)"));
            // 切到「官方」要把已经铺满过的卡片还原：只停止叠加绘制是回不去的，
            // shader 的 local matrix 是持久副作用。invalidate 后下一帧 after 钩子会做还原。
            invalidateCards();
        }
        saveLocalMemory();
    }

    /** 记住 Launcher 的 Context（provider 查询要用），只取一次。 */
    private static void captureContext(Object view) {
        if (sCtx != null) return;
        if (!(view instanceof android.view.View)) return;
        try {
            sCtx = ((android.view.View) view).getContext().getApplicationContext();
        } catch (Throwable ignored) {
            return;
        }
        // Context 到手的第一时间做两件事：
        // 1. 把上次选的显示方式装回内存 —— 否则第一帧会按默认值（裁剪页）画，重启后看起来就是「变回默认」；
        // 2. 叫醒轮询线程立刻查一次 provider，拿到模块侧的权威值（用户可能在桌面没跑的时候改过）。
        loadLocalMemory();
        synchronized (sPollLock) {
            sPollLock.notifyAll();
        }
    }

    /** 本地记忆的落盘缓存：值没变就不重复写文件（轮询每 2 秒会走一遍这里）。 */
    private static volatile int sSavedMode = Integer.MIN_VALUE;
    private static volatile int sSavedCover = -1;

    /**
     * 装上「重启记忆」。
     *
     * <p>只在进程起来后第一次拿到 Context 时读一次：Launcher 私有目录里一个小文件，
     * 几十字节，成本可以忽略。如果模块侧的值已经先到（xprefs 在少数机器上可读），
     * 以模块侧为准，本地记忆不覆盖。</p>
     */
    private static void loadLocalMemory() {
        if (sLocalLoaded) return;
        final android.content.Context c = sCtx;
        if (c == null) return;
        sLocalLoaded = true;
        try {
            android.content.SharedPreferences p =
                    c.getSharedPreferences(LOCAL_PREFS, android.content.Context.MODE_PRIVATE);
            int m = p.getInt(KEY_DISPLAY_MODE, -1);
            boolean cover = p.getBoolean(KEY_CARD_COVER, enabled);
            if (!sRemoteSeen) {
                if (m >= 0) sMode = normalizeMode(m);
                enabled = cover;
            }
            // 记下已落盘的值，避免之后每轮轮询都重写一遍。
            sSavedMode = m >= 0 ? normalizeMode(m) : Integer.MIN_VALUE;
            sSavedCover = cover ? 1 : 0;
            XposedBridge.log(TAG + " local memory: mode=" + sMode + " cover=" + enabled);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " local memory load failed: " + t);
        }
    }

    /** 值变了就落盘一份，供下次桌面启动时第一帧使用。 */
    private static void saveLocalMemory() {
        final android.content.Context c = sCtx;
        if (c == null) return;
        final int mode = sMode;
        final int cover = enabled ? 1 : 0;
        if (mode == sSavedMode && cover == sSavedCover) return;
        try {
            c.getSharedPreferences(LOCAL_PREFS, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_DISPLAY_MODE, mode)
                    .putBoolean(KEY_CARD_COVER, cover == 1)
                    .apply();
            sSavedMode = mode;
            sSavedCover = cover;
        } catch (Throwable ignored) {
        }
    }

    private static int sLogCount;

    private static void markCoverApplied(Object view) {
        synchronized (sCoverApplied) {
            if (sCoverApplied.put(view, Boolean.TRUE) == null) {
                sCoverAppliedCount = sCoverApplied.size();
            }
        }
    }

    /**
     * 把这张卡片还原成官方 contain 形态。
     *
     * <p>{@code applyCover} 改的是 {@code mBitmapShader} 的 local matrix 和
     * {@code mFullscreenParams} 的缩放/inset——都是会一直生效的副作用。开关关掉后
     * 我们只是不再叠加绘制，官方 onDraw 仍然套着 cover 矩阵，所以看起来像没关掉。
     * 这里直接再调一次官方 {@code updateThumbnailMatrix()}，让它按 contain 重算一遍。</p>
     */
    private static void restoreCoverIfApplied(Object view) {
        if (sCoverAppliedCount <= 0) return;
        boolean hit;
        synchronized (sCoverApplied) {
            hit = sCoverApplied.remove(view) != null;
            if (hit) sCoverAppliedCount = sCoverApplied.size();
        }
        if (!hit || sRestoringCover) return;

        sRestoringCover = true;
        try {
            XposedHelpers.callMethod(view, "updateThumbnailMatrix");
            if (willLog()) logLine("cover restored -> official contain");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " restore cover failed: " + t);
        } finally {
            sRestoringCover = false;
        }
        if (view instanceof android.view.View) {
            try {
                ((android.view.View) view).invalidate();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void applyCover(Object view) {
        // 这里每张卡片都会走（updateThumbnailMatrix），是拿到 Launcher Context 最稳的时机；
        // 没它就没法查 provider，「封面铺满」开关会读不到关闭状态。
        captureContext(view);
        // 「官方」模式下完全不碰矩阵；还原过程中也不能再套一次，否则 updateThumbnailMatrix
        // 的 after 钩子会把刚算好的 contain 又改回 cover，还原就失效了。
        if (sMode == MODE_OFFICIAL || sRestoringCover || !enabled) return;

        Object shaderObj = getField(view, "mBitmapShader");
        Object td = getField(view, "mThumbnailData");
        if (!(shaderObj instanceof BitmapShader) || td == null) {
            if (willLog()) logLine("skip: shader/td null shader=" + (shaderObj != null)
                    + " td=" + (td != null));
            return;
        }

        int flexible = getIntField(td, "flexibleWindowMode");
        int windowing = getIntField(td, "windowingMode");

        Object bmpObj = getField(td, "thumbnail");
        if (!(bmpObj instanceof Bitmap)) {
            if (willLog()) logLine("skip: thumbnail not bitmap");
            return;
        }
        Bitmap bmp = (Bitmap) bmpObj;
        if (bmp.isRecycled() || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) {
            if (willLog()) logLine("skip: bitmap invalid");
            return;
        }

        int vw = measuredWidth(view);
        int vh = measuredHeight(view);
        if (vw <= 0 || vh <= 0) {
            if (willLog()) logLine("skip: view size 0 (" + vw + "x" + vh + ")");
            return;
        }

        // 判定条件放宽：柔性小窗标志命中，或快照比例与卡片比例明显不符（黑边特征）。
        // 普通全屏应用快照比例≈卡片比例，差异小，不会被误伤。
        float snapshotAspect = bmp.getWidth() / (float) bmp.getHeight();
        float viewAspect = vw / (float) vh;
        boolean aspectMismatch = snapshotAspect > viewAspect * 1.15f || snapshotAspect < viewAspect * 0.85f;
        boolean flexibleHit = (flexible == 2 || windowing == 6);
        if (willLog()) {
            logLine("hit? flex=" + flexible + " win=" + windowing
                    + " bmp=" + bmp.getWidth() + "x" + bmp.getHeight()
                    + " view=" + vw + "x" + vh
                    + " mismatch=" + aspectMismatch);
        }
        if (!flexibleHit && !aspectMismatch) return;

        // cover = max 缩放，快照铺满卡片；居中后溢出部分被圆角裁掉，不留黑边。
        float scale = Math.max(vw / (float) bmp.getWidth(), vh / (float) bmp.getHeight());
        float dw = bmp.getWidth() * scale;
        float dh = bmp.getHeight() * scale;
        float dx = (vw - dw) * 0.5f;
        float dy = (vh - dh) * 0.5f;
        if (willLog()) logLine("APPLY COVER scale=" + scale);

        Matrix m = sCoverMatrix;
        m.setScale(scale, scale);
        m.postTranslate(dx, dy);
        ((BitmapShader) shaderObj).setLocalMatrix(m);

        // 绘制框钉死为整张卡片：清零 inset、scale=1，防止官方 contain 的缩放/偏移把画面又缩小。
        Object fp = getField(view, "mFullscreenParams");
        if (fp != null) {
            setFloatField(fp, "mScale", 1.0f);
            Object insets = getField(fp, "mCurrentDrawnInsets");
            if (insets instanceof RectF) {
                ((RectF) insets).set(0f, 0f, 0f, 0f);
            }
        }
        // 记下这个 view 已被动过：关掉开关时必须还原，光「不再画」是回不去的。
        markCoverApplied(view);
    }

    /**
     * onDraw after：此时系统 canvas 的 scale/translate 已 restore，坐标系即 view 坐标。
     * 直接按 measuredWidth/Height 画一遍 cover 内容，圆角裁剪到卡片框内，
     * 彻底覆盖官方 contain 留下的黑边。args = (canvas)
     */
    private static void drawCoverOnView(Object view, Object[] args) {
        sLastDrawAt = System.currentTimeMillis();
        if (args == null || args.length < 1 || !(args[0] instanceof Canvas)) return;
        if (sMode == MODE_OFFICIAL || !enabled) {
            // 关掉开关不能只「不再画」：applyCover 改过的 shader 矩阵是持久副作用，
            // 官方 onDraw 会继续用它，必须让官方重算一次才能回到 contain 观感。
            restoreCoverIfApplied(view);
            return;
        }

        int vw = measuredWidth(view);
        int vh = measuredHeight(view);
        if (vw <= 0 || vh <= 0) return;

        // 每帧都会走到这里，所以身份信息（taskId / 窗口模式 / 包名）按 view 缓存，
        // 只有快照对象换了才重新反射读取。
        Object td = getField(view, "mThumbnailData");
        CardInfo ci = infoOf(view, td);

        Object bmpObj = ci.thumb;
        if (!(bmpObj instanceof Bitmap)) return;
        Bitmap bmp = (Bitmap) bmpObj;
        if (bmp.isRecycled() || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) return;

        float snapshotAspect = bmp.getWidth() / (float) bmp.getHeight();
        float viewAspect = vw / (float) vh;
        boolean aspectMismatch = snapshotAspect > viewAspect * 1.1f || snapshotAspect < viewAspect * 0.9f;
        if (!aspectMismatch && ci.flexible != 2 && ci.windowing != 6) return;

        float scale = Math.max(vw / (float) bmp.getWidth(), vh / (float) bmp.getHeight()) * 1.03f;
        float dw = bmp.getWidth() * scale;
        float dh = bmp.getHeight() * scale;
        float dx = (vw - dw) * 0.5f;
        float dy = (vh - dh) * 0.5f;

        Canvas canvas = (Canvas) args[0];
        Path clip = clipPathOf(view, ci, vw, vh);
        canvas.save();
        try {
            canvas.clipPath(clip);
            sDrawRect.set(dx, dy, dx + dw, dy + dh);
            canvas.drawBitmap(bmp, null, sDrawRect, sBitmapPaint);
        } finally {
            canvas.restore();
        }
        if (willLog()) logLine("ON_DRAW COVER bmp=" + bmp.getWidth() + "x" + bmp.getHeight()
                + " view=" + vw + "x" + vh + " scale=" + scale);
    }

    // 每帧复用的绘制对象，避免在 onDraw 里反复分配。
    private static final Paint sBitmapPaint =
            new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final RectF sDrawRect = new RectF();
    private static final RectF sScaledDst = new RectF();
    /** applyCover 的矩阵复用：动画里 per-frame 调用，new Matrix 属于纯浪费。 */
    private static final Matrix sCoverMatrix = new Matrix();

    private static int measuredWidth(Object view) {
        return view instanceof android.view.View ? ((android.view.View) view).getMeasuredWidth() : 0;
    }

    private static int measuredHeight(Object view) {
        return view instanceof android.view.View ? ((android.view.View) view).getMeasuredHeight() : 0;
    }

    /**
     * before onDraw：如果这张卡该显示启动页，就把整张卡片画完并返回 true（调用方据此跳过原方法）。
     *
     * <p><b>为什么要跳过原方法</b>：官方 onDraw 会把 1440x2560 的快照底图按 contain 画一遍，
     * 我们再用不透明底色盖住，等于每帧白白多采样一张两兆像素的纹理。进出最近任务的动画
     * （卡片缩放 + 模糊）本来就吃满 GPU，这一层多余的全卡绘制就是那点掉帧的来源。
     * 跳过之后，启动页模式的绘制成本反而比官方更低。</p>
     */
    private static boolean drawIconPageIfActive(Object view, Canvas canvas) {
        sLastDrawAt = System.currentTimeMillis();
        if (!(view instanceof android.view.View)) return false;

        // 读 sMode 之前必须先拿 Context：装载「重启记忆」就发生在里面。
        // 不然进程起来后的头一帧还是按默认值（裁剪页）画，用户就会觉得「重启后变回默认」。
        captureContext(view);
        final int mode = sMode;

        // 官方：整条接管路径都不走，连反射读 mThumbnailData 都省掉。
        if (mode == MODE_OFFICIAL) return false;
        // 「封面铺满」是总开关：关掉以后连自定义图和启动页图标都不画，
        // 整张卡片交还官方（after 钩子里 restoreCoverIfApplied 负责把 shader 矩阵还原）。
        if (!enabled) return false;
        // 裁剪页 + 没有任何自定义图：没有可画的东西，直接交给 after 补 cover。
        if (mode == MODE_COVER && !CustomCovers.hasAny()) return false;

        int vw = measuredWidth(view);
        int vh = measuredHeight(view);
        if (vw <= 0 || vh <= 0) return false;

        Object td = getField(view, "mThumbnailData");
        CardInfo ci = infoOf(view, td);
        if (ci.windowing != 100 && !(ci.taskId >= 0 && IconTaskMarks.isMarked(ci.taskId))) {
            return false;
        }

        // 自定义图优先于「裁剪页 / 启动页」：这是用户对单个应用的显式指定。
        // 只有「官方」才整体让路（那时上面已经 return 了）。
        if (drawCustomCover(view, canvas, vw, vh, ci)) {
            if (willLog()) logLine("CUSTOM COVER taskId=" + ci.taskId + " pkg=" + ci.pkg);
            return true;
        }

        // 裁剪页：剩下的一律交给 after 钩子补 cover。
        if (mode == MODE_COVER) return false;

        drawIconPage(view, canvas, vw, vh, ci);
        if (willLog()) logLine("ICON PAGE taskId=" + ci.taskId + " win=" + ci.windowing
                + " view=" + vw + "x" + vh + " pkg=" + ci.pkg);
        return true;
    }

    /**
     * 画用户自定义的卡片图：整张卡片 cover 铺满 + 同一套圆角裁剪。
     *
     * <p>图在配置轮询线程里就解码好了（{@link CustomCovers#sync}），这里只做一次矩阵
     * 计算和一次 drawBitmap，绘制线程上没有 IO、没有解码。</p>
     *
     * @return false 表示这个应用没有自定义图（或图还没解码好），调用方继续走原逻辑
     */
    private static boolean drawCustomCover(Object view, Canvas canvas, int vw, int vh,
                                          CardInfo ci) {
        Bitmap bm = CustomCovers.bitmapFor(ci.pkg);
        if (bm == null || bm.isRecycled() || bm.getWidth() <= 0 || bm.getHeight() <= 0) {
            return false;
        }
        float scale = Math.max(vw / (float) bm.getWidth(), vh / (float) bm.getHeight());
        float dw = bm.getWidth() * scale;
        float dh = bm.getHeight() * scale;
        float dx = (vw - dw) * 0.5f;
        float dy = (vh - dh) * 0.5f;

        canvas.save();
        try {
            canvas.clipPath(clipPathOf(view, ci, vw, vh));
            sDrawRect.set(dx, dy, dx + dw, dy + dh);
            canvas.drawBitmap(bm, null, sDrawRect, sBitmapPaint);
        } finally {
            canvas.restore();
        }
        return true;
    }

    /** 单个卡片的绘制状态缓存。key 是 view 实例（WeakHashMap，随卡片回收自动失效）。 */
    private static final class CardInfo {
        boolean inited;
        Object td;
        Object thumb;
        int taskId = -1;
        int windowing;
        int flexible;
        String pkg;

        /** 圆角裁剪路径缓存：路径形状只跟尺寸和圆角有关，滑动中不变。 */
        Path clip;
        int clipW;
        int clipH;
        float clipRadius = -1f;
        boolean clipEight;

        /** 启动页离屏渲染缓存：只跟包名和尺寸有关，圆角/动画都不参与。 */
        Bitmap rendered;
        String renderedPkg;
        int renderedW;
        int renderedH;

        /** 该 app 的启动页资源。纯图标型（无整图）不需要位图，每帧直接画更省内存。 */
        SplashArt splash;
        boolean splashTried;

        /** 纯图标型用的图标小位图（短边 45%），换包名或尺寸才重渲。 */
        Bitmap iconBmp;
        String iconPkg;
        int iconSize;
    }

    private static final Map<Object, CardInfo> CARD_INFO = new WeakHashMap<>();

    private static CardInfo infoOf(Object view, Object td) {
        CardInfo ci;
        synchronized (CARD_INFO) {
            ci = CARD_INFO.get(view);
            if (ci == null) {
                ci = new CardInfo();
                CARD_INFO.put(view, ci);
            }
        }
        // 快照对象或缩略图位图变化 = 这张卡换了任务/换了内容，身份信息需要重新解析。
        Object thumb = td == null ? null : getField(td, "thumbnail");
        if (!ci.inited || ci.td != td || ci.thumb != thumb) {
            ci.inited = true;
            ci.td = td;
            ci.thumb = thumb;
            ci.taskId = taskIdOf(view);
            ci.pkg = pkgOf(view);
            ci.windowing = td == null ? 0 : getIntField(td, "windowingMode");
            ci.flexible = td == null ? 0 : getIntField(td, "flexibleWindowMode");
            ci.clip = null;
            ci.splash = null;
            ci.splashTried = false;
        }
        return ci;
    }

    /** 圆角数组的复用缓冲：动画里 mRadius 每帧都在变，不能每帧 new float[8]。 */
    private static final float[] sRadiiScratch = new float[8];
    private static final RectF sClipBox = new RectF();

    /** 圆角裁剪路径：命中缓存直接复用，避免每帧 new Path + addRoundRect。 */
    private static Path clipPathOf(Object view, CardInfo ci, int vw, int vh) {
        float[] radii = getFloatArrayField(view, "mRadius");
        boolean useEight = radii != null && radii.length >= 8 && hasPositive(radii);
        float radius;
        if (useEight) {
            for (int i = 0; i < 8; i++) sRadiiScratch[i] = radii[i] * 0.8f;
            radius = maxOf(sRadiiScratch);
        } else {
            radius = getFloatField(view, "mCurrentCornerRadius");
            if (radius <= 0f) {
                Object fp = getField(view, "mFullscreenParams");
                if (fp != null) radius = getFloatField(fp, "mCurrentDrawnCornerRadius");
            }
            if (radius <= 0f) radius = dp(view, 15f);
            radius *= 0.8f;
        }
        if (ci.clip != null && ci.clipW == vw && ci.clipH == vh
                && ci.clipEight == useEight && Math.abs(ci.clipRadius - radius) < 0.5f) {
            return ci.clip;
        }
        // 圆角在缩放动画里连续变化，命中不了缓存时就地复用同一个 Path 重算，
        // 而不是每帧新建一个交给 GC（120Hz 下每秒要扔掉一百多个）。
        Path p = ci.clip != null ? ci.clip : new Path();
        p.reset();
        sClipBox.set(-1f, -1f, vw + 1f, vh + 1f);
        if (useEight) p.addRoundRect(sClipBox, sRadiiScratch, Path.Direction.CW);
        else p.addRoundRect(sClipBox, radius, radius, Path.Direction.CW);
        ci.clip = p;
        ci.clipW = vw;
        ci.clipH = vh;
        ci.clipRadius = radius;
        ci.clipEight = useEight;
        return p;
    }

    /** 从 TaskThumbnailView.mTask.key 读取 taskId。 */
    private static int taskIdOf(Object view) {
        try {
            Object task = getField(view, "mTask");
            if (task == null) return -1;
            Object key = getField(task, "key");
            if (key == null) return -1;
            return getIntField(key, "id");
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 画图标页：还原该 app 自己的启动页（SplashScreen）资源。
     *
     * <p><b>性能</b>：两条路分开走。</p>
     * <ul>
     *   <li>纯色底 + 图标（日志里最常见的 {@code color-app-icon}）：只预渲染一张小图标位图，
     *       不做整卡片大位图。一张 936x2038 的 ARGB 位图就是 7.6MB，几张小窗卡片同时出现
     *       就是几十兆分配。</li>
     *   <li>整张启动图（{@code window-bg}）：渲染一次到位图，之后每帧只 drawBitmap。</li>
     * </ul>
     *
     * <p><b>缓存不跟 view 尺寸绑死</b>。卡片在进出最近任务的动画里 measured 尺寸会连续变化，
     * 如果按「尺寸必须完全相等」判缓存，动画里就会每帧重建一次整卡位图。这里只在「尺寸明显
     * 变大」时才重渲，缩小时把已有位图直接缩放画上去——缩放一张静态启动图几乎不花钱，
     * 而重建一张 7.6MB 位图足够卡一帧。</p>
     *
     * <p><b>圆角不参与位图渲染</b>。卡片在缩放动画里圆角是连续变化的（实测 113.2→115.7），
     * 把圆角写进位图就得每帧 createBitmap + 整张离屏重绘，这也是之前卡顿的直接原因。</p>
     */
    private static void drawIconPage(Object view, Canvas canvas, int vw, int vh, CardInfo ci) {
        SplashArt splash = splashOf(view, ci);

        if (splash == null || splash.art == null) {
            // 只有背景色 + 应用图标：用一张小图标位图，不做整卡片大位图。
            drawIconOnly(view, canvas, vw, vh, ci, splash);
            return;
        }

        String pkg = ci.pkg;
        Bitmap art = ci.rendered;
        boolean pkgChanged = pkg == null ? ci.renderedPkg != null : !pkg.equals(ci.renderedPkg);
        // 只有明显变大才重渲；缩小/微调直接缩放旧图，动画里就不会每帧重建。
        boolean growTooMuch = art != null
                && (vw > ci.renderedW * 1.12f || vh > ci.renderedH * 1.12f);
        if (art == null || art.isRecycled() || pkgChanged || growTooMuch) {
            art = renderIconArt(vw, vh, pkg, splash, ci);
        }

        if (art == null) {
            // 位图分配失败：退化成即时绘制，不缓存，至少保证画面正确。
            canvas.save();
            try {
                canvas.clipPath(clipPathOf(view, ci, vw, vh));
                drawIconArtContent(canvas, vw, vh, splash, 0);
            } finally {
                canvas.restore();
            }
            return;
        }

        canvas.save();
        try {
            canvas.clipPath(clipPathOf(view, ci, vw, vh));
            if (art.getWidth() == vw + ICON_PAD * 2 && art.getHeight() == vh + ICON_PAD * 2) {
                canvas.drawBitmap(art, -ICON_PAD, -ICON_PAD, sBitmapPaint);
            } else {
                sScaledDst.set(-ICON_PAD, -ICON_PAD, vw + ICON_PAD, vh + ICON_PAD);
                canvas.drawBitmap(art, null, sScaledDst, sBitmapPaint);
            }
        } finally {
            canvas.restore();
        }
    }

    /** 该 app 的启动页资源，按卡片缓存（SplashArt.of 内部还有按包名的缓存）。 */
    private static SplashArt splashOf(Object view, CardInfo ci) {
        if (ci.splashTried) return ci.splash;
        ci.splashTried = true;
        try {
            if (ci.pkg != null && view instanceof android.view.View) {
                ci.splash = SplashArt.of(((android.view.View) view).getContext(), ci.pkg);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " splash load failed taskId=" + ci.taskId + " " + t);
        }
        if (willLog()) {
            SplashArt s = ci.splash;
            String extra = "";
            if (s != null && s.art != null) {
                // 把 Drawable 类型、固有尺寸、选中后的内容框一起打出来：
                // 米家这类「有 src 但看不见图标」的，看这几个值就能判断是画不出像素（退到
                // branding / app 图标），还是大画布里的一小块 logo。
                String box = "fullbleed";
                if (!s.fullBleed) {
                    prepareArt(s);
                    float[] b = s.chosenBox;
                    box = b == null ? "blank" : ((int) (b[0] * 100) + "," + (int) (b[1] * 100)
                            + "," + (int) (b[2] * 100) + "," + (int) (b[3] * 100));
                }
                extra = " cls=" + s.art.getClass().getSimpleName()
                        + " intr=" + s.art.getIntrinsicWidth() + "x" + s.art.getIntrinsicHeight()
                        + " iconBg=" + Integer.toHexString(s.iconBg)
                        + " use=" + (s.chosen == null ? "-" : s.chosen.getClass().getSimpleName())
                        + " box=" + box;
            }
            logLine("ICON ART taskId=" + ci.taskId
                    + " src=" + (s == null ? "none" : s.source) + extra + " pkg=" + ci.pkg);
        }
        return ci.splash;
    }

    /**
     * 图标相对卡片短边的比例。
     *
     * <p>原本是 0.45（接近 SplashScreen 的观感），实测在这个 ROM 的卡片上偏大，
     * 用户要求缩到一半，即 0.225。</p>
     */
    private static final float ICON_RATIO = 0.225f;

    /**
     * 纯色底 + 居中图标的图标页。
     *
     * <p>图标单独预渲染成一张小位图（短边 {@link #ICON_RATIO}，约 210x210 ≈ 180KB），
     * 而不是按整张卡片渲染（936x2038 ≈ 7.6MB）。上滑停顿进后台时会同时出现好几张卡片，
     * 7.6MB × N 的分配就是那一下卡顿的来源。</p>
     *
     * <p>缓存尺寸同样不跟 view 绑死：只有明显变大才重渲，其余情况下把已有图标
     * 缩放绘制到当前尺寸，动画里不会每帧重建位图。</p>
     */
    private static void drawIconOnly(Object view, Canvas canvas, int vw, int vh,
                                     CardInfo ci, SplashArt splash) {
        int bg = splash == null ? 0xFF2C2C2A : splash.bg;
        Drawable icon = splash == null ? null : splash.icon;
        int size = Math.round(Math.min(vw, vh) * ICON_RATIO);

        Bitmap ib = null;
        if (icon != null && size > 0) {
            ib = ci.iconBmp;
            boolean pkgChanged = ci.pkg == null ? ci.iconPkg != null : !ci.pkg.equals(ci.iconPkg);
            if (ib == null || ib.isRecycled() || pkgChanged || size > ci.iconSize * 1.15f) {
                ib = renderIconBmp(icon, size);
                if (ib != null) {
                    ci.iconBmp = ib;
                    ci.iconSize = size;
                    ci.iconPkg = ci.pkg;
                } else {
                    ib = ci.iconBmp;    // 分配失败就继续用旧的（可能为空）
                }
            }
        }

        canvas.save();
        try {
            canvas.clipPath(clipPathOf(view, ci, vw, vh));
            canvas.drawColor(bg);
            if (ib != null) {
                float half = size * 0.5f;
                float cx = vw * 0.5f;
                float cy = vh * 0.5f;
                sScaledDst.set(cx - half, cy - half, cx + half, cy + half);
                canvas.drawBitmap(ib, null, sScaledDst, sBitmapPaint);
            } else if (icon != null) {
                // 位图分配失败才走这里：直接让 Drawable 自己画。
                drawCentered(canvas, icon, vw, vh);
            }
        } finally {
            canvas.restore();
        }
    }

    private static Bitmap renderIconBmp(Drawable icon, int size) {
        try {
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            icon.setBounds(0, 0, size, size);
            icon.draw(c);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 渲染一份「内容位图」：背景铺满 + 启动页主视觉。跟圆角、和卡片动画完全无关。 */
    private static Bitmap renderIconArt(int vw, int vh, String pkg, SplashArt splash, CardInfo ci) {
        Bitmap target;
        try {
            // 四周各留 1px，配合裁剪框外扩 1px，边缘不会露出发丝白线。
            target = Bitmap.createBitmap(vw + ICON_PAD * 2, vh + ICON_PAD * 2,
                    Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            return null;
        }
        drawIconArtContent(new Canvas(target), vw, vh, splash, ICON_PAD);

        ci.rendered = target;
        ci.renderedPkg = pkg;
        ci.renderedW = vw;
        ci.renderedH = vh;
        rememberRendered(ci);
        return target;
    }

    /** 把启动页内容画出来（不含圆角裁剪）。pad 是四周留白，位图路径传 1，直画传 0。 */
    private static void drawIconArtContent(Canvas canvas, int vw, int vh,
                                           SplashArt splash, int pad) {
        // 背景先铺满，这样裁剪外扩的那 1px 也是有底的。
        canvas.drawColor(splash == null ? 0xFF2C2C2A : splash.bg);

        if (splash == null) return;
        canvas.save();
        try {
            if (pad != 0) canvas.translate(pad, pad);
            if (splash.fullBleed && splash.art != null) {
                // 整张启动图：等比铺满卡片（cover），多出的部分被圆角裁掉。
                drawCoverDrawable(canvas, splash.art, vw, vh);
            } else if (splash.art != null) {
                drawSplashIcon(canvas, splash, vw, vh);
            } else if (splash.icon != null) {
                drawCentered(canvas, splash.icon, vw, vh);
            }
        } finally {
            canvas.restore();
        }
    }

    /** 量启动图标内容框用的探针边长（每包一次，不在每帧路径上）。 */
    private static final int ART_PROBE = 256;
    /** 有底圈色时画的圆。 */
    private static final Paint sCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 把任意 drawable 画到探针位图上，扫描 alpha 得到内容框；全透明返回 null。 */
    private static float[] contentBoxOf(Drawable d) {
        if (d == null) return null;
        final int n = ART_PROBE;
        Bitmap probe = null;
        try {
            probe = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, n, n);
            d.draw(new Canvas(probe));
            int[] px = new int[n * n];
            probe.getPixels(px, 0, n, 0, 0, n, n);
            int minX = n, minY = n, maxX = -1, maxY = -1;
            for (int y = 0; y < n; y++) {
                int row = y * n;
                for (int x = 0; x < n; x++) {
                    if ((px[row + x] >>> 24) > 8) {
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
            if (maxX < minX || maxY < minY) return null;
            return new float[]{
                    minX / (float) n, minY / (float) n,
                    (maxX + 1) / (float) n, (maxY + 1) / (float) n};
        } catch (Throwable t) {
            return null;
        } finally {
            if (probe != null) {
                try {
                    probe.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 从 layer-list 里取出「唯一的内容层」，取不到返回 null。
     *
     * <p>米家这类 app 的启动图标是 {@code layer-list}，里面一个 item 写死了
     * {@code width/height=44dp}。LayerDrawable 的固定层尺寸<b>不受 setBounds 缩放影响</b>，
     * 所以按容器放大到 210px 时，logo 仍然只画 44dp（约 132px），看起来就像没有图标。
     * 只有「恰好一层、且不是纯色底」时才拆出这一层直接画，多层结构（色块 + logo + 角标）
     * 保持整体绘制，避免破坏原本的构图。</p>
     */
    private static Drawable singleVisualLayer(Drawable d) {
        if (!(d instanceof android.graphics.drawable.LayerDrawable)) return null;
        android.graphics.drawable.LayerDrawable ld =
                (android.graphics.drawable.LayerDrawable) d;
        Drawable only = null;
        try {
            for (int i = 0; i < ld.getNumberOfLayers(); i++) {
                Drawable c = ld.getDrawable(i);
                if (c == null) continue;
                if (c instanceof ColorDrawable) {
                    // 底色层：透明的忽略，有颜色的说明是「色块 + logo」，保持整体绘制。
                    if (((ColorDrawable) c).getColor() == 0) continue;
                    return null;
                }
                if (only != null) return null;
                only = c;
            }
        } catch (Throwable t) {
            return null;
        }
        return only;
    }

    /**
     * 选定这张卡片真正要画的启动图标，算一次后缓存在 SplashArt 上。
     *
     * <p>三级降级：</p>
     * <ol>
     *   <li><b>主视觉</b>（windowSplashScreenAnimatedIcon）。是单层 layer-list 时拆出内容层，
     *       绕开 item 里写死的固定尺寸。</li>
     *   <li>主视觉里<b>一个像素都画不出来</b>时，退到<b>底部署名图</b>
     *       （windowSplashScreenBrandingImage）。米家正是这种情况：AnimatedIcon 指向
     *       {@code @color/#00000000}（全透明），真正的 logo 挂在 BrandingImage 上。</li>
     *   <li>都没有内容，才用 app 图标兜底。</li>
     * </ol>
     */
    private static void prepareArt(SplashArt s) {
        if (s.chosenTried) return;
        s.chosenTried = true;

        Drawable d = s.art;
        float[] box = contentBoxOf(d);
        Drawable single = singleVisualLayer(d);
        if (single != null) {
            float[] sb = contentBoxOf(single);
            if (sb != null) {
                d = single;
                box = sb;
            }
        }

        if (box == null && s.branding != null) {
            Drawable b = s.branding;
            float[] bb = contentBoxOf(b);
            Drawable bs = singleVisualLayer(b);
            if (bs != null) {
                float[] sb = contentBoxOf(bs);
                if (sb != null) {
                    b = bs;
                    bb = sb;
                }
            }
            if (bb != null) {
                d = b;
                box = bb;
                if (willLog()) logLine("ICON ART icon blank -> branding src=" + s.source);
            }
        }

        s.chosen = box == null ? null : d;
        s.chosenBox = box;
    }

    /**
     * 画居中启动图标：按「内容框」而不是「画布」对尺寸，内容占卡片短边的 {@link #ICON_RATIO}。
     *
     * <p>内容框 = 整块画布时（绝大多数 app），行为与原来的居中绘制完全一样；
     * 内容只占画布一小块时，会按比例放大，logo 不会缩成一个点。</p>
     */
    private static void drawSplashIcon(Canvas canvas, SplashArt s, int vw, int vh) {
        prepareArt(s);
        Drawable art = s.chosen;
        float[] box = s.chosenBox;
        if (box == null || art == null) {
            if (willLog()) logLine("ICON ART blank src=" + s.source + " -> app-icon");
            if (s.icon != null) drawCentered(canvas, s.icon, vw, vh);
            return;
        }

        int target = Math.round(Math.min(vw, vh) * ICON_RATIO);
        float frac = Math.max(box[2] - box[0], box[3] - box[1]);
        if (frac <= 0.01f) frac = 1f;
        int size = Math.round(target / frac);
        // 内容极小（比如只占 3%）时不要放大到离谱的尺寸，给个上限。
        int cap = Math.round(Math.min(vw, vh) * 1.6f);
        if (size > cap) size = cap;
        if (size <= 0) return;

        float cx = vw * 0.5f;
        float cy = vh * 0.5f;
        // 让内容框的中心落在卡片中心，而不是画布中心。
        float bcx = (box[0] + box[2]) * 0.5f;
        float bcy = (box[1] + box[3]) * 0.5f;
        int left = Math.round(cx - bcx * size);
        int top = Math.round(cy - bcy * size);

        if (s.iconBg != 0) {
            // 系统启动页会在图标后面垫一个圆（windowSplashScreenIconBackgroundColor）。
            // 白 logo + 白背景这类「白上白」缺了这层就是看不见。
            sCirclePaint.setColor(s.iconBg);
            canvas.drawCircle(cx, cy, size * 0.5f, sCirclePaint);
        }
        art.setBounds(left, top, left + size, top + size);
        art.draw(canvas);
    }

    /** 图标页离屏位图四周的余量，与 cover 分支裁剪框外扩的 1px 对应。 */
    private static final int ICON_PAD = 1;

    /** 离屏位图最多保留 6 张，超出的丢掉引用交给 GC（不 recycle，绘制指令可能还引用着）。 */
    private static final java.util.ArrayDeque<CardInfo> RENDER_QUEUE = new java.util.ArrayDeque<>();

    private static void rememberRendered(CardInfo ci) {
        synchronized (RENDER_QUEUE) {
            RENDER_QUEUE.remove(ci);
            RENDER_QUEUE.addLast(ci);
            while (RENDER_QUEUE.size() > 6) {
                CardInfo old = RENDER_QUEUE.removeFirst();
                old.rendered = null;
                old.renderedPkg = null;
            }
        }
    }

    /** 把 drawable 等比铺满整块（cover）：短边贴合，长边溢出后被裁掉。 */
    private static void drawCoverDrawable(Canvas canvas, Drawable d, int vw, int vh) {
        int iw = d.getIntrinsicWidth();
        int ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) {
            // 无固有尺寸（layer-list / inset 之类）：直接给卡片尺寸，
            // 内部 gravity 会自己把 logo 摆到中间，正好是启动页原样。
            d.setBounds(0, 0, vw, vh);
            d.draw(canvas);
            return;
        }
        float scale = Math.max(vw / (float) iw, vh / (float) ih);
        int dw = Math.round(iw * scale);
        int dh = Math.round(ih * scale);
        int dx = (vw - dw) / 2;
        int dy = (vh - dh) / 2;
        d.setBounds(dx, dy, dx + dw, dy + dh);
        d.draw(canvas);
    }

    /** 居中画图标，尺寸取卡片短边的 {@link #ICON_RATIO}。 */
    private static void drawCentered(Canvas canvas, Drawable d, int vw, int vh) {
        int size = Math.round(Math.min(vw, vh) * ICON_RATIO);
        int left = (vw - size) / 2;
        int top = (vh - size) / 2;
        d.setBounds(left, top, left + size, top + size);
        d.draw(canvas);
    }

    /** 从 TaskThumbnailView.mTask.key 读出包名。 */
    private static String pkgOf(Object view) {
        try {
            Object task = getField(view, "mTask");
            Object key = task == null ? null : getField(task, "key");
            if (key == null) return null;
            Object intent = getField(key, "baseIntent");
            if (intent != null) {
                Object p = callMethod(intent, "getPackage");
                if (p instanceof String && !((String) p).isEmpty()) return (String) p;
            }
            Object comp = getField(key, "sourceComponent");
            if (comp != null) {
                Object p = callMethod(comp, "getPackageName");
                if (p instanceof String && !((String) p).isEmpty()) return (String) p;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Method 缓存：同上，getMethod 每帧调用也很贵。 */
    private static final Map<String, Method> METHOD_CACHE = new HashMap<>();
    private static final Map<String, Boolean> METHOD_MISS = new HashMap<>();

    private static Method findMethod(Object obj, String name) {
        Class<?> cls = obj == null ? null : obj.getClass();
        if (cls == null) return null;
        String key = cls.getName() + '#' + name;
        synchronized (METHOD_CACHE) {
            Method hit = METHOD_CACHE.get(key);
            if (hit != null) return hit;
            if (METHOD_MISS.containsKey(key)) return null;
        }
        try {
            Method m = cls.getMethod(name);
            m.setAccessible(true);
            synchronized (METHOD_CACHE) {
                METHOD_CACHE.put(key, m);
            }
            return m;
        } catch (Throwable t) {
            synchronized (METHOD_CACHE) {
                METHOD_MISS.put(key, Boolean.TRUE);
            }
            return null;
        }
    }

    private static Object callMethod(Object obj, String name) {
        Method m = findMethod(obj, name);
        if (m == null) return null;
        try {
            return m.invoke(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasPositive(float[] arr) {
        for (float v : arr) {
            if (v > 0.5f) return true;
        }
        return false;
    }

    private static float maxOf(float[] arr) {
        float m = 0f;
        for (float v : arr) if (v > m) m = v;
        return m;
    }

    private static float[] getFloatArrayField(Object obj, String name) {
        Field f = findField(obj, name);
        if (f == null) return null;
        try {
            Object v = f.get(obj);
            return v instanceof float[] ? (float[]) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static float getFloatField(Object obj, String name) {
        Field f = findField(obj, name);
        if (f == null) return 0f;
        try {
            return f.getFloat(obj);
        } catch (Throwable t) {
            return 0f;
        }
    }

    private static float dp(Object view, float value) {
        try {
            Object res = callMethod(view, "getResources");
            Object dm = res == null ? null : callMethod(res, "getDisplayMetrics");
            if (!(dm instanceof android.util.DisplayMetrics)) return value * 3f;
            return android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_DIP, value,
                    (android.util.DisplayMetrics) dm);
        } catch (Throwable t) {
            return value * 3f;
        }
    }

    /**
     * 是否需要输出这条日志。
     *
     * <p>热路径上必须先问这个、再拼字符串：onDraw 里每帧拼几个字符串，
     * 120Hz 下每秒就是几百次分配，本身就会制造 GC 抖动。</p>
     */
    private static boolean willLog() {
        int n = ++sLogCount;
        return n <= 40 || n % 200 == 0;
    }

    private static void logLine(String msg) {
        XposedBridge.log(TAG + " " + msg);
    }

    /** 冷路径（安装、反射失败等）用；热路径请用 willLog() + logLine()。 */
    private static void logOnce(String msg) {
        if (willLog()) logLine(msg);
    }

    // ---------------- 反射工具（self-contained，避免影响公共 helper） ----------------

    /** Field 缓存：类名+字段名 → Field。getDeclaredField + setAccessible 每帧做几次非常贵。 */
    private static final Map<String, Field> FIELD_CACHE = new HashMap<>();
    private static final Map<String, Boolean> FIELD_MISS = new HashMap<>();

    private static Field findField(Object obj, String name) {
        Class<?> cls = obj == null ? null : obj.getClass();
        if (cls == null) return null;
        String key = cls.getName() + '#' + name;
        synchronized (FIELD_CACHE) {
            Field hit = FIELD_CACHE.get(key);
            if (hit != null) return hit;
            if (FIELD_MISS.containsKey(key)) return null;
        }
        Class<?> c = cls;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                synchronized (FIELD_CACHE) {
                    FIELD_CACHE.put(key, f);
                }
                return f;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                break;
            }
        }
        synchronized (FIELD_CACHE) {
            FIELD_MISS.put(key, Boolean.TRUE);
        }
        return null;
    }

    private static Object getField(Object obj, String name) {
        Field f = findField(obj, name);
        if (f == null) return null;
        try {
            return f.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int getIntField(Object obj, String name) {
        Field f = findField(obj, name);
        if (f == null) return 0;
        try {
            return f.getInt(obj);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void setFloatField(Object obj, String name, float value) {
        Field f = findField(obj, name);
        if (f == null) return;
        try {
            f.setFloat(obj, value);
        } catch (Throwable ignored) {
        }
    }

    private static int callInt(Object obj, String name) {
        if (obj == null) return 0;
        Method m = findMethod(obj, name);
        if (m == null) return 0;
        try {
            Object v = m.invoke(obj);
            return v instanceof Integer ? (Integer) v : 0;
        } catch (Throwable t) {
            return 0;
        }
    }
}
