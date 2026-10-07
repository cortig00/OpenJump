"""Select an available, shut-down iPhone from the installed iOS 26.0 runtime."""

import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import tempfile
import uuid

EXPECTED_RUNTIME = "com.apple.CoreSimulator.SimRuntime.iOS-26-0"
EXPECTED_DEVICE_TYPE = "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro"
EXPECTED_DEVICE_NAME = "iPhone 17 Pro"
OWNED_NAME_PREFIX = "OpenJump-ephemeral-"
OWNED_NAME_PATTERN = re.compile(r"^OpenJump-ephemeral-[A-Za-z0-9-]{1,32}$")
OWNED_NAME_MAX_LENGTH = 64
USAGE = ("usage: select_simulator.py GITHUB_OUTPUT "
         "[--create-owned --receipt PATH [--name NAME]] | --cleanup --receipt PATH")
# Query 60s + create 30s = 90s leaves margin inside the outer discovery 120s
# helper (run_command.py --timeout 120); never serial full-120s subprocess limits.
DISCOVERY_QUERY_TIMEOUT = 60
CREATE_TIMEOUT = 30
CLEANUP_TIMEOUT = 60


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


def is_valid_uuid(value):
    try:
        uuid.UUID(str(value))
        return True
    except (ValueError, AttributeError, TypeError):
        return False


def normalize_uuid(value):
    """Normalize any UUID representation to dashed-upper for safe comparison."""
    try:
        return str(uuid.UUID(str(value))).upper()
    except (ValueError, AttributeError, TypeError):
        raise ValueError("not a valid UUID; refusing unsafe ownership action")


def validate_owned_name(name):
    """Accept only OpenJump-scoped names; reject control/newline/injection/unbounded."""
    if not isinstance(name, str):
        raise ValueError("owned device name must stay OpenJump-scoped")
    if len(name) > OWNED_NAME_MAX_LENGTH:
        raise ValueError("owned device name must stay OpenJump-scoped")
    if not OWNED_NAME_PATTERN.fullmatch(name):
        raise ValueError("owned device name must stay OpenJump-scoped")
    return name


class _StrictParser(argparse.ArgumentParser):
    def error(self, message):
        raise SystemExit(USAGE)


def parse_cli_args(argv):
    """Strict CLI parse; returns (mode, github_output, receipt_path, owned_name).

    Modes are "legacy", "create" or "cleanup". Any unknown flag, missing
    value, value-is-flag, conflicting create/cleanup mix or extra positional
    raises SystemExit(USAGE) before any subprocess or file side effect.
    """
    parser = _StrictParser(prog="select_simulator.py", usage=USAGE,
                           add_help=False, allow_abbrev=False)
    parser.add_argument("github_output", nargs="?")
    parser.add_argument("--create-owned", action="store_true")
    parser.add_argument("--cleanup", action="store_true")
    parser.add_argument("--receipt")
    parser.add_argument("--name")
    parser.add_argument("-h", "--help", action="store_true")
    parsed = parser.parse_args(argv)
    if parsed.help:
        raise SystemExit(USAGE)
    for value in (parsed.receipt, parsed.name):
        if value is not None and value.startswith("-"):
            raise SystemExit(USAGE)
    if parsed.cleanup:
        if (parsed.create_owned or parsed.name is not None
                or parsed.github_output is not None or parsed.receipt is None):
            raise SystemExit(USAGE)
        return ("cleanup", None, parsed.receipt, None)
    if parsed.create_owned:
        if parsed.github_output is None or parsed.receipt is None:
            raise SystemExit(USAGE)
        if parsed.name is not None:
            try:
                validate_owned_name(parsed.name)
            except ValueError:
                raise SystemExit(USAGE)
        return ("create", parsed.github_output, parsed.receipt, parsed.name)
    if (parsed.github_output is None or parsed.receipt is not None
            or parsed.name is not None):
        raise SystemExit(USAGE)
    return ("legacy", parsed.github_output, None, None)


def find_creation_template(data):
    """Validate exact iPhone 17 Pro / iOS-26-0 template from available metadata."""
    candidates = [
        (runtime, device)
        for runtime, devices in data.get("devices", {}).items()
        if runtime == EXPECTED_RUNTIME
        for device in devices
        if device.get("name") == EXPECTED_DEVICE_NAME
        and device.get("deviceTypeIdentifier") == EXPECTED_DEVICE_TYPE
        and device.get("isAvailable")
    ]
    if not candidates:
        raise ValueError("No available iPhone 17 Pro template for iOS-26-0 creation")
    runtime, template = sorted(candidates, key=lambda item: str(item[1].get("udid", "")))[0]
    template_udid = str(template.get("udid", ""))
    if not is_valid_uuid(template_udid):
        raise ValueError("Template UUID is not a valid UUID; refusing owned creation")
    return (EXPECTED_RUNTIME, EXPECTED_DEVICE_TYPE, template_udid.upper())


def build_create_argv(name, device_type=EXPECTED_DEVICE_TYPE, runtime=EXPECTED_RUNTIME):
    return ["xcrun", "simctl", "create", name, device_type, runtime]


def build_delete_argv(udid):
    return ["xcrun", "simctl", "delete", udid]


def parse_created_uuid(output, template_udid):
    if isinstance(output, bytes):
        output = output.decode("utf-8", errors="replace")
    lines = [line.strip() for line in str(output).splitlines() if line.strip()]
    if len(lines) != 1:
        raise ValueError("ambiguous simctl create output; refusing unsafe delete")
    created = str(uuid.UUID(lines[0])).upper()
    if created == str(template_udid).upper():
        raise ValueError("created UUID matches template; refusing to reuse factory device")
    return created


