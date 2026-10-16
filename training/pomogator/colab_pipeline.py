#!/usr/bin/env python3
"""Reproducible Colab pipeline for the Russian Pomogator openWakeWord model."""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import wave
from pathlib import Path


OWW_COMMIT = "368c03716d1e92591906a84949bc477f3a834455"
PIPER_COMMIT = "2971426a55072f7d22fec416ca7800df8bd23207"
LEGACY_PIPER_COMMIT = "f1988a4d54eddb23d99e86f0adfef6226a85acc7"
MIT_RIR_COMMIT = "b824a1ef2821f112fda0b9cb26e4278c62b425bb"
FMA_COMMIT = "039ff9b049ab4240f2a389a9d1fc6d0a14ba2cb0"
FEATURES_COMMIT = "985bf1b47e7f19c07741af82bfe32d5a9dc56096"
AUDIOSET_COMMIT = "196c0900867eff791b8f4d4be57db277e9a5b131"
AUDIOSET_SHA256 = "9ab88550e77c8289acf71d19ae3f254fadad16f3cea305ef08a16949a928acf9"

ROOT = Path.cwd()
PROJECT = ROOT / "ph-server"
TRAINING = PROJECT / "training" / "pomogator"
MODEL_ROOT = ROOT / "my_custom_model"
SAMPLE_ROOT = MODEL_ROOT / "pomogator"


def run(command: list[str], *, env: dict[str, str] | None = None) -> None:
    print("+", subprocess.list2cmdline(command), flush=True)
    subprocess.run(command, check=True, env=env)


