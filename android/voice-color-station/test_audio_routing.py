"""Source-contract checks for audio routing, not a microphone integration test."""
import unittest
from pathlib import Path


class AudioRoutingContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = (Path(__file__).parent / 'src/local/ivand/voicecolorstation/VoiceListenerService.java').read_text(encoding='utf-8')

    def test_wake_branch_does_not_decode_audio_or_fabricate_transcript(self):
        branch = self.source.split('if (score >= wakeThreshold) {', 1)[1].split('} else if', 1)[0]
        self.assertIn('recognizer.reset();', branch)
        self.assertNotIn('acceptWaveForm', branch)
        self.assertIn('publishHeard("", false);', branch)
        self.assertNotIn('publishHeard(wakePhrase', branch)

    def test_only_command_branch_passes_frames_to_vosk(self):
        self.assertNotIn('preRoll', self.source)
        self.assertEqual(self.source.count('recognizer.acceptWaveForm('), 1)
        branch = self.source.split('if (commandWindowUntil != 0L) {', 1)[1].split('} else {', 1)[0]
        self.assertIn('recognizer.acceptWaveForm(frame, frame.length)', branch)

    def test_background_launch_requires_user_permission_and_does_not_unlock(self):
        branch = self.source.split('private void showWakeScreen()', 1)[1].split('private synchronized', 1)[0]
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


if __name__ == '__main__':
    unittest.main()
