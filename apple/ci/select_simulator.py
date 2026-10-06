"""Select an available, shut-down iPhone from the installed iOS 26.0 runtime."""

import json
import subprocess
import sys


def select_device(data):
    matches = [
        (runtime, device)
        for runtime, devices in data["devices"].items()
        if runtime.endswith("iOS-26-0")
        for device in devices
        if device["name"].startswith("iPhone")
        and device["isAvailable"]
        and device["state"] == "Shutdown"
    ]
    if not matches:
        raise ValueError("No available iOS 26 iPhone simulator in Shutdown state")
    return matches[0]


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: select_simulator.py GITHUB_OUTPUT")
    raw = subprocess.check_output(
        ["xcrun", "simctl", "list", "devices", "available", "-j"], timeout=100
    )
    runtime, device = select_device(json.loads(raw))
    with open(sys.argv[1], "a", encoding="utf-8") as output:
        output.write(f"udid={device['udid']}\nname={device['name']}\nruntime={runtime}\n")
    print(f"Selected {device['name']} ({device['udid']}) on {runtime}", flush=True)


if __name__ == "__main__":
    main()
