# In-Game Testing Checklist

Everything in the agent tool loop is verified outside the game (unit tests +
real-model integration against llama.cpp/LittleLamb). The final gate is the
actual in-game run, which needs a Minecraft client. This checklist maps to the
goal document's milestones so the verification is quick and unambiguous.

## Setup

1. Build the mod jars:
   - Forge 1.20.1: `./gradlew :mod-forge:build` → `mod-forge/build/libs/mindcraft-forge-<v>.jar`
   - Fabric 1.21.1: `./gradlew :mod-fabric:build` → `mod-fabric/build/libs/*.jar`
2. Create/install a modded instance:
   - Forge: 1.20.1 with Forge 47.4.0 (or newer 47.x); put the jar in `mods/`.
   - Fabric: 1.21.1 with Fabric Loader 0.16.x + Fabric API 0.116.x; same.
3. Assemble the runtime bundle into the instance's game directory:
   ```
   python3 tools/bundle_runtime.py --target <instance-game-dir> --verify
   ```
   Expected layout:
   ```
   <game-dir>/mindcraft/bin/llama-server[.exe]
   <game-dir>/mindcraft/models/littlelamb-0.3b-toolcalling-q8_0.gguf
   ```
   `--verify` boots the bundled server and health-checks it before you launch.
4. Launch the game, open a single-player world. Watch the log for:
   `inference engine healthy on port ...` and `companion agent online`.

## Milestone 1 — follow + reply

1. Say: `Come over here and follow me.`
2. Expect:
   - a villager-shaped companion spawns near you;
   - chat shows `Vera: ...` (in-character reply);
   - the companion walks to you and keeps following.
3. Say: `Stop following me and go back home.` (after `Remember this spot as
   home.` earlier, or say `Remember this spot as home.` first).
4. Expect: the companion stops following, walks toward the saved spot, and
   replies in character.

## Milestone 2 — multi-tool task

1. Say: `Collect 8 iron ore for me.`
2. Expect: `Vera` acknowledges, `start_collect_task` is logged (debug), the
   companion walks to iron ore, breaks blocks, and after a while `get_task_status`
   shows progress; when done she says something in character.
3. Say: `Give me an iron ingot.` → the item appears in the world next to you.

## Milestone 3 — ambient awareness

1. With a creeper within ~12 blocks of you (or spawn one in creative), wait.
2. Expect: within a few seconds the companion warns in chat (once per minute),
   e.g. about the creeper, and (if close enough) automatically backs away and
   interrupts any active collection task.
3. Ordinary noise (grass, cobblestone) must NOT trigger chat.

## Debug mode

Set the agent logger to debug (e.g. `--debug-log` on the launcher or the mod's
`LOGGER.debug` lines) to see the hidden loop:

```text
Iteration 1 LLM -> {"tool":"follow_player","arguments":{"player":"Sean","distance":4}}
Iteration 1 tool follow_player -> success
Iteration 2 LLM -> {"tool":"respond","arguments":{"text":"Right behind you!"}}
Turn complete in 2 iteration(s); response: Right behind you!
```

## Reporting results

For each milestone, record: the `Vera:` line, the debug loop transcript, and
any deviations. That evidence completes the goal's success criterion:
perceive → decide → act → react → remember → speak believably.
