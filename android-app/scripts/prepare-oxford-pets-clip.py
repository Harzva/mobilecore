#!/usr/bin/env python3
"""Extract exact OpenAI CLIP ViT-B/16 encoders and build the Oxford sidecar."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
from onnx import numpy_helper
from transformers import AutoTokenizer


CLASS_NAMES = [
    "Abyssinian", "american_bulldog", "american_pit_bull_terrier",
    "basset_hound", "beagle", "Bengal", "Birman", "Bombay", "boxer",
    "British_Shorthair", "chihuahua", "Egyptian_Mau",
    "english_cocker_spaniel", "english_setter", "german_shorthaired",
    "great_pyrenees", "havanese", "japanese_chin", "keeshond",
    "leonberger", "Maine_Coon", "miniature_pinscher", "newfoundland",
    "Persian", "pomeranian", "pug", "Ragdoll", "Russian_Blue",
    "saint_bernard", "samoyed", "scottish_terrier", "shiba_inu",
    "Siamese", "Sphynx", "staffordshire_bull_terrier", "wheaten_terrier",
    "yorkshire_terrier",
]
PROMPT_TEMPLATE = "a photo of a {class}"
EXPECTED_COMBINED_SHA256 = "16bfb556094227fbd0b3ef10c92abe6475405d090b60b4e86012aa2b6d08b706"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def logit_scale(model: onnx.ModelProto) -> float:
    for initializer in model.graph.initializer:
        if initializer.name.endswith("logit_scale"):
            raw = float(np.asarray(numpy_helper.to_array(initializer)).reshape(-1)[0])
            return math.exp(raw)
    return 100.0


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    source = args.source_dir / "model.onnx"
    actual_sha = sha256(source)
    if actual_sha != EXPECTED_COMBINED_SHA256:
        raise SystemExit(f"combined CLIP SHA-256 mismatch: {actual_sha}")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    image_model = args.output_dir / "openai-clip-vit-b16-image.onnx"
    text_model = args.output_dir / "openai-clip-vit-b16-text.onnx"
    model = onnx.load(source, load_external_data=True)
    input_names = {item.name for item in model.graph.input}
    output_names = {item.name for item in model.graph.output}
    required_inputs = {"pixel_values", "input_ids", "attention_mask"}
    required_outputs = {"image_embeds", "text_embeds"}
    if not required_inputs.issubset(input_names) or not required_outputs.issubset(output_names):
        raise SystemExit(
            f"unexpected CLIP graph inputs={sorted(input_names)}, outputs={sorted(output_names)}"
        )
    onnx.utils.extract_model(
        str(source), str(image_model), ["pixel_values"], ["image_embeds"]
    )
    onnx.utils.extract_model(
        str(source), str(text_model), ["input_ids", "attention_mask"], ["text_embeds"]
    )

    tokenizer = AutoTokenizer.from_pretrained(args.source_dir, local_files_only=True)
    prompts = [PROMPT_TEMPLATE.format(**{"class": name.replace("_", " ")}) for name in CLASS_NAMES]
    tokenized = tokenizer(
        prompts,
        padding="max_length",
        max_length=77,
        truncation=True,
        return_tensors="np",
    )
    session = ort.InferenceSession(str(text_model), providers=["CPUExecutionProvider"])
    feed = {
        "input_ids": tokenized["input_ids"].astype(np.int64),
        "attention_mask": tokenized["attention_mask"].astype(np.int64),
    }
    embeddings = np.asarray(session.run(["text_embeds"], feed)[0], dtype=np.float32)
    embeddings /= np.linalg.norm(embeddings, axis=1, keepdims=True).clip(min=1e-12)
    sidecar = {
        "schema_version": 1,
        "model_id": "openai/clip-vit-base-patch16",
        "source_combined_sha256": actual_sha,
        "image_encoder_sha256": sha256(image_model),
        "prompt_template": PROMPT_TEMPLATE,
        "logit_scale": logit_scale(model),
        "preprocess": {
            "resize_shortest": 224,
            "center_crop": 224,
            "mean": [0.48145466, 0.4578275, 0.40821073],
            "std": [0.26862954, 0.26130258, 0.27577711],
        },
        "embeddings": [
            {"label": label, "prompt": prompt, "embedding": vector.tolist()}
            for label, prompt, vector in zip(CLASS_NAMES, prompts, embeddings, strict=True)
        ],
    }
    sidecar_file = args.output_dir / "oxford-pets-clip-vit-b16-text-embeddings.json"
    sidecar_file.write_text(json.dumps(sidecar, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({
        "image_model": str(image_model),
        "image_model_sha256": sha256(image_model),
        "text_model": str(text_model),
        "sidecar": str(sidecar_file),
        "logit_scale": sidecar["logit_scale"],
    }, indent=2))


if __name__ == "__main__":
    main()
