# MindCraft STT POC — whisper.cpp sidecar (mic → text)

## Verdict: **GO (draft)** — engine drafted, sidecar not yet built/benchmarked
The STT half of the voice loop. Pairs with the TTS POC:
`mic → whisper.cpp → ChatSession.reply(text) → TtsEngine.speakWav → TtsAudioPlayer.play`.

## Why whisper.cpp
- CPU standard for on-device STT; single C binary, no runtime deps beyond libc.
- `whisper-server` (the `examples/server` target) is **OpenAI-compatible**:
  `POST /v1/audio/transcriptions` and `/v1/audio/translations` (multipart → JSON
  `{"text": ...}`). That's exactly the shape `SttEngine` drives — same
  HTTP-to-server, no-JNI locked design as `InferenceEngine`/`TtsEngine`.
- Model: `ggml-small.en.bin` (~480 MB) is the CPU sweet spot (fast, accurate
  English). `ggml-tiny.en` (~75 MB) if you want it lighter/slower-accuracy.

## Drafted (in this commit)
- `core/.../engine/SttConfig.java` — immutable config (binary, modelPath, host,
  port, threads, language, extraArgs).
- `core/.../engine/SttEngine.java` — spawns `whisper-server`, health-gates on
  the root endpoint, drives multipart transcription. JDK-only. Methods:
  `transcribe(byte[], fileName)`, `translate(byte[], fileName)`.
- `mod-forge/.../MindCraftMod.java` — `startStt()` (degraded-safe), `stt()`,
  `transcribe(audio, name)` accessor. Resolves `<game dir>/mindcraft/stt/{bin,models}`.

## Not yet done (next steps)
1. **Build the STT bundle**: clone whisper.cpp, `cmake -DWHISPER_SERVER=ON`,
   build `whisper-server`, download `ggml-small.en.bin` (HuggingFace
   ggerganov/whisper.cpp). Layout: `<game dir>/mindcraft/stt/bin/whisper-server`
   + `models/ggml-small.en.bin`. (Mirror `spikes/tts-poc/build-tts-bundle.sh`.)
2. **Mic capture**: the client-side recorder is the missing input. Options:
   - Forge: `net.minecraft.client.audio` / a `SoundCapture` via
     `javax.sound.sampled.TargetDataLine` (16 kHz mono) on a background thread,
     ring-buffer, VAD endpointing (silence → send).
   - Simpler MVP: record a fixed 3–5 s clip on a hotkey, transcribe, feed
     `ChatSession.reply`.
3. **Benchmark** whisper.cpp on the 7800X3D (RTF for small.en at 4 threads).
4. **Windows**: `whisper-server.exe` (CMake handles win-x64).

## Bundle footprint (expected)
- `whisper-server` binary: ~1–2 MB
- `ggml-small.en.bin`: ~480 MB (or `ggml-tiny.en.bin` ~75 MB)
- Total STT bundle: ~480 MB (small) / ~75 MB (tiny)

## Links
- Sibling: [[2026-08-26-mindcraft-tts-pockettts-voice-clone]]
- Parent: [[2026-08-21-mindcraft-minecraft-cpu-inference-mod]]
