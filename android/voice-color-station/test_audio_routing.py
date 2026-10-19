"""Source-contract checks for audio routing, not a microphone integration test."""
import unittest
from pathlib import Path


class AudioRoutingContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = (Path(__file__).parent / 'src/local/ivand/voicecolorstation/VoiceListenerService.java').read_text(encoding='utf-8')

    def test_wake_branch_does_not_decode_audio_or_fabricate_transcript(self):
        branch = self.source.split('if (confirmed) {', 1)[1].split('} else if', 1)[0]
        self.assertIn('recognizer.reset();', branch)
        self.assertNotIn('acceptWaveForm', branch)
        self.assertIn('publishHeard("", false);', branch)
        self.assertNotIn('publishHeard(wakePhrase', branch)

    def test_wake_gate_is_reset_with_acoustic_context(self):
        self.assertIn('new WakeConfirmation(wakeThreshold, 3)', self.source)
        self.assertEqual(self.source.count('detector.reset();'), 2)
        self.assertEqual(self.source.count('wakeConfirmation.reset();'), 2)

    def test_only_command_branch_passes_frames_to_vosk(self):
        self.assertNotIn('preRoll', self.source)
        self.assertEqual(self.source.count('recognizer.acceptWaveForm('), 1)
        branch = self.source.split('if (commandWindowUntil != 0L) {', 1)[1].split('} else {', 1)[0]
        self.assertIn('recognizer.acceptWaveForm(frame, frame.length)', branch)

    def test_background_launch_requires_user_permission_and_does_not_unlock(self):
        branch = self.source.split('private boolean showWakeScreen(long session)', 1)[1].split('private synchronized', 1)[0]
        self.assertLess(branch.index('Settings.canDrawOverlays'), branch.index('startActivity('))
        main = (Path(__file__).parent / 'src/local/ivand/voicecolorstation/MainActivity.java').read_text(encoding='utf-8')
        self.assertIn('setShowWhenLocked(true)', main)
        self.assertIn('setTurnScreenOn(true)', main)
        self.assertNotIn('requestDismissKeyguard', main)

    def test_boot_reminder_does_not_start_microphone(self):
        boot = (Path(__file__).parent / 'src/local/ivand/voicecolorstation/BootReminderReceiver.java').read_text(encoding='utf-8')
        self.assertIn('PendingIntent.getActivity', boot)
        self.assertNotIn('startForegroundService(', boot)
        self.assertNotIn('startService(', boot)

    def test_wake_lock_has_timeout_and_cleanup(self):
        self.assertIn('PowerManager.PARTIAL_WAKE_LOCK', self.source)
        self.assertIn('cpuLock.acquire(10 * 60 * 1000L)', self.source)
        self.assertIn('releaseCpuLock();', self.source.split('} finally {', 1)[1])
        self.assertIn('releaseCpuLock();', self.source.split('public void onDestroy()', 1)[1])

    def test_screen_off_requires_final_exact_command_in_command_window(self):
        handler = self.source.split('private void handleJson', 1)[1].split('private void screenOff', 1)[0]
        self.assertLess(handler.index('if (partial) return;'), handler.index('screenOff();'))
        self.assertIn('commandWindowUntil != 0L && "отбой".equals(phrase)', handler)
        screen = self.source.split('private void screenOff()', 1)[1].split('private void publishHeard', 1)[0]
        self.assertLess(screen.index('isAdminActive(admin)'), screen.index('policies.lockNow()'))
        self.assertNotIn('stopSelf(', screen)
        self.assertNotIn('releaseCpuLock(', screen)

    def test_device_admin_requests_only_force_lock(self):
        import xml.etree.ElementTree as ET
        admin = ET.parse(Path(__file__).parent / 'res/xml/screen_lock_admin.xml').getroot()
        self.assertEqual([policy.tag for policy in admin.find('uses-policies')], ['force-lock'])

    def test_speech_idle_timeout_rechecks_foreground_on_main_thread(self):
        timeout = self.source.split('private void checkIdleTimeout(long now)', 1)[1].split('private void lockScreen', 1)[0]
        self.assertIn('idleCountdown.expired(now)', timeout)
        self.assertIn('new Handler(Looper.getMainLooper())', timeout)
        self.assertIn('WakeUiTimeoutPolicy.shouldLockScreen(', timeout)
        self.assertIn('lockScreen("speech idle timeout=6000ms")', timeout)
        color = self.source.split('private void applyColor(', 1)[1].split('@Override', 1)[0]
        self.assertNotIn('idleCountdown.cancel()', color)
        self.assertNotIn('commandWindowUntil = 0L;', color)

    def test_ui_animates_monotonic_deadline_only_while_visible(self):
        widget = (Path(__file__).parent / 'src/local/ivand/voicecolorstation/IdleCountdownView.java').read_text(encoding='utf-8')
        self.assertIn('deadline - SystemClock.elapsedRealtime()', widget)
        self.assertIn('remaining > 0L && isShown()', widget)
        self.assertIn('postInvalidateDelayed(33L)', widget)
        self.assertIn('idleCountdown.observe(phrase, !partial, wordAt)', self.source)


if __name__ == '__main__':
    unittest.main()
