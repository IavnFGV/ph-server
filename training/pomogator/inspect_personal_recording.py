"""Decode a local recording and save acoustic QA plus optional Vosk hints privately.

Does not label, split into training clips, upload or overwrite the original.
Requires numpy, FFmpeg, and optionally the Vosk Python package and model.
"""
import argparse
import hashlib
import json
import math
import re
import subprocess
import wave
from pathlib import Path

import numpy as np


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--ffmpeg', required=True)
    parser.add_argument('--vosk-model', type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit('Use a new output directory to preserve earlier inspection artifacts')
    args.output.mkdir(parents=True)
    pcm_path = args.output / 'recording-16000.wav'
    decode = subprocess.run([
        args.ffmpeg, '-nostdin', '-hide_banner', '-xerror', '-i', str(args.source),
        '-vn', '-ac', '1', '-ar', '16000', '-c:a', 'pcm_s16le', str(pcm_path),
    ], capture_output=True, text=True, check=True)
    (args.output / 'decode.log').write_text(decode.stderr, encoding='utf-8')
    with wave.open(str(pcm_path), 'rb') as source:
        assert source.getframerate() == 16000 and source.getnchannels() == 1
        pcm_bytes = source.readframes(source.getnframes())
    samples = np.frombuffer(pcm_bytes, dtype='<i2')
    if not len(samples):
        raise RuntimeError('No audio decoded')
    normalized = samples.astype(np.float64) / 32768
    peak = float(np.max(np.abs(normalized)))
    rms = float(np.sqrt(np.mean(normalized ** 2)))
    qa = {
        'source_name': args.source.name,
        'source_bytes': args.source.stat().st_size,
        'source_sha256': hashlib.sha256(args.source.read_bytes()).hexdigest(),
        'duration_seconds': len(samples) / 16000,
        'sample_rate': 16000,
        'channels': 1,
        'peak_dbfs': 20 * math.log10(max(peak, 1e-12)),
        'rms_dbfs': 20 * math.log10(max(rms, 1e-12)),
        'clipped_sample_fraction': float(np.mean(np.abs(samples.astype(np.int32)) >= 32767)),
        'phrase_labels_verified': False,
    }
    silence = subprocess.run([
        args.ffmpeg, '-nostdin', '-hide_banner', '-i', str(pcm_path),
        '-af', 'silencedetect=noise=-35dB:d=0.55', '-f', 'null', '-',
    ], capture_output=True, text=True, check=True)
    (args.output / 'silence.log').write_text(silence.stderr, encoding='utf-8')
    qa['silence_intervals_at_minus35db_550ms'] = len(re.findall('silence_start:', silence.stderr))

    if args.vosk_model:
        from vosk import Model, KaldiRecognizer, SetLogLevel
        SetLogLevel(-1)
        recognizer = KaldiRecognizer(Model(str(args.vosk_model)), 16000)
        recognizer.SetWords(True)
        results = []
        for position in range(0, len(pcm_bytes), 8000):
            if recognizer.AcceptWaveform(pcm_bytes[position:position + 8000]):
                results.append(json.loads(recognizer.Result()))
        results.append(json.loads(recognizer.FinalResult()))
        (args.output / 'vosk-hints.json').write_text(
            json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8'
        )
        words = [word for result in results for word in result.get('result', [])]
        qa['vosk_word_count'] = len(words)
        qa['vosk_hey_count_hint'] = sum(word['word'] == 'эй' for word in words)
        qa['vosk_hints_are_ground_truth'] = False
    (args.output / 'qa.json').write_text(json.dumps(qa, indent=2), encoding='utf-8')
    print(json.dumps(qa, indent=2))


if __name__ == '__main__':
    main()
