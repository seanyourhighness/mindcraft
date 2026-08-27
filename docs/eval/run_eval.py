#!/usr/bin/env python3
"""ClankerJockey Task 2 model bake-off runner.

Drives a llama.cpp llama-server binary over its OpenAI-compatible HTTP API,
per candidate model, over the 30-prompt eval set in prompts.txt. Measures:

  - tool-call JSON validity (strict: whole output parses to {"tool": str,
    "args": dict}) free-form AND under the GBNF grammar in toolcall.gbnf
  - AMBIG (mixed/refusal) appropriateness per a small rubric
  - tokens/sec (server-reported predicted_n/predicted_ms)
  - peak RSS (VmHWM from /proc/<pid>/status)
  - model load time (spawn -> /health OK)

Writes per-candidate results to results/<name>.json (machine-readable) and
results/<name>.md (readable transcript with all outputs).

Usage:
  python3 run_eval.py --model <model.gguf> --name <candidate> [--port N] [--server <llama-server>]
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
DEFAULT_SERVER = REPO / ".deps" / "llama.cpp" / "build" / "bin" / "llama-server"
PROMPTS_FILE = HERE / "prompts.txt"
GRAMMAR_FILE = HERE / "toolcall.gbnf"
OUT_DIR = HERE / "results"

DIALOGUE_SYSTEM = (
    "You are Elias, a friendly librarian villager NPC in Minecraft. Answer in "
    "character with 1-3 short sentences. You know about the village, mining, "
    "crafting, biomes, and Minecraft mobs. Never break character."
)
TOOL_SYSTEM = (
    "You are Elias, a villager NPC in Minecraft who can perform game actions for "
    "the player. Valid tools: give_item (args: item, count), teleport (args: target), "
    "set_block (args: block), spawn_entity (args: entity), follow (args: target), "
    "stop_following (args: none), time_set (args: time). When the player asks you to "
    "do an in-game action, respond ONLY with a JSON object of the form "
    '{"tool": "<tool>", "args": {...}}. Refuse unreasonable requests (absurd '
    "quantities, impossible actions) with short in-character dialogue instead. For "
    "anything else, reply with 1-3 short sentences of in-character dialogue."
)
SPEED_PROMPT = (
    "Write a detailed story about a miner discovering a hidden cave full of "
    "treasure in Minecraft. Make it at least 150 words long."
)

MAX_TOKENS = 120
CTX = 2048
THREADS = 4


def read_prompts():
    cats = {"DIALOGUE": [], "TOOL": [], "AMBIG": []}
    for line in PROMPTS_FILE.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        cat, _, text = line.partition("|")
        if cat in cats:
            cats[cat].append(text)
    return cats


def chat(port, system, user, grammar=None, temperature=0.7, no_think=False):
    body = {
        "model": "local",
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        "max_tokens": MAX_TOKENS,
        "temperature": temperature,
        "stream": False,
    }
    if no_think:
        body["chat_template_kwargs"] = {"enable_thinking": False}
    if grammar is not None:
        body["grammar"] = grammar
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}/v1/chat/completions",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=600) as resp:
        return json.loads(resp.read().decode())


def health(port):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=2) as r:
            return r.status == 200 and "ok" in r.read().decode()
    except Exception:
        return False


def vmhwm_kb(pid):
    try:
        for line in Path(f"/proc/{pid}/status").read_text().splitlines():
            if line.startswith("VmHWM:"):
                return int(re.sub(r"[^0-9]", "", line))
    except Exception:
        pass
    return -1


def parse_tool_json(text):
    """Return (strict_ok, tool_name, args, found_json) for a response."""
    t = text.strip()
    strict_ok, tool, args = False, None, None
    found = None
    # strict: whole output is the JSON object
    try:
        obj = json.loads(t)
        if isinstance(obj, dict) and isinstance(obj.get("tool"), str) and isinstance(obj.get("args"), dict):
            strict_ok, tool, args, found = True, obj["tool"], obj["args"], t
    except Exception:
        pass
    if not strict_ok:
        # lenient: find a JSON object with tool+args anywhere (strip fences first)
        cands = re.findall(r"\{[^{}]*\"tool\"[^{}]*\}", t)
        for c in cands:
            try:
                obj = json.loads(c)
                if isinstance(obj.get("tool"), str) and isinstance(obj.get("args"), dict):
                    tool, args, found = obj["tool"], obj["args"], c
                    break
            except Exception:
                continue
    return strict_ok, tool, args, found


VALID_TOOLS = {"give_item", "teleport", "set_block", "spawn_entity", "follow",
               "stop_following", "time_set", "give", "tp", "setblock", "summon"}

# Expected tool per TOOL prompt (order matches prompts.txt TOOL section)
EXPECTED_TOOLS = ["give_item", "teleport", "set_block", "spawn_entity", "follow",
                  "stop_following", "give_item", "give_item", "teleport", "spawn_entity"]


def ambig_rubric(idx, text, strict_ok, tool, args):
    """Appropriate behavior per AMBIG prompt index (0-based within category).

    Honest rubric: tool JSON for an absurd request, echo-of-the-request
    passed off as fulfillment, invented tools, and meta-instructions (code,
    'use the give_item command') are all INAPPROPRIATE. A refusal or a sane
    in-character dialogue line is appropriate.
    """
    t = text.strip()
    if not t:
        return False, "empty output"
    low = t.lower()
    refusal = any(k in low for k in ("sorry", "can't", "cannot", "afraid",
                                     "refuse", "won't", "not able", "can not"))
    has_tool_json = any(k in low for k in ('"tool"', "give_item", "teleport",
                                           "spawn_entity", '"follow"', "set_block"))
    meta = any(k in low for k in ("command", "```", "you can do that by",
                                  "using the following", "code:"))
    # 0: "Give me 1000 diamonds" -> must refuse, never grant
    if idx == 0:
        if strict_ok or has_tool_json:
            return False, "tool JSON emitted for absurd request"
        return refusal, f"dialogue{' refusal' if refusal else ' but no refusal signal'}"
    # 1: "I'm hungry" -> food give or food dialogue
    if idx == 1:
        if strict_ok and tool == "give_item":
            item = str(args.get("item", "")).lower()
            return any(k in item for k in ("bread", "apple", "food", "beef",
                                           "pork", "chicken", "cookie", "meat")), \
                f"give_item {item}"
        return ("food" in low or "eat" in low), "dialogue (food mention?)"
    # 2: "hand me some wood from that chest" -> give wood or in-character help
    if idx == 2:
        if strict_ok and tool == "give_item":
            item = str(args.get("item", "")).lower()
            return any(k in item for k in ("wood", "log", "plank")), f"give_item {item}"
        if meta or has_tool_json:
            return False, "meta-instruction or stray tool mention"
        return True, "in-character dialogue/help"
    # 3: "follow me, but only if you're not too busy" -> follow or dialogue
    if idx == 3:
        if strict_ok and tool == "follow":
            return True, "follow tool"
        if strict_ok:
            return False, f"wrong tool: {tool}"
        if has_tool_json:
            return False, "malformed tool JSON"
        return True, "dialogue"
    # 4: "Can you make it daytime?" -> time_set or refusal; never invented tool/code
    if idx == 4:
        if strict_ok and tool == "time_set":
            return True, "time_set tool"
        if strict_ok:
            return False, f"invented tool: {tool}"
        if has_tool_json:
            return False, "malformed/invented tool JSON"
        if meta:
            return False, "meta-instruction/code"
        return True, "dialogue"
    return False, "unhandled"


def run_candidate(args):
    prompts = read_prompts()
    grammar = GRAMMAR_FILE.read_text()
    results = {"name": args.name, "model": args.model,
               "load_time_s": None, "tok_per_s_dialogue": [], "tok_per_s_speed": None,
               "peak_rss_kb": None, "gens": []}
    log_file = OUT_DIR / f"{args.name}.log"
    cmd = [str(args.server), "-m", str(args.model), "-c", str(CTX), "-t", str(THREADS),
           "-np", "1", "--port", str(args.port), "--no-webui",
           "--log-file", str(log_file)]
    t0 = time.monotonic()
    proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        up = False
        for _ in range(1200):
            if proc.poll() is not None:
                print(f"[{args.name}] FATAL: server exited early, code {proc.returncode}")
                print((log_file.read_text() if log_file.exists() else "")[-3000:])
                sys.exit(1)
            if health(args.port):
                up = True
                break
            time.sleep(0.1)
        if not up:
            print(f"[{args.name}] FATAL: never healthy")
            sys.exit(1)
        results["load_time_s"] = round(time.monotonic() - t0, 3)
        print(f"[{args.name}] healthy in {results['load_time_s']}s")

        def gen(system, user, category, mode, temperature, rubric=None,
                rubric_idx=None, expected_tool=None):
            resp = chat(args.port, system, user, temperature=temperature,
                        grammar=grammar if mode == "grammar" else None,
                        no_think=getattr(args, "no_think", False))
            content = resp["choices"][0]["message"]["content"]
            usage = resp.get("usage", {})
            pn = usage.get("prompt_tokens", -1)
            dn = usage.get("completion_tokens", -1)
            tps = resp.get("timings", {})
            pms = tps.get("predicted_ms", -1)
            tok_s = (dn / (pms / 1000.0)) if pms and dn and dn > 0 else None
            strict, tool, targs, found = parse_tool_json(content)
            rec = {"prompt": user, "category": category, "mode": mode,
                   "output": content, "prompt_n": pn, "predicted_n": dn,
                   "predicted_ms": pms,
                   "tok_per_s": round(tok_s, 2) if tok_s else None,
                   "strict_valid": strict, "tool": tool, "args": targs}
            if expected_tool is not None:
                rec["expected_tool"] = expected_tool
                rec["tool_ok"] = tool == expected_tool
            if rubric is not None:
                ok, note = rubric(rubric_idx, content, strict, tool, targs)
                rec["appropriate"] = ok
                rec["rubric_note"] = note
            results["gens"].append(rec)
            return rec

        # 1. dialogue (free-form)
        for p in prompts["DIALOGUE"]:
            gen(DIALOGUE_SYSTEM, p, "DIALOGUE", "free", 0.7)
        # 2. tool calls: free-form then grammar
        for p, exp in zip(prompts["TOOL"], EXPECTED_TOOLS):
            gen(TOOL_SYSTEM, p, "TOOL", "free", 0.2, expected_tool=exp)
        for p, exp in zip(prompts["TOOL"], EXPECTED_TOOLS):
            gen(TOOL_SYSTEM, p, "TOOL", "grammar", 0.2, expected_tool=exp)
        # 3. ambiguous (free-form only; refusals must be dialogue)
        for i, p in enumerate(prompts["AMBIG"]):
            gen(TOOL_SYSTEM, p, "AMBIG", "free", 0.2, rubric=ambig_rubric,
                rubric_idx=i)
        # 4. speed benchmark (120-token cap, story prompt)
        resp = chat(args.port, DIALOGUE_SYSTEM, SPEED_PROMPT, temperature=0.7)
        dn = resp["usage"].get("completion_tokens", -1)
        pms = resp.get("timings", {}).get("predicted_ms", -1)
        results["tok_per_s_speed"] = round(dn / (pms / 1000.0), 2) if pms and dn else None
        results["speed_output_len"] = dn
        # 5. RSS
        results["peak_rss_kb"] = vmhwm_kb(proc.pid)
    finally:
        proc.terminate()
        try:
            proc.wait(10)
        except subprocess.TimeoutExpired:
            proc.kill()
    # per-dialogue tok/s (these hit the 120 cap -> clean speed numbers)
    results["tok_per_s_dialogue"] = [g["tok_per_s"] for g in results["gens"]
                                     if g["mode"] == "free" and g["tok_per_s"]]
    # stats grouped by category+mode
    def stat(recs):
        n = len(recs)
        valid = sum(1 for g in recs if g["strict_valid"])
        tps = [g["tok_per_s"] for g in recs if g["tok_per_s"]]
        out = {"n": n, "strict_valid": valid,
               "valid_pct": round(100 * valid / n, 1) if n else None,
               "avg_tok_per_s": round(sum(tps) / len(tps), 2) if tps else None}
        if recs and all("tool_ok" in g for g in recs):
            ok = sum(1 for g in recs if g.get("tool_ok"))
            out["tool_ok"] = ok
            out["tool_ok_pct"] = round(100 * ok / n, 1)
        if recs and all("appropriate" in g for g in recs):
            ok = sum(1 for g in recs if g.get("appropriate"))
            out["appropriate"] = ok
            out["appropriate_pct"] = round(100 * ok / n, 1)
        return out

    results["stats"] = {}
    for cat in ("DIALOGUE", "TOOL", "AMBIG"):
        for mode in ("free", "grammar"):
            recs = [g for g in results["gens"] if g["category"] == cat and g["mode"] == mode]
            if recs:
                results["stats"][f"{cat}/{mode}"] = stat(recs)
    return results


def write_transcript(results):
    lines = [f"# {results['name']} - eval transcript",
             f"model: {results['model']}",
             f"load_time_s: {results['load_time_s']}",
             f"speed_tok_per_s: {results['tok_per_s_speed']}",
             f"peak_rss_kb: {results['peak_rss_kb']}",
             f"stats: {json.dumps(results['stats'])}",
             ""]
    for i, g in enumerate(results["gens"]):
        lines.append(f"--- gen {i} [{g['category']}/{g['mode']}] (prompt_n={g['prompt_n']}, "
                     f"pred_n={g['predicted_n']}, tok/s={g['tok_per_s']}, "
                     f"strict_valid={g['strict_valid']})")
        if "appropriate" in g:
            lines.append(f"appropriate={g['appropriate']} ({g['rubric_note']})")
        lines.append(f"USER: {g['prompt']}")
        out = g["output"].replace("\n", "\n  ")
        lines.append(f"NPC:  {out}")
        lines.append("")
    (OUT_DIR / f"{results['name']}.md").write_text("\n".join(lines))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--port", type=int, default=18080)
    ap.add_argument("--no-think", action="store_true",
                    help="send enable_thinking=false (thinking models)")
    ap.add_argument("--server", default=str(DEFAULT_SERVER))
    args = ap.parse_args()
    OUT_DIR.mkdir(exist_ok=True)
    results = run_candidate(args)
    write_transcript(results)
    (OUT_DIR / f"{args.name}.json").write_text(json.dumps(results, indent=2))
    print(f"[{args.name}] === SUMMARY ===")
    print(json.dumps(results["stats"], indent=2))
    print(f"speed tok/s: {results['tok_per_s_speed']}")
    print(f"peak RSS: {results['peak_rss_kb']} kB")
    print(f"transcript: {OUT_DIR / (args.name + '.md')}")


if __name__ == "__main__":
    main()
