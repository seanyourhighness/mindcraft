#!/usr/bin/env python3
"""Minimal live dashboard for the MindCraft vLLM server.

Serves a single-page dashboard at http://localhost:8765 and proxies the
vLLM Prometheus /metrics endpoint as JSON so the page can render current
decode / prompt / per-sequence / draft-acceptance stats live.

Usage:
    python server.py [--port 8765] [--metrics-url http://localhost:8016/metrics]

Environment overrides:
    DASHBOARD_PORT, VLLM_METRICS_URL
"""

import json
import os
import re
import sys
import threading
import time
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DEFAULT_PORT = 8765
DEFAULT_METRICS_URL = "http://localhost:8016/metrics"

METRIC_RE = re.compile(r"^(vllm:[A-Za-z0-9_:]+)(?:\{([^}]*)\})?\s+([-+0-9.eE]+)\s*$")

# Metric names we care about. Histograms expose both _sum and _count.
WANTED = {
    "generation_tokens_total",
    "prompt_tokens_total",
    "num_requests_running",
    "num_requests_waiting",
    "kv_cache_usage_perc",
    "prefix_cache_hits_total",
    "prefix_cache_queries_total",
    "spec_decode_num_drafts_total",
    "spec_decode_num_accepted_tokens_total",
    "inter_token_latency_seconds_sum",
    "inter_token_latency_seconds_count",
    "time_to_first_token_seconds_sum",
    "time_to_first_token_seconds_count",
    "request_generation_tokens_sum",
    "request_generation_tokens_count",
    "request_decode_time_seconds_sum",
    "request_decode_time_seconds_count",
    "request_success_total",
    "engine_sleep_state",
}


def parse_labels(labels):
    out = {}
    for part in labels.split(","):
        k, _, v = part.partition("=")
        out[k] = v.strip('"')
    return out


def parse_metrics(text):
    """Return {model, values} with the counters/gauges the dashboard needs."""
    values = {}
    model = "unknown"
    for line in text.splitlines():
        m = METRIC_RE.match(line)
        if not m:
            continue
        name, labels, value = m.group(1), m.group(2) or "", float(m.group(3))
        if name == "vllm:spec_decode_num_accepted_tokens_per_pos_total":
            pos = parse_labels(labels).get("position")
            if pos is not None:
                values.setdefault("spec_decode_accepted_per_pos", {})[int(pos)] = value
            continue
        if name == "vllm:request_success_total":
            values["request_success_total"] = (
                values.get("request_success_total", 0.0) + value
            )
            continue
        if name == "vllm:engine_sleep_state":
            if parse_labels(labels).get("sleep_state") == "awake":
                values["engine_awake"] = value
            continue
        short = name[len("vllm:"):]
        if short in WANTED:
            values[short] = value
            if model == "unknown":
                model = parse_labels(labels).get("model_name", "unknown")



    if "spec_decode_accepted_per_pos" in values:
        pos_map = values.pop("spec_decode_accepted_per_pos")
        values["spec_decode_accepted_per_pos"] = [
            pos_map[i] for i in sorted(pos_map)
        ]
    return {"model": model, "values": values}


def fetch_metrics():
    with urllib.request.urlopen(METRICS_URL, timeout=5) as resp:
        return parse_metrics(resp.read().decode("utf-8", "replace"))


class Handler(BaseHTTPRequestHandler):
    def handle_one_request(self):
        try:
            super().handle_one_request()
        except Exception:
            pass  # client aborted mid-request

    def handle_one_request(self):
        try:
            super().handle_one_request()
        except Exception:
            pass  # client aborted mid-request

    def handle_one_request(self):
        try:
            super().handle_one_request()
        except Exception:
            pass  # client aborted mid-request

    def handle_one_request(self):
        try:
            super().handle_one_request()
        except Exception:
            pass  # client aborted mid-request

    def handle_one_request(self):
        try:
            super().handle_one_request()
        except Exception:
            pass  # client aborted mid-request

    def handle_one_request(self):
        try:
            super().handle_one_request()
        except Exception:
            pass  # client aborted mid-request

    def log_message(self, *args):
        pass

    def _send(self, code, body, content_type):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path in ("/", "/index.html"):
            body = (ROOT / "index.html").read_bytes()
            self._send(200, body, "text/html; charset=utf-8")
        elif self.path == "/api/metrics":
            try:
                payload = json.dumps(
                    {"ts": time.time(), **fetch_metrics()}
                ).encode()
                self._send(200, payload, "application/json")
            except Exception as exc:  # noqa: BLE001 - report upstream failure to the page
                payload = json.dumps(
                    {"ts": time.time(), "error": str(exc)}
                ).encode()
                self._send(502, payload, "application/json")
        elif self.path == "/favicon.ico":
            self._send(204, b"", "image/x-icon")
        else:
            self._send(404, b"not found", "text/plain")


def main():
    args = sys.argv[1:]
    port = DEFAULT_PORT
    url = DEFAULT_METRICS_URL
    for i, arg in enumerate(args):
        if arg == "--port" and i + 1 < len(args):
            port = int(args[i + 1])
        if arg == "--metrics-url" and i + 1 < len(args):
            url = args[i + 1]
    port = int(os.environ.get("DASHBOARD_PORT", port))
    url = os.environ.get("VLLM_METRICS_URL", url)
    no_browser = "--no-browser" in args
    global METRICS_URL
    METRICS_URL = url
    httpd = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"vLLM dashboard: http://localhost:{port}")
    print(f"metrics source: {url}")
    if not no_browser:
        threading.Timer(0.8, lambda: webbrowser.open(f"http://localhost:{port}")).start()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped")


if __name__ == "__main__":
    main()
