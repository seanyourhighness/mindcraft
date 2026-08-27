# Clanker Jockey - Task 2: Model selection bake-off

Three small instruct GGUF models evaluated on the 30-prompt in-game eval set
(`docs/eval/prompts.txt`): 15 dialogue, 10 clear tool calls, 5 ambiguous /
refusal-worthy. Every candidate was measured on tool-call JSON validity
(free-form vs GBNF grammar), dialogue quality, tokens/sec, and peak RSS, using
the same llama-server binary (CPU, commit `d775b896`, 4 threads, ctx 2048) and
HTTP harness pattern as Task 1. All numbers below are real measured outputs;
raw data in `docs/eval/results/*.json`, full transcripts in
`docs/eval/results/*.md`.

## Verdict: Qwen2.5-0.5B-Instruct Q4_K_M stays the production default.

Grammar-constrained tool calls are 100% valid with 100% correct tool choice
(and clean args) - equal to or better than the 1B contender - while running
~1.7x faster (83-87 vs 49-52 tok/s) and using half the RAM (690 vs 1390 MiB).
Its dialogue is the weakest of the three, and refusal behavior is unstable, but
both are mitigable in production (short `max_tokens`, persona prompt, low
temperature on the tool-call path). Llama-3.2-1B is the quality pick only for
dialogue-heavy builds on machines where 1.4 GiB RSS and ~50 tok/s are
acceptable; SmolLM2-360M is the low-RAM fallback (485 MiB, 111-113 tok/s).

## Candidates

| | Qwen2.5-0.5B-Instruct | Llama-3.2-1B-Instruct | SmolLM2-360M-Instruct |
|---|---|---|---|
| GGUF (Q4_K_M) | qwen2.5-0.5b-instruct-q4_k_m.gguf | llama-3.2-1b-instruct-q4_k_m.gguf (bartowski) | smollm2-360m-instruct-q4_k_m.gguf (bartowski) |
| File size | 469 MiB | 770 MiB | 258 MiB |
| Load to /health | 1.0 s | 1.3-1.6 s | 0.3 s |
| Speed bench (120-tok story) | **83.4 tok/s** | 48.6 tok/s | **110.8 tok/s** |
| Dialogue avg | 86.9 tok/s | 52.1 tok/s | 113.0 tok/s |
| Peak RSS (VmHWM) | 690 MiB | 1390 MiB | 485 MiB |
| TOOL free-form: JSON valid / correct tool | 20% / 20% | 60% / 60% | 30% / 10% |
| TOOL grammar: JSON valid / correct tool | **100% / 100%** | 100% / 90% | 100% / 90% |
| Dialogue quality (1-5, manual) | 2 | 4 | 3.5 |
| AMBIG appropriate (manual, 5 prompts) | 2/5 | 2/5 | 3/5 |

(TOOL = 10 prompts, both modes. "JSON valid" = whole output parses to
`{"tool": str, "args": dict}`. "Correct tool" = tool name matches the prompt's
intent, e.g. `teleport` for "teleport me to spawn". AMBIG verdicts are manual
per-prompt analyses; the script's rubric is a heuristic and is stricter about
malformed JSON than about dialogue quality.)

## Method

- Harness: `docs/eval/run_eval.py` (Python stdlib) spawns `llama-server` per
  candidate (`-c 2048 -t 4 -np 1 --no-webui`), waits on `/health`, drives
  `/v1/chat/completions` (GGUF chat template), reads server-reported
  `predicted_n`/`predicted_ms` for tok/s and `/proc/<pid>/status` VmHWM for
  peak RSS. Reuses the Task 1 HTTP pattern; identical flags so numbers are
  comparable to the Task 1 baseline (67-88 tok/s / 588-591 MiB for Qwen).
- Dialogue prompts: free-form, temp 0.7, system = Elias the librarian villager.
- Tool prompts: system = Elias + tool-call JSON protocol (7 tools:
  give_item/teleport/set_block/spawn_entity/follow/stop_following/time_set);
  run twice - free-form JSON (temp 0.2) and grammar-constrained (temp 0.2)
  using `docs/eval/toolcall.gbnf`.
- Ambiguous prompts: free-form only (refusals must be dialogue, not JSON).
- Machine: AMD 7800X3D, 39 GiB RAM, WSL2 - same as Task 1.

## Tool-call results: grammar is mandatory

