# Clanker Jockey

In-game Minecraft AI assistant powered by **local llama.cpp inference** — no cloud, no API keys. The mod spawns a llama.cpp server as a child process of the game JVM (loopback only, dies with the game). Ships for **Fabric 1.21.1** and **Forge 1.20.1**.

## Repo layout

```
core/            Pure-JDK library: InferenceEngine (llama-server process lifecycle),
                 GenOptions (incl. noThink + grammar), MiniJson, ToolGrammar
                 generator, agent loop + tool framework, memory ledger,
                 loader-neutral AgentWorld abstraction. No Minecraft deps.
mod-fabric/      Fabric 1.21.1 wrapper: companion entity, FabricWorldAdapter,
                 agent chat wiring (fabric-loom)
mod-forge/       Forge 1.20.1 wrapper: companion entity, ForgeWorldAdapter,
                 agent chat wiring (ForgeGradle 6, MDK entrypoint)
docs/eval/       30-prompt eval harness (run_eval.py) + results
docs/model-selection.md  Model bake-off report
docs/parity-map.md       Upstream mindcraft-bots/mindcraft audit + classifications
docs/agent-loop.md       Agent tool-loop architecture and milestone-1 tools
.deps/llama.cpp/ llama.cpp checkout (gitignored); build at .deps/llama.cpp/build/bin/llama-server
```

## Building

Requirements: **JDK 21** (Gradle/Loom) **and JDK 17** (Forge toolchain; apt `openjdk-17-jdk-headless` on Ubuntu). Internet required on first build (downloads MC + Forge).

```bash
./gradlew :core:test          # core unit tests
./gradlew :mod-forge:build    # → mod-forge/build/libs/clankerjockey-forge-<v>.jar
./gradlew :mod-fabric:build   # → mod-fabric/build/libs/*.jar
```

ForgeGradle quirk: Forge's `HackyJavaCompile` forces a JDK 17 toolchain; the
foojay resolver in `settings.gradle` auto-provisions it. Fabric Loom requires
Gradle to run on JDK 21. Both coexist: Gradle daemon on 21,
`options.release = 17` for mod-forge/core.

## Runtime assets (NOT in the repo)

The mod expects at `<gamedir>/clankerjockey/`:

- `llama-server` — llama.cpp built CPU-only (`cmake -B build -DGGML_CUDA=OFF && cmake --build build`), commit `d775b896` known-good.
- `model.gguf` — default model **LittleLamb 0.3B Tool-Calling Q8_0** (303 MB):
  `https://huggingface.co/mradermacher/LittleLamb-ToolCalling-GGUF/resolve/main/LittleLamb-ToolCalling.Q8_0.gguf`

Assemble it with the runtime bundler (finds your llama-server + model, copies
them into the right layout, and can verify the bundled server starts):

```bash
python3 tools/bundle_runtime.py --target /path/to/game-dir --verify
```

## Critical model quirk

LittleLamb is a thinking-template model: with the default chat template it returns **empty strings**. The engine always sends `chat_template_kwargs: {"enable_thinking": false}` (see `GenOptions.noThink()`) and llama-server must run with `--jinja`. Do not remove either.

## Engine flags used

`-m <model> -c 2048 -t 4 -np 1 --port <free> --no-webui --jinja` (debug ctx 512). Tool calls use a GBNF grammar — **camelCase rule names only**; this llama.cpp build rejects underscores in rule names.

The runtime tool-call grammar is now **generated from the registered tool
schemas** by `ToolGrammar` (see `docs/agent-loop.md`); the hand-written
`docs/eval/toolcall.gbnf` remains for model-bakeoff comparisons. The
generator was verified against the real llama.cpp fork: tool-name literals
must include escaped quotes (`"\"follow_player\""`) or the model emits
invalid unquoted JSON.

## Eval harness

```bash
# terminal 1
/home/sean/clankerjockey/.deps/llama.cpp/build/bin/llama-server -m <model.gguf> -c 2048 -t 4 -np 1 --port 18083 --no-webui --jinja
# terminal 2
cd docs/eval && python3 run_eval.py --model <model.gguf> --name <run-name> --port 18083 --no-think
```

Results land in `docs/eval/results/`.

## Current model verdict (2026-08-22)

LittleLamb 0.3B TC Q8_0 is the production default: grammar tool calls 100/100, ambiguous-refusal 4/5, ~114 tok/s, ~838 MiB RSS. Fallback: Qwen2.5-0.5B-Instruct Q4_K_M. See `docs/model-selection.md`.

## Status / next steps

- Core agent tool loop: Tool/ToolCall/ToolResult/ToolRegistry/Validator/Executor,
  schema-generated GBNF, multi-call loop with safety limits, cancellation.
- Milestone-1 tools: `get_self_state`, `get_nearby_entities`, `get_inventory`,
  `get_player_state`, `get_nearby_players`, `get_player_distance`,
  `get_hostile_entities`, `find_nearest_entity`, `find_nearby_block`,
  `has_item`, `count_item`, `go_to_player`, `go_to_coordinates`,
  `go_to_remembered_place`, `follow_player`, `stop_following`,
  `remember_here`, `forget_place`, `recall_place`, `list_known_places`.
- Persistent spatial memory (`PlaceMemory`): the companion can remember named
  places and navigate back to them ("stop following me and go back home").
- Long-running task lifecycle (`core/tasks`): `start_task`,
  `get_task_status`, `cancel_task`, `list_tasks` with the full
  PENDING→RUNNING→terminal state machine.
- Item actions on the virtual inventory: `give_item` (spawns the items
  in-world next to the player), `drop_item`, `equip_item`, `consume_item`,
  plus `move_away` / `look_at` movement utilities.
- Combat/protection: `attack_entity` (owner-only, denied below the model for
  non-owners), `defend_player`, `flee_from_entity`.
- Containers: persistent virtual chests (`open_container`, `view_container`,
  `put_in_container`, `take_from_container`) with real inventory transfers.
- Executing tasks: `start_collect_task(block, count)` drives a real worker
  (search → walk → break → inventory) scheduled on the server thread, with
  progress visible via `get_task_status`.
- Event/salience/reflex layer (`core/events`): P0-P6 priority ladder,
  salience gating with repetition/cooldown suppression, bounded event log
  rendered into the prompt, and LLM-free reflexes that interrupt tasks on
  danger (creeper near → stop gathering, evade, then tell the model).
- Ambient awareness: a proactive tick scans for hostiles near the player and
  the companion warns in character on its own (gated + 60 s cooldown), so a
  creeper behind you gets a reaction without event spam.
- Raw event bridges on both loaders: player damage/death/join/leave plus
  sunset/night/weather/dimension transitions feed the semantic event log and
  can trigger ambient reactions ("It's getting dark, want to head home?").
- Companion body (villager-shaped, follow/move goals) on Forge 1.20.1 + Fabric 1.21.1;
  chat → LLM → tool → world action → in-character reply wired on both loaders.
- Core unit tests green; both mod jars build. Integration tests pass against
  the real llama.cpp fork + LittleLamb model: the loop produces
  `follow_player` → success → `"Right behind you!"` end-to-end.
- Not yet done in-game: launch the mod inside an actual Minecraft client
  (needs the runtime bundle in the game dir), multiplayer companion body,
  spatial memory, task manager, event salience / reflex layer, ambient
  awareness (Phases 4-7).
