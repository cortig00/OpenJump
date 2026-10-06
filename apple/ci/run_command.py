#!/usr/bin/env python3
"""Run one command with inherited output and a bounded direct-child wait."""

import argparse
import datetime
import subprocess
import sys


def timestamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--timeout", required=True, type=float)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    command = args.command
    if command and command[0] == "--":
        command = command[1:]
    if args.timeout <= 0 or not command:
        parser.error("timeout must be positive and a command is required after --")
    print(f"[{timestamp()}] START timeout={args.timeout:g}s command={command!r}", flush=True)
    process = subprocess.Popen(command)
    try:
        result = process.wait(timeout=args.timeout)
    except subprocess.TimeoutExpired:
        print(f"[{timestamp()}] TIMEOUT command={command!r}; terminating direct child", flush=True)
        process.kill()
        process.wait()
        print(f"[{timestamp()}] FINISH exit=124 command={command!r}", flush=True)
        return 124
    print(f"[{timestamp()}] FINISH exit={result} command={command!r}", flush=True)
    return result if result >= 0 else min(128 - result, 255)


if __name__ == "__main__":
    sys.exit(main())
