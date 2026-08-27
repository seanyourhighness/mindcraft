#!/usr/bin/env python3
"""Assemble the ClankerJockey runtime bundle into a game directory.

The mod expects, at <game dir>/clankerjockey/:

    bin/llama-server[.exe]                       llama.cpp server binary
    models/littlelamb-0.3b-toolcalling-q8_0.gguf  default model

This script finds (or downloads) those assets and copies them into place, so
the mod can start inference in-game. Run it from Windows PowerShell or from
WSL/Linux; paths can be given explicitly or resolved from known locations and
environment variables.

Examples:
    python3 tools/bundle_runtime.py --target /path/to/game-dir
    python3 tools/bundle_runtime.py --target "C:\\Users\\Sean\\AppData\\Roaming\\.minecraft" \\
        --server "D:\\llama-cpp\\llama-server.exe" --model "C:\\path\\model.gguf"
    python3 tools/bundle_runtime.py --target /path/to/game-dir --verify
"""
import argparse
import os
import pathlib
import shutil
import socket
import stat
import subprocess
import sys
import time
import urllib.request


MODEL_NAME = "littlelamb-0.3b-toolcalling-q8_0.gguf"
MODEL_URL = (
    "https://huggingface.co/mradermacher/LittleLamb-ToolCalling-GGUF/resolve/main/"
    "LittleLamb-ToolCalling.Q8_0.gguf"
)


def is_windows():
    return os.name == "nt"


def server_file_name():
    return "llama-server.exe" if is_windows() else "llama-server"


def find_server(explicit):
    if explicit:
        return pathlib.Path(explicit)
    repo = pathlib.Path(__file__).resolve().parent.parent
    candidates = [
        pathlib.Path.home() / "clankerjockey/.deps/llama.cpp/build/bin/llama-server",
        repo / ".deps/llama.cpp/build/bin/llama-server",
        pathlib.Path("D:/llama-cpp/llama-server.exe"),
    ]
    if is_windows():
        candidates.append(pathlib.Path("D:/llama-cpp/llama-server.exe"))
    for c in candidates:
        if c.exists():
            return c
    return None


def find_model(explicit):
    if explicit:
        return pathlib.Path(explicit)
    repo = pathlib.Path(__file__).resolve().parent.parent
    candidates = [
        pathlib.Path.home() / "clankerjockey/spikes/jni-inference/models" / MODEL_NAME,
        repo / "spikes/jni-inference/models" / MODEL_NAME,
    ]
    env = os.environ.get("CLANKERJOCKEY_TEST_MODEL")
    if env:
        candidates.insert(0, pathlib.Path(env))
    for c in candidates:
        if c.exists():
            return c
    return None


def download(url, dest):
    print(f"[bundle] downloading {url}")
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    with urllib.request.urlopen(url, timeout=600) as resp, open(tmp, "wb") as out:
        shutil.copyfileobj(resp, out)
    tmp.replace(dest)


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def verify(bundle_dir):
    """Start the bundled server briefly and confirm /health returns ok."""
    server = bundle_dir / "bin" / server_file_name()
    model = next((bundle_dir / "models").glob("*.gguf"), None)
    if not model:
        print("[bundle] verify skipped: no model in bundle")
        return False
    port = free_port()
    cmd = [str(server), "-m", str(model), "-c", "512", "-t", "2",
           "-np", "1", "--port", str(port), "--no-webui", "--jinja"]
    proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        deadline = time.time() + 60
        while time.time() < deadline:
            if proc.poll() is not None:
                print(f"[bundle] verify FAILED: server exited with code {proc.returncode}")
                return False
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=2) as r:
                    if r.status == 200 and b"ok" in r.read():
                        print("[bundle] verify OK: bundled llama-server is healthy")
                        return True
            except Exception:
                pass
            time.sleep(0.5)
        print("[bundle] verify FAILED: server did not become healthy in time")
        return False
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()


def main():
    ap = argparse.ArgumentParser(description="Assemble the ClankerJockey runtime bundle.")
    ap.add_argument("--target", required=True, help="game directory (where the mod runs)")
    ap.add_argument("--server", help="path to llama-server binary (auto-detected otherwise)")
    ap.add_argument("--model", help="path to the LittleLamb GGUF (auto-detected otherwise)")
    ap.add_argument("--download-model", action="store_true",
                    help="download the default model from Hugging Face if not found")
    ap.add_argument("--verify", action="store_true", help="start the bundled server and check /health")
    args = ap.parse_args()

    target = pathlib.Path(args.target)
    bundle = target / "clankerjockey"
    bin_dir = bundle / "bin"
    models_dir = bundle / "models"

    server = find_server(args.server)
    if server is None:
        print("[bundle] ERROR: could not find llama-server. Pass --server explicitly.")
        sys.exit(1)
    model = find_model(args.model)
    if model is None:
        if args.download_model:
            model = models_dir / MODEL_NAME
            download(MODEL_URL, model)
        else:
            print("[bundle] ERROR: could not find the model. Pass --model, or use --download-model.")
            sys.exit(1)

    bin_dir.mkdir(parents=True, exist_ok=True)
    models_dir.mkdir(parents=True, exist_ok=True)

    server_dest = bin_dir / server_file_name()
    print(f"[bundle] copying {server} -> {server_dest}")
    shutil.copy2(server, server_dest)
    if not is_windows():
        server_dest.chmod(server_dest.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)

    model_dest = models_dir / MODEL_NAME
    print(f"[bundle] copying {model} -> {model_dest}")
    shutil.copy2(model, model_dest)

    print(f"[bundle] done. Runtime bundle ready at:\n  {bundle}")
    if args.verify:
        return 0 if verify(bundle) else 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
