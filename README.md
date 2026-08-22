# MindCraft

In-game Minecraft AI assistant powered by **local llama.cpp inference** — no cloud, no API keys. The mod spawns a llama.cpp server as a child process of the game JVM (loopback only, dies with the game). Ships for **Fabric 1.21.1** and **Forge 1.20.1**.

## Repo layout

```
core/            Pure-JDK library: InferenceEngine (llama-server process lifecycle),
                 GenOptions (incl. noThink), MiniJson, ToolGrammar. No Minecraft deps.
mod-fabric/      Fabric 1.21.1 wrapper (fabric-loom)
mod-forge/       Forge 1.20.1 wrapper (ForgeGradle 6, MDK entrypoint)
docs/eval/       30-prompt eval harness (run_eval.py) + results
docs/model-selection.md  Model bake-off report
.deps/llama.cpp/ llama.cpp checkout (gitignored); build at .deps/llama.cpp/build/bin/llama-server
```

## Building

Requirements: **JDK 21** (Gradle/Loom) **and JDK 17** (Forge toolchain; apt `openjdk-17-jdk-headless` on Ubuntu). Internet required on first build (downloads MC + Forge).

```bash
./gradlew :core:test          # core unit tests
./gradlew :mod-forge:build    # → mod-forge/build/libs/mindcraft-forge-<v>.jar
./gradlew :mod-fabric:build   # → mod-fabric/build/libs/*.jar
```

ForgeGradle quirk: Forge's `HackyJavaCompile` needs a real JDK 17 on disk (toolchain spec is forced to 17). Fabric Loom requires Gradle to run on JDK 21. Both coexist: Gradle daemon on 21, `options.release = 17` for mod-forge/core.

## Runtime assets (NOT in the repo)

The mod expects at `<gamedir>/mindcraft/`:

- `llama-server` — llama.cpp built CPU-only (`cmake -B build -DGGML_CUDA=OFF && cmake --build build`), commit `d775b896` known-good.
- `model.gguf` — default model **LittleLamb 0.3B Tool-Calling Q8_0** (303 MB):
  `https://huggingface.co/mradermacher/LittleLamb-ToolCalling-GGUF/resolve/main/LittleLamb-ToolCalling.Q8_0.gguf`

## Critical model quirk

LittleLamb is a thinking-template model: with the default chat template it returns **empty strings**. The engine always sends `chat_template_kwargs: {"enable_thinking": false}` (see `GenOptions.noThink()`) and llama-server must run with `--jinja`. Do not remove either.

## Engine flags used

`-m <model> -c 2048 -t 4 -np 1 --port <free> --no-webui --jinja` (debug ctx 512). Tool calls use a per-tool GBNF grammar — **camelCase rule names only**; this llama.cpp build rejects underscores in rule names.

## Eval harness

```bash
# terminal 1
/home/sean/mindcraft/.deps/llama.cpp/build/bin/llama-server -m <model.gguf> -c 2048 -t 4 -np 1 --port 18083 --no-webui --jinja
# terminal 2
cd docs/eval && python3 run_eval.py --model <model.gguf> --name <run-name> --port 18083 --no-think
```

Results land in `docs/eval/results/`.

## Current model verdict (2026-08-22)

LittleLamb 0.3B TC Q8_0 is the production default: grammar tool calls 100/100, ambiguous-refusal 4/5, ~114 tok/s, ~838 MiB RSS. Fallback: Qwen2.5-0.5B-Instruct Q4_K_M. See `docs/model-selection.md`.

## Status / next steps

- Forge + Fabric modules compile; engine startup smoke-tested outside the game.
- Not yet done: in-game launch test, runtime asset bundler script, Verity JE endpoint-compat test.
