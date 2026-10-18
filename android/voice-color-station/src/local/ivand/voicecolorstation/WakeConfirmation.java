package local.ivand.voicecolorstation;

/** Confirm sustained scores; adjacent scores are correlated, not independent votes. */
public final class WakeConfirmation {
    private final float threshold;
    private final int requiredFrames;
    private int frames;
    private float peak;
    private boolean latched;

    public WakeConfirmation(float threshold, int requiredFrames) {
        if (!Float.isFinite(threshold) || threshold <= 0 || threshold >= 1 || requiredFrames < 1)
            throw new IllegalArgumentException("Invalid wake confirmation configuration");
        this.threshold = threshold;
        this.requiredFrames = requiredFrames;
    }

    public boolean accept(float score) {
        if (!Float.isFinite(score) || score < threshold) {
            reset();
            return false;
        }
        if (latched) return false;
        frames++;
        peak = Math.max(peak, score);
        if (frames == requiredFrames) {
            latched = true;
            return true;
        }
        return false;
    }

    public int frames() { return frames; }
    public float peak() { return peak; }
    public void reset() { frames = 0; peak = 0; latched = false; }
}
