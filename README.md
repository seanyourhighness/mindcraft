# MindCraft

Minecraft Fabric 1.21.x mod embedding a CPU/ram-only LLM inference server
(llama.cpp) for NPC dialogue and grammar-constrained tool calls.

Status: Phase 0 feasibility spike complete -> **GO** (see
`spikes/jni-inference/RESULTS.md`).

- `spikes/jni-inference/` - Task 1 spike: llama.cpp CPU build + Java HTTP
  harness, measured 67-90 tok/s at 4 threads and ~590 MiB peak RSS on a 0.5B
  Q4_K_M model.
- `.deps/llama.cpp` - local llama.cpp checkout (gitignored).

## Module layout

Gradle multi-project (see `settings.gradle`):

- `core/` - Plain Java 21 library, **no Minecraft dependencies**. LLM runtime,
  tokenizer, prompt/generation logic. Package root `net.mindcraft.core`,
  JUnit 5 tests.
- `mod-fabric/` - Fabric mod targeting Minecraft 1.21.1 (Loom 1.7, Yarn
  mappings, Fabric Loader 0.16.x, Fabric API). Wires the core library into the
  game: entrypoints (`net.mindcraft.mod.MindCraftMod` / `MindCraftClient`),
  config, chat events. No mixins yet.

Build commands:

- `./gradlew projects` - list all modules
- `./gradlew :core:test` - core unit tests
- `./gradlew :mod-fabric:build` - compile, test and remap the mod jar
  (output: `mod-fabric/build/libs/`)

