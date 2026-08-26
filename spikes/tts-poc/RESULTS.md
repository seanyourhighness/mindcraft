# MindCraft TTS POC — PocketTTS.cpp sidecar

## Verdict: **GO** — verified end-to-end on this machine

PocketTTS.cpp (single-file C++ ONNX runtime for Kyutai's Pocket TTS) is the
supertonic replacement. It is strictly better for MindCraft's requirements:
zero-shot voice cloning built in, CPU-only, and it drops into the exact
locked design the llama-server spike settled on (HTTP-to-server, no JNI).

## Why not Supertonic
- Repo carries a **CAUTION: being archived (2026-07-23)**, no further development.
- **Voice Builder (the only official voice-cloning path) shuts down 2026-08-31.**
- Custom-voice path requires a hosted JSON export, not a local reference-audio clone.

## Measured (Ryzen 7 7800X3D, 16 threads, INT8, 8 threads)
| Path | RTFx | Time-to-first-audio |
|---|---|---|
| Cold (first voice encode) | 2.25x | 2070 ms (one-time, disk-cached) |
| Warm (voice cached) | **12.8x** | **27 ms** |
| HTTP server, warm | **14.3x** | ~28 ms |

- Voice encoding is the only slow step and it's cached to `voices/.cache/`
  (`.emb`), so every subsequent line for the same voice is ~30 ms to first audio.
- Audio verified real speech (RMS 0.043, peak 0.42) — not silence.

## Integration (mirrors `InferenceEngine`)
- `core/.../engine/TtsConfig.java` — immutable config (binary, modelsDir,
  voicesDir, tokenizerPath, host, port, threads, precision, lsdSteps).
- `core/.../engine/TtsEngine.java` — spawns `pocket-tts --server` on a free
  loopback port, gates on `GET /health`, drives `POST /v1/audio/speech`
  (WAV) and `POST /tts` (streaming f32le PCM). JDK-only, no JNI.
  - `speakWav(text, voice)` → RIFF WAV bytes (IEEE-float 24 kHz mono) for
    `SoundEvent` registration.
  - `speakPcm(text, voice)` → raw f32le PCM (lowest latency, chunked playback).
  - `f32leToPcm16(byte[])` → 16-bit LE PCM for `SoundEvent`/`BufferedAudio`.
- `TtsEngine` sets `LD_LIBRARY_PATH` to the binary's dir so the bundled
  `libonnxruntime.so` resolves regardless of the game CWD (no patchelf needed).

## Shippable bundle (INT8) — ~197 MB
```
bin/pocket-tts            (1.5 MB, links libonnxruntime.so.1)
bin/libonnxruntime.so.1.23.2 (+ .so.1, .so symlinks)   22 MB
models/
  flow_lm_main_int8.onnx   73 MB
  flow_lm_flow_int8.onnx   9.5 MB
  mimi_decoder_int8.onnx  22 MB
  mimi_encoder.onnx       70 MB   (fp32 — no INT8 variant)
  text_conditioner.onnx   16 MB   (fp32 — required, no INT8 variant)
  tokenizer.model          60 KB
voices/<name>.wav         reference samples for cloning
```
- INT8 models are 4-5x smaller than fp32 with negligible quality loss
  (validated: INT8 checks pass, rel err ~2e-2).
- The two fp32 files (mimi_encoder, text_conditioner) are required — the
  C++ runtime loads them unquantized.

## Voice cloning
- Drop any short WAV/MP3/FLAC into `voices/` (auto-resampled to 24 kHz mono).
- `TtsEngine.speakWav("...", "jo.wav")` clones that voice. Reference sample
  used here: `jo.wav` (the existing NeuTTS voice sample).

## Reproduce
```
./build-tts-bundle.sh   # builds PocketTTS.cpp, exports ONNX, assembles bundle
```

## Open / next
- **STT (mic)** drafted — `SttEngine`/`SttConfig` (whisper.cpp sidecar) +
  `mod-forge` wiring done; sidecar build + mic capture still to do. See
  `spikes/stt-poc/RESULTS.md`.
- **Audio playback**: `TtsAudioPlayer` (mod-forge) plays the WAV via a custom
  in-memory `SoundInstance` (no resource registration). `MindCraftMod.speakAndPlay(text)`
  is the full TTS loop. Mic capture (the STT input) is the remaining gap.
- Windows: build `pocket-tts.exe` + `onnxruntime.dll` (CMake already handles
  win-x64); the `LD_LIBRARY_PATH` line is a no-op there (DLLs load from the
  binary dir).
- Wire `TtsEngine`/`SttEngine` into `MindCraftClient` (Fabric) alongside the
  Forge entrypoint once the Fabric chat path exists.
