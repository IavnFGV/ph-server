"""Make reviewable speech candidates from pauses; labels require human approval.

Input is an already decoded mono PCM16 WAV. Test requires a frozen model report.
FFmpeg's silence threshold is configurable; this is segmentation, not ASR.
"""
import argparse
import hashlib
import json
import re
import subprocess
import wave
from pathlib import Path


def speech_spans(events, duration):
    spans = []
    cursor = 0.0
    for kind, value in events:
        point = min(float(value), duration)
        if kind == 'start':
            if point > cursor:
                spans.append([cursor, point])
        else:
            cursor = point
    if cursor < duration and (not events or events[-1][0] == 'end'):
        spans.append([cursor, duration])
    return spans


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--wav', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--ffmpeg', required=True)
    parser.add_argument('--split', choices=['train', 'validation', 'test'], required=True)
    parser.add_argument('--frozen-report', type=Path)
    parser.add_argument('--silence-db', type=float, default=-35)
    parser.add_argument('--silence-seconds', type=float, default=0.55)
    args = parser.parse_args()
    frozen = None
    if args.split == 'test':
        if not args.frozen_report:
            raise ValueError('Freeze model and threshold before opening test')
        report = json.loads(args.frozen_report.read_text(encoding='utf-8'))
        model = args.frozen_report.parent / 'hey-pomogator-personal.onnx'
        if hashlib.sha256(model.read_bytes()).hexdigest() != report['onnx_sha256']:
            raise ValueError('Frozen model hash mismatch')
        frozen = {'model_sha256': report['onnx_sha256'],
                  'threshold': report['suggested_threshold'],
                  'training_report_sha256': hashlib.sha256(args.frozen_report.read_bytes()).hexdigest()}
    if args.output.exists():
        raise SystemExit('Use a new directory; previous candidates are preserved')
    with wave.open(str(args.wav), 'rb') as source:
        if (source.getnchannels(), source.getsampwidth(), source.getframerate()) != (1, 2, 16000):
            raise ValueError('Expected mono PCM16 at 16 kHz')
        audio = source.readframes(source.getnframes())
    duration = len(audio) / 32000
    detection = subprocess.run([
        args.ffmpeg, '-nostdin', '-hide_banner', '-i', str(args.wav),
        '-af', f'silencedetect=noise={args.silence_db}dB:d={args.silence_seconds}',
        '-f', 'null', '-',
    ], capture_output=True, text=True, check=True)
    events = re.findall(r'silence_(start|end): ([0-9.]+)', detection.stderr)
    spans = [item for item in speech_spans(events, duration) if item[1] - item[0] >= 0.15]
    args.output.mkdir(parents=True)
    entries = []
    for index, (start, end) in enumerate(spans, 1):
        clip_start = max(0, start - 0.15)
        clip_end = min(duration, end + 0.2)
        name = f'{args.split}-{index:03d}.wav'
        with wave.open(str(args.output / name), 'wb') as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(16000)
            output.writeframes(audio[int(clip_start * 16000) * 2:int(clip_end * 16000) * 2])
        entries.append({
            'file': name, 'source_start': start, 'source_end': end,
            'clip_start': clip_start, 'clip_end': clip_end,
            'duration': clip_end - clip_start,
            'approved': False, 'label': None,
            'review_flags': (['short_fragment'] if end - start < 0.5 else [])
                            + (['possibly_multiple_phrases'] if end - start > 2.5 else []),
        })
    manifest = {
        'split': args.split,
        'source_pcm_sha256': hashlib.sha256(args.wav.read_bytes()).hexdigest(),
        'target_phrase': 'Эй, Помогатор',
        'silence_db': args.silence_db, 'silence_seconds': args.silence_seconds,
        'review_required': True, 'clips': entries,
    }
    if frozen:
        manifest['frozen_evaluation'] = frozen
    (args.output / 'review.json').write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'{args.split}: {len(entries)} candidates (not approved for training)')
    for item in entries:
        print(f"{item['file']} {item['source_start']:.2f}-{item['source_end']:.2f}s "
              f"speech={item['source_end'] - item['source_start']:.2f}s {item['review_flags']}")


if __name__ == '__main__':
    main()
