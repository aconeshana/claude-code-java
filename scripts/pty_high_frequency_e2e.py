#!/usr/bin/env python3
"""Run one high-frequency interactive scenario against Java and Claude Code 2.1.197.

The case uses a real PTY and loopback HTTP server. It covers startup readiness,
slash-command filtering, the config overlay, file suggestions, a streamed model
turn, the restored empty prompt, and the model-visible tool catalogue sent on
the wire. Each target gets an isolated config and workspace.

Run from the repository root::

    PYTHONPATH=/tmp/ccj-perf/pydeps python3 scripts/pty_high_frequency_e2e.py \
      --official ~/.local/share/claude/versions/2.1.197 \
      --java-jar claude-code-app/build/libs/claude-code-app-0.1.0-SNAPSHOT.jar
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

from pty_ui_benchmark import (
    DEFAULT_JAR,
    PtySession,
    isolated_config,
    prompt_contains,
    prompt_is_empty,
    ready,
)


DEFAULT_OFFICIAL = Path.home() / ".local" / "share" / "claude" / "versions" / "2.1.197"
MARKER = "PONG_FROM_HIGH_FREQUENCY_E2E"


def event(name: str, payload: dict[str, object]) -> str:
    return f"event: {name}\ndata: {json.dumps(payload, separators=(',', ':'))}\n\n"


TURN = "".join(
    [
        event(
            "message_start",
            {
                "type": "message_start",
                "message": {
                    "id": "msg_high_frequency_e2e",
                    "type": "message",
                    "role": "assistant",
                    "model": "claude-opus-5",
                    "content": [],
                    "stop_reason": None,
                    "stop_sequence": None,
                    "usage": {"input_tokens": 1, "output_tokens": 1},
                },
            },
        ),
        event(
            "content_block_start",
            {
                "type": "content_block_start",
                "index": 0,
                "content_block": {"type": "text", "text": ""},
            },
        ),
        event(
            "content_block_delta",
            {
                "type": "content_block_delta",
                "index": 0,
                "delta": {"type": "text_delta", "text": MARKER},
            },
        ),
        event("content_block_stop", {"type": "content_block_stop", "index": 0}),
        event(
            "message_delta",
            {
                "type": "message_delta",
                "delta": {"stop_reason": "end_turn", "stop_sequence": None},
                "usage": {"output_tokens": 1},
            },
        ),
        event("message_stop", {"type": "message_stop"}),
    ]
).encode()


class FakeAnthropicServer:
    def __init__(self) -> None:
        self.requests: list[dict[str, object]] = []
        owner = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, _format: str, *_args: object) -> None:
                return

            def do_POST(self) -> None:
                length = int(self.headers.get("content-length", "0"))
                raw = self.rfile.read(length)
                try:
                    body: object = json.loads(raw)
                except json.JSONDecodeError:
                    body = raw.decode("utf-8", errors="replace")
                owner.requests.append({"path": self.path, "body": body})
                if urlsplit(self.path).path.endswith("/v1/messages"):
                    self.send_response(200)
                    self.send_header("content-type", "text/event-stream")
                    self.send_header("cache-control", "no-cache")
                    self.send_header("connection", "close")
                    self.end_headers()
                    self.wfile.write(TURN)
                    self.wfile.flush()
                    self.close_connection = True
                    return
                payload = b"{}"
                self.send_response(200)
                self.send_header("content-type", "application/json")
                self.send_header("content-length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self) -> "FakeAnthropicServer":
        self.thread.start()
        return self

    def __exit__(self, *_args: object) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self.server.server_address[1]}"


def latest_model_request(requests: list[dict[str, object]]) -> dict[str, object]:
    for request in reversed(requests):
        body = request.get("body")
        if isinstance(body, dict) and isinstance(body.get("messages"), list):
            return body
    raise AssertionError("target sent no Messages API request")


def run_target(
    target: str,
    executable: str,
    arguments: list[str],
    root: Path,
) -> dict[str, object]:
    workspace = root / "workspace"
    config = root / "config"
    workspace.mkdir(parents=True)
    (workspace / "build.gradle.kts").write_text("plugins { java }\n", encoding="utf-8")
    isolated_config(config, workspace)

    with FakeAnthropicServer() as fake:
        session = PtySession(
            target,
            executable,
            arguments,
            config,
            cwd=workspace,
            extra_env={
                "ANTHROPIC_BASE_URL": fake.base_url,
                "DISABLE_AUTOUPDATER": "1",
            },
        )
        try:
            session.wait_for(ready, 15.0)
            assert session.step(0, "slash_open", b"/", lambda c: "/config" in c.text()).success
            assert session.step(
                0,
                "slash_filter_config",
                b"config",
                lambda c: "/config" in c.bottom_prompt(),
            ).success
            assert session.step(
                0,
                "config_open",
                b"\r",
                lambda c: "Auto-compact" in c.text(),
                timeout=4.0,
            ).success
            session.child.send(b"\x1b\x1b\x1b")
            session.wait_for(prompt_is_empty, 3.0)

            assert session.step(
                0,
                "file_suggestion",
                b"@build",
                lambda c: "build.gradle.kts" in c.text(),
                timeout=3.0,
            ).success
            session.child.send(b"\x7f" * 6)
            session.wait_for(prompt_is_empty, 2.0)

            prompt = "high frequency e2e ping"
            assert session.step(0, "prompt_type", prompt.encode(), prompt_contains(prompt)).success
            session.child.send(b"\r")
            session.wait_for(lambda c: MARKER in c.text(), 8.0)
            session.wait_for(prompt_is_empty, 3.0)

            session.child.send(b"/cost\r")
            session.wait_for(
                lambda c: "Usage by model:" in c.text() and "Total cost:" in c.text(),
                4.0,
            )

            request = latest_model_request(fake.requests)
            messages = request.get("messages")
            assert prompt in json.dumps(messages, ensure_ascii=False)
            tools = request.get("tools")
            tool_names = [tool.get("name") for tool in tools] if isinstance(tools, list) else []
            for required in ("Bash", "Read", "Edit", "Write", "Agent"):
                assert required in tool_names, f"{target} missing required tool {required}"
            return {
                "target": target,
                "model": request.get("model"),
                "toolCount": len(tool_names),
                "toolNames": tool_names,
                "requestCount": len(fake.requests),
                "finalScreen": session.capture.text(),
            }
        finally:
            session.close()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--official", type=Path, default=DEFAULT_OFFICIAL)
    parser.add_argument("--java-jar", type=Path, default=DEFAULT_JAR)
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def main() -> int:
    options = parse_args()
    if not options.official.exists():
        raise SystemExit(f"Official 2.1.197 executable not found: {options.official}")
    if not options.java_jar.exists():
        raise SystemExit(f"Java application JAR not found: {options.java_jar}")

    with tempfile.TemporaryDirectory(prefix="ccj-high-frequency-e2e-") as temp:
        root = Path(temp)
        results = [
            run_target(
                "official197",
                str(options.official),
                ["--strict-mcp-config", "--mcp-config", '{"mcpServers":{}}'],
                root / "official197",
            ),
            run_target(
                "java",
                os.environ.get("JAVA", "java"),
                ["-jar", str(options.java_jar), "--strict-mcp-config"],
                root / "java",
            ),
        ]

    report = {"marker": MARKER, "results": results}
    rendered = json.dumps(report, indent=2, ensure_ascii=False) + "\n"
    if options.output:
        options.output.parent.mkdir(parents=True, exist_ok=True)
        options.output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
