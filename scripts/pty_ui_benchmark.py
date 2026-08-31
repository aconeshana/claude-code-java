#!/usr/bin/env python3
"""Compare Claude Code Java TUI latency with the released 2.1.197 CLI.

The benchmark drives both applications through a real PTY with the same
terminal size, project directory, isolated configuration, and input sequence.
It measures from writing a key sequence to the first semantically complete
screen state, rather than sleeping for a fixed amount of time.

One-time setup (kept outside the repository build tree)::

    npm install --prefix /tmp/ccj-perf/official-2.1.197 \
      --no-package-lock --no-save @anthropic-ai/claude-code@2.1.197
    python3 -m pip install --target /tmp/ccj-perf/pydeps pexpect pyte psutil

Run::

    PYTHONPATH=/tmp/ccj-perf/pydeps \
      python3 scripts/pty_ui_benchmark.py --warmups 2 --runs 30

Use at least 20 measured runs when treating P95 as an acceptance gate. With
15 samples nearest-rank P95 equals the single maximum and overweights one host
scheduler pause; 30 samples makes it the second-highest observation.
"""

from __future__ import annotations

import argparse
import codecs
import json
import math
import os
from pathlib import Path
import random
import statistics
import sys
import tempfile
import time
from dataclasses import asdict, dataclass
from typing import Callable, Iterable


DEFAULT_PYDEPS = Path(os.environ.get("CCJ_PERF_PYDEPS", "/tmp/ccj-perf/pydeps"))
if DEFAULT_PYDEPS.is_dir():
    sys.path.insert(0, str(DEFAULT_PYDEPS))

try:
    import pexpect  # type: ignore
    import psutil  # type: ignore
    import pyte  # type: ignore
except ModuleNotFoundError as error:  # pragma: no cover - setup diagnostic
    raise SystemExit(
        f"Missing PTY benchmark dependency {error.name!r}. "
        "Install pexpect, pyte, and psutil as shown in this script's header."
    ) from error


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OFFICIAL = Path(
    os.environ.get(
        "CCJ_OFFICIAL_197",
        "/tmp/ccj-perf/official-2.1.197/node_modules/.bin/claude",
    )
)
DEFAULT_JAR = ROOT / "claude-code-app/build/libs/claude-code-app-0.1.0-SNAPSHOT.jar"
ROWS = 40
COLS = 120


@dataclass(frozen=True)
class Measurement:
    target: str
    run: int
    case: str
    latency_ms: float
    output_bytes: int
    success: bool
    detail: str = ""


@dataclass(frozen=True)
class Summary:
    target: str
    case: str
    samples: int
    success_rate: float
    median_ms: float
    p95_ms: float
    median_output_bytes: float


class TerminalCapture:
    def __init__(self, started: float) -> None:
        self.started = started
        self.first_byte_seconds: float | None = None
        self.total_bytes = 0
        self.screen = pyte.Screen(COLS, ROWS)
        self.stream = pyte.Stream(self.screen)
        self.decoder = codecs.getincrementaldecoder("utf-8")("replace")

    def write(self, data: bytes | str) -> None:
        encoded = data.encode() if isinstance(data, str) else data
        if self.first_byte_seconds is None:
            self.first_byte_seconds = time.monotonic() - self.started
        self.total_bytes += len(encoded)
        self.stream.feed(self.decoder.decode(encoded))

    def flush(self) -> None:
        pass

    def lines(self) -> list[str]:
        return [line.rstrip() for line in self.screen.display]

    def text(self) -> str:
        return "\n".join(self.lines())

    def cursor_line(self) -> str:
        return self.lines()[self.screen.cursor.y].strip()

    def bottom_prompt(self) -> str:
        line = self.cursor_line()
        return line if "❯" in line else ""

    def selected_line(self) -> str:
        candidates = [line.strip() for line in self.lines() if "❯" in line]
        return candidates[-1] if candidates else ""


