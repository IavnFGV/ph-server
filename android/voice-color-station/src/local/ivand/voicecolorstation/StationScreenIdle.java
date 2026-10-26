package local.ivand.voicecolorstation;

/** Main-thread owned screen timer; independent of ASR/audio-worker availability. */
public final class StationScreenIdle {
    private final SpeechIdleCountdown clock;
    private boolean active;
    private boolean pinned;
    private long generation;
    public StationScreenIdle(long duration) { clock = new SpeechIdleCountdown(duration); }
    public void resume(long now) { generation++; active = true; pinned = false; clock.arm(now); }
    public void pause() { active = false; pinned = false; clock.cancel(); }
    public void interact(long now) { if (active && !pinned) clock.refresh(now); }
    public void speech(String text, boolean isFinal, long now) {
        if (active) clock.observe(text, isFinal, now);
    }
    public void togglePin(long now) {
        if (!active) return;
        pinned = !pinned;
        if (pinned) clock.cancel(); else clock.arm(now);
    }
    public boolean active() { return active; }
    public boolean pinned() { return pinned; }
    public long session() { return active ? generation : 0L; }
    public long deadline() { return active && !pinned ? clock.deadline() : 0L; }
    public boolean shouldLock(long now, long scheduledSession, boolean ownsForeground) {
        return active && !pinned && clock.expired(now)
                && WakeUiTimeoutPolicy.shouldLockScreen(scheduledSession, ownsForeground, session());
    }
}
