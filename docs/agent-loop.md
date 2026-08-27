# Agent Tool Loop

The agentic tool loop is the companion's primary mechanism for acting in the
world. One player message can trigger many sequential, validated tool calls
without any further player input:

```text
Minecraft chat event
        ↓
AgentContext (player, world adapter, memory, history, cancellation)
        ↓
LLM call (llama.cpp, GBNF-constrained to exactly one JSON tool call)
        ↓
{"tool": "follow_player", "arguments": {"player": "Sean", "distance": 3}}
        ↓
validate (tool exists, arg names/types/ranges, permissions)
        ↓
execute on the game-server thread (companion body acts)
        ↓
compact ToolResult feeds back into the context
        ↓
LLM call again … until the model emits {"tool": "respond", ...}
        ↓
Vera's final line is broadcast in chat
```

## Layout

```text
core/
  agent/    AgentLoop, AgentContext, AgentResponse, AgentLoopConfig, RespondTool
  tools/    Tool, ToolDefinition, ToolCall, ToolResult, ToolRegistry,
            ToolValidator, ToolExecutor, ParamSpec, SecurityClass
  tools/impl/  the milestone-1 tools (queries + movement)
  world/    AgentWorld abstraction + records + CompanionInventory
  engine/   ToolGrammar (GBNF generation), GenOptions.withGrammar
  memory/   MemoryLedger, PromptAssembler, ChatSession (loader-neutral)

mod-forge/  companion entity + goals, ForgeWorldAdapter, ServerGate, MindCraftAgent
mod-fabric/ companion entity + goals, FabricWorldAdapter, ServerGate, MindCraftAgent
```

## Structured output

The model never emits free-form command text. Every response is constrained by
a schema-generated GBNF grammar to exactly one object:

```json
{"tool": "get_nearby_entities", "arguments": {"radius": 16}}
```

The synthetic `respond` tool carries the final in-character message:

```json
{"tool": "respond", "arguments": {"text": "Right behind you!"}}
```

`ToolGrammar.generate()` builds the grammar from the registered
`ToolDefinition`s, so adding a tool automatically extends the constraint.
Rule names are camelCase (this llama.cpp build rejects underscores in rule
names); quoted literals keep underscores.

Verified against the real llama.cpp fork (commit d775b89): tool-name literals
must carry the escaped surrounding quotes (`"\"follow_player\""`), otherwise
the grammar matches the bare name and the model emits invalid JSON like
`{"tool": follow_player}`. `docs/eval/grammar_bisect.py` reproduces the
diagnosis; `docs/eval/probe_grammar.py` sends raw prompts + grammars to a
running server for quick checks.

## Safety limits (`AgentLoopConfig`)

| Limit | Default |
|---|---|
| Max tool calls per turn | 8 |
| Max inference iterations | 12 |
| Max repeated identical calls | 2 |
| Max consecutive tool failures | 3 |
| Per-call timeout | 30 s |
| Whole-turn timeout | 180 s |

Cancellation is first-class: `AgentContext.requestCancel()` from any thread
stops the loop at the next iteration boundary.

## Milestone-1 tools

Read-only:

- `get_self_state` — position, dimension, biome, time, weather, health, follow status
- `get_nearby_entities` — compact type/distance/direction list (max 12 in prompt)
- `get_inventory` — virtual companion inventory
- `get_player_state` — a named player's position, distance, health, online status
- `get_nearby_players` — names of players within a radius
- `get_player_distance` — distance to a named player
- `get_hostile_entities` — nearby monsters (`{"hostiles":[{"type":"creeper","distance":4.2,...}]}`)
- `find_nearest_entity` — nearest entity of a type within a radius
- `find_nearby_block` — nearest block of a type (e.g. `iron_ore`) within a radius
- `has_item` / `count_item` — virtual inventory checks
- `recall_place` / `list_known_places` — spatial memory lookups
- `get_visible_blocks` — distinct block types around the companion

Actions:

- `go_to_player(player, closeness)`
- `go_to_coordinates(x, y, z, closeness)`
- `go_to_remembered_place(name)`
- `follow_player(player, distance)` — persistent; returns immediately
- `stop_following()`
- `remember_here(name)` / `forget_place(name)` — spatial memory writes
- `start_task(description)` / `get_task_status(task_id)` /
  `cancel_task(task_id)` / `list_tasks()` — long-running task lifecycle
- `start_collect_task(block, count)` — long-running collection: the task
  worker searches → walks → breaks blocks → adds them to the inventory,
  reporting progress through `get_task_status`
- `give_item(player, item, count)` — hand items to a player (spawns them
  in-world, removes them from the virtual inventory)
- `drop_item(item, count)` / `equip_item(item)` / `consume_item(item, count)`
- `move_away(distance)` / `look_at(player)` — utility movement
- `flee_from_entity(type, distance)` — walk away from the nearest entity of a type
- `attack_entity(type)` — attack the nearest entity of a type (**owner-only**,
  enforced below the model)
- `defend_player(player, distance)` — attack hostiles near a player
- `search_for_block(block, range)` / `search_for_entity(type, range)` — find
  and walk to the nearest match in one action
