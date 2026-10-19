package local.ivand.voicecolorstation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/** Draw only while visible; animation reads the same monotonic deadline as the service. */
public final class IdleCountdownView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long deadline;
    private long duration = 6000L;
    private int foreground = 0xFFFFFFFF;
    private int lastSpokenSeconds = -1;
    public IdleCountdownView(Context context) { super(context); setVisibility(GONE); }
    public void setForeground(int color) { foreground = color; invalidate(); }
    public void setDeadline(long value, long durationMillis) {
        deadline = value;
        duration = durationMillis;
        setVisibility(value == 0L ? GONE : VISIBLE);
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (deadline == 0L) return;
        long remaining = Math.max(0L, deadline - SystemClock.elapsedRealtime());
        float fraction = Math.min(1f, remaining / (float) duration);
        float density = getResources().getDisplayMetrics().density;
        float scaled = getResources().getDisplayMetrics().scaledDensity;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float radius = Math.min(getWidth(), getHeight()) / 2f - 9f * density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(5f * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor((foreground & 0x00FFFFFF) | 0x30000000);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setColor(foreground);
        canvas.drawArc(new RectF(cx - radius, cy - radius, cx + radius, cy + radius),
                -90f, 360f * fraction, false, paint);
        int seconds = (int) ((remaining + 999L) / 1000L);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(30f * scaled);
        canvas.drawText(Integer.toString(seconds), cx, cy + 3f * density, paint);
        paint.setTextSize(13f * scaled);
        canvas.drawText("до сна", cx, cy + 25f * density, paint);
        if (seconds != lastSpokenSeconds) {
            setContentDescription("До гашения экрана " + seconds + " секунд");
            lastSpokenSeconds = seconds;
        }
        if (remaining > 0L && isShown()) postInvalidateDelayed(33L);
    }
}
