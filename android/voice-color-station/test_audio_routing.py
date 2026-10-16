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


if __name__ == '__main__':
    unittest.main()
