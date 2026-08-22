#!/usr/bin/env bash
# MindCraft Task 1 spike repro: build llama-server (if missing), download model (if missing), run Java harness.
set -euo pipefail
cd "$(dirname "$0")"

LLAMA_DIR="$(pwd)/../../.deps/llama.cpp"
SERVER="$LLAMA_DIR/build/bin/llama-server"
MODEL="models/qwen2.5-0.5b-instruct-q4_k_m.gguf"
MODEL_URL="https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"

if [ ! -x "$SERVER" ]; then
  echo "[run.sh] building llama-server (CPU-only)..."
  cmake -B "$LLAMA_DIR/build" -S "$LLAMA_DIR" -DCMAKE_BUILD_TYPE=Release -DGGML_CUDA=OFF -DGGML_NATIVE=ON -DLLAMA_CURL=OFF -DBUILD_SHARED_LIBS=OFF
  cmake --build "$LLAMA_DIR/build" --config Release -j"$(nproc)" --target llama-server
fi

if [ ! -f "$MODEL" ]; then
  echo "[run.sh] downloading model..."
  mkdir -p models
  curl -L --fail --progress-bar -o "$MODEL" "$MODEL_URL"
fi

echo "[run.sh] running Java harness (cold start; drop caches if you want a truly cold run)"
exec java src/SpikeHarness.java "$SERVER" "$MODEL" "${1:-18080}"
