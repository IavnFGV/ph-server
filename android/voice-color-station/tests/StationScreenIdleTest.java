package local.ivand.voicecolorstation;

public final class StationScreenIdleTest {
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        StationScreenIdle timer = new StationScreenIdle(6000L);
        timer.resume(1000L); // manual open, resume after screen/power wake
        long first = timer.session();
        check(timer.deadline() == 7000L);
        check(!timer.shouldLock(6999L, first, true));
        timer.interact(4000L); // touch or power event
        check(timer.deadline() == 10000L);
        timer.speech("синий", false, 5000L);
        check(timer.deadline() == 11000L);
        timer.speech("синий", false, 6000L);
        check(timer.deadline() == 11000L); // duplicate partial must not refresh
        check(!timer.shouldLock(11000L, first, false)); // other foreground app
        check(timer.shouldLock(11000L, first, true));
        timer.togglePin(7000L);
        check(timer.pinned() && timer.deadline() == 0L);
        check(!timer.shouldLock(999999L, first, true));
        timer.interact(8000L);
        timer.speech("зелёный", true, 9000L);
        check(timer.deadline() == 0L); // pin has precedence over interactions
        timer.togglePin(10000L);
        check(!timer.pinned() && timer.deadline() == 16000L);
        timer.togglePin(11000L);
        timer.pause();
        check(!timer.pinned() && !timer.active() && timer.deadline() == 0L);
        timer.resume(20000L);
        check(timer.session() != first && timer.deadline() == 26000L);
        check(!timer.shouldLock(30000L, first, true)); // stale callback
        check(timer.shouldLock(26000L, timer.session(), true));
        System.out.println("PASS: manual/power resume, touch, speech, duplicate text, pin/unpin, focus loss, stale callback");
    }
}
