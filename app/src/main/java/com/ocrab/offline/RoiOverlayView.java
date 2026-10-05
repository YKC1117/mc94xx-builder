package com.ocrab.offline;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class RoiOverlayView extends View {
    public static final float ROI_LEFT = 0.05f;
    public static final float ROI_TOP = 0.35f;
    public static final float ROI_RIGHT = 0.95f;
    public static final float ROI_BOTTOM = 0.51f;

    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelText = new Paint(Paint.ANTI_ALIAS_FLAG);

    public RoiOverlayView(Context context) {
        super(context);
        init();
    }

    public RoiOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        shade.setColor(Color.argb(150, 0, 0, 0));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(dp(3));
        border.setColor(Color.rgb(56, 189, 248));
        labelBg.setColor(Color.argb(190, 0, 0, 0));
        labelText.setColor(Color.WHITE);
        labelText.setTextSize(dp(13));
        labelText.setFakeBoldText(true);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        RectF roi = new RectF(w * ROI_LEFT, h * ROI_TOP, w * ROI_RIGHT, h * ROI_BOTTOM);

        canvas.drawRect(0, 0, w, roi.top, shade);
        canvas.drawRect(0, roi.bottom, w, h, shade);
        canvas.drawRect(0, roi.top, roi.left, roi.bottom, shade);
        canvas.drawRect(roi.right, roi.top, w, roi.bottom, shade);
        canvas.drawRoundRect(roi, dp(12), dp(12), border);

        String msg = "只把料號放在藍框內";
        float tw = labelText.measureText(msg);
        float padX = dp(9), padY = dp(5);
        RectF bg = new RectF(
                roi.centerX() - tw / 2 - padX,
                roi.bottom - dp(34),
                roi.centerX() + tw / 2 + padX,
                roi.bottom - dp(8));
        canvas.drawRoundRect(bg, dp(8), dp(8), labelBg);
        canvas.drawText(msg, roi.centerX() - tw / 2, bg.bottom - padY, labelText);
    }
}