def clone_at(url: str, destination: Path, commit: str) -> None:
    if not destination.exists():
        run(["git", "clone", url, str(destination)])
    run(["git", "-C", str(destination), "fetch", "origin", commit, "--depth", "1"])
    run(["git", "-C", str(destination), "checkout", "--detach", commit])


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(8 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def download(url: str, target: Path, *, expected_size: int | None = None,
             expected_sha256: str | None = None) -> None:
    valid = target.exists()
    if valid and expected_size is not None:
        valid = target.stat().st_size == expected_size
    if valid and expected_sha256 is not None:
        valid = sha256(target) == expected_sha256.lower()
    if not valid:
        target.parent.mkdir(parents=True, exist_ok=True)
        run(["curl", "-fL", "--retry", "3", "-o", str(target), url])
    if expected_size is not None and target.stat().st_size != expected_size:
        raise RuntimeError(f"Unexpected size for {target}: {target.stat().st_size}")
    if expected_sha256 is not None and sha256(target) != expected_sha256.lower():
        raise RuntimeError(f"SHA-256 mismatch for {target}")


def setup() -> None:
    clone_at("https://github.com/dscripka/openWakeWord.git", ROOT / "openwakeword", OWW_COMMIT)
    clone_at(
        "https://github.com/dscripka/piper-sample-generator.git",
        ROOT / "piper-sample-generator-legacy",
        LEGACY_PIPER_COMMIT,
    )
    pip = [sys.executable, "-m", "pip", "install", "-q"]
    run(pip + ["-e", "./openwakeword", "--no-deps"])
    run(pip + [f"git+https://github.com/rhasspy/piper-sample-generator.git@{PIPER_COMMIT}"])
    run(
        pip
        + [
            "mutagen==1.47.0",
            "torchinfo==1.8.0",
            "torchmetrics==1.2.0",
            "speechbrain==0.5.14",
            "audiomentations==0.33.0",
            "torch-audiomentations==0.11.2",
            "acoustics==0.2.6",
            "onnxruntime==1.22.1",
            "onnx==1.19.1",
            "onnxsim",
            "pronouncing==0.2.0",
            "datasets==2.14.6",
            "deep-phonemizer==0.0.19",
            "soundfile",
        ]
    )
    run(pip + ["--force-reinstall", "--no-deps", "pyarrow==20.0.0"])

    resources = ROOT / "openwakeword" / "openwakeword" / "resources" / "models"
    download(
        "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/embedding_model.onnx",
        resources / "embedding_model.onnx",
    )
    download(
        "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/melspectrogram.onnx",
        resources / "melspectrogram.onnx",
    )
    run(
        [
            sys.executable,
            "-c",
            "import pyarrow,datasets,torch,torchaudio; "
            "print('pyarrow',pyarrow.__version__,'datasets',datasets.__version__,"
            "'torch',torch.__version__,'torchaudio',torchaudio.__version__)",
        ]
    )


def generate() -> None:
    voices = ROOT / "voices"
    voice_base = "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/ru/ru_RU"
    for name in ("denis", "dmitri", "irina", "ruslan"):
        stem = f"ru_RU-{name}-medium.onnx"
        url = f"{voice_base}/{name}/medium/{stem}"
        download(url, voices / stem, expected_size=63_201_294)
        download(url + ".json", voices / (stem + ".json"))

    environment = os.environ.copy()
    legacy_path = str(ROOT / "piper-sample-generator-legacy")
    environment["PYTHONPATH"] = legacy_path + os.pathsep + environment.get("PYTHONPATH", "")
    run(
        [
            sys.executable,
            str(TRAINING / "generate_russian_samples.py"),
            "--voices-dir",
            str(voices),
            "--output-dir",
            str(SAMPLE_ROOT),
            "--train-samples",
            "1000",
            "--test-samples",
            "500",
            "--overwrite",
        ],
        env=environment,
    )
    expected = {
        "positive_train": 1000,
        "positive_test": 500,
        "negative_train": 1000,
        "negative_test": 500,
    }
    for name, count in expected.items():
        files = list((SAMPLE_ROOT / name).glob("*.wav"))
        if len(files) != count:
            raise RuntimeError(f"Expected {count} WAVs in {name}, found {len(files)}")
        with wave.open(str(files[0]), "rb") as wav:
            if wav.getframerate() != 16000 or wav.getnchannels() != 1 or wav.getsampwidth() != 2:
                raise RuntimeError(f"Unexpected WAV format in {files[0]}")
        print(f"{name}: {len(files)} valid WAV files")


def data() -> None:
    run(["apt-get", "update", "-qq"])
    run(["apt-get", "install", "-y", "-qq", "git-lfs"])
    run(["git", "lfs", "install"])

    rir_source = ROOT / "MIT_environmental_impulse_responses"
    clone_at(
        "https://huggingface.co/datasets/davidscripka/MIT_environmental_impulse_responses",
        rir_source,
        MIT_RIR_COMMIT,
    )
    run(["git", "-C", str(rir_source), "lfs", "pull"])
    rir_files = sorted((rir_source / "16khz").glob("*.wav"))
    if len(rir_files) < 270:
        raise RuntimeError(f"Expected at least 270 RIR files, found {len(rir_files)}")
    rir_output = ROOT / "mit_rirs"
    rir_output.mkdir(exist_ok=True)
    for source in rir_files:
        with source.open("rb") as stream:
            header = stream.read(4)
        if header not in (b"RIFF", b"RF64", b"RIFX"):
            raise RuntimeError(f"Git LFS pointer or invalid WAV: {source}")
        target = rir_output / source.name
        if not target.exists():
            shutil.copy2(source, target)

    archive = ROOT / "audioset" / "bal_train09.tar"
    download(
        "https://huggingface.co/datasets/agkphysics/AudioSet/"
        f"resolve/{AUDIOSET_COMMIT}/bal_train09.tar",
        archive,
        expected_sha256=AUDIOSET_SHA256,
    )
    subprocess.run(
        ["tar", "-tf", str(archive)], stdout=subprocess.DEVNULL, check=True
    )
    audio_root = ROOT / "audioset"
    if not (audio_root / "audio").exists():
        run(["tar", "-xf", str(archive), "-C", str(audio_root)])

    import datasets
    import numpy as np
    import scipy.io.wavfile
    from tqdm.auto import tqdm

    flacs = sorted((audio_root / "audio").rglob("*.flac"))
    if not flacs:
        raise RuntimeError("AudioSet archive contains no FLAC files")
    audioset_output = ROOT / "audioset_16k"
    audioset_output.mkdir(exist_ok=True)
    audio_dataset = datasets.Dataset.from_dict(
        {"audio": [str(path) for path in flacs]}
    ).cast_column("audio", datasets.Audio(sampling_rate=16000))
    for source, row in zip(flacs, tqdm(audio_dataset, total=len(flacs), desc="AudioSet")):
        target = audioset_output / f"{source.stem}.wav"
        if target.exists():
            continue
        audio = np.asarray(row["audio"]["array"])
        pcm = (np.clip(audio, -1, 1) * 32767).astype(np.int16)
        scipy.io.wavfile.write(target, 16000, pcm)

    fma_output = ROOT / "fma"
    fma_output.mkdir(exist_ok=True)
    if len(list(fma_output.glob("*.wav"))) < 120:
        stream = datasets.load_dataset(
            "rudraml/fma",
            name="small",
            split="train",
            streaming=True,
            revision=FMA_COMMIT,
        ).cast_column("audio", datasets.Audio(sampling_rate=16000))
        for index, row in enumerate(tqdm(iter(stream), total=120, desc="FMA")):
            if index >= 120:
                break
            target = fma_output / (Path(row["audio"]["path"]).stem + ".wav")
            audio = np.asarray(row["audio"]["array"])
            scipy.io.wavfile.write(
                target, 16000, (np.clip(audio, -1, 1) * 32767).astype(np.int16)
            )

    feature_base = (
        "https://huggingface.co/datasets/davidscripka/openwakeword_features/resolve/"
        + FEATURES_COMMIT
    )
    download(
        feature_base + "/openwakeword_features_ACAV100M_2000_hrs_16bit.npy",
        ROOT / "openwakeword_features_ACAV100M_2000_hrs_16bit.npy",
        expected_size=17_280_000_128,
    )
    download(
        feature_base + "/validation_set_features.npy",
        ROOT / "validation_set_features.npy",
        expected_size=184_836_608,
    )
    print(
        f"Data ready: RIR={len(list(rir_output.glob('*.wav')))}, "
        f"AudioSet={len(list(audioset_output.glob('*.wav')))}, "
        f"FMA={len(list(fma_output.glob('*.wav')))}"
    )


def run_stage(flag: str) -> None:
    name = flag.removeprefix("--")
    log_path = ROOT / f"pomogator-{name}.log"
    command = [
        sys.executable,
        "-u",
        str(ROOT / "openwakeword" / "openwakeword" / "train.py"),
        "--training_config",
        str(ROOT / "pomogator.yml"),
        flag,
    ]
    environment = os.environ.copy()
    environment["PYTHONUNBUFFERED"] = "1"
    with log_path.open("w", encoding="utf-8") as log, subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
        env=environment,
    ) as process:
        assert process.stdout is not None
        for line in process.stdout:
            print(line, end="", flush=True)
            log.write(line)
            log.flush()
        code = process.wait()
    if code != 0:
        raise RuntimeError(f"{name} failed with exit code {code}; see {log_path}")


