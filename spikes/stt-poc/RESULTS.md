# MindCraft STT POC — whisper.cpp sidecar (mic → text)

## Verdict: **GO — verified end-to-end** (Linux + Windows cross-compile)
The STT half of the voice loop. Pairs with the TTS POC:
`mic → whisper.cpp → LLM reply → TtsEngine.speakWav → TtsAudioPlayer.play`.

## What was built
- **whisper-server** (whisper.cpp `examples/server`, commit `9781133`) — CMake
  build, CPU-only (`--no-gpu`), OpenAI-style multipart API:
  - `GET /health` — readiness (model loads at startup, so health = ready)
  - `POST /inference` — multipart `file` + `language` + `response_format=json`
    + optional `translate`/`temperature` → `{"text": "..."}`
- **`SttEngine`/`SttConfig`** (core, JDK-only) — spawns `whisper-server`,
  health-gates on `/health`, drives `/inference`. Sets `LD_LIBRARY_PATH` to the
  bundle `bin/` dir (libwhisper/libggml ship next to the binary). Same locked
  design as `InferenceEngine`/`TtsEngine` (HTTP-to-server, no JNI).
- **`VoiceCapture`** (mod-forge) — client-side mic capture: 16 kHz mono
  16-bit via `javax.sound.sampled`, energy-VAD endpointing (250 ms open,
  700 ms close, 15 s max, 300 ms min), emits self-contained WAV clips.
  Missing mic degrades to no-op.
- **`MindCraftMod.startVoiceLoop()`** — the immersive entry point:
  mic clip → `SttEngine.transcribe` → `generate()` (LLM) →
  `speakAndPlay()` (TTS + in-game audio). No typing, no UI.

## Measured (Ryzen 7 7800X3D, ggml-small.en, 8 threads)
- Model load at startup: **565 ms**
- Transcribe a 2.72 s clip (warm): **~1.7 s** (≈1.6× RTF)
- Round-trip TTS→STT: "The quick brown fox jumps over the lazy dog." →
  "The quick brown fox **sucks** over the lazy dog." (whisper's known
  "jumps/sucks" confusion; everything else exact)
- Latency budget for the voice loop: ~1.7 s STT + ~0.5–2 s LLM + ~0.3 s TTS
  ≈ 2.5–4 s end-to-end per exchange. Acceptable for conversation; the
  `ggml-tiny.en` model (~75 MB) trades accuracy for ~2× speed if needed.

## Bundles (both built + assembled)
- **Linux**: `/tmp/mindcraft-stt-bundle/` — `bin/whisper-server` + 4 .so
  (libwhisper, libggml{,-base,-cpu}) + `models/ggml-small.en.bin` (480 MB).
  ~469 MB total.
- **Windows**: `/tmp/mindcraft-stt-bundle-win/` — `bin/whisper-server.exe`
  (PE32+, whisper/ggml statically linked) + 3 mingw runtime DLLs
  (libstdc++-6, libgomp-1, libgcc_s_seh-1) + model. ~32 MB + model.
  **Cross-compiled from WSL via mingw-w64** — needs one smoke test on a real
  Windows box (run `whisper-server.exe --port 8080 --model models/... --no-gpu`
  and hit `/health`).

### Windows cross-compile gotcha (verified)
Ubuntu's mingw-w64 headers lack `THREAD_POWER_THROTTLING_STATE` (Win8.1+
API) used by ggml-cpu. Fix: pass `-D_WIN32_WINNT=0x0601` to both C and CXX
flags so that code block is excluded. Without it the build fails at
`ggml-cpu.c:2553`.

## Reproduce
```
./build-stt-bundle.sh /tmp/mindcraft-stt-bundle /tmp/mindcraft-stt-bundle-win
```
(2nd arg = Windows output; requires `g++-mingw-w64-x86-64` installed.)

## Open / next
- **Windows smoke test** — run the .exe once on the Windows host (it's
  cross-compiled, not executed, here).
- **Mic in-game UX** — `startVoiceLoop()` is exposed; needs a trigger
  (hotkey or NPC-interaction event) wired to a client screen. VAD thresholds
  may need tuning per-mic (default 0.02 RMS).
- **Fabric**: wire `SttEngine`/`VoiceCapture` into `MindCraftClient` once the
  Fabric chat path exists.

## Links
- Sibling: [[2026-08-26-mindcraft-tts-pockettts-voice-clone]]
- Parent: [[2026-08-21-mindcraft-minecraft-cpu-inference-mod]]
