#!/usr/bin/env sh
# Append model files larger than 50 MB to .gitignore (git can't ignore by size).
# Run from the repo root before committing model exports: sh scripts/ignore-large-models.sh
set -eu
cd "$(dirname "$0")/.."

find ml data -type f -size +50M \
  \( -name '*.onnx' -o -name '*.tflite' -o -name '*.pt' -o -name '*.pth' -o -name '*.ckpt' \
     -o -name '*.h5' -o -name '*.bin' -o -name '*.safetensors' -o -name '*.npy' -o -name '*.pb' \) \
  2>/dev/null | while read -r f; do
    if ! grep -qxF "$f" .gitignore; then
      echo "$f" >> .gitignore
      echo "ignored: $f"
    fi
  done
