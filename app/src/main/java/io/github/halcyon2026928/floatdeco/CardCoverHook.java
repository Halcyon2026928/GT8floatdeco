package io.github.halcyon2026928.floatdeco;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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
    private static volatile long lastReload = 0L;
    private static XSharedPreferences prefs;

    /** 与 SettingsActivity / ConfigProvider 共用的开关键。 */
    static final String KEY_CARD_COVER = "recents_card_cover";

    void install(ClassLoader cl) {
        reloadConfig();
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
            // hook 它的 onDraw after：此时 canvas 的 scale/translate 已 restore，坐标系即 view 坐标，
            // 直接按 measuredWidth/Height 画一遍 cover 内容覆盖官方 contain 的黑边。
            try {
                Class<?> impl = XposedHelpers.findClass(
                        "com.android.quickstep.views.OplusTaskThumbnailViewImpl", cl);
                XposedHelpers.findAndHookMethod(impl, "onDraw",
                        android.graphics.Canvas.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    drawCoverOnView(param.thisObject, param.args);
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

    /** 开关即时生效：节流读 prefs，避免每次布局都做文件 IO。 */
    private static void reloadConfig() {
        long now = System.currentTimeMillis();
        if (now - lastReload < 500L && prefs != null) return;
        lastReload = now;
        try {
            if (prefs == null) {
                prefs = new XSharedPreferences(ModulePrefs.PKG, ModulePrefs.NAME);
            } else {
                prefs.reload();
            }
            if (prefs.hasData()) {
                enabled = prefs.getBoolean(KEY_CARD_COVER, true);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " reload config failed: " + t);
        }
    }

    private static int sLogCount;

    private static void applyCover(Object view) {
        reloadConfig();
        if (!enabled) return;

        Object shaderObj = getField(view, "mBitmapShader");
        Object td = getField(view, "mThumbnailData");
        if (!(shaderObj instanceof BitmapShader) || td == null) {
            logOnce("skip: shader/td null shader=" + (shaderObj != null) + " td=" + (td != null));
            return;
        }

        int flexible = getIntField(td, "flexibleWindowMode");
        int windowing = getIntField(td, "windowingMode");

        Object bmpObj = getField(td, "thumbnail");
        if (!(bmpObj instanceof Bitmap)) {
            logOnce("skip: thumbnail not bitmap");
            return;
        }
        Bitmap bmp = (Bitmap) bmpObj;
        if (bmp.isRecycled() || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) {
            logOnce("skip: bitmap invalid");
            return;
        }

        int vw = callInt(view, "getMeasuredWidth");
        int vh = callInt(view, "getMeasuredHeight");
        if (vw <= 0 || vh <= 0) {
            logOnce("skip: view size 0 (" + vw + "x" + vh + ")");
            return;
        }

        // 判定条件放宽：柔性小窗标志命中，或快照比例与卡片比例明显不符（黑边特征）。
        // 普通全屏应用快照比例≈卡片比例，差异小，不会被误伤。
        float snapshotAspect = bmp.getWidth() / (float) bmp.getHeight();
        float viewAspect = vw / (float) vh;
        boolean aspectMismatch = snapshotAspect > viewAspect * 1.15f || snapshotAspect < viewAspect * 0.85f;
        boolean flexibleHit = (flexible == 2 || windowing == 6);
        logOnce("hit? flex=" + flexible + " win=" + windowing
                + " bmp=" + bmp.getWidth() + "x" + bmp.getHeight()
                + " view=" + vw + "x" + vh
                + " snapAspect=" + String.format("%.2f", snapshotAspect)
                + " viewAspect=" + String.format("%.2f", viewAspect)
                + " mismatch=" + aspectMismatch);
        if (!flexibleHit && !aspectMismatch) return;

        // cover = max 缩放，快照铺满卡片；居中后溢出部分被圆角裁掉，不留黑边。
        float scale = Math.max(vw / (float) bmp.getWidth(), vh / (float) bmp.getHeight());
        float dw = bmp.getWidth() * scale;
        float dh = bmp.getHeight() * scale;
        float dx = (vw - dw) * 0.5f;
        float dy = (vh - dh) * 0.5f;
        logOnce("APPLY COVER scale=" + String.format("%.3f", scale));

        Matrix m = new Matrix();
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
    }

    /**
     * onDraw after：此时系统 canvas 的 scale/translate 已 restore，坐标系即 view 坐标。
     * 直接按 measuredWidth/Height 画一遍 cover 内容，圆角裁剪到卡片框内，
     * 彻底覆盖官方 contain 留下的黑边。args = (canvas)
     */
    private static void drawCoverOnView(Object view, Object[] args) {
        reloadConfig();
        if (!enabled || args == null || args.length < 1 || !(args[0] instanceof Canvas)) return;

        Object td = getField(view, "mThumbnailData");
        if (td == null) return;
        Object bmpObj = getField(td, "thumbnail");
        if (!(bmpObj instanceof Bitmap)) return;
        Bitmap bmp = (Bitmap) bmpObj;
        if (bmp.isRecycled() || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) return;

        int vw = callInt(view, "getMeasuredWidth");
        int vh = callInt(view, "getMeasuredHeight");
        if (vw <= 0 || vh <= 0) return;

        float snapshotAspect = bmp.getWidth() / (float) bmp.getHeight();
        float viewAspect = vw / (float) vh;
        boolean aspectMismatch = snapshotAspect > viewAspect * 1.1f || snapshotAspect < viewAspect * 0.9f;
        int flexible = getIntField(td, "flexibleWindowMode");
        int windowing = getIntField(td, "windowingMode");
        if (!aspectMismatch && flexible != 2 && windowing != 6) return;

        // cover = max 缩放：快照短边贴卡片短边，长边溢出被圆角裁掉。
        // 额外放大 3%：cover 在“短边刚好贴合”时缩放后尺寸恰好等于卡片，边缘一点误差就会露黑，
        // 留出余量让画面多盖住一圈，彻底消除发丝黑边。
        float scale = Math.max(vw / (float) bmp.getWidth(), vh / (float) bmp.getHeight()) * 1.03f;
        float dw = bmp.getWidth() * scale;
        float dh = bmp.getHeight() * scale;
        float dx = (vw - dw) * 0.5f;
        float dy = (vh - dh) * 0.5f;

        // 取系统圆角，并略微收小：让画面多覆盖到四角一点，盖住官方 contain 留下的发丝黑边。
        // 优先用 mRadius[8]（四角 x/y），但 onDraw after 时它可能尚未更新为全 0 → 校验有效性。
        float[] radii = getFloatArrayField(view, "mRadius");
        boolean useEight = radii != null && radii.length >= 8 && hasPositive(radii);

        float radius;
        if (useEight) {
            // 四角各收小一点点（乘 0.9），保持圆角形状又多盖住角落。
            float[] adjusted = new float[8];
            for (int i = 0; i < 8; i++) adjusted[i] = radii[i] * 0.8f;
            radii = adjusted;
            radius = maxOf(radii);
        } else {
            radius = getFloatField(view, "mCurrentCornerRadius");
            if (radius <= 0f) {
                Object fp = getField(view, "mFullscreenParams");
                if (fp != null) radius = getFloatField(fp, "mCurrentDrawnCornerRadius");
            }
            if (radius <= 0f) radius = dp(view, 15f);
            radius *= 0.8f;
        }

        Canvas canvas = (Canvas) args[0];
        canvas.save();
        try {
            Path clip = new Path();
            RectF box = new RectF(-1f, -1f, vw + 1f, vh + 1f);
            if (useEight) {
                clip.addRoundRect(box, radii, Path.Direction.CW);
            } else {
                clip.addRoundRect(box, radius, radius, Path.Direction.CW);
            }
            canvas.clipPath(clip);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(bmp, null, new RectF(dx, dy, dx + dw, dy + dh), paint);
        } finally {
            canvas.restore();
        }
        logOnce("ON_DRAW COVER bmp=" + bmp.getWidth() + "x" + bmp.getHeight()
                + " view=" + vw + "x" + vh + " scale=" + String.format("%.3f", scale)
                + " radii8=" + useEight + " radius=" + String.format("%.1f", radius));
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
            Method m = view.getClass().getMethod("getResources");
            Object res = m.invoke(view);
            float density = android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_DIP, value,
                    (android.util.DisplayMetrics) res.getClass().getMethod("getDisplayMetrics").invoke(res));
            return density;
        } catch (Throwable t) {
            return value * 3f;
        }
    }

    /** 前 30 条每次都打，之后每 50 条打一条，避免刷屏但能看到稳定复现。 */
    private static void logOnce(String msg) {
        int n = ++sLogCount;
        if (n <= 30 || n % 50 == 0) {
            XposedBridge.log(TAG + " #" + n + " " + msg);
        }
    }

    // ---------------- 反射工具（self-contained，避免影响公共 helper） ----------------

    private static Field findField(Object obj, String name) {
        Class<?> c = obj == null ? null : obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
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
        try {
            Method m = obj.getClass().getMethod(name);
            return (Integer) m.invoke(obj);
        } catch (Throwable t) {
            return 0;
        }
    }
}
