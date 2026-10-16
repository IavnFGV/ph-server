"""Exercise compatibility patches against pinned source files without importing ML runtimes."""

import ast
import tempfile
import unittest
from pathlib import Path

from patch_colab_runtime import patch_openwakeword, patch_torch_audiomentations, replace_once


class PatchTests(unittest.TestCase):
    @unittest.skipUnless(
        (Path(__file__).resolve().parents[2] / '.tools/openWakeWord/openwakeword/train.py').exists()
        and (Path(__file__).resolve().parents[2] / '.tools/wheels/torch_audiomentations/utils/io.py').exists(),
        'Integration test requires the pinned upstream sources in .tools',
    )
    def test_pinned_sources_and_second_application(self):
        root = Path(__file__).resolve().parents[2]
        fixtures = root / '.tools'
        with tempfile.TemporaryDirectory() as directory:
            train = Path(directory) / 'train.py'
            audio = Path(directory) / 'io.py'
            train.write_bytes((fixtures / 'openWakeWord/openwakeword/train.py').read_bytes())
            audio.write_bytes((fixtures / 'wheels/torch_audiomentations/utils/io.py').read_bytes())
            patch_openwakeword(train)
            patch_torch_audiomentations(audio)
            first = (train.read_bytes(), audio.read_bytes())
            patch_openwakeword(train)
            patch_torch_audiomentations(audio)
            self.assertEqual(first, (train.read_bytes(), audio.read_bytes()))
            tree = ast.parse(train.read_text(encoding='utf-8'))
            self.assertTrue(any(isinstance(node, ast.keyword) and node.arg == 'dynamo'
                                for node in ast.walk(tree)))
            self.assertIn('value.detach().cpu()', train.read_text(encoding='utf-8'))
            self.assertNotIn('torchaudio.info(', audio.read_text(encoding='utf-8'))

    def test_unknown_source_is_rejected(self):
        with self.assertRaises(RuntimeError):
            replace_once('changed upstream', 'expected code', 'patched code', 'test')


if __name__ == '__main__':
    unittest.main()