class PtySession:
    def __init__(
        self,
        target: str,
        executable: str,
        args: list[str],
        config: Path,
        cwd: Path = ROOT,
        extra_env: dict[str, str] | None = None,
    ) -> None:
        self.target = target
        env = os.environ.copy()
        env.pop("ANTHROPIC_API_KEY", None)
        env.update(
            {
                "CLAUDE_CONFIG_DIR": str(config),
                "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1",
                "CLAUDE_CODE_DISABLE_AUTO_MEMORY": "1",
                "DISABLE_AUTOUPDATER": "1",
                "TERM": "xterm-256color",
                "COLORTERM": "truecolor",
            }
        )
        if extra_env:
            env.update(extra_env)
        self.started = time.monotonic()
        self.child = pexpect.spawn(
            executable,
            args,
            cwd=str(cwd),
            env=env,
            dimensions=(ROWS, COLS),
            encoding=None,
            timeout=0.001,
        )
        # pexpect defaults to a 50 ms pre-send delay intended for fragile
        # interactive programs. Including that artificial sleep made every UI
        # case look roughly 50 ms slower and obscured sub-frame differences.
        self.child.delaybeforesend = 0
        self.capture = TerminalCapture(self.started)
        self.child.logfile_read = self.capture

    def close(self) -> None:
        if self.child.isalive():
            try:
                self.child.sendcontrol("c")
                time.sleep(0.03)
            finally:
                self.child.terminate(force=True)

    def pump(self, timeout_seconds: float = 0.001) -> None:
        try:
            self.child.read_nonblocking(65536, timeout=timeout_seconds)
        except pexpect.TIMEOUT:
            pass
        except pexpect.EOF:
            pass

    def wait_for(self, predicate: Callable[[TerminalCapture], bool], timeout: float) -> float:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            self.pump()
            if predicate(self.capture):
                return time.monotonic()
            if not self.child.isalive():
                break
        raise TimeoutError("screen predicate was not satisfied")

    def settle_output(self, quiet_seconds: float = 0.03, timeout: float = 0.3) -> None:
        """Drain repaint bytes so one case cannot inflate the next case."""
        deadline = time.monotonic() + timeout
        quiet_since = time.monotonic()
        previous_bytes = self.capture.total_bytes
        while time.monotonic() < deadline:
            self.pump(min(quiet_seconds, 0.001))
            current_bytes = self.capture.total_bytes
            if current_bytes != previous_bytes:
                previous_bytes = current_bytes
                quiet_since = time.monotonic()
            elif time.monotonic() - quiet_since >= quiet_seconds:
                return

    def step(
        self,
        run: int,
        case: str,
        payload: bytes,
        predicate: Callable[[TerminalCapture], bool],
        timeout: float = 2.0,
        detail: Callable[[TerminalCapture], str] | None = None,
    ) -> Measurement:
        before_bytes = self.capture.total_bytes
        before = time.monotonic()
        self.child.send(payload)
        try:
            completed = self.wait_for(predicate, timeout)
            measurement = Measurement(
                self.target,
                run,
                case,
                (completed - before) * 1000.0,
                self.capture.total_bytes - before_bytes,
                True,
                detail(self.capture) if detail else "",
            )
            self.settle_output()
            return measurement
        except TimeoutError:
            screen = " | ".join(line for line in self.capture.lines() if line)
            return Measurement(
                self.target,
                run,
                case,
                timeout * 1000.0,
                self.capture.total_bytes - before_bytes,
                False,
                screen[-1200:],
            )

    def rss_megabytes(self) -> float:
        try:
            process = psutil.Process(self.child.pid)
            total = process.memory_info().rss
            for descendant in process.children(recursive=True):
                try:
                    total += descendant.memory_info().rss
                except psutil.Error:
                    pass
            return total / (1024.0 * 1024.0)
        except psutil.Error:
            return 0.0


def isolated_config(directory: Path, workspace: Path = ROOT) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    global_config = {
        "theme": "dark",
        "hasCompletedOnboarding": True,
        # A syntactically plausible placeholder keeps both CLIs on their normal
        # authenticated main screen. No benchmark case sends a model request.
        "primaryApiKey": "sk-ant-api03-benchmark-placeholder-key",
        "projects": {str(workspace.resolve()): {"hasTrustDialogAccepted": True}},
    }
    (directory / ".claude.json").write_text(json.dumps(global_config), encoding="utf-8")
    (directory / "settings.json").write_text("{}\n", encoding="utf-8")


