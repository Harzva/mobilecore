#!/usr/bin/env python3
"""Create tiny CLIP-compatible CIFAR10 smoke-test assets.

The generated ONNX is intentionally tiny: it accepts a CLIP-style image tensor
`[1, 3, 224, 224]` and returns the mean RGB embedding `[1, 3]`. It validates the
MobileCore ONNX Runtime + CIFAR10 sidecar path without pretending to be a real
CLIP model.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import onnx
from onnx import TensorProto, helper


def build_model(out_dir: Path) -> Path:
    input_info = helper.make_tensor_value_info(
        "pixel_values",
        TensorProto.FLOAT,
        [1, 3, 224, 224],
    )
    output_info = helper.make_tensor_value_info(
        "image_embeds",
        TensorProto.FLOAT,
        [1, 3],
    )
    reduce_mean = helper.make_node(
        "ReduceMean",
        inputs=["pixel_values"],
        outputs=["image_embeds"],
        axes=[2, 3],
        keepdims=0,
    )
    graph = helper.make_graph(
        [reduce_mean],
        "mobilecore_clip_cifar10_smoke",
        [input_info],
        [output_info],
    )
    model = helper.make_model(
        graph,
        producer_name="mobilecore",
        opset_imports=[helper.make_operatorsetid("", 11)],
    )
    model.ir_version = 7
    onnx.checker.check_model(model)
    path = out_dir / "clip-cifar10-smoke.onnx"
    onnx.save(model, path)
    return path


def build_sidecar(out_dir: Path) -> Path:
    # Vectors are tuned for the fixture model's 3-value RGB embedding after
    # MobileCore's CLIP image normalization. They are not real CLIP text vectors.
    labels = [
        ("airplane", [0.9, -0.2, -0.3]),
        ("automobile", [0.7, -0.1, 0.2]),
        ("bird", [0.2, 0.7, -0.1]),
        ("cat", [0.1, 0.8, 0.2]),
        ("deer", [0.2, 0.6, 0.4]),
        ("dog", [0.3, 0.7, 0.2]),
        ("frog", [-0.2, 0.8, 0.4]),
        ("horse", [0.5, 0.4, -0.1]),
        ("ship", [-1.0, -0.2, 1.6]),
        ("truck", [0.6, -0.2, 0.5]),
    ]
    path = out_dir / "cifar10-text-embeddings.json"
    path.write_text(
        json.dumps(
            [{"label": label, "embedding": values} for label, values in labels],
            indent=2,
            ensure_ascii=True,
        )
        + "\n",
        encoding="utf-8",
    )
    return path


def main() -> int:
    out_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "vision-fixtures")
    out_dir.mkdir(parents=True, exist_ok=True)
    model_path = build_model(out_dir)
    sidecar_path = build_sidecar(out_dir)
    manifest = {
        "object": "mobilecore.vision_fixture",
        "model": model_path.name,
        "sidecar": sidecar_path.name,
        "task": "clip-cifar10-smoke",
        "note": "Pipeline fixture only; not a real CLIP quality benchmark.",
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
