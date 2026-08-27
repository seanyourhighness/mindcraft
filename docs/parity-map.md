# Upstream MindCraft Parity Map

Audit of `mindcraft-bots/mindcraft` (reference) vs. our native mod
(`seanyourhighness/mindcraft`). Classification:

```
COPY CONCEPT        - take the idea, design it fresh for our architecture
ADAPT               - port the pattern with structural changes
REIMPLEMENT NATIVELY- same capability, different mechanism (native mod, no mineflayer)
SKIP                - deliberately excluded (security / architecture)
LATER               - valuable, out of the current milestone
```

## 1. Agent loop (`src/agent/agent.js`)

| Upstream concept | Verdict | Our equivalent |
|---|---|---|
| `handleMessage` loop: max responses per message, repeatedly prompt → detect `!command` → execute → append result → prompt again | ADAPT | `AgentLoop` in `core/agent`; structured JSON tool calls instead of text `!commands` |
| Loop ends on conversational response without command | COPY CONCEPT | Synthetic `respond` tool returns the final natural-language answer |
| History gets system entries for tool results | COPY CONCEPT | Tool results appended to the context between iterations (kept out of player-visible output) |
| Empty response ends the loop | COPY CONCEPT | Empty/blank output → loop ends with a fallback response |
| User-forced `!stop` / `!stfu` commands | SKIP | Natural-language + structured tools; cancellation is a first-class loop control |
| Translation before processing | LATER | Keep native; no translator in milestone 1 |

## 2. Action management (`src/agent/action_manager.js`)

| Upstream concept | Verdict | Our equivalent |
|---|---|---|
| `runAction(label, fn, {timeout, resume})` with lifecycle + interrupt | ADAPT | `ToolExecutor`: per-tool timeout, interruptible flag, status result |
| `stop()` interrupts current action (pathfinder/pvp/dig) | ADAPT | `AgentLoop.cancel()` + `CompanionBehavior` interrupt on the Forge side |
| Fast-action-loop detection (recent-action counter, 3/5 strikes) | COPY CONCEPT | `AgentLoopConfig`: max repeated identical calls (2), max consecutive failures (3) |
| Action output truncated to 500 chars before re-prompt | COPY CONCEPT | `ToolResult` render caps at compact size |
| Resume/`cancelResume` for endless actions (follow) | ADAPT | Long-lived companion behaviors (follow) managed by `CompanionBehavior`; follow is a state, not a blocking call |
| Timeout in minutes, force stop | ADAPT | Per-call timeout + loop timeout in `AgentLoopConfig` |

## 3. Commands → tools

### Queries (`src/agent/commands/queries.js`)

| Upstream | Verdict | Our tool / status |
|---|---|---|
| `!stats` (pos, health, hunger, biome, weather, time, nearby players) | ADAPT | `get_self_state` (milestone 1) |
| `!inventory` (+ worn armor) | ADAPT | `get_inventory` (companion virtual inventory, milestone 1) |
| `!entities` (nearby players/entities w/ villager professions) | ADAPT | `get_nearby_entities` (milestone 1) |
| `!nearbyBlocks` / `!craftable` | LATER | `get_visible_blocks` / `get_craftable` (Phase 3/4) |
| `!help`, `!savedPlaces`, `!searchWiki`, blueprint queries | LATER | Tool docs are in the system prompt; wiki/blueprints out of scope |

### Actions (`src/agent/commands/actions.js`)

| Upstream | Verdict | Our tool / status |
|---|---|---|
| `!goToPlayer(player_name, closeness)` | REIMPLEMENT NATIVELY | `go_to_player` via companion pathfinding goal (milestone 1) |
| `!followPlayer(player_name, follow_dist)` | REIMPLEMENT NATIVELY | `follow_player` as persistent companion behavior (milestone 1) |
| `!stop` (force stop all) | ADAPT | `stop_following` + loop cancellation (milestone 1) |
| `!goToCoordinates(x, y, z, closeness)` | REIMPLEMENT NATIVELY | `go_to_coordinates` (cheap; same goal machinery) |
| `!moveAway` | LATER | `move_away` (Phase 4) |
| `!searchForBlock` / `!searchForEntity` | LATER | `search_for_block` / `search_for_entity` (Phase 4) |
| `!rememberHere` / `!goToRememberedPlace` | ADAPT | Spatial memory tools (Phase 6), backed by a persistent place store |
| `!givePlayer`, `!consume`, `!equip`, `!discard`, chest ops, `!craftRecipe`, `!smeltItem`, `!tradeWithVillager`, `!useOn`, `!collectBlocks`, `!attack`, `!defendSelf` | LATER | Phase 4 action parity list in goal doc |
| `!newAction` (LLM-generated code) | SKIP | No arbitrary Java/JS/shell; deterministic tools only |
| `!restart`, `!clearChat`, `!goToBed`, `!goToSurface`, `!digDown`, `!stay` | LATER | Not needed for companion-first milestone |

