package local.ivand.voicecolorstation;

public final class WakeConfirmationTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        WakeConfirmation gate = new WakeConfirmation(0.65f, 3);
        check(!gate.accept(0.74f));
        check(!gate.accept(0.02f)); // isolated peak rejected
        check(gate.frames() == 0);
        check(!gate.accept(0.8f));
        check(!gate.accept(0.9f));
        check(!gate.accept(0.64f)); // two-frame peak rejected
        check(!gate.accept(0.65f));
        check(!gate.accept(0.65f));
        check(gate.accept(0.65f)); // equality follows >= threshold
        check(!gate.accept(0.99f)); // no duplicate while sustained
        gate.reset();
        check(!gate.accept(0.9f));
        gate.reset();
        check(!gate.accept(0.9f)); // reset must discard previous candidate
        check(!gate.accept(Float.NaN));
        check(gate.frames() == 0);
        check(!gate.accept(Float.POSITIVE_INFINITY));
        check(!gate.accept(0.9f));
        check(!gate.accept(0.9f));
        check(gate.accept(0.9f));
        boolean invalidRejected = false;
        try { new WakeConfirmation(Float.NaN, 3); } catch (IllegalArgumentException e) { invalidRejected = true; }
        check(invalidRejected);
        System.out.println("PASS: isolated/two-frame peaks, three-frame confirmation, equality, latch, reset, non-finite input/config");
    }
}
