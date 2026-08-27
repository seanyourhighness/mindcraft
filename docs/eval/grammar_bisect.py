#!/usr/bin/env python3
"""Grammar debug helper: writes reduced grammar variants and probes a server.

Used to bisect which GBNF construct a given llama.cpp build fails to enforce.
"""
import argparse
import json
import pathlib
import urllib.request


def q(s):
    return '"\\"' + s + '\\""'


def chat(port, grammar):
    body = {
        "messages": [
            {"role": "system", "content": (
                "You are Vera, a companion in Minecraft who acts through tools. "
                "Valid tools: follow_player (args: player, distance), "
                "get_nearby_entities (args: radius), respond (args: text). "
                "Respond ONLY with one JSON object: "
                '{"tool": "<name>", "arguments": {...}}.'
            )},
            {"role": "user", "content": "Come over here and follow me."},
        ],
        "max_tokens": 120,
        "temperature": 0.2,
        "stream": False,
        "chat_template_kwargs": {"enable_thinking": False},
        "grammar": grammar,
    }
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}/v1/chat/completions",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=600) as resp:
        return json.loads(resp.read().decode())["choices"][0]["message"]["content"]


def flat(key):
    return "\n".join([
        "root ::= respond | followPlayer",
        'respond ::= "{" ws ' + q("tool") + ' ws ":" ws ' + q("respond") + ' ws "," ws '
        + q(key) + ' ws ":" ws "{" ws ' + q("text") + ' ws ":" ws string ws "}" ws "}"',
        'followPlayer ::= "{" ws ' + q("tool") + ' ws ":" ws ' + q("follow_player") + ' ws "," ws '
        + q(key) + ' ws ":" ws "{" ws ' + q("player") + ' ws ":" ws string ws "," ws '
        + q("distance") + ' ws ":" ws number ws "}" ws "}"',
        'string ::= "\\"" ( [^"\\\\] | "\\\\" (["\\\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]) )* "\\""',
        'number ::= "-"? INT ("." [0-9]*)?',
        'INT ::= "0" | [1-9] [0-9]*',
        "ws ::= [ \\t\\n]*",
    ])


def indir():
    return "\n".join([
        "root ::= mcCall",
        'mcCall ::= "{" mcWs ' + q("tool") + ' mcWs ":" mcWs mcToolName mcWs "," mcWs '
        + q("arguments") + ' mcWs ":" mcWs mcArgs mcWs "}"',
        'mcToolRespond ::= ' + q("respond"),
        'mcToolFollowPlayer ::= ' + q("follow_player"),
        'mcArgsRespond ::= "{" mcWs ' + q("text") + ' mcWs ":" mcWs mcString mcWs "}"',
        'mcArgsFollowPlayer ::= "{" mcWs ' + q("player") + ' mcWs ":" mcWs mcString mcWs "," mcWs '
        + q("distance") + ' mcWs ":" mcWs mcNumber mcWs "}"',
        "mcToolName ::= mcToolRespond | mcToolFollowPlayer",
        "mcArgs ::= mcArgsRespond | mcArgsFollowPlayer",
        'mcString ::= "\\"" ( [^"\\\\] | "\\\\" (["\\\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]) )* "\\""',
        'mcNumber ::= "-"? mcInt ("." [0-9]*)?',
        'mcInt ::= "0" | [1-9] [0-9]*',
        "mcWs ::= [ \\t\\n]*",
    ])


def flat_optional():
    return "\n".join([
        "root ::= respond | followPlayer",
        'respond ::= "{" ws ' + q("tool") + ' ws ":" ws ' + q("respond") + ' ws "," ws '
        + q("arguments") + ' ws ":" ws "{" ws ' + q("text") + ' ws ":" ws string ws "}" ws "}"',
        'followPlayer ::= "{" ws ' + q("tool") + ' ws ":" ws ' + q("follow_player") + ' ws "," ws '
        + q("arguments") + ' ws ":" ws "{" ws ' + q("player") + ' ws ":" ws string'
        + ' ("," ws ' + q("distance") + ' ws ":" ws number)? ws "}" ws "}"',
        'string ::= "\\"" ( [^"\\\\] | "\\\\" (["\\\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]) )* "\\""',
        'number ::= "-"? INT ("." [0-9]*)?',
        'INT ::= "0" | [1-9] [0-9]*',
        "ws ::= [ \\t\\n]*",
    ])


def flat_bare_name():
    """The classic generator bug: tool-name literal WITHOUT escaped quotes."""
    return "\n".join([
        "root ::= respond | followPlayer",
        'respond ::= "{" ws ' + q("tool") + ' ws ":" ws "respond" ws "," ws '
        + q("arguments") + ' ws ":" ws "{" ws ' + q("text") + ' ws ":" ws string ws "}" ws "}"',
        'followPlayer ::= "{" ws ' + q("tool") + ' ws ":" ws "follow_player" ws "," ws '
        + q("arguments") + ' ws ":" ws "{" ws ' + q("player") + ' ws ":" ws string ws "," ws '
        + q("distance") + ' ws ":" ws number ws "}" ws "}"',
        'string ::= "\\"" ( [^"\\\\] | "\\\\" (["\\\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]) )* "\\""',
        'number ::= "-"? INT ("." [0-9]*)?',
        'INT ::= "0" | [1-9] [0-9]*',
        "ws ::= [ \\t\\n]*",
    ])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=18099)
    ap.add_argument("--out", default="/tmp")
    args = ap.parse_args()
    variants = {
        "flat_args": flat("args"),
        "flat_arguments": flat("arguments"),
        "indir": indir(),
        "flat_optional": flat_optional(),
        "flat_bare_name": flat_bare_name(),
    }
    for name, g in variants.items():
        path = pathlib.Path(args.out) / f"{name}.gbnf"
        path.write_text(g)
        try:
            content = chat(args.port, g)
            print(f"[{name}] output={content!r}")
        except Exception as e:
            print(f"[{name}] ERROR {e}")


if __name__ == "__main__":
    main()
