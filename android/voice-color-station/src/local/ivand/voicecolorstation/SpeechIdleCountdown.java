package local.ivand.voicecolorstation;

/** Audio-worker owned. Changed nonempty ASR text is an approximation of a new word. */
public final class SpeechIdleCountdown {
    private final long duration;
    private long deadline;
    private String previous = "";
    public SpeechIdleCountdown(long duration) {
        if (duration <= 0) throw new IllegalArgumentException("Positive duration required");
        this.duration = duration;
    }
    public void arm(long now) { previous = ""; deadline = now + duration; }
    public void cancel() { previous = ""; deadline = 0L; }
    public void refresh(long now) { if (deadline != 0L) deadline = now + duration; }
    public boolean observe(String text, boolean isFinal, long now) {
        boolean changed = !text.isEmpty() && !text.equals(previous);
        if (changed && deadline != 0L) deadline = now + duration;
        previous = isFinal ? "" : text;
        return changed;
    }
    public long deadline() { return deadline; }
    public boolean expired(long now) { return deadline != 0L && now >= deadline; }
}