def create_heavy_inventory(config: Path, scratch: Path) -> Path:
    """Build a deterministic local/plugin inventory outside measured time."""
    user_skills = config / "skills"
    user_agents = config / "agents"
    for index in range(160):
        skill = user_skills / f"benchmark-user-{index:03d}" / "SKILL.md"
        skill.parent.mkdir(parents=True, exist_ok=True)
        skill.write_text(
            "---\n"
            f"name: benchmark-user-{index:03d}\n"
            f"description: Benchmark user skill {index:03d}\n"
            "argument-hint: [value]\n"
            "---\n"
            f"Run benchmark user skill {index:03d} with $ARGUMENTS.\n",
            encoding="utf-8",
        )
    user_agents.mkdir(parents=True, exist_ok=True)
    for index in range(40):
        (user_agents / f"benchmark-agent-{index:03d}.md").write_text(
            "---\n"
            f"name: benchmark-agent-{index:03d}\n"
            f"description: Benchmark agent {index:03d}\n"
            "---\n"
            "Handle deterministic benchmark work.\n",
            encoding="utf-8",
        )

    plugin = scratch / "benchmark-heavy-plugin"
    (plugin / ".claude-plugin").mkdir(parents=True, exist_ok=True)
    (plugin / ".claude-plugin" / "plugin.json").write_text(
        json.dumps({"name": "benchmark-heavy", "version": "1.0.0"}),
        encoding="utf-8",
    )
    for index in range(120):
        command = plugin / "commands" / f"command-{index:03d}.md"
        command.parent.mkdir(parents=True, exist_ok=True)
        command.write_text(
            "---\n"
            f"description: Benchmark plugin command {index:03d}\n"
            "argument-hint: [value]\n"
            "---\n"
            f"Run plugin command {index:03d} with $ARGUMENTS.\n",
            encoding="utf-8",
        )
    for index in range(80):
        skill = plugin / "skills" / f"skill-{index:03d}" / "SKILL.md"
        skill.parent.mkdir(parents=True, exist_ok=True)
        skill.write_text(
            "---\n"
            f"name: plugin-skill-{index:03d}\n"
            f"description: Benchmark plugin skill {index:03d}\n"
            "---\n"
            f"Run plugin skill {index:03d}.\n",
            encoding="utf-8",
        )
    return plugin


def prompt_contains(expected: str) -> Callable[[TerminalCapture], bool]:
    return lambda capture: expected in capture.bottom_prompt()


def prompt_is_empty(capture: TerminalCapture) -> bool:
    prompt = capture.bottom_prompt().replace("\u00a0", " ").strip()
    return prompt == "❯" and (
        "shortcuts" in capture.text() or capture.screen.cursor.y >= ROWS - 6
    )


def search_query_is(capture: TerminalCapture, expected: str) -> bool:
    """Match the search value without confusing a value with its prefix.

    Java renders the cursor block immediately after the value, while the
    official UI pads the value inside a bordered row. Both are semantic
    boundaries; an alphanumeric continuation is not.
    """
    marker = "⌕ "
    for line in capture.lines():
        offset = line.find(marker)
        if offset < 0:
            continue
        suffix = line[offset + len(marker):].lstrip()
        if suffix.startswith(expected) and (
            len(suffix) == len(expected) or suffix[len(expected)] in " █│"
        ):
            return True
    return False


def ready(capture: TerminalCapture) -> bool:
    return prompt_is_empty(capture)


