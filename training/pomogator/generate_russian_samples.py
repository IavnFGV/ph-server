#!/usr/bin/env python3
"""Generate 16 kHz Russian positive and hard-negative wake-word clips."""

from __future__ import annotations

import argparse
import math
import random
import shutil
import tempfile
from pathlib import Path

POSITIVE_PHRASES = ["Помогатор", "Помогатор!", "Эй, Помогатор"]
NEGATIVE_PHRASES = [
    "помогать",
    "помоги",
    "помощник",
    "помощь",
    "навигатор",
    "агитатор",
    "модератор",
    "провокатор",
    "индикатор",
    "генератор",
    "коммутатор",
    "аллигатор",
    "включи синий",
    "включи зелёный",
    "выключи экран",
]

LENGTH_SCALES = (0.72, 0.82, 0.92, 1.0, 1.1, 1.22, 1.35)
NOISE_SCALES = (0.55, 0.62, 0.667, 0.72, 0.8, 0.9)
NOISE_WIDTH_SCALES = (0.65, 0.72, 0.8, 0.9, 1.0)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--voices-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, default=Path("my_custom_model/pomogator"))
    parser.add_argument("--train-samples", type=int, default=1000)
    parser.add_argument("--test-samples", type=int, default=500)
    parser.add_argument("--seed", type=int, default=20261016)
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args()


def distribute(total: int, count: int) -> list[int]:
    base, remainder = divmod(total, count)
    return [base + (index < remainder) for index in range(count)]


def resample_directory(source: Path, destination: Path, prefix: str) -> None:
    import numpy as np
    from scipy.io import wavfile
    from scipy.signal import resample_poly

    destination.mkdir(parents=True, exist_ok=True)
    start_index = len(list(destination.glob("*.wav")))
    for offset, source_path in enumerate(sorted(source.glob("*.wav"))):
        sample_rate, audio = wavfile.read(source_path)
        if audio.ndim > 1:
            audio = audio[:, 0]
        if sample_rate != 16000:
            divisor = math.gcd(sample_rate, 16000)
            audio = resample_poly(audio.astype(np.float32), 16000 // divisor, sample_rate // divisor)
        audio = np.clip(audio, -32768, 32767).astype(np.int16)
        wavfile.write(destination / f"{prefix}-{start_index + offset:05d}.wav", 16000, audio)


def generate_split(
    voices: list[Path], phrases: list[str], count: int, destination: Path, seed: int
) -> None:
    from piper_sample_generator.__main__ import generate_samples_onnx

    rng = random.Random(seed)
    allocations = distribute(count, len(voices))
    with tempfile.TemporaryDirectory(prefix="pomogator-tts-") as temporary:
        temporary_root = Path(temporary)
        for voice_index, (voice, voice_count) in enumerate(zip(voices, allocations)):
            if voice_count == 0:
                continue
            phrase_sequence = [phrases[index % len(phrases)] for index in range(voice_count)]
            rng.shuffle(phrase_sequence)
            raw_dir = temporary_root / f"voice-{voice_index}"
            generate_samples_onnx(
                text=phrase_sequence,
                output_dir=raw_dir,
                model=str(voice),
                max_samples=voice_count,
                length_scales=LENGTH_SCALES,
                noise_scales=NOISE_SCALES,
                noise_scale_ws=NOISE_WIDTH_SCALES,
            )
            resample_directory(raw_dir, destination, voice.stem)


def main() -> None:
    args = parse_args()
    voices = sorted(args.voices_dir.glob("ru_RU-*-medium.onnx"))
    if len(voices) != 4:
        raise SystemExit(f"Expected 4 Russian Piper voices, found {len(voices)} in {args.voices_dir}")

    if args.output_dir.exists() and args.overwrite:
        shutil.rmtree(args.output_dir)
    if args.output_dir.exists() and any(args.output_dir.iterdir()):
        raise SystemExit(f"Output directory is not empty: {args.output_dir}; use --overwrite")

    jobs = [
        ("positive_train", POSITIVE_PHRASES, args.train_samples, args.seed),
        ("positive_test", POSITIVE_PHRASES, args.test_samples, args.seed + 1),
        ("negative_train", NEGATIVE_PHRASES, args.train_samples, args.seed + 2),
        ("negative_test", NEGATIVE_PHRASES, args.test_samples, args.seed + 3),
    ]
    for name, phrases, count, seed in jobs:
        print(f"Generating {count} clips in {name}", flush=True)
        generate_split(voices, phrases, count, args.output_dir / name, seed)

    print("Done. All clips are mono 16-bit PCM at 16 kHz.")


if __name__ == "__main__":
    main()
