package local.ivand.voicecolorstation;

public final class SpeechIdleCountdownTest {
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        SpeechIdleCountdown clock = new SpeechIdleCountdown(6000L);
        clock.arm(1000L);
        check(clock.deadline() == 7000L);
        check(clock.observe("включи", false, 2000L));
        check(clock.deadline() == 8000L);
        check(!clock.observe("включи", false, 4000L));
        check(clock.deadline() == 8000L); // repeated partial is not more speech
        check(clock.observe("включи синий", false, 5000L));
        check(clock.deadline() == 11000L);
        check(!clock.observe("включи синий", true, 6000L));
        check(clock.deadline() == 11000L); // final duplicates last partial
        check(!clock.expired(10999L));
        check(clock.expired(11000L));
        check(clock.observe("синий", false, 12000L));
        check(clock.deadline() == 18000L);
        check(!clock.observe("", true, 13000L));
        check(clock.observe("синий", false, 14000L)); // new segment, same word
        check(clock.deadline() == 20000L);
        clock.cancel();
        check(clock.deadline() == 0L && !clock.expired(30000L));
        check(clock.observe("слово", false, 31000L));
        check(clock.deadline() == 0L); // manual/no owned screen stays unarmed
        clock.arm(40000L);
        check(clock.deadline() == 46000L);
        System.out.println("PASS: last-word deadline, repeated partial/final, new segment, boundary, cancel, unarmed timer");
    }
}