def run_session(
    target: str,
    executable: str,
    args: list[str],
    run: int,
    startup_timeout: float,
) -> list[Measurement]:
    results: list[Measurement] = []
    with tempfile.TemporaryDirectory(prefix=f"ccj-pty-{target}-") as temp:
        config = Path(temp) / "config"
        isolated_config(config)
        session = PtySession(target, executable, args, config)
        try:
            startup_before_bytes = session.capture.total_bytes
            try:
                ready_at = session.wait_for(ready, startup_timeout)
                first_byte = session.capture.first_byte_seconds or (ready_at - session.started)
                results.append(
                    Measurement(target, run, "startup_first_byte", first_byte * 1000.0,
                                session.capture.total_bytes - startup_before_bytes, True)
                )
                results.append(
                    Measurement(target, run, "startup_ready", (ready_at - session.started) * 1000.0,
                                session.capture.total_bytes - startup_before_bytes, True,
                                f"rss_mb={session.rss_megabytes():.1f}")
                )
                session.settle_output()
            except TimeoutError:
                screen = " | ".join(line for line in session.capture.lines() if line)
                results.append(Measurement(target, run, "startup_ready", startup_timeout * 1000.0,
                                           session.capture.total_bytes, False, screen[-1200:]))
                return results

            results.append(session.step(run, "type_character", b"x", prompt_contains("x")))
            results.append(session.step(run, "backspace_character", b"\x7f", prompt_is_empty))

            burst = b"abcdefghijklmnopqrstuvwxyz012345"
            results.append(session.step(run, "type_burst_32", burst,
                                        prompt_contains(burst.decode("ascii"))))
            results.append(session.step(run, "backspace_burst_32", b"\x7f" * len(burst),
                                        prompt_is_empty))

            results.append(session.step(run, "slash_open", b"/", lambda c: "/add-dir" in c.text()))
            results.append(session.step(run, "slash_filter_config", b"config",
                                        lambda c: "/config" in c.bottom_prompt()
                                        and "/update-config" in c.text()))
            results.append(session.step(run, "slash_backspace_config", b"\x7f" * 6,
                                        lambda c: "/add-dir" in c.text()
                                        and "/agents" in c.text()
                                        and "/update-config" not in c.text()))
            results.append(session.step(run, "slash_filter_model", b"model",
                                        lambda c: "/model" in c.bottom_prompt()
                                        and "Set the AI model" in c.text()))
            results.append(session.step(run, "model_open", b"\r",
                                        lambda c: "Select model" in c.text()
                                        and "Enter" in c.text()))

            previous_selection = session.capture.selected_line()
            # Both implementations open on Default with an isolated empty
            # settings file. Move to Opus, then confirm a normal model row.
            results.append(session.step(run, "model_move", b"\x1b[B",
                                        lambda c: c.selected_line() != previous_selection
                                        and "Select model" in c.text()))
            results.append(session.step(
                run,
                "model_confirm",
                b"\r",
                lambda c: "Select model" not in c.text()
                and ("Set model to" in c.text() or "Authentication failed" in c.text()),
                timeout=3.0,
                detail=lambda c: "authentication_error" if "Authentication failed" in c.text() else "ok",
            ))

            # Wait for the empty prompt after the model breadcrumb before the
            # next command. A failure is already recorded above; do not cascade.
            try:
                session.wait_for(prompt_is_empty, 1.0)
            except TimeoutError:
                return results

            results.append(session.step(run, "config_type", b"/config",
                                        prompt_contains("/config")))
            results.append(session.step(run, "config_open", b"\r",
                                        lambda c: "Auto-compact" in c.text()
                                        and ("Settings" in c.text() or "Config" in c.text()),
                                        timeout=3.0))
            results.append(session.step(run, "config_search_model", b"model",
                                        lambda c: "model" in c.text().lower()
                                        and "Auto-compact" not in c.text()))
            results.append(session.step(run, "config_backspace_search", b"\x7f",
                                        lambda c: search_query_is(c, "mode")))
            results.append(session.step(run, "config_restore_search", b"l",
                                        lambda c: search_query_is(c, "model")))
            results.append(session.step(run, "config_clear_search", b"\x1b",
                                        lambda c: "Auto-compact" in c.text()
                                        and "⌕ model" not in c.text()))
            results.append(session.step(run, "config_exit_search", b"\x1b",
                                        lambda c: "Auto-compact" in c.text()
                                        and ("Esc to close" in c.text()
                                             or "Esc cancel" in c.text())))
            results.append(session.step(run, "config_close", b"\x1b",
                                        lambda c: "Auto-compact" not in c.text()
                                        and prompt_is_empty(c)))

            results.append(session.step(run, "file_suggestion", b"@build",
                                        lambda c: "build.gradle.kts" in c.text(), timeout=3.0))
            results.append(session.step(run, "file_suggestion_clear", b"\x7f" * 6,
                                        prompt_is_empty))

            send_text = "benchmark local send"
            results.append(session.step(run, "send_type", send_text.encode("ascii"),
                                        prompt_contains(send_text)))
            results.append(session.step(run, "send_accept", b"\r",
                                        lambda c: send_text in c.text()
                                        and send_text not in c.bottom_prompt(),
                                        timeout=3.0))
            return results
        finally:
            session.close()