def train() -> None:
    shutil.copy2(TRAINING / "pomogator.yml", ROOT / "pomogator.yml")
    import torch_audiomentations.utils.io as audio_io

    run(
        [
            sys.executable,
            str(TRAINING / "patch_colab_runtime.py"),
            "--openwakeword-train",
            str(ROOT / "openwakeword" / "openwakeword" / "train.py"),
            "--torch-audio-io",
            str(Path(audio_io.__file__)),
        ]
    )
    run_stage("--augment_clips")
    run_stage("--train_model")
    model = MODEL_ROOT / "pomogator.onnx"
    checkpoint = MODEL_ROOT / "pomogator-checkpoint.pt"
    report = MODEL_ROOT / "training-report.txt"
    run(
        [
            sys.executable,
            str(TRAINING / "verify_model.py"),
            "--model",
            str(model),
            "--checkpoint",
            str(checkpoint),
            "--report",
            str(report),
        ]
    )
    with report.open("a", encoding="utf-8") as stream:
        project_commit = subprocess.check_output(
            ["git", "-C", str(PROJECT), "rev-parse", "HEAD"], text=True
        ).strip()
        stream.write(f"project_commit={project_commit}\n")
        stream.write(f"openwakeword_commit={OWW_COMMIT}\n")
        stream.write(f"piper_generator_commit={PIPER_COMMIT}\n")
        stream.write(f"legacy_piper_commit={LEGACY_PIPER_COMMIT}\n")
        stream.write(f"mit_rir_commit={MIT_RIR_COMMIT}\n")
        stream.write(f"fma_commit={FMA_COMMIT}\n")
        stream.write(f"features_commit={FEATURES_COMMIT}\n")
        stream.write(f"audioset_commit={AUDIOSET_COMMIT}\n")
        stream.write("\ninstalled_packages:\n")
        stream.write(subprocess.check_output(
            [sys.executable, "-m", "pip", "freeze"], text=True
        ))
    print(report.read_text(encoding="utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("stage", choices=("setup", "generate", "data", "train"))
    args = parser.parse_args()
    globals()[args.stage]()


if __name__ == "__main__":
    main()