## 4. Modes / reflex layer (`src/agent/modes.js`)

| Upstream | Verdict | Our equivalent |
|---|---|---|
| Ordered tick-mode list with priority, `interrupts`, pause/unpause | ADAPT | Reflex layer + event salience (goal doc Phase 5); priorities P0-P6 |
| `self_preservation` (drowning, lava/fire, low health, falling blocks) | ADAPT | Reflex layer: critical-health retreat, fire, falling-danger |
| `unstuck` (stuck detection) | LATER | Companion pathfinding diagnostics |
| `cowardice`, `self_defense`, `hunting`, `item_collecting`, `torch_placing`, `elbow_room`, `idle_staring` | LATER | Autonomous behavior (Phase 7) |
| Behavior log fed back to LLM (`Recent behaviors log:`) | COPY CONCEPT | Semantic event log with salience gating |

## 5. Self prompting (`src/agent/self_prompter.js`)

| Upstream | Verdict | Our equivalent |
|---|---|---|
| Ambient loop: self-prompt → require command → cooldown → repeat | ADAPT | Ambient awareness (Phase 7); driven by semantic events + salience, not timers |
| Interrupt on user message, no-command watchdog | COPY CONCEPT | Loop cancellation + repeated-failure limits |
| Cooldown / idle-time gating | COPY CONCEPT | Salience cooldown + repetition penalty |

## 6. Memory (`src/agent/history.js`, `memory_bank.js`)

| Upstream | Verdict | Our equivalent |
|---|---|---|
| Rolling history + LLM summarization into a 500-char memory string | ADAPT | Already implemented: `MemoryLedger` + `PromptAssembler` (facts/summary/trust/inside jokes). Keep and extend. |
| `memory_bank.rememberPlace/recallPlace/getKeys` (in-memory places) | ADAPT | Persistent spatial memory store (Phase 6) |
| `history.save/load` per bot | ADAPT | `MemoryLedger` JSON persistence per world/player |

## 7. Tasks (`src/agent/tasks/`)

| Upstream | Verdict | Our equivalent |
|---|---|---|
| `Task` with goal, timeout, validator, `blocked_actions` | ADAPT | `AgentTask`/`TaskManager`/`TaskStatus` in `core/tasks` (PENDING…TIMED_OUT lifecycle) |
| Long tasks run async; `isDone()` polled in update loop | COPY CONCEPT | `start_task` / `get_task_status` / `cancel_task` tools (Phase 4) |
| Task-scoped command blocking | COPY CONCEPT | Tool permissions/security classes per task context |

## 8. Prompt design (`profiles/defaults/_default.json`, `src/models/prompter.js`)

| Upstream | Verdict | Our equivalent |
|---|---|---|
| System prompt = persona + memory + `$STATS` + `$INVENTORY` + `$COMMAND_DOCS` + examples | ADAPT | `AgentLoop` system prompt: persona + memory block + compact world context + tool schemas |
| Textual `!command("arg")` syntax | SKIP | Structured JSON `{"tool":..., "arguments":{...}}` with GBNF grammar |
| Conversation examples in prompt | COPY CONCEPT | A few tool-call examples in the system prompt |

## 9. Skills library (`src/agent/library/skills.js`)

Large mineflayer skill set (move, collect, build, chest, trade, cooking…).
Used as the **Phase 4 inspiration list**; not ported wholesale. Each skill will be
reimplemented natively behind the `WorldAdapter` interface.

## 10. Security / architecture rules

| Upstream | Verdict | Notes |
|---|---|---|
| `!newAction` arbitrary code execution | SKIP | Deterministic tools only; missing behavior = new reviewed tool |
| `allow_insecure_coding` flag | SKIP | Not implemented at all |
| Multi-agent conversations | LATER | Companion-first; multi-companion later |
| Vision (browser viewer + interpreter) | LATER | Phase 7+ |