def write_ownership_receipt(path, record):
    if not isinstance(record, dict):
        raise ValueError("ownership record is not a mapping; refusing unsafe write")
    udid = normalize_uuid(record.get("udid"))
    validate_owned_name(record.get("name"))
    if record.get("runtime") != EXPECTED_RUNTIME:
        raise ValueError("ownership record runtime mismatch; refusing unsafe write")
    if record.get("device_type") != EXPECTED_DEVICE_TYPE:
        raise ValueError("ownership record device-type mismatch; refusing unsafe write")
    template_udid = normalize_uuid(record.get("template_udid"))
    if udid == template_udid:
        raise ValueError("record UUID matches template; refusing to claim factory device")
    if os.path.lexists(path):
        raise ValueError("refusing to overwrite existing ownership receipt")
    payload = {"udid": udid, "name": record["name"],
               "runtime": record["runtime"], "device_type": record["device_type"],
               "template_udid": template_udid,
               "created_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")}
    directory = os.path.dirname(os.path.abspath(path)) or "."
    fd, tmp_path = tempfile.mkstemp(dir=directory, prefix=".owned-simulator-", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=True, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp_path, path)
    except BaseException:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass
        raise


def read_ownership_receipt(path):
    with open(path, encoding="utf-8") as handle:
        record = json.load(handle)
    if not isinstance(record, dict):
        raise ValueError("ownership receipt is not a mapping; refusing unsafe delete")
    udid = normalize_uuid(record.get("udid"))
    if record.get("runtime") != EXPECTED_RUNTIME:
        raise ValueError("ownership receipt runtime mismatch; refusing unsafe delete")
    if record.get("device_type") != EXPECTED_DEVICE_TYPE:
        raise ValueError("ownership receipt device-type mismatch; refusing unsafe delete")
    validate_owned_name(record.get("name"))
    template_udid = normalize_uuid(record.get("template_udid"))
    if udid == template_udid:
        raise ValueError("receipt UUID matches template; refusing to delete factory device")
    record["udid"] = udid
    record["template_udid"] = template_udid
    return record


def create_owned_record(name=None, list_timeout=DISCOVERY_QUERY_TIMEOUT,
                        create_timeout=CREATE_TIMEOUT):
    if name is None:
        name = f"{OWNED_NAME_PREFIX}{uuid.uuid4().hex[:8]}"
    validate_owned_name(name)
    raw = subprocess.check_output(
        ["xcrun", "simctl", "list", "devices", "available", "-j"], timeout=list_timeout)
    runtime, device_type, template_udid = find_creation_template(json.loads(raw))
    owned_name = name
    argv = build_create_argv(owned_name, device_type, runtime)
    created_udid = parse_created_uuid(
        subprocess.check_output(argv, timeout=create_timeout), template_udid)
    return {"udid": created_udid, "name": owned_name, "runtime": runtime,
            "device_type": device_type, "template_udid": template_udid}


def main():
    args = sys.argv[1:]
    if args == ["--help"] or args == ["-h"]:
        raise SystemExit(USAGE)
    mode, github_output, receipt_path, owned_name = parse_cli_args(args)
    if mode == "cleanup":
        try:
            record = read_ownership_receipt(receipt_path)
        except (OSError, ValueError, AttributeError, TypeError) as exc:
            print(f"Owned cleanup UNKNOWN: no valid receipt ({type(exc).__name__}); "
                  f"ephemeral runner disposal applies, no delete attempted.", flush=True)
            return 0
        argv = build_delete_argv(record["udid"])
        try:
            subprocess.check_output(argv, timeout=CLEANUP_TIMEOUT)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError) as exc:
            print(f"Owned cleanup failed for {record['udid']}: {type(exc).__name__}; "
                  f"does not certify boot/test PASS.", flush=True)
            return 1
        print(f"Deleted owned ephemeral simulator {record['udid']}", flush=True)
        return 0
    if mode == "legacy":
        raw = subprocess.check_output(
            ["xcrun", "simctl", "list", "devices", "available", "-j"], timeout=100
        )
        runtime, device = select_device(json.loads(raw))
        with open(github_output, "a", encoding="utf-8") as output:
            output.write(f"udid={device['udid']}\nname={device['name']}\nruntime={runtime}\n")
        print(f"Selected {device['name']} ({device['udid']}) on {runtime}", flush=True)
        return 0
    if os.path.lexists(receipt_path):
        print("Owned creation collision: existing receipt kept; "
              "no device created, no delete attempted.", flush=True)
        return 1
    try:
        record = create_owned_record(name=owned_name)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError, ValueError) as exc:
        print(f"Owned creation UNKNOWN: {type(exc).__name__}; no fallback UUID emitted.", flush=True)
        raise SystemExit(1)
    # Retain cleanup ownership even if later validation/output steps fail: receipt first.
    try:
        write_ownership_receipt(receipt_path, record)
    except (OSError, ValueError) as exc:
        print(f"Owned creation UNKNOWN: {type(exc).__name__}; "
              f"receipt unwritten, no GITHUB_OUTPUT emitted.", flush=True)
        raise SystemExit(1)
    try:
        with open(github_output, "a", encoding="utf-8") as output:
            output.write(f"udid={record['udid']}\nname={record['name']}\n"
                         f"runtime={record['runtime']}\nowned=true\n")
    except OSError as exc:
        print(f"Owned creation UNKNOWN: {type(exc).__name__}; "
              f"receipt retained for guarded cleanup, no GITHUB_OUTPUT emitted.", flush=True)
        raise SystemExit(1)
    print(f"Created owned {record['name']} ({record['udid']}) on {record['runtime']}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
