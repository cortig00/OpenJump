#!/usr/bin/env python3
"""Read-only, best-effort readiness evidence; never a simulator health certificate."""

import argparse
import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time
import uuid

BUDGET_SECONDS = 40
COMMAND_SECONDS = 7
CAPTURE_BYTES = 65536


def timestamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds")


def clean(value):
    text = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", str(value))
    return "".join(c for c in text if c.isascii() and c.isprintable())[:160]


def run_probe(command, timeout):
    result = {"command": command, "started_utc": timestamp(), "exit_code": None,
              "timeout_seconds": timeout, "timed_out": False,
              "stdout_capture_truncated": False, "stdout_capture_complete": False,
              "stderr_discarded_bytes": 0, "stderr_drain_complete": False,
              "status": "unknown"}
    captured = bytearray()
    readers = []
    try:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

        def drain_stdout():
            with process.stdout:
                while True:
                    block = process.stdout.read(4096)
                    if not block:
                        result["stdout_capture_complete"] = True
                        break
                    remaining = CAPTURE_BYTES - len(captured)
                    captured.extend(block[:remaining])
                    if len(block) > remaining:
                        result["stdout_capture_truncated"] = True

        def drain_stderr():
            with process.stderr:
                while True:
                    block = process.stderr.read(1024)
                    if not block:
                        result["stderr_drain_complete"] = True
                        break
                    result["stderr_discarded_bytes"] += len(block)

        for target in (drain_stdout, drain_stderr):
            reader = threading.Thread(target=target, daemon=True)
            readers.append(reader)
            reader.start()
        try:
            result["exit_code"] = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            result["timed_out"] = True
            process.kill()  # Only our direct probe child; never services or process trees.
            result["exit_code"] = process.wait(timeout=0.5)
    except (OSError, subprocess.TimeoutExpired):
        result["error"] = "probe unavailable or direct-child reap deadline exceeded"
    for reader in readers:
        reader.join(timeout=0.1)
    result["finished_utc"] = timestamp()
    return result, bytes(captured).decode("utf-8", errors="replace")


def probe_output_complete(result):
    return (result.get("exit_code") == 0 and not result.get("timed_out")
            and not result.get("stdout_capture_truncated")
            and result.get("stdout_capture_complete") is True
            and result.get("stderr_drain_complete") is True)


def list_filter_support(raw):
    """Return only explicit grammar facts from the SDK's list help text."""
    # simctl advertises one generic search term, not per-category placeholders.
    usage = [line for line in raw.splitlines()
             if re.match(r"^\s*Usage:\s+(?:simctl\s+)?list\b", line, re.IGNORECASE)
             and re.search(r"<search(?:\s+|-)term>", line, re.IGNORECASE)]
    return {"device_filter_advertised": any(
                re.search(r"\bdevices\b", line, re.IGNORECASE) for line in usage),
            "runtime_filter_advertised": any(
                re.search(r"\bruntimes\b", line, re.IGNORECASE) for line in usage)}


def selected_data(kind, raw, udid, runtime):
    """Allowlisted fields only: never expose raw command output or error messages."""
    if kind == "simctl_list_help":
        return list_filter_support(raw)
    if kind == "consumer_xcode":
        lines = [line for line in raw.splitlines()
                 if re.fullmatch(r"(?:Xcode [0-9.]+|Build version [A-Za-z0-9]+)", line)]
        return {"version": [clean(line) for line in lines]} if len(lines) == 2 else {}
    if kind == "service":
        fields = {}
        for line in raw.splitlines():
            match = re.fullmatch(r"\s*(state|pid|runs|last exit code) = ([A-Za-z0-9_-]+)\s*", line)
            if match:
                fields[match[1]] = clean(match[2])
        return fields
    data = json.loads(raw)
    if kind == "device":
        for key, devices in data.get("devices", {}).items():
            if key != runtime:
                continue
            for device in devices:
                if device.get("udid", "").upper() == udid:
                    return {"udid": udid, "runtime": clean(key), **{
                        field: clean(device[field]) for field in
                        ("name", "state", "isAvailable", "deviceTypeIdentifier") if field in device}}
    if kind == "runtime":
        for item in data.get("runtimes", []):
            if item.get("identifier") == runtime:
                return {field: clean(item[field]) for field in
                        ("identifier", "name", "version", "buildversion", "isAvailable") if field in item}
    if kind == "pairs":
        pairs = []
        for pair in data.get("pairs", {}).values():
            if any(pair.get(side, {}).get("udid", "").upper() == udid for side in ("phone", "watch")):
                pairs.append({"state": clean(pair.get("state", "unknown")),
                              "selected_device_paired": True})
        return {"matching_pairs": pairs[:8], "matches_clipped": len(pairs) > 8}
    return {}


