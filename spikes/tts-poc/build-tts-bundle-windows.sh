#!/usr/bin/env bash
# Build the ClankerJockey TTS Windows bundle: pocket-tts.exe (cross-compiled via
# mingw-w64) + onnxruntime.dll + ONNX models + voices.
#
# Reuses the ONNX models already exported by build-tts-bundle.sh (platform-
# independent files) at /tmp/pocketcpp/models. If absent, re-exports them
# (requires the python venv + torch from the Linux build).
#
# Output layout (unpack into <gamedir>/clankerjockey/tts/):
#   bin/pocket-tts.exe + onnxruntime.dll + mingw runtime DLLs
#   models/*  voices/*
#
# Prereqs: g++-mingw-w64-x86-64, cmake, zip, network (onnxruntime-win-x64).
#
# Usage:
#   ./build-tts-bundle-windows.sh [win_out_dir]
set -euo pipefail

OUT="${1:-/tmp/clankerjockey-tts-bundle-win}"
SRC=/tmp/pocketcpp
MODELS="$SRC/models"
JO_WAV="${JO_WAV:-/home/sean/.hermes/hermes-agent/tools/neutts_samples/jo.wav}"

command -v x86_64-w64-mingw32-g++ >/dev/null || {
  echo "ERROR: mingw-w64 not found. Install: sudo apt-get install g++-mingw-w64-x86-64"; exit 1; }

echo "==> [1/4] ONNX models (reuse Linux export, or re-export)"
if [ ! -f "$MODELS/flow_lm_main_int8.onnx" ]; then
  echo "    models not found at $MODELS — run build-tts-bundle.sh first (Linux)"
  echo "    (ONNX export is platform-independent; the Windows build only needs the .onnx files)"
  exit 1
fi

echo "==> [2/4] Cross-compile pocket-tts.exe (mingw-w64, win-x64 onnxruntime)"
cmake -S "$SRC" -B "$SRC/.build-win" -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_SYSTEM_NAME=Windows \
  -DCMAKE_C_COMPILER=x86_64-w64-mingw32-gcc \
  -DCMAKE_CXX_COMPILER=x86_64-w64-mingw32-g++
cmake --build "$SRC/.build-win" --target pocket-tts -j"$(nproc)"

echo "==> [3/4] Assemble Windows bundle at $OUT"
rm -rf "$OUT"
mkdir -p "$OUT/bin" "$OUT/models" "$OUT/voices"
# CMake emits the exe at the build root; locate it (top-level or .build-win)
EXE=""
for cand in "$SRC/pocket-tts.exe" "$SRC/.build-win/pocket-tts.exe" \
            "$SRC/.build-win/pocket-tts/pocket-tts.exe"; do
  [ -f "$cand" ] && EXE="$cand" && break
done
[ -n "$EXE" ] || { echo "SMOKE FAIL: pocket-tts.exe not found after build"; exit 1; }
cp "$EXE" "$OUT/bin/"
# onnxruntime-win-x64 ships onnxruntime.dll + onnxruntime_providers_shared.dll
ORTWIN="$SRC/.build-win/_deps/onnxruntime-src/lib"
cp "$ORTWIN/onnxruntime.dll" "$ORTWIN/onnxruntime_providers_shared.dll" "$OUT/bin/"
# mingw runtime DLLs (pocket-tts links libstdc++/libgcc; win32 thread model)
for dll in libgcc_s_seh-1.dll libstdc++-6.dll libwinpthread-1.dll; do
  found="$(find /usr/lib/gcc/x86_64-w64-mingw32 /usr/x86_64-w64-mingw32 -name "$dll" 2>/dev/null | head -1 || true)"
  if [ -n "$found" ]; then cp "$found" "$OUT/bin/"; else echo "    WARN: $dll not found"; fi
done
# models (platform-independent ONNX) + reference voice
cp "$MODELS"/*_int8.onnx \
   "$MODELS/mimi_encoder.onnx" \
   "$MODELS/text_conditioner.onnx" \
   "$MODELS/tokenizer.model" \
   "$OUT/models/"
[ -f "$JO_WAV" ] && cp "$JO_WAV" "$OUT/voices/jo.wav"

echo "==> [4/4] Verify"
file "$OUT/bin/pocket-tts.exe" | grep -q "PE32+" || { echo "SMOKE FAIL: not a PE32+ exe"; exit 1; }
echo "    (run on a Windows host: pocket-tts.exe --server --port 8199 --models-dir models --voices-dir voices --tokenizer models/tokenizer.model --precision int8 --threads 8)"
du -sh "$OUT"
echo "Bundle ready at $OUT — unpack into <gamedir>/clankerjockey/tts/ and smoke-test on Windows."
