#!/usr/bin/env python3
"""Probe a llama-server instance with/without a GBNF grammar.

Diagnoses grammar issues against the real llama.cpp build: start a server,
then run this with a prompt + grammar file and inspect the raw response.

Example:
  python3 probe_grammar.py --port 18099 --grammar agent-grammar.gbnf \
      --prompt "Come over here and follow me."
"""
import argparse
import json
import urllib.request


def chat(port, system, user, grammar=None, temperature=0.2, max_tokens=120):
    body = {
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        "max_tokens": max_tokens,
        "temperature": temperature,
        "stream": False,
        "chat_template_kwargs": {"enable_thinking": False},
    }
    if grammar is not None:
        body["grammar"] = grammar
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}/v1/chat/completions",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=600) as resp:
        return json.loads(resp.read().decode())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=18099)
    ap.add_argument("--grammar", help="path to a GBNF file (omit for unconstrained)")
    ap.add_argument("--prompt", default="Come over here and follow me.")
    ap.add_argument("--system", default=(
        "You are Vera, a companion in Minecraft who acts through tools. "
        "Valid tools: get_self_state (args: none), get_nearby_entities (args: radius), "
        "get_inventory (args: none), get_player_state (args: player), "
        "get_nearby_players (args: radius), go_to_player (args: player, closeness), "
        "go_to_coordinates (args: x, y, z, closeness), follow_player (args: player, distance), "
        "stop_following (args: none), respond (args: text). "
        "Respond ONLY with one JSON object: {\"tool\": \"<name>\", \"arguments\": {...}}. "
        "Use respond with text as your final message."
    ))
    args = ap.parse_args()
    grammar = open(args.grammar).read() if args.grammar else None
    out = chat(args.port, args.system, args.prompt, grammar)
    print(json.dumps(out, indent=2)[:4000])


if __name__ == "__main__":
    main()