def run_heavy_inventory_session(
    target: str,
    executable: str,
    args: list[str],
    run: int,
    startup_timeout: float,
) -> list[Measurement]:
    results: list[Measurement] = []
    with tempfile.TemporaryDirectory(prefix=f"ccj-heavy-{target}-") as temp:
        scratch = Path(temp)
        config = scratch / "config"
        isolated_config(config)
        plugin = create_heavy_inventory(config, scratch)
        session = PtySession(
            target,
            executable,
            args + ["--plugin-dir", str(plugin)],
            config,
        )
        try:
            startup_before_bytes = session.capture.total_bytes
            try:
                ready_at = session.wait_for(ready, startup_timeout)
                first_byte = session.capture.first_byte_seconds or (ready_at - session.started)
                results.append(Measurement(
                    target, run, "heavy_startup_first_byte", first_byte * 1000.0,
                    session.capture.total_bytes - startup_before_bytes, True))
                results.append(Measurement(
                    target, run, "heavy_startup_ready",
                    (ready_at - session.started) * 1000.0,
                    session.capture.total_bytes - startup_before_bytes, True,
                    f"rss_mb={session.rss_megabytes():.1f}"))
                session.settle_output()
            except TimeoutError:
                screen = " | ".join(line for line in session.capture.lines() if line)
                results.append(Measurement(
                    target, run, "heavy_startup_ready", startup_timeout * 1000.0,
                    session.capture.total_bytes, False, screen[-1200:]))
                return results

            results.append(session.step(
                run, "heavy_slash_open", b"/", lambda c: "/add-dir" in c.text()))
            results.append(session.step(
                run,
                "heavy_slash_filter",
                b"benchmark-heavy:command-119",
                lambda c: "/benchmark-heavy:command-119" in c.bottom_prompt()
                and "Benchmark plugin command 119" in c.text(),
                timeout=3.0,
            ))
            return results
        finally:
            session.close()


def percentile(values: Iterable[float], quantile: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return math.nan
    index = max(0, min(len(ordered) - 1, math.ceil(quantile * len(ordered)) - 1))
    return ordered[index]


def summarize(measurements: list[Measurement]) -> list[Summary]:
    grouped: dict[tuple[str, str], list[Measurement]] = {}
    for item in measurements:
        grouped.setdefault((item.target, item.case), []).append(item)
    summaries: list[Summary] = []
    for (target, case), items in sorted(grouped.items()):
        successful = [item for item in items if item.success]
        values = [item.latency_ms for item in successful]
        output = [item.output_bytes for item in successful]
        summaries.append(
            Summary(
                target,
                case,
                len(items),
                len(successful) / len(items),
                statistics.median(values) if values else math.nan,
                percentile(values, 0.95),
                statistics.median(output) if output else math.nan,
            )
        )
    return summaries


def markdown_report(
    summaries: list[Summary],
    candidate_target: str,
    candidate_label: str,
) -> str:
    by_case: dict[str, dict[str, Summary]] = {}
    for summary in summaries:
        by_case.setdefault(summary.case, {})[summary.target] = summary
    lines = [
        f"# PTY UI benchmark: {candidate_label} vs Claude Code 2.1.197",
        "",
        f"Terminal: `{COLS}x{ROWS}`. Lower latency and fewer output bytes are better.",
        "",
        f"| Case | Official median / P95 | {candidate_label} median / P95 "
        f"| {candidate_label} ÷ official | Result |",
        "|---|---:|---:|---:|:---:|",
    ]
    for case in sorted(by_case):
        official = by_case[case].get("official197")
        candidate = by_case[case].get(candidate_target)
        if official is None or candidate is None:
            continue
        ratio = candidate.median_ms / official.median_ms if official.median_ms > 0 else math.inf
        passed = (
            candidate.success_rate == 1.0
            and official.success_rate == 1.0
            and candidate.median_ms < official.median_ms
            and candidate.p95_ms < official.p95_ms
        )
        lines.append(
            f"| `{case}` | {official.median_ms:.2f} / {official.p95_ms:.2f} ms "
            f"| {candidate.median_ms:.2f} / {candidate.p95_ms:.2f} ms | {ratio:.2f}x "
            f"| {'PASS' if passed else 'OPTIMIZE'} |"
        )
    lines.extend(
        [
            "",
            "## Output volume",
            "",
            f"| Case | Official median bytes | {candidate_label} median bytes |",
            "|---|---:|---:|",
        ]
    )
    for case in sorted(by_case):
        official = by_case[case].get("official197")
        candidate = by_case[case].get(candidate_target)
        if official is not None and candidate is not None:
            lines.append(
                f"| `{case}` | {official.median_output_bytes:.0f} | "
                f"{candidate.median_output_bytes:.0f} |"
            )
    return "\n".join(lines) + "\n"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--official", type=Path, default=DEFAULT_OFFICIAL)
    parser.add_argument("--java-jar", type=Path, default=DEFAULT_JAR)
    parser.add_argument("--native-executable", type=Path,
                        help="Benchmark a GraalVM native executable instead of the JVM JAR")
    parser.add_argument("--java-archive", type=Path,
                        help="Optional AppCDS archive used by the Java target")
    parser.add_argument("--java-option", action="append", default=[],
                        help="Additional JVM option; may be repeated")
    parser.add_argument("--warmups", type=int, default=1)
    parser.add_argument("--runs", type=int, default=30)
    parser.add_argument("--seed", type=int, default=197)
    parser.add_argument("--startup-timeout", type=float, default=8.0,
                        help="Seconds to wait for the normal inventory input-ready screen")
    parser.add_argument("--heavy-startup-timeout", type=float, default=12.0,
                        help="Seconds to wait for the heavy inventory input-ready screen")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "build/pty-ui-benchmark")
    return parser.parse_args()