- `open_container(name)` / `view_container(name)` /
  `put_in_container(name, item, count)` / `take_from_container(name, item, count)`
  — persistent virtual containers (NPC bodies have no real chest slots); items
  move in/out of the virtual inventory for real

Every action returns a structured result (`success` / `blocked` / `failed`)
with a reason, so the model can recover intelligently instead of guessing.

## Long-running tasks

`core/tasks` implements the goal doc's ActionManager lifecycle:
`AgentTask`, `TaskManager` and `TaskStatus`
(PENDING → RUNNING → SUCCEEDED/FAILED/BLOCKED/INTERRUPTED/CANCELLED/TIMED_OUT).
Tasks are state containers with thread-safe transitions and terminal-state
finality; `start_task` returns a `task_id` immediately so the companion can
keep talking while a task progresses, and `get_task_status` / `cancel_task`
poll or stop it. `CollectTaskWorker` is the first real executor: a pure state
machine (search → walk → break → add → repeat) that loaders schedule on the
server thread once per second, so `start_collect_task("iron_ore", 32)` really
gathers ore into the virtual inventory while the companion keeps talking.

## Events, salience and reflexes

`core/events` implements the goal's Phase 5 concepts:

- `EventPriority` — the P0-P6 ladder (survival → player command → protecting
  the player → environment → task → autonomous → idle curiosity).
- `SemanticEvent` / `EventLog` — meaningful events converted from raw game
  ticks, kept in a bounded log that is rendered into the prompt so the
  companion is aware without seeing per-tick noise.
- `SalienceGate` — scores events by priority, proximity and novelty, then
  suppresses same-type repeats and cooldown spam. A creeper 3m away always
  reaches the model; grass nearby never does; the same zombie ten seconds
  later is suppressed.
- `ReflexLayer` — LLM-free reflexes (critical health retreat, imminent
  hostile evade). Both loaders run it once per second before advancing tasks:
  a reflex cancels active tasks (the wood-gathering interrupt example) and
  records a `[P0] REFLEX` system event so the model learns about it after the
  fact instead of mid-crisis.
- Ambient awareness (`ProactivePolicy` + system-notice turns): the per-second
  tick also scans for hostiles near the player. A hostile inside 12 blocks
  becomes a gated `HOSTILE_NEAR_PLAYER` event; when salient, the companion
  runs a self-initiated `runNotice` turn ("You noticed: a creeper is 3.2m
  from Sean") and warns the player in character — at most once per 60 s, so
  ordinary game events never spam the LLM.
- Raw-event bridges: Forge subscribes to damage/death/join/leave; Fabric uses
  `ServerLivingEntityEvents` / `ServerPlayConnectionEvents`. `WorldAwareness`
  turns continuous world state into rare discrete events (sunset, night,
  weather change, dimension change), so the companion can say "it's getting
  dark, want to head home?" and notice when the player is hurt.

## Spatial memory

`PlaceMemory` persists named places per world/player (`places.json` next to
the conversation ledger). The milestone-1 chain
`stop_following → recall_place/go_to_remembered_place → respond` is covered
by deterministic mock tests and verified against the real model, so the
companion can eventually answer "go back home" without coordinates.

## Companion body

Both loaders register a `mindcraft:companion` entity (villager-shaped) with
the vanilla goals removed. The agent drives it through custom goals:

- follow a named player at a distance until told to stop
- walk to a coordinate and stop within a closeness radius
- otherwise stand still

The companion is spawned next to the local player in single-player worlds
(integrated server). Multiplayer bodies are a later milestone. The companion
has a virtual inventory persisted per world (NPC bodies have no real slots);
item-gathering tools will mutate it in Phase 4.

## Observable debug trace

Attach an `AgentLogger` to `AgentContext` (or run with debug logging) to see
the hidden loop:

```text
Iteration 1 LLM -> {"tool":"follow_player","arguments":{"player":"Sean","distance":3}}
Iteration 1 tool follow_player -> SUCCESS
Iteration 2 LLM -> {"tool":"respond","arguments":{"text":"Right behind you!"}}
Turn complete in 2 iteration(s); response: Right behind you!
```

Normal players never see this; only the final `Vera:` line is broadcast.

## Verified end-to-end (real model)

Ran against the actual runtime bundle — the fork's `llama-server`
(commit d775b89) with LittleLamb 0.3B Tool-Calling Q8_0 via
`AgentLoopIntegrationTest`:

```text
Iteration 1 LLM -> {"tool":"follow_player","arguments":{"player":"Sean","distance":4}}
Iteration 1 tool follow_player -> success
Iteration 2 LLM -> {"tool":"respond","arguments":{"text":"Right behind you!"}}
Turn complete in 2 iteration(s); response: Right behind you!
```

This exercises the complete milestone-1 path: chat trigger → grammar-
constrained LLM call → parse → validate → execute against the world adapter →
tool result fed back → second LLM call → in-character final response. The
integration tests are env-gated (`MINDCRAFT_LLAMA_SERVER` /
`MINDCRAFT_TEST_MODEL`) and run inside the WSL environment where the runtime
bundle lives.

The loop also recovers from repetition: when the model emits the same
successful call a third time, the loop injects one system nudge ("You already
did that — respond now") and only hard-stops if the model keeps repeating.
