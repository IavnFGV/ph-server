#!/usr/bin/env python3
"""Apply pinned, fail-fast compatibility patches needed by the Colab runtime."""

from __future__ import annotations

import argparse
import py_compile
from pathlib import Path


def replace_once(source: str, old: str, new: str, label: str) -> str:
    if new in source:
        print(f"Already patched: {label}")
        return source
    count = source.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one {label} pattern, found {count}")
    print(f"Patched: {label}")
    return source.replace(old, new)


def patch_openwakeword(path: Path) -> None:
    source = path.read_text(encoding="utf-8")
    source = replace_once(
        source,
        'os.path.join(output_dir, model_name + ".onnx"), opset_version=13)',
        'os.path.join(output_dir, model_name + ".onnx"), opset_version=13, dynamo=False)',
        "legacy ONNX exporter",
    )
    source = replace_once(
        source,
        "        # Export the trained model to onnx\n"
        '        oww.export_model(model=best_model, model_name=config["model_name"], output_dir=config["output_dir"])',
        "        # Save a recoverable checkpoint before ONNX export\n"
        "        checkpoint = os.path.join(\n"
        "            config[\"output_dir\"], config[\"model_name\"] + \"-checkpoint.pt\"\n"
        "        )\n"
        "        torch.save({\n"
        "            \"state_dict\": {\n"
        "                key: value.detach().cpu()\n"
        "                for key, value in best_model.state_dict().items()\n"
        "            },\n"
        "            \"input_shape\": tuple(int(value) for value in input_shape),\n"
        "            \"model_type\": config[\"model_type\"],\n"
        "            \"layer_dim\": config[\"layer_size\"],\n"
        "        }, checkpoint)\n"
        "        print(f\"Checkpoint saved: {checkpoint}\", flush=True)\n\n"
        "        # Export the trained model to onnx\n"
        '        oww.export_model(model=best_model, model_name=config["model_name"], output_dir=config["output_dir"])',
        "checkpoint before export",
    )
    source = replace_once(
        source,
        "        if args.convert_to_tflite:\n",
        "        if args.convert_to_tflite is True:\n",
        "boolean convert_to_tflite flag",
    )
    if "    if args.generate_clips is True:\n" not in source:
        raise RuntimeError("Pinned openWakeWord no longer has the expected boolean generate_clips guard")
    path.write_text(source, encoding="utf-8")
    py_compile.compile(str(path), doraise=True)


def patch_torch_audiomentations(path: Path) -> None:
    source = path.read_text(encoding="utf-8")
    marker = "        import soundfile as sf\n"
    if marker in source:
        print("Already patched: TorchAudio metadata compatibility")
        return
    start_marker = "    @staticmethod\n    def get_audio_metadata("
    end_marker = "\n    def get_num_samples("
    start = source.find(start_marker)
    end = source.find(end_marker, start)
    if start < 0 or end < 0:
        raise RuntimeError("torch-audiomentations Audio.get_audio_metadata layout changed")
    replacement = (
        "    @staticmethod\n"
        "    def get_audio_metadata(file_path):\n"
        "        \"\"\"Return (num_samples, sample_rate) without removed torchaudio.info.\"\"\"\n"
        "        import soundfile as sf\n"
        "        info = sf.info(str(file_path))\n"
        "        return info.frames, info.samplerate\n"
    )
    path.write_text(source[:start] + replacement + source[end:], encoding="utf-8")
    py_compile.compile(str(path), doraise=True)
    print("Patched: TorchAudio metadata compatibility")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--openwakeword-train", type=Path, required=True)
    parser.add_argument("--torch-audio-io", type=Path, required=True)
    args = parser.parse_args()
    patch_openwakeword(args.openwakeword_train)
    patch_torch_audiomentations(args.torch_audio_io)


if __name__ == "__main__":
    main()