def main() -> int:
    options = parse_args()
    if not options.official.exists():
        raise SystemExit(f"Official 2.1.197 executable not found: {options.official}")
    if options.native_executable and not options.native_executable.exists():
        raise SystemExit(f"Native executable not found: {options.native_executable}")
    if not options.native_executable and not options.java_jar.exists():
        raise SystemExit(f"Java application JAR not found: {options.java_jar}")

    candidate_target = "native" if options.native_executable else "java"
    candidate_label = "GraalVM native" if options.native_executable else "Java"
    candidate = (
        str(options.native_executable),
        ["--strict-mcp-config"],
    ) if options.native_executable else (
        "java",
        list(options.java_option)
        + ([f"-XX:SharedArchiveFile={options.java_archive}"]
           if options.java_archive else [])
        + ["-jar", str(options.java_jar), "--strict-mcp-config"],
    )
    targets = {
        "official197": (
            str(options.official),
            ["--strict-mcp-config", "--mcp-config", '{"mcpServers":{}}'],
        ),
        candidate_target: candidate,
    }
    rng = random.Random(options.seed)

    for warmup in range(options.warmups):
        order = list(targets)
        rng.shuffle(order)
        for target in order:
            executable, arguments = targets[target]
            run_session(target, executable, arguments, -(warmup + 1),
                        options.startup_timeout)
            run_heavy_inventory_session(
                target, executable, arguments, -(warmup + 1),
                options.heavy_startup_timeout)

    measurements: list[Measurement] = []
    for run in range(options.runs):
        order = list(targets)
        rng.shuffle(order)
        for target in order:
            executable, arguments = targets[target]
            print(f"run {run + 1}/{options.runs}: {target}", flush=True)
            measurements.extend(run_session(
                target, executable, arguments, run, options.startup_timeout))
            measurements.extend(run_heavy_inventory_session(
                target, executable, arguments, run, options.heavy_startup_timeout))

    summaries = summarize(measurements)
    options.output_dir.mkdir(parents=True, exist_ok=True)
    raw_path = options.output_dir / "measurements.json"
    report_path = options.output_dir / "report.md"
    raw_path.write_text(
        json.dumps(
            {
                "terminal": {"columns": COLS, "rows": ROWS},
                "measurements": [asdict(item) for item in measurements],
                "summaries": [asdict(item) for item in summaries],
            },
            indent=2,
            ensure_ascii=False,
        )
        + "\n",
        encoding="utf-8",
    )
    report_path.write_text(
        markdown_report(summaries, candidate_target, candidate_label),
        encoding="utf-8",
    )
    print(report_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
