package local.ivand.voicecolorstation;

public final class WakeUiTimeoutPolicyTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        check(WakeUiTimeoutPolicy.shouldLockScreen(123L, true, 123L));
        check(!WakeUiTimeoutPolicy.shouldLockScreen(0L, true, 0L)); // manually opened
        check(!WakeUiTimeoutPolicy.shouldLockScreen(123L, false, 123L)); // other app
        check(!WakeUiTimeoutPolicy.shouldLockScreen(123L, true, 0L)); // left and returned/settings
        check(!WakeUiTimeoutPolicy.shouldLockScreen(123L, true, 456L)); // newer wake owns UI
        System.out.println("PASS: timeout locks only the matching visible voice-opened session");
    }
}