def collect(udid, device_name, runtime, uid):
    started = time.monotonic()
    report = {"schema_version": 2, "purpose": "readiness evidence, not PASS",
              "started_utc": timestamp(), "budget_seconds": BUDGET_SECONDS,
              "selected": {"udid": udid, "name": clean(device_name), "runtime": clean(runtime)},
              "probes": []}
    commands = [
        ("simctl_list_help", ["xcrun", "simctl", "help", "list"]),
    ]
    help_data = {}
    for kind, command in commands:
        remaining = BUDGET_SECONDS - (time.monotonic() - started) - 1
        if remaining <= 0:
            result, raw = {"exit_code": None, "timed_out": False,
                           "status": "unknown", "error": "budget exhausted"}, ""
        else:
            result, raw = run_probe(command, min(COMMAND_SECONDS, remaining))
        result["kind"] = kind
        result["data"] = {}
        if probe_output_complete(result):
            try:
                help_data = selected_data(kind, raw, udid, runtime)
                if any(help_data.values()):
                    result["data"] = help_data
                    result["status"] = "observed"
                else:
                    result["error"] = "unsupported or incomplete list filter help"
            except (ValueError, TypeError, AttributeError):
                result["error"] = "unsupported or malformed response"
        report["probes"].append(result)

    inventory_commands = [
        ("device", ["xcrun", "simctl", "list", "-j", "devices", udid],
         help_data.get("device_filter_advertised", False)),
        ("runtime", ["xcrun", "simctl", "list", "-j", "runtimes", runtime],
         help_data.get("runtime_filter_advertised", False)),
    ]
    for kind, command, supported in inventory_commands:
        if not supported:
            report["probes"].append({"kind": kind, "status": "unknown", "data": {},
                                     "error": "skipped: selected-target filter not advertised by simctl help"})
            continue
        remaining = BUDGET_SECONDS - (time.monotonic() - started) - 1
        if remaining <= 0:
            report["probes"].append({"kind": kind, "status": "unknown", "data": {},
                                     "error": "budget exhausted"})
            continue
        result, raw = run_probe(command, min(COMMAND_SECONDS, remaining))
        result["kind"] = kind
        result["data"] = {}
        if probe_output_complete(result):
            try:
                result["data"] = selected_data(kind, raw, udid, runtime)
                if result["data"]:
                    result["status"] = "observed"
            except (ValueError, TypeError, AttributeError):
                result["error"] = "unsupported or malformed response"
        report["probes"].append(result)

    for kind, command in (
            ("consumer_xcode", ["xcodebuild", "-version"]),
            ("service", ["launchctl", "print", f"gui/{uid}/com.apple.CoreSimulator.CoreSimulatorService"])):
        remaining = BUDGET_SECONDS - (time.monotonic() - started) - 1
        if remaining <= 0:
            report["probes"].append({"kind": kind, "status": "unknown", "data": {},
                                     "error": "budget exhausted"})
            continue
        result, raw = run_probe(command, min(COMMAND_SECONDS, remaining))
        result["kind"] = kind
        result["data"] = {}
        if probe_output_complete(result):
            try:
                result["data"] = selected_data(kind, raw, udid, runtime)
                if result["data"]:
                    result["status"] = "observed"
            except (ValueError, TypeError, AttributeError):
                result["error"] = "unsupported or malformed response"
        report["probes"].append(result)
    report["finished_utc"] = timestamp()
    report["elapsed_seconds"] = round(time.monotonic() - started, 3)
    return report


def write_report(report, output):
    output.mkdir(parents=True, exist_ok=True)
    encoded = json.dumps(report, indent=2, ensure_ascii=True)
    (output / "report.json").write_text(encoded + "\n", encoding="utf-8")
    # Both files together stay modest; text deliberately contains no raw probe output.
    lines = ["Simulator readiness diagnostics: evidence only; original gate failure remains.",
             f"UTC: {report['started_utc']} -> {report['finished_utc']}",
             f"Selected: {json.dumps(report['selected'], ensure_ascii=True)}"]
    for probe in report["probes"]:
        lines.append(f"{probe['kind']}: {probe['status']}; exit={probe.get('exit_code')}; "
                     f"timeout={probe.get('timed_out', False)}; "
                     f"stdout_truncated={probe.get('stdout_capture_truncated', False)}; "
                     f"stderr_bytes_discarded={probe.get('stderr_discarded_bytes', 0)}")
    (output / "report.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--udid", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        udid = str(uuid.UUID(args.udid)).upper()
    except ValueError:
        parser.error("an explicit valid simulator UUID is required; no device fallback")
    report = collect(udid, os.environ.get("SIMULATOR_NAME", "unknown"),
                     os.environ.get("SIMULATOR_RUNTIME", "unknown"), os.getuid())
    write_report(report, args.output)
    print("Bounded readiness evidence written; this does not certify simulator readiness.")


if __name__ == "__main__":
    main()
