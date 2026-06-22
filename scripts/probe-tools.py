#!/usr/bin/env python3
"""
BurpMCP smoke probe.

Opens the SSE stream, performs the MCP initialize + notifications/initialized
handshake, calls tools/list, and prints the live tool roster.

Usage:
    python3 scripts/probe-tools.py              # default http://localhost:8181
    python3 scripts/probe-tools.py http://host:port

Exits 0 on success, 1 if the server isn't reachable or tools/list fails.

Requires: requests
"""
import json
import queue
import sys
import threading
from urllib.parse import urlparse

try:
    import requests
except ImportError:
    sys.exit("Install dependencies first: pip3 install requests")


def main(base: str = "http://localhost:8181") -> int:
    base = base.rstrip("/")
    sse_url = base + "/mcp/sse"
    q: "queue.Queue[str]" = queue.Queue()

    def reader() -> None:
        try:
            r = requests.get(sse_url, stream=True, timeout=15)
        except Exception as exc:
            q.put(f"__error__:{exc}")
            return
        for line in r.iter_lines():
            if not line:
                continue
            s = line.decode()
            if s.startswith("data:"):
                q.put(s[5:].strip())

    threading.Thread(target=reader, daemon=True).start()
    endpoint = q.get(timeout=5)
    if endpoint.startswith("__error__"):
        print(f"FAILED to connect to {sse_url}: {endpoint[10:]}", file=sys.stderr)
        return 1
    msg_url = base + endpoint if endpoint.startswith("/") else endpoint

    def rpc(method: str, params: dict | None = None, _id: int | None = None):
        msg = {"jsonrpc": "2.0", "method": method}
        if _id is not None:
            msg["id"] = _id
        if params is not None:
            msg["params"] = params
        requests.post(msg_url, json=msg, timeout=5)
        if _id is not None:
            return json.loads(q.get(timeout=10))
        return None

    rpc(
        "initialize",
        {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "burpmcp-probe", "version": "1.0"},
        },
        _id=1,
    )
    rpc("notifications/initialized")
    resp = rpc("tools/list", _id=2)
    tools = resp.get("result", {}).get("tools", [])
    names = sorted(t["name"] for t in tools)
    print(f"TOTAL: {len(names)}")
    for n in names:
        print(" -", n)
    return 0


if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8181"
    sys.exit(main(target))
