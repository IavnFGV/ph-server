#!/usr/bin/env python3
"""Validate a trained Pomogator ONNX without deserializing the PyTorch checkpoint."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import zipfile
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--checkpoint", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    graph = onnx.load(str(args.model))
    onnx.checker.check_model(graph)
    session = ort.InferenceSession(str(args.model), providers=["CPUExecutionProvider"])
    model_input = session.get_inputs()[0]
    model_output = session.get_outputs()[0]
    shape = tuple(int(value) for value in model_input.shape)
    if shape != (1, 16, 96):
        raise RuntimeError(f"Unexpected model input shape: {shape}")
    output = session.run(
        None, {model_input.name: np.zeros(shape, dtype=np.float32)}
    )[0]
    if output.shape != (1, 1) or not np.isfinite(output).all():
        raise RuntimeError(f"Unexpected model output: shape={output.shape}, value={output}")

    lines = [
        "model=pomogator.onnx",
        f"model_size={args.model.stat().st_size}",
        f"model_sha256={sha256(args.model)}",
        f"onnx_ir_version={graph.ir_version}",
        "onnx_opset=" + ",".join(
            f"{item.domain or 'ai.onnx'}:{item.version}" for item in graph.opset_import
        ),
        f"input={model_input.name} {list(model_input.shape)} {model_input.type}",
        f"output={model_output.name} {list(model_output.shape)} {model_output.type}",
        f"zero_input_score={float(output[0, 0]):.9f}",
    ]
    if args.checkpoint:
        if not zipfile.is_zipfile(args.checkpoint):
            raise RuntimeError("Checkpoint is not a valid PyTorch ZIP container")
        with zipfile.ZipFile(args.checkpoint) as archive:
            if not any(name.endswith("data.pkl") for name in archive.namelist()):
                raise RuntimeError("Checkpoint does not contain data.pkl")
            storage = [archive.read(name) for name in archive.namelist() if "/data/" in name]
        weights = list(graph.graph.initializer)
        if not weights or any(not item.raw_data or item.raw_data not in storage for item in weights):
            raise RuntimeError("ONNX weights do not match checkpoint tensor storage")
        lines.extend(
            [
                f"checkpoint={args.checkpoint.name}",
                f"checkpoint_size={args.checkpoint.stat().st_size}",
                f"checkpoint_sha256={sha256(args.checkpoint)}",
                "checkpoint_deserialized=false",
                f"checkpoint_matching_weight_tensors={len(weights)}",
            ]
        )
    lines.append(f"verified_utc={dt.datetime.now(dt.timezone.utc).isoformat()}")
    report = "\n".join(lines) + "\n"
    print(report, end="")
    if args.report:
        args.report.write_text(report, encoding="utf-8")


if __name__ == "__main__":
    main()
