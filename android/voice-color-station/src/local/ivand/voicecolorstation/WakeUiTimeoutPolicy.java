package local.ivand.voicecolorstation;

/** Do not lock an unrelated foreground task or a manually opened station. */
public final class WakeUiTimeoutPolicy {
    private WakeUiTimeoutPolicy() { }
    public static boolean shouldLockScreen(long expiredSession, boolean stationVisible, long activeSession) {
        return expiredSession != 0L && stationVisible && expiredSession == activeSession;
    }
}
