"""Small deterministic regression checks; no personal recordings are read."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

from train_personal import approved, augment, frame, prepare


class PersonalTrainingTests(unittest.TestCase):
    def test_test_segmentation_requires_frozen_report_before_reading_audio(self):
        script = Path(__file__).with_name('prepare_personal_candidates.py')
        result = subprocess.run([
            sys.executable, '-S', str(script), '--split', 'test',
            '--wav', 'does-not-exist.wav', '--output', 'does-not-exist-output',
            '--ffmpeg', 'does-not-exist-ffmpeg',
        ], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Freeze model and threshold before opening test', result.stderr)

    def test_augmentation_shape_finite_peak_and_seed(self):
        audio = (4000 * np.sin(np.arange(16000) / 12)).astype(np.float32)
        background = np.ones(64000, np.float32) * 100
        first = augment(audio, background, np.random.default_rng(42))
        second = augment(audio, background, np.random.default_rng(42))
        self.assertEqual(first.shape, (32000,))
        self.assertTrue(np.isfinite(first).all())
        self.assertLessEqual(float(np.max(np.abs(first))), 32001)
        np.testing.assert_array_equal(first, second)
        with self.assertRaises(ValueError):
            frame(np.zeros(32001), np.random.default_rng(42))

    def test_review_requires_all_decisions_and_matching_source(self):
        with tempfile.TemporaryDirectory() as directory:
            private = Path(directory)
            candidates = private / 'candidates' / 'sample'
            candidates.mkdir(parents=True)
            manifest = {'source_pcm_sha256': 'source-a', 'clips': [{'file': 'one.wav'}]}
            (candidates / 'review.json').write_text(json.dumps(manifest), encoding='utf-8')
            decisions = {'source_pcm_sha256': {'train': 'source-a'}, 'decisions': {}}
            review = private / 'review-decisions.json'
            review.write_text(json.dumps(decisions), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'Unreviewed'):
                approved(private, 'train', 'sample')
            decisions['decisions']['train/one.wav'] = True
            review.write_text(json.dumps(decisions), encoding='utf-8')
            self.assertEqual(len(approved(private, 'train', 'sample')), 1)
            decisions['source_pcm_sha256']['train'] = 'different-source'
            review.write_text(json.dumps(decisions), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'different source'):
                approved(private, 'train', 'sample')

    def test_prepare_preserves_existing_run(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            marker = path / 'marker.txt'
            marker.write_text('preserve', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'previous runs are preserved'):
                prepare(path, path, path, 30, 30)
            self.assertEqual(marker.read_text(encoding='utf-8'), 'preserve')


if __name__ == '__main__':
    unittest.main()