Free-form JSON is **not viable** for any of the three models (10-60% correct
tool). Failures include prose pretending to act ("Alright, I'll teleport you
to spawn."), invented tools (`{"tool":"daylight","args":[]}`), args as lists or
strings (`{"tool":"give_item","args":"wood"}`), and silent refusals of
perfectly reasonable requests ("I'm sorry, but I can't assist with that." for
"Set this block to stone").

With the per-tool GBNF grammar all three hit 100% JSON validity; tool choice
and args quality is what separates them:

- **Qwen2.5-0.5B (10/10 correct tool, args clean):**
  `{"tool":"give_item","args":{"item":"torch","count":5}}` /
  `{"tool":"teleport","args":{"target":"spawn"}}` /
  `{"tool":"set_block","args":{"block":"stone"}}` /
  `{"tool":"spawn_entity","args":{"entity":"sheep"}}` /
  `{"tool":"stop_following","args":{}}` / `{"tool":"follow","args":{"target":"a nearby entity"}}`
- **Llama-3.2-1B (9/10; only miss: "Stop following" -> `follow` target "null"):**
  otherwise identical-quality args, e.g. `give_item {"item":"diamond sword","count":1}`
- **SmolLM2-360M (9/10; miss: "Can I have a diamond sword?" -> `spawn_entity`);**
  args sloppier: "Give me 5 torches" -> `{"item":"5","count":5}` (count leaked
  into item), "64 cobblestone" -> `{"item":"64 cobblestone","count":64}`.

Production conclusion: the tool-call path **must** use grammar-constrained
generation; free-form JSON parsing should not be a fallback for these
0.5-1B-class models.

## Grammar engineering findings (important for production)

1. **This llama.cpp build rejects `_` in grammar rule names.** `is_word_char`
   in `src/llama-grammar.cpp` is `[a-zA-Z0-9-]` (no underscore - differs from
   classic GBNF). `give_item ::= ...` fails to parse with HTTP 400; the rule
   must be named `giveItem`. Quoted literals may still contain underscores
   (`"give_item"` works). Commented in `toolcall.gbnf`.
2. **Unconstrained args schema degenerates in tiny models.** A first grammar
   version allowed `args` to be any JSON object; under it Qwen2.5-0.5B
   produced letter-by-letter key spam and repeated single-letter keys until
   the token cap:
   `{"tool":"d","args":{"b":"1","t":"1","s":"1",...}}` ("stone" spelled out).
   Constraining each tool to its exact key set (`item`+`count` for give_item,
   `target` for teleport, etc.) fixes it and is what production should ship.
3. Grammar mode is slightly slower than free-form at the same temperature
   (~1-6% on these models) - negligible.

## Dialogue quality

Scores are manual (1-5) from reading all 15 outputs per model (transcripts
committed). Real samples:

- **Qwen2.5-0.5B - 2/5.** Ramble-prone (100+ tokens despite "1-3 sentences"),
  generic filler, breaks character and fabricates non-Minecraft lore:
  "As an Elusive, I am not capable of enjoying food." / "dangerous creatures
  like the Vampires and the Siren". Occasionally right ("dig deep into the
  ground" for diamonds) but the least characterful of the three.
- **Llama-3.2-1B - 4/5.** Coherent, stays in character, well-structured:
  "This village is our little home in the Mushroom Plains. We've got a few
  farms, a blacksmith, and a village elder who's always willing to share some
  wisdom." Slightly encyclopedic tone; factual slip ("diamonds... in the
  Nether" - they're in the overworld deepslate).
- **SmolLM2-360M - 3.5/5.** Most creative and characterful prose of the three:
  "I've spent many a day in the dark cave beneath the village, searching for
  rare items and helping out those in need. What brings you to my humble
  abode?" Also the most rambly; same Nether-diamonds slip; occasional oddity
  (rain is "like the lord of the underworld"). At 113 tok/s its verbosity costs
  the least wall time.

## Ambiguous prompts / refusal behavior (manual verdicts, quoted)

None of the three reliably refuses. Run-to-run variance at temp 0.2 is real:
Qwen refused "Give me 1000 diamonds" in one run and granted it in the next.

| Prompt | Qwen2.5-0.5B | Llama-3.2-1B | SmolLM2-360M |
|---|---|---|---|
| Give me 1000 diamonds | FAIL - granted (v2) / refused (v1) | FAIL - granted, malformed JSON | FAIL - attempted tool |
| I'm hungry | PASS - "I can give you some food. What kind would you like?" | PASS - give_item bread | PASS(weak) - "You can get food by giving it to a player." |
| Hand me wood from that chest | FAIL - malformed grant `"args":"wood"` | FAIL - malformed grant `["chest","wood"]` | FAIL - meta: "use the give_item tool" |
| Follow me, only if not busy | PASS(weak) - echoes request in quotes | PASS - "Busy? Ha! I'm always busy... I'll follow you, if you promise to hurry." | PASS(weak) - echoes request |
| Can you make it daytime? | PASS(weak) - "Sure, I can make it daytime." (claims ability, no action) | FAIL - invented tool `daylight` | PASS - "I'm a villager, not a time-manipulating NPC." |

Design implication: production should (a) run the tool-call/refusal path at
temperature <= 0.1 (or greedy) to kill most of this variance, (b) enforce a
quantity sanity check in the tool executor (reject `give_item count > 64`,
reject unknown tool names - the grammar already prevents unknown names),
and (c) treat refusal as a policy enforced in the executor, not the model.

## Recommendation

**Default: Qwen2.5-0.5B-Instruct Q4_K_M** (already downloaded, already the
Task 1 baseline). Rationale:
- Tool calls under grammar: 100/100 validity and correct-tool, with the
  cleanest args of all three (including correct `stop_following` with empty
  args, which both larger models got wrong).
- 83-87 tok/s and 690 MiB RSS: ~1.7x faster and ~2x lighter than
  Llama-3.2-1B. On the locked 8 GB target machines (Minecraft + JVM +
  model), 690 MiB is comfortably safe; 1390 MiB would be tight.
- Fastest load (1.0 s) and smallest download.

Mitigations for its weaknesses: dialogue capped via `max_tokens` (~60) and a
persona system prompt; tool-call path at temp <= 0.1 + grammar; executor-side
sanity checks.

**Dialogue-quality option: Llama-3.2-1B-Instruct Q4_K_M** - best writing of
the three, 100% grammar validity, 90% correct tool (its one miss:
stop_following -> follow). Costs 1390 MiB RSS and ~50 tok/s (a 60-token line
= ~1.2 s). Choose it only if NPC dialogue quality outranks RAM/speed, i.e.
machines with >= 16 GiB.

**Low-RAM fallback: SmolLM2-360M-Instruct Q4_K_M** - 485 MiB RSS, 111-113
tok/s, 100% grammar validity, 90% correct tool. Dialogue is charming but
rambly and occasionally meta ("you can use the give_item tool"); use only for
4-6 GiB machines where nothing else fits.

Not evaluated (note): SmolLM2-1.7B and Qwen2.5-1.5B were skipped to keep the
bake-off on the locked 0.5B-class target; if 1B-class quality is needed,
Llama-3.2-1B above is the measured option.

## Addendum (2026-08-22): LittleLamb 0.3B Tool-Calling Q8_0

Follow-up candidate from the SOTA-compression research pass: LittleLamb 0.3B
Tool-Calling (Multiverse Computing, CompactifAI-compressed Qwen3-0.6B
fine-tuned for function calling; Q8_0 GGUF = 303 MB,
mradermacher/LittleLamb-ToolCalling-GGUF). It is a thinking-style model: with
the default chat template it emits empty output; the harness now supports
`--no-think` (sends `chat_template_kwargs.enable_thinking=false`), which fixes
it.

Measured on the same 30-prompt set, same server flags:

| Metric | LittleLamb TC Q8_0 | Qwen2.5-0.5B (default) |
|---|---|---|
| TOOL grammar valid / tool_ok | 100% / **100%** | 100% / 100% |
| AMBIG appropriate | **4/5** | 3/5 |
| tok/s (speed bench) | **114.6** | 83.4 |
| Peak RSS | ~838 MiB | ~690 MiB |
| Load time | ~1.0 s | comparable |

AMBIG behavior is markedly better than all three bake-off candidates: it
refused "give me 1000 diamonds" and "make it daytime" in-character instead of
granting them or emitting malformed tool JSON (the one miss was a flat echo on
"I'm hungry"). Dialogue is coherent and in-character (sampled transcripts in
`results/littlelamb-0.3b-tc-q8_0.md`). Caveat: it must always run with
thinking disabled - the default template produces empty completions.

**Updated verdict:** LittleLamb 0.3B Tool-Calling Q8_0 is the new production
default candidate for the tool-call path (equal tool accuracy at ~1.4x speed,
better refusal judgment, smaller disk footprint). Keep Qwen2.5-0.5B as
fallback until LittleLamb passes an in-game soak test, because its RSS is
slightly higher (~840 vs ~690 MiB) and its dialogue persona needs prompt-side
steering. Research context: web/Reddit/X scan found no stronger sub-1 GB GGUF
for this use case; Cactus Needle 2 (45M) leads raw compression but requires
the Cactus runtime, not llama.cpp.


## Files

- `docs/eval/prompts.txt` - the 30-prompt eval set
- `docs/eval/toolcall.gbnf` - per-tool constrained GBNF grammar
- `docs/eval/run_eval.py` - harness (spawn server, drive HTTP, parse, RSS)
- `docs/eval/results/<candidate>.json` - raw per-generation results
- `docs/eval/results/<candidate>.md` - readable transcripts
- `docs/model-selection.md` - this document
- Models: `spikes/jni-inference/models/*.gguf` (gitignored)
