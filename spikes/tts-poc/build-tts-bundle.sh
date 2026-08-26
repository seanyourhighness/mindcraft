#!/usr/bin/env bash
# Build the MindCraft TTS runtime bundle (PocketTTS.cpp + ONNX models + voices).
#
# Output: <out>/bin/{pocket-tts,libonnxruntime.so*}, <out>/models/*, <out>/voices/*
#
# Prereqs: cmake >= 3.28, a C++17 compiler, python3 + venv, network access
# (downloads ONNX Runtime, SentencePiece, and the ungated Pocket TTS weights).
#
# Usage:
#   ./build-tts-bundle.sh [out_dir]
#   OUT=/some/dir ./build-tts-bundle.sh
set -euo pipefail

OUT="${1:-${OUT:-/tmp/mindcraft-tts-bundle}}"
SRC=/tmp/pocketcpp
WORK=/tmp/pocketcpp-work
VENV="$SRC/.venv"
JO_WAV="${JO_WAV:-/home/sean/.hermes/hermes-agent/tools/neutts_samples/jo.wav}"

echo "==> [1/5] Clone PocketTTS.cpp"
[ -d "$SRC/.git" ] || git clone --depth 1 https://github.com/VolgaGerm/PocketTTS.cpp "$SRC"

echo "==> [2/5] Python venv + ONNX export (pocket-tts 2.0.0 + b6369a24 config)"
python3 -m venv "$VENV"
"$VENV/bin/pip" install -q --upgrade pip
"$VENV/bin/pip" install -q torch --index-url https://download.pytorch.org/whl/cpu
"$VENV/bin/pip" install -q "pocket-tts==2.0.0" onnx onnxruntime
# b6369a24.yaml ships in pocket-tts 1.1.1; the 2.0.0 TTSModel needs it to load
# the voice-cloning weights (2.1.0/3.x renamed the config and broke the export).
"$VENV/bin/pip" download -q "pocket-tts==1.1.1" --no-deps -d "$WORK"
cd "$WORK"
unzip -o -q *.whl "pocket_tts/config/b6369a24.yaml"
cp pocket_tts/config/b6369a24.yaml /tmp/b6369a24.yaml
# point the tokenizer at the local file the export downloads
sed -i 's|hf://kyutai/pocket-tts-without-voice-cloning/tokenizer.model@.*|./models/tokenizer.model|' /tmp/b6369a24.yaml
cd "$SRC"
"$VENV/bin/python" export_onnx.py --output-dir ./models --config /tmp/b6369a24.yaml

echo "==> [3/5] Build C++ runtime (CLI + HTTP server)"
cmake -B "$SRC/.build" -DCMAKE_BUILD_TYPE=Release
cmake --build "$SRC/.build" -j"$(nproc)"

echo "==> [4/5] Assemble bundle"
rm -rf "$OUT"
mkdir -p "$OUT/bin" "$OUT/models" "$OUT/voices"
cp "$SRC/pocket-tts" "$OUT/bin/"
# onnxruntime: real file + the .so.1 / .so symlinks the binary links against
ORTLIB="$SRC/.build/_deps/onnxruntime-src/lib"
cp -L "$ORTLIB/libonnxruntime.so.1.23.2" "$OUT/bin/"
ln -sf libonnxruntime.so.1.23.2 "$OUT/bin/libonnxruntime.so.1"
ln -sf libonnxruntime.so.1.23.2 "$OUT/bin/libonnxruntime.so"
# INT8 models + the two required fp32 files + tokenizer
cp "$SRC"/models/*_int8.onnx \
   "$SRC"/models/mimi_encoder.onnx \
   "$SRC"/models/text_conditioner.onnx \
   "$SRC"/models/tokenizer.model \
   "$OUT/models/"
# reference voice sample for cloning
[ -f "$JO_WAV" ] && cp "$JO_WAV" "$OUT/voices/jo.wav"

echo "==> [5/5] Smoke test"
"$OUT/bin/pocket-tts" --server --port 8199 \
  --models-dir "$OUT/models" --voices-dir "$OUT/voices" \
  --tokenizer "$OUT/models/tokenizer.model" --precision int8 --threads 4 \
  > /tmp/tts-smoke.log 2>&1 &
SRV=$!
trap 'kill $SRV 2>/dev/null || true' EXIT
for i in $(seq 1 30); do
  curl -sf http://127.0.0.1:8199/health && break
  sleep 1
done
curl -sf -X POST http://127.0.0.1:8199/v1/audio/speech \
  -H 'Content-Type: application/json' \
  -d '{"input":"Bundle smoke test, one two three.","voice":"jo.wav","response_format":"wav"}' \
  -o /tmp/tts-smoke.wav
kill $SRV 2>/dev/null || true
trap - EXIT
[ "$(head -c 4 /tmp/tts-smoke.wav)" = "RIFF" ] || { echo "SMOKE FAIL"; exit 1; }

echo "==> Bundle ready at $OUT"
du -sh "$OUT/bin" "$OUT/models" "$OUT/voices"
echo "    (set LD_LIBRARY_PATH=\$OUT/bin or use TtsEngine, which sets it automatically)"
