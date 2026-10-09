import contextlib
import io
import json
import os
import runpy
import shlex
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

import diagnose_simulator
import select_simulator

ROOT = Path(__file__).resolve().parent
RUNNER = ROOT / "run_command.py"


class CommandRunnerTests(unittest.TestCase):
    def run_helper(self, timeout, *command):
        return subprocess.run(
            [sys.executable, str(RUNNER), "--timeout", str(timeout), "--", *command],
            text=True,
            capture_output=True,
            timeout=3,
        )

    def test_success_inherits_command_output(self):
        result = self.run_helper(2, sys.executable, "-c", "print('visible')")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("visible", result.stdout)
        self.assertIn("START", result.stdout)
        self.assertIn("FINISH exit=0", result.stdout)

    def test_nonzero_exit_is_propagated(self):
        result = self.run_helper(2, sys.executable, "-c", "raise SystemExit(7)")
        self.assertEqual(result.returncode, 7)
        self.assertIn("FINISH exit=7", result.stdout)

    def test_timeout_kills_and_reaps_direct_child(self):
        started = time.monotonic()
        result = self.run_helper(0.1, sys.executable, "-c", "import time; time.sleep(10)")
        self.assertEqual(result.returncode, 124)
        self.assertLess(time.monotonic() - started, 0.5)
        self.assertIn("TIMEOUT", result.stdout)
        self.assertIn("FINISH exit=124", result.stdout)

    def test_boot_failure_does_not_run_readiness_command(self):
        with tempfile.TemporaryDirectory() as temp:
            marker = Path(temp) / "readiness-ran"
            python = Path(sys.executable).as_posix()
            boot = shlex.join([python, RUNNER.as_posix(), "--timeout", "2", "--",
                               python, "-c", "raise SystemExit(7)"])
            readiness = shlex.join([python, "-c", f"open({str(marker)!r}, 'w').close()"])
            # Exercise the workflow's actual fail-fast shell sequencing, not a Python conditional.
            result = subprocess.run(["bash", "-c", f"set -euo pipefail\n{boot}\n{readiness}"],
                                    text=True, capture_output=True, timeout=3)
            self.assertEqual(result.returncode, 7, result.stdout + result.stderr)
            self.assertFalse(marker.exists())


class SimulatorPickerTests(unittest.TestCase):
    def test_selects_only_available_shutdown_ios26_iphone(self):
        data = {"devices": {
            "com.apple.CoreSimulator.SimRuntime.iOS-26-0": [
                {"name": "iPhone 17 Pro", "udid": "chosen", "isAvailable": True, "state": "Shutdown"},
                {"name": "iPhone running", "udid": "running", "isAvailable": True, "state": "Booted"},
                {"name": "iPhone unavailable", "udid": "unavailable", "isAvailable": False, "state": "Shutdown"},
            ],
            "com.apple.CoreSimulator.SimRuntime.iOS-18-0": [
                {"name": "iPhone old", "udid": "old", "isAvailable": True, "state": "Shutdown"}
            ],
        }}
        runtime, device = select_simulator.select_device(data)
        self.assertEqual((runtime, device["udid"]), ("com.apple.CoreSimulator.SimRuntime.iOS-26-0", "chosen"))

    def test_no_match_fails_without_fallback(self):
        with self.assertRaisesRegex(ValueError, "No available iOS 26"):
            select_simulator.select_device({"devices": {}})

    def test_device_discovery_has_a_deadline(self):
        timeout = subprocess.TimeoutExpired(["xcrun"], 100)
        with patch.object(select_simulator.subprocess, "check_output", side_effect=timeout) as discover:
            with patch.object(sys, "argv", ["select_simulator.py", "unused-output"]):
                with self.assertRaises(subprocess.TimeoutExpired):
                    select_simulator.main()
        self.assertEqual(discover.call_args.kwargs["timeout"], 100)


class SimulatorDiagnosticsTests(unittest.TestCase):
    UDID = "517BAAA1-7BAB-4B50-8B25-04EF0238019B"
    RUNTIME = "com.apple.CoreSimulator.SimRuntime.iOS-26-0"

    def test_probe_nonzero_and_missing_command_are_unknown(self):
        result, _ = diagnose_simulator.run_probe(
            [sys.executable, "-c", "raise SystemExit(7)"], 2)
        self.assertEqual(result["exit_code"], 7)
        self.assertEqual(result["status"], "unknown")
        self.assertFalse(result["timed_out"])
        with patch.object(diagnose_simulator.subprocess, "Popen", side_effect=FileNotFoundError):
            result, raw = diagnose_simulator.run_probe(["unsupported-probe"], 2)
        self.assertEqual((result["status"], result["exit_code"], raw), ("unknown", None, ""))

    def test_stdout_cap_remains_independent_while_stderr_is_discarded(self):
        secret = "PRIVATE_STDERR_SENTINEL"
        code = ("import sys; sys.stdout.write('x' * 200000); sys.stdout.flush(); "
                "sys.stderr.write(('PRIVATE_' + 'STDERR_' + 'SENTINEL') * 10000)")
        result, raw = diagnose_simulator.run_probe([sys.executable, "-c", code], 2)
        self.assertEqual(result["exit_code"], 0)
        self.assertEqual(len(raw), 65536)
        self.assertTrue(result["stdout_capture_truncated"])
        self.assertTrue(result["stdout_capture_complete"])
        self.assertTrue(result["stderr_drain_complete"])
        self.assertEqual(result["stderr_discarded_bytes"], len(secret) * 10000)
        self.assertNotIn(secret, json.dumps(result))

    def test_probe_timeout_is_bounded_and_not_health_pass(self):
        started = time.monotonic()
        result, _ = diagnose_simulator.run_probe(
            [sys.executable, "-c", "import time; time.sleep(10)"], 0.1)
        self.assertLess(time.monotonic() - started, 1)
        self.assertTrue(result["timed_out"])
        self.assertIsNotNone(result["exit_code"])
        self.assertEqual(result["status"], "unknown")
        self.assertEqual(result["timeout_seconds"], 0.1)
        self.assertIn("started_utc", result)
        self.assertIn("finished_utc", result)

    def test_probe_output_is_capped_without_a_raw_file(self):
        result, raw = diagnose_simulator.run_probe(
            [sys.executable, "-c", "print('x' * 200000)"], 2)
        self.assertEqual(result["exit_code"], 0)
        self.assertTrue(result["stdout_capture_truncated"])
        self.assertTrue(result["stdout_capture_complete"])
        self.assertEqual(len(raw), diagnose_simulator.CAPTURE_BYTES)

    def test_probe_separates_large_private_stderr_from_stdout_json(self):
        secret = "PRIVATE_STDERR_SENTINEL"
        payload = json.dumps({"devices": {}})
        code = ("import sys; sys.stdout.write(" + repr(payload) + "); "
                "sys.stdout.flush(); sys.stderr.write(('PRIVATE_' + 'STDERR_' + 'SENTINEL') * 10000)")
        result, raw = diagnose_simulator.run_probe([sys.executable, "-c", code], 2)
        self.assertEqual(result["exit_code"], 0)
        self.assertEqual(raw, payload)
        self.assertFalse(result["stdout_capture_truncated"])
        self.assertTrue(result["stdout_capture_complete"])
        self.assertEqual(result["stderr_discarded_bytes"], len(secret) * 10000)
        self.assertNotIn(secret, json.dumps(result))

    def test_report_filters_selected_uuid_and_omits_private_output(self):
        secret = "PRIVATE_STDERR_SENTINEL"
        devices = {"devices": {self.RUNTIME: [
            {"udid": self.UDID, "name": "iPhone\x1b[31m 17\nPro", "state": "Booted",
             "isAvailable": True, "private": secret},
            {"udid": "other", "name": secret},
        ]}}
        runtimes = {"runtimes": [{"identifier": self.RUNTIME, "name": "iOS 26.0",
                                  "isAvailable": True, "bundlePath": secret}]}
        pairs = {"pairs": {"other": {"phone": {"udid": "other"}, "state": secret}}}
        # Published generic list usage, not placeholders invented for this parser.
        help_stdout = ("Usage: list [-j|--json] [-e|--enc] "
                     "[devices|devicetypes|runtimes|pairs] [<search term>|available]\n")
        help_stderr = ""
        responses = [json.dumps(devices), json.dumps(runtimes),
                     "Xcode 26.0.1\nBuild version 17A400\n" + secret,
                     "state = running\npid = 123\nenvironment = " + secret]

        def help_probe(command, timeout):
            self.assertLessEqual(timeout, diagnose_simulator.COMMAND_SECONDS)
            self.assertEqual(command, ["xcrun", "simctl", "help", "list"])
            return ({"command": command, "exit_code": 0, "timed_out": False,
                    "stdout_capture_truncated": False, "stdout_capture_complete": True,
                    "stderr_capture_truncated": False, "stderr_capture_complete": True,
                    "stdout_captured_bytes": len(help_stdout.encode()),
                    "stderr_captured_bytes": 0,
                    "status": "unknown"}, help_stdout, help_stderr)

        def probe(command, timeout):
            self.assertLessEqual(timeout, diagnose_simulator.COMMAND_SECONDS)
            return {"command": command, "exit_code": 0, "timed_out": False,
                    "stdout_capture_truncated": False, "stdout_capture_complete": True,
                    "stderr_drain_complete": True, "stderr_discarded_bytes": 0,
                    "status": "unknown"}, responses.pop(0)

        with patch.object(diagnose_simulator, "run_help_probe", side_effect=help_probe) as help_run, \
                patch.object(diagnose_simulator, "run_probe", side_effect=probe) as run, \
                patch.dict(diagnose_simulator.os.environ, {"DEVELOPER_DIR": secret}):
            report = diagnose_simulator.collect(self.UDID, "iPhone", self.RUNTIME, 501)
        self.assertEqual(help_run.call_count, 1)
        commands = [call.args[0] for call in run.call_args_list]
        self.assertEqual(len(commands), 4)
        self.assertTrue(all("boot" not in cmd and "bootstatus" not in cmd for cmd in commands))
        self.assertEqual(commands[0], ["xcrun", "simctl", "list", "-j", "devices", self.UDID])
        self.assertEqual(commands[1], ["xcrun", "simctl", "list", "-j", "runtimes", self.RUNTIME])
        self.assertEqual(commands[-1], ["launchctl", "print",
                         "gui/501/com.apple.CoreSimulator.CoreSimulatorService"])
        self.assertEqual(report["schema_version"], 3)
        self.assertEqual(report["probes"][1]["data"]["name"], "iPhone 17Pro")
        self.assertEqual(report["probes"][1]["data"]["udid"], self.UDID)
        self.assertEqual(report["probes"][2]["data"]["identifier"], self.RUNTIME)
        self.assertEqual(report["probes"][0]["data"], {
            "device_filter_advertised": True, "runtime_filter_advertised": True,
            "help_stdout_present": True, "help_stderr_present": False,
            "help_stdout_bytes": len(help_stdout.encode()), "help_stderr_bytes": 0})
        self.assertEqual(diagnose_simulator.selected_data(
            "pairs", json.dumps(pairs), self.UDID, self.RUNTIME)["matching_pairs"], [])
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "diagnostics"
            diagnose_simulator.write_report(report, output)
            self.assertEqual({p.name for p in output.iterdir()}, {"report.json", "report.txt"})
            self.assertLess(sum(p.stat().st_size for p in output.iterdir()), 65536)
            text = "".join(p.read_text(encoding="utf-8") for p in output.iterdir())
            self.assertNotIn(secret, text)
            self.assertNotIn("consumer_developer_dir", report)
            self.assertNotIn("\x1b", text)
            self.assertEqual(json.loads((output / "report.json").read_text())["selected"]["udid"],
                             self.UDID)

    def test_list_help_recognizes_generic_usage_not_placeholder_guesses(self):
        cases = [
            ("Usage: list [-j|--json] [-e|--enc] "
             "[devices|devicetypes|runtimes|pairs] [<search term>|available]",
             {"device_filter_advertised": True, "runtime_filter_advertised": True}),
            ("usage: simctl list devices <search term>",
             {"device_filter_advertised": True, "runtime_filter_advertised": False}),
            ("Usage: list [devices|runtimes]",
             {"device_filter_advertised": False, "runtime_filter_advertised": False}),
            ("devices [--json] <device>\nruntimes [--json] <runtime>",
             {"device_filter_advertised": False, "runtime_filter_advertised": False}),
            ("Example: list [devices|runtimes] [<search term>]",
             {"device_filter_advertised": False, "runtime_filter_advertised": False}),
        ]
        for text, expected in cases:
            with self.subTest(help=text):
                self.assertEqual(diagnose_simulator.list_filter_support(text), expected)

    def test_unsupported_help_skips_inventory_without_global_fallback(self):
        help_stdout = "usage: simctl list [devices|runtimes]"
        help_stderr = ""
        responses = ["Xcode 26.0.1\nBuild version 17A400\n", ""]

        def help_probe(command, timeout):
            return ({"command": command, "exit_code": 0, "timed_out": False,
                     "stdout_capture_truncated": False, "stdout_capture_complete": True,
                     "stderr_capture_truncated": False, "stderr_capture_complete": True,
                     "stdout_captured_bytes": len(help_stdout.encode()),
                     "stderr_captured_bytes": 0,
                     "status": "unknown"}, help_stdout, help_stderr)

        def probe(command, timeout):
            return ({"command": command, "exit_code": 0, "timed_out": False,
                     "stdout_capture_truncated": False, "stdout_capture_complete": True,
                     "stderr_drain_complete": True, "stderr_discarded_bytes": 0,
                     "status": "unknown"}, responses.pop(0))

        with patch.object(diagnose_simulator, "run_help_probe", side_effect=help_probe) as help_run, \
                patch.object(diagnose_simulator, "run_probe", side_effect=probe) as run:
            report = diagnose_simulator.collect(self.UDID, "iPhone", self.RUNTIME, 501)
        self.assertEqual(help_run.call_count, 1)
        commands = [call.args[0] for call in run.call_args_list]
        self.assertEqual(commands, [["xcodebuild", "-version"],
                                    ["launchctl", "print",
                                     "gui/501/com.apple.CoreSimulator.CoreSimulatorService"]])
        self.assertEqual(len(report["probes"]), 5)
        self.assertTrue(all("skipped" in report["probes"][index]["error"]
                            for index in (1, 2)))
        self.assertNotIn("devices", json.dumps(commands))
        self.assertNotIn("runtimes", json.dumps(commands))

    def test_target_parser_rejects_absent_or_wrong_uuid_and_runtime(self):
        self.assertEqual(diagnose_simulator.selected_data("device", json.dumps({
            "devices": {self.RUNTIME: [{"udid": "OTHER"}]}}), self.UDID, self.RUNTIME), {})
        self.assertEqual(diagnose_simulator.selected_data("device", json.dumps({
            "devices": {self.RUNTIME: []}}), self.UDID, self.RUNTIME), {})
        self.assertEqual(diagnose_simulator.selected_data("device", json.dumps({
            "devices": {"other-runtime": [{"udid": self.UDID}]}}), self.UDID, self.RUNTIME), {})
        self.assertEqual(diagnose_simulator.selected_data("runtime", json.dumps({
            "runtimes": [{"identifier": "OTHER"}]}), self.UDID, self.RUNTIME), {})

    def test_failed_truncated_and_malformed_probes_stay_unknown(self):
        for exit_code, truncated, stderr_complete, raw in [
                (3, False, True, "private unsupported error"),
                (0, True, True, "private clipped output"),
                (0, False, True, "invalid JSON"),
                (0, False, False, "private incomplete drain")]:
            result = {"exit_code": exit_code, "stdout_capture_truncated": truncated,
                      "stdout_capture_complete": True, "stderr_drain_complete": stderr_complete,
                      "stderr_discarded_bytes": 0, "timed_out": False, "status": "unknown"}
            help_result = {"exit_code": exit_code, "stdout_capture_truncated": truncated,
                           "stdout_capture_complete": True, "stderr_capture_truncated": truncated,
                           "stderr_capture_complete": stderr_complete,
                           "stdout_captured_bytes": 0, "stderr_captured_bytes": 0,
                           "timed_out": False, "status": "unknown"}
            with patch.object(diagnose_simulator, "run_help_probe",
                              side_effect=lambda *args: (dict(help_result), raw, "")), \
                    patch.object(diagnose_simulator, "run_probe",
                              side_effect=lambda *args: (dict(result), raw)):
                report = diagnose_simulator.collect(self.UDID, "iPhone", self.RUNTIME, 501)
            self.assertTrue(all(p["status"] == "unknown" and p["data"] == {}
                                for p in report["probes"]))
            self.assertNotIn(raw, json.dumps(report))

    def test_total_budget_exhaustion_skips_all_commands(self):
        with patch.object(diagnose_simulator.time, "monotonic", side_effect=[0] + [40] * 6):
            with patch.object(diagnose_simulator, "run_help_probe") as help_run, \
                    patch.object(diagnose_simulator, "run_probe") as run:
                report = diagnose_simulator.collect(self.UDID, "iPhone", self.RUNTIME, 501)
        help_run.assert_not_called()
        run.assert_not_called()
        self.assertEqual(report["elapsed_seconds"], 40)
        self.assertEqual(len(report["probes"]), 5)
        self.assertTrue(all(p["status"] == "unknown" for p in report["probes"]))

    def test_cli_requires_explicit_valid_uuid_without_fallback(self):
        for args in [[], ["--udid", "booted"], ["--udid", ""]]:
            result = subprocess.run(
                [sys.executable, str(ROOT / "diagnose_simulator.py"), "--output", "unused", *args],
                text=True, capture_output=True, timeout=3)
            self.assertEqual(result.returncode, 2)
            self.assertNotIn("Bounded readiness evidence written", result.stdout)

    def test_help_on_stderr_is_consumed_without_raw_leak(self):
        help_line = ("Usage: list [-j|--json] [-e|--enc] "
                     "[devices|devicetypes|runtimes|pairs] [<search term>|available]")
        # Build help in the child via concatenation so the parent test command
        # does not itself contain the full help line as a substring.
        code = ("import sys; sys.stdout.flush(); "
                "sys.stderr.write('Usage:' + ' list [-j|--json] [-e|--enc] ' + "
                "'[devices|devicetypes|runtimes|pairs] [<search term>|available]\\n'); "
                "sys.stderr.flush()")
        result, stdout_raw, stderr_raw = diagnose_simulator.run_help_probe(
            [sys.executable, "-c", code], 2)
        self.assertEqual(result["exit_code"], 0)
        self.assertTrue(diagnose_simulator.help_output_complete(result))
        self.assertEqual(stdout_raw, "")
        self.assertIn("Usage:", stderr_raw)
        combined = diagnose_simulator.help_text_from_streams(stdout_raw, stderr_raw)
        self.assertEqual(diagnose_simulator.list_filter_support(combined),
                         {"device_filter_advertised": True, "runtime_filter_advertised": True})
        self.assertTrue(result["stderr_capture_complete"])
        self.assertFalse(result["stderr_capture_truncated"])
        self.assertEqual(result["stderr_captured_bytes"], len(stderr_raw.encode()))
        self.assertNotIn(help_line, json.dumps(result))
        self.assertLessEqual(result["stdout_captured_bytes"] + result["stderr_captured_bytes"],
                             diagnose_simulator.CAPTURE_BYTES)

    def test_help_dual_stream_cap_truncation_timeout_and_privacy(self):
        secret = "PRIVATE_STDERR_SENTINEL"
        # Noisy stdout plus large private stderr via child-side repetition, so the
        # parent command stays short (Windows argv limit) while output exceeds cap.
        code = ("import sys; sys.stdout.write('x' * 70000); sys.stdout.flush(); "
                "sys.stderr.write(('PRIVATE_' + 'STDERR_' + 'SENTINEL') * 5000)")
        result, stdout_raw, stderr_raw = diagnose_simulator.run_help_probe(
            [sys.executable, "-c", code], 2)
        self.assertEqual(result["exit_code"], 0)
        self.assertTrue(result["stdout_capture_truncated"] or result["stderr_capture_truncated"])
        self.assertFalse(diagnose_simulator.help_output_complete(result))
        total = result["stdout_captured_bytes"] + result["stderr_captured_bytes"]
        self.assertLessEqual(total, diagnose_simulator.CAPTURE_BYTES)
        self.assertNotIn(secret, json.dumps(result))
        self.assertNotIn(secret, stdout_raw + stderr_raw[:0])
        # Timeout stays UNKNOWN and bounded, never a health PASS.
        started = time.monotonic()
        timeout_result, _, _ = diagnose_simulator.run_help_probe(
            [sys.executable, "-c", "import time; time.sleep(10)"], 0.1)
        self.assertLess(time.monotonic() - started, 1)
        self.assertTrue(timeout_result["timed_out"])
        self.assertFalse(diagnose_simulator.help_output_complete(timeout_result))
        self.assertEqual(timeout_result["status"], "unknown")

    def test_help_stderr_recovery_enables_selected_inventory(self):
        devices = {"devices": {self.RUNTIME: [
            {"udid": self.UDID, "name": "iPhone 17 Pro", "state": "Shutdown",
             "isAvailable": True, "deviceTypeIdentifier":
             "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro"}]}}
        runtimes = {"runtimes": [{"identifier": self.RUNTIME, "name": "iOS 26.0",
                                  "isAvailable": True}]}
        help_stdout = ""
        help_stderr = ("Usage: list [-j|--json] [-e|--enc] "
                       "[devices|devicetypes|runtimes|pairs] [<search term>|available]\n")
        help_result = {"command": ["xcrun", "simctl", "help", "list"], "exit_code": 0,
                       "timed_out": False, "stdout_capture_truncated": False,
                       "stdout_capture_complete": True, "stderr_capture_truncated": False,
                       "stderr_capture_complete": True, "stdout_captured_bytes": 0,
                       "stderr_captured_bytes": len(help_stderr.encode()), "status": "unknown"}
        inventory = [json.dumps(devices), json.dumps(runtimes),
                     "Xcode 26.0.1\nBuild version 17A400\n",
                     "state = running\npid = 123\n"]

        def help_probe(command, timeout):
            return dict(help_result), help_stdout, help_stderr

        def probe(command, timeout):
            return ({"command": command, "exit_code": 0, "timed_out": False,
                     "stdout_capture_truncated": False, "stdout_capture_complete": True,
                     "stderr_drain_complete": True, "stderr_discarded_bytes": 0,
                     "status": "unknown"}, inventory.pop(0))

        with patch.object(diagnose_simulator, "run_help_probe", side_effect=help_probe), \
                patch.object(diagnose_simulator, "run_probe", side_effect=probe):
            report = diagnose_simulator.collect(self.UDID, "iPhone 17 Pro", self.RUNTIME, 501)
        self.assertEqual(report["probes"][0]["status"], "observed")
        self.assertTrue(report["probes"][0]["data"]["help_stderr_present"])
        self.assertFalse(report["probes"][0]["data"]["help_stdout_present"])
        self.assertEqual(report["probes"][1]["data"]["udid"], self.UDID)
        self.assertNotIn(help_stderr.strip(), json.dumps(report["probes"][0]["data"]))


class OwnedEphemeralDeviceTests(unittest.TestCase):
    TEMPLATE_UDID = "AAAAAAAA-1111-2222-3333-444444444444"
    CREATED_UDID = "BBBBBBBB-1111-2222-3333-444444444444"
    RUNTIME = "com.apple.CoreSimulator.SimRuntime.iOS-26-0"
    DEVICE_TYPE = "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro"

    def template_data(self):
        return {"devices": {self.RUNTIME: [
            {"name": "iPhone 17 Pro", "udid": self.TEMPLATE_UDID, "isAvailable": True,
             "state": "Shutdown", "deviceTypeIdentifier": self.DEVICE_TYPE},
            {"name": "iPhone 17", "udid": "CCCCCCCC-1111-2222-3333-444444444444",
             "isAvailable": True, "state": "Shutdown",
             "deviceTypeIdentifier": "com.apple.CoreSimulator.SimDeviceType.iPhone-17"},
        ]}}

    def test_owned_template_requires_exact_model_type_runtime(self):
        runtime, device_type, template = select_simulator.find_creation_template(self.template_data())
        self.assertEqual((runtime, device_type, template),
                         (self.RUNTIME, self.DEVICE_TYPE, self.TEMPLATE_UDID))
        with self.assertRaisesRegex(ValueError, "No available"):
            select_simulator.find_creation_template({"devices": {}})
        wrong_name = {"devices": {self.RUNTIME: [
            {"name": "iPhone 17", "udid": self.TEMPLATE_UDID, "isAvailable": True,
             "deviceTypeIdentifier": self.DEVICE_TYPE}]}}
        with self.assertRaisesRegex(ValueError, "No available"):
            select_simulator.find_creation_template(wrong_name)
        wrong_type = {"devices": {self.RUNTIME: [
            {"name": "iPhone 17 Pro", "udid": self.TEMPLATE_UDID, "isAvailable": True,
             "deviceTypeIdentifier": "com.apple.CoreSimulator.SimDeviceType.iPhone-17"}]}}
        with self.assertRaisesRegex(ValueError, "No available"):
            select_simulator.find_creation_template(wrong_type)
        # Existing pure selector remains meaningful and unchanged.
        runtime, device = select_simulator.select_device({
            "devices": {self.RUNTIME: [
                {"name": "iPhone 17 Pro", "udid": "chosen", "isAvailable": True,
                 "state": "Shutdown"}]}})
        self.assertEqual(device["udid"], "chosen")

    def test_owned_create_argv_is_fixed_and_parse_validates_ownership(self):
        expected_create = ["xcrun", "simctl", "create", "OpenJump-ephemeral-abc12345",
                           "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro",
                           "com.apple.CoreSimulator.SimRuntime.iOS-26-0"]
        self.assertEqual(select_simulator.build_create_argv("OpenJump-ephemeral-abc12345"),
                         expected_create)
        self.assertEqual(select_simulator.build_delete_argv(self.CREATED_UDID),
                         ["xcrun", "simctl", "delete", self.CREATED_UDID])
        self.assertEqual(select_simulator.parse_created_uuid(self.CREATED_UDID + "\n",
                                                             self.TEMPLATE_UDID),
                         self.CREATED_UDID)
        with self.assertRaisesRegex(ValueError, "matches template"):
            select_simulator.parse_created_uuid(self.TEMPLATE_UDID + "\n", self.TEMPLATE_UDID)
        with self.assertRaises(ValueError):
            select_simulator.parse_created_uuid("not-a-uuid\n", self.TEMPLATE_UDID)
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            select_simulator.parse_created_uuid(self.CREATED_UDID + "\n" + self.CREATED_UDID + "\n",
                                                self.TEMPLATE_UDID)
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            select_simulator.parse_created_uuid("", self.TEMPLATE_UDID)

    def test_owned_creation_uses_bounded_query_and_create_without_retry(self):
        listing = json.dumps(self.template_data()).encode()
        calls = []

        def fake_check_output(argv, timeout=None):
            calls.append((list(argv), timeout))
            if argv[:3] == ["xcrun", "simctl", "list"]:
                return listing
            self.assertEqual(list(argv),
                             ["xcrun", "simctl", "create", "OpenJump-ephemeral-test1234",
                              "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro",
                              "com.apple.CoreSimulator.SimRuntime.iOS-26-0"])
            return (self.CREATED_UDID + "\n").encode()

        with patch.object(select_simulator.subprocess, "check_output", side_effect=fake_check_output):
            record = select_simulator.create_owned_record(name="OpenJump-ephemeral-test1234")
        self.assertEqual(record["udid"], self.CREATED_UDID)
        self.assertEqual(record["name"], "OpenJump-ephemeral-test1234")
        self.assertEqual(record["runtime"], self.RUNTIME)
        self.assertEqual(record["template_udid"], self.TEMPLATE_UDID)
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[0][0], ["xcrun", "simctl", "list", "devices", "available", "-j"])
        self.assertEqual(calls[0][1], 60)
        self.assertEqual(calls[1][1], 30)

    def test_owned_cleanup_only_deletes_receipt_uuid(self):
        with tempfile.TemporaryDirectory() as temp:
            receipt = str(Path(temp) / "owned.json")
            record = {"udid": self.CREATED_UDID, "name": "OpenJump-ephemeral-test1234",
                      "runtime": self.RUNTIME, "device_type": self.DEVICE_TYPE,
                      "template_udid": self.TEMPLATE_UDID}
            select_simulator.write_ownership_receipt(receipt, record)
            loaded = select_simulator.read_ownership_receipt(receipt)
            self.assertEqual(loaded["udid"], self.CREATED_UDID)
            with patch.object(select_simulator.subprocess, "check_output") as delete:
                delete.return_value = b""
                with patch.object(sys, "argv",
                                  ["select_simulator.py", "--cleanup", "--receipt", receipt]):
                    self.assertEqual(select_simulator.main(), 0)
            delete.assert_called_once_with(["xcrun", "simctl", "delete", self.CREATED_UDID],
                                            timeout=60)
            # Invalid receipt never deletes an arbitrary or template UUID.
            bad = str(Path(temp) / "bad.json")
            Path(bad).write_text(json.dumps({"udid": self.TEMPLATE_UDID, "runtime": "other",
                                              "device_type": "other", "name": "other"}),
                                 encoding="utf-8")
            with patch.object(select_simulator.subprocess, "check_output") as delete:
                with patch.object(sys, "argv",
                                  ["select_simulator.py", "--cleanup", "--receipt", bad]):
                    self.assertEqual(select_simulator.main(), 0)
            delete.assert_not_called()
            missing = str(Path(temp) / "missing.json")
            with patch.object(select_simulator.subprocess, "check_output") as delete:
                with patch.object(sys, "argv",
                                  ["select_simulator.py", "--cleanup", "--receipt", missing]):
                    self.assertEqual(select_simulator.main(), 0)
            delete.assert_not_called()

    def test_owned_budgets_leave_margin_within_discovery_cap(self):
        self.assertEqual(select_simulator.DISCOVERY_QUERY_TIMEOUT, 60)
        self.assertEqual(select_simulator.CREATE_TIMEOUT, 30)
        self.assertLess(select_simulator.DISCOVERY_QUERY_TIMEOUT + select_simulator.CREATE_TIMEOUT, 120)
        self.assertEqual(select_simulator.EXPECTED_RUNTIME, self.RUNTIME)
        self.assertEqual(select_simulator.EXPECTED_DEVICE_TYPE, self.DEVICE_TYPE)
        self.assertTrue(select_simulator.OWNED_NAME_PREFIX.startswith("OpenJump"))


class OwnedCliStrictTests(unittest.TestCase):
    TEMPLATE_UDID = "AAAAAAAA-1111-2222-3333-444444444444"
    CREATED_UDID = "BBBBBBBB-1111-2222-3333-444444444444"
    RUNTIME = "com.apple.CoreSimulator.SimRuntime.iOS-26-0"
    DEVICE_TYPE = "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro"
    SCRIPT = "select_simulator.py"

    def template_listing(self):
        return json.dumps({"devices": {self.RUNTIME: [
            {"name": "iPhone 17 Pro", "udid": self.TEMPLATE_UDID, "isAvailable": True,
             "state": "Shutdown", "deviceTypeIdentifier": self.DEVICE_TYPE},
        ]}}).encode()

    def make_list_create_fake(self, calls, expected_name=None, fail_create=None):
        listing = self.template_listing()

        def fake_check_output(argv, timeout=None):
            calls.append((list(argv), timeout))
            if list(argv) == ["xcrun", "simctl", "list", "devices", "available", "-j"]:
                return listing
            if fail_create is not None:
                raise fail_create
            expected = ["xcrun", "simctl", "create", expected_name or argv[3],
                        self.DEVICE_TYPE, self.RUNTIME]
            self.assertEqual(list(argv), expected)
            return (self.CREATED_UDID + "\n").encode()

        return fake_check_output

    def run_main(self, argv):
        stdout = io.StringIO()
        with patch.object(sys, "argv", argv), \
                contextlib.redirect_stdout(stdout):
            result = select_simulator.main()
        return result, stdout.getvalue()

    def test_main_create_owned_workflow_cli_exact(self):
        # Literal workflow argv: GITHUB_OUTPUT --create-owned --receipt PATH.
        with tempfile.TemporaryDirectory() as temp:
            github_output = str(Path(temp) / "github_output")
            receipt = str(Path(temp) / "owned.json")
            calls = []
            argv = [self.SCRIPT, github_output, "--create-owned", "--receipt", receipt]
            fake = self.make_list_create_fake(calls)
            with patch.object(select_simulator.subprocess, "check_output", side_effect=fake):
                rc, stdout = self.run_main(argv)
            self.assertEqual(rc, 0)
            self.assertEqual(len(calls), 2)
            self.assertEqual(calls[0][0],
                             ["xcrun", "simctl", "list", "devices", "available", "-j"])
            self.assertEqual(calls[0][1], 60)
            self.assertEqual(calls[1][0][:3], ["xcrun", "simctl", "create"])
            self.assertEqual(calls[1][0][4:], [self.DEVICE_TYPE, self.RUNTIME])
            self.assertEqual(calls[1][1], 30)
            created_name = calls[1][0][3]
            self.assertRegex(created_name, r"^OpenJump-ephemeral-[0-9a-f]{8}$")
            record = select_simulator.read_ownership_receipt(receipt)
            self.assertEqual(record["udid"], self.CREATED_UDID)
            self.assertEqual(record["name"], created_name)
            body = Path(github_output).read_text(encoding="utf-8")
            self.assertIn(f"udid={self.CREATED_UDID}\n", body)
            self.assertIn(f"name={created_name}\n", body)
            self.assertIn(f"runtime={self.RUNTIME}\n", body)
            self.assertIn("owned=true\n", body)

    def test_main_create_owned_with_name_uses_fixed_argv(self):
        with tempfile.TemporaryDirectory() as temp:
            github_output = str(Path(temp) / "github_output")
            receipt = str(Path(temp) / "owned.json")
            calls = []
            name = "OpenJump-ephemeral-abc12345"
            argv = [self.SCRIPT, github_output, "--create-owned", "--receipt", receipt,
                    "--name", name]
            fake = self.make_list_create_fake(calls, expected_name=name)
            with patch.object(select_simulator.subprocess, "check_output", side_effect=fake):
                rc, _ = self.run_main(argv)
            self.assertEqual(rc, 0)
            self.assertEqual(calls[1][0], ["xcrun", "simctl", "create", name,
                                           self.DEVICE_TYPE, self.RUNTIME])
            record = select_simulator.read_ownership_receipt(receipt)
            self.assertEqual(record["name"], name)

    def test_main_create_owned_failure_emits_no_outputs_or_fallback(self):
        with tempfile.TemporaryDirectory() as temp:
            github_output = str(Path(temp) / "github_output")
            receipt = str(Path(temp) / "owned.json")
            calls = []
            argv = [self.SCRIPT, github_output, "--create-owned", "--receipt", receipt,
                    "--name", "OpenJump-ephemeral-abc12345"]
            failure = subprocess.CalledProcessError(1, ["xcrun", "simctl", "create"])
            fake = self.make_list_create_fake(calls, expected_name="OpenJump-ephemeral-abc12345",
                                              fail_create=failure)
            stdout = io.StringIO()
            with patch.object(select_simulator.subprocess, "check_output", side_effect=fake), \
                    patch.object(sys, "argv", argv), \
                    contextlib.redirect_stdout(stdout):
                with self.assertRaises(SystemExit) as raised:
                    select_simulator.main()
            self.assertEqual(raised.exception.code, 1)
            self.assertIn("UNKNOWN", stdout.getvalue())
            self.assertNotIn(self.CREATED_UDID, stdout.getvalue())
            self.assertFalse(Path(github_output).exists())
            self.assertFalse(Path(receipt).exists())

    def test_cli_rejects_unknown_missing_flagvalue_conflicts_extra(self):
        cases = [
            ["GH", "--create-owned", "--receipt", "R", "--bogus"],
            ["GH", "--create-owned", "--receipt"],
            ["GH", "--create-owned", "--receipt", "--name",
             "OpenJump-ephemeral-abc12345"],
            ["GH", "--create-owned", "--receipt", "R", "--cleanup"],
            ["GH", "EXTRA", "--create-owned", "--receipt", "R"],
            ["GH", "EXTRA"],
            ["GH", "--receipt", "R"],
            ["GH", "--create-owned", "--name", "OpenJump-ephemeral-abc12345"],
            ["--cleanup", "--receipt", "R", "EXTRA"],
            ["GH", "--cleanup", "--receipt", "R", "--create-owned"],
            ["--cleanup", "--receipt", "--create-owned"],
            [],
        ]
        for extra in cases:
            with self.subTest(argv=extra):
                with tempfile.TemporaryDirectory() as temp:
                    argv = [self.SCRIPT] + [
                        item if item not in ("GH", "R")
                        else str(Path(temp) / ("github_output" if item == "GH" else "owned.json"))
                        for item in extra]
                    with patch.object(select_simulator.subprocess,
                                      "check_output") as discover:
                        with patch.object(sys, "argv", argv):
                            with self.assertRaises(SystemExit) as raised:
                                select_simulator.main()
                    self.assertIn("usage:", str(raised.exception.code))
                    discover.assert_not_called()
                    for item in argv[1:]:
                        if not item.startswith("-"):
                            self.assertFalse(Path(item).exists())

    def test_invalid_names_rejected_before_inventory_query(self):
        bad_names = ["evil", "OpenJump-ephemeral-", "OpenJump-ephemeral-bad name",
                     "OpenJump-ephemeral-a=b", "OpenJump-ephemeral-ok\ninjected=true",
                     "OpenJump-ephemeral-ok\rinjected=true", "--create-owned",
                     "OpenJump-ephemeral-" + "a" * 33, "x" * 65,
                     "OpenJump-ephemeral-ok;rm -rf /"]
        for bad in bad_names:
            with self.subTest(name=bad):
                with tempfile.TemporaryDirectory() as temp:
                    github_output = str(Path(temp) / "github_output")
                    receipt = str(Path(temp) / "owned.json")
                    argv = [self.SCRIPT, github_output, "--create-owned", "--receipt",
                            receipt, "--name", bad]
                    with patch.object(select_simulator.subprocess,
                                      "check_output") as discover:
                        with patch.object(sys, "argv", argv):
                            with self.assertRaises(SystemExit):
                                select_simulator.main()
                    discover.assert_not_called()
                    self.assertFalse(Path(receipt).exists())
                    self.assertFalse(Path(github_output).exists())

    def test_actual_cli_exit_propagation_via_runpy(self):
        script = str(ROOT / "select_simulator.py")
        with tempfile.TemporaryDirectory() as temp:
            receipt = str(Path(temp) / "owned.json")
            record = {"udid": self.CREATED_UDID, "name": "OpenJump-ephemeral-test1234",
                      "runtime": self.RUNTIME, "device_type": self.DEVICE_TYPE,
                      "template_udid": self.TEMPLATE_UDID}
            select_simulator.write_ownership_receipt(receipt, record)
            failures = [subprocess.CalledProcessError(7, ["xcrun", "simctl", "delete"]),
                        subprocess.TimeoutExpired(["xcrun", "simctl", "delete"], 60),
                        OSError("simctl missing")]
            for failure in failures:
                with self.subTest(failure=type(failure).__name__):
                    argv = [self.SCRIPT, "--cleanup", "--receipt", receipt]
                    with patch.object(sys, "argv", argv), \
                            patch.object(subprocess, "check_output", side_effect=failure):
                        with self.assertRaises(SystemExit) as raised:
                            runpy.run_path(script, run_name="__main__")
                    self.assertEqual(raised.exception.code, 1)
            with patch.object(sys, "argv", [self.SCRIPT, "--cleanup", "--receipt", receipt]), \
                    patch.object(subprocess, "check_output", return_value=b""):
                with self.assertRaises(SystemExit) as raised:
                    runpy.run_path(script, run_name="__main__")
            self.assertEqual(raised.exception.code, 0)
            missing = str(Path(temp) / "missing.json")
            with patch.object(sys, "argv", [self.SCRIPT, "--cleanup", "--receipt", missing]), \
                    patch.object(subprocess, "check_output") as delete:
                with self.assertRaises(SystemExit) as raised:
                    runpy.run_path(script, run_name="__main__")
            self.assertEqual(raised.exception.code, 0)
            delete.assert_not_called()

    def test_cleanup_rejects_nonmapping_receipts_without_traceback(self):
        secret = "PRIVATE_RECEIPT_SENTINEL_9f8e7d"
        payloads = [[], "x", None, 42, {}, {"udid": secret},
                    {"udid": self.CREATED_UDID, "name": secret,
                     "runtime": self.RUNTIME, "device_type": self.DEVICE_TYPE,
                     "template_udid": self.TEMPLATE_UDID},
                    '{"udid": "unterminated"']
        for payload in payloads:
            with self.subTest(payload=repr(payload)[:40]):
                with tempfile.TemporaryDirectory() as temp:
                    receipt = str(Path(temp) / "owned.json")
                    if isinstance(payload, str) and payload.startswith("{"):
                        Path(receipt).write_text(payload + secret, encoding="utf-8")
                    else:
                        Path(receipt).write_text(json.dumps(payload), encoding="utf-8")
                    argv = [self.SCRIPT, "--cleanup", "--receipt", receipt]
                    with patch.object(select_simulator.subprocess,
                                      "check_output") as delete:
                        rc, stdout = self.run_main(argv)
                    self.assertEqual(rc, 0)
                    delete.assert_not_called()
                    self.assertIn("UNKNOWN", stdout)
                    self.assertNotIn(secret, stdout)
                    self.assertNotIn(receipt, stdout)

    def test_receipt_uuid_normalized_factory_match_refuses_delete(self):
        canonical = self.TEMPLATE_UDID
        variants = [canonical, canonical.lower(), canonical.replace("-", ""),
                    "{" + canonical + "}", canonical.lower().replace("-", "")]
        for variant in variants:
            with self.subTest(variant=variant[:20]):
                with tempfile.TemporaryDirectory() as temp:
                    receipt = str(Path(temp) / "owned.json")
                    Path(receipt).write_text(json.dumps(
                        {"udid": variant, "name": "OpenJump-ephemeral-test1234",
                         "runtime": self.RUNTIME, "device_type": self.DEVICE_TYPE,
                         "template_udid": canonical}), encoding="utf-8")
                    with patch.object(select_simulator.subprocess,
                                      "check_output") as delete:
                        rc, _ = self.run_main([self.SCRIPT, "--cleanup", "--receipt", receipt])
                    self.assertEqual(rc, 0)
                    delete.assert_not_called()
        # Missing or invalid template never deletes either.
        for template in [None, "not-a-uuid"]:
            with tempfile.TemporaryDirectory() as temp:
                receipt = str(Path(temp) / "owned.json")
                record = {"udid": self.CREATED_UDID, "name": "OpenJump-ephemeral-test1234",
                          "runtime": self.RUNTIME, "device_type": self.DEVICE_TYPE}
                if template is not None:
                    record["template_udid"] = template
                Path(receipt).write_text(json.dumps(record), encoding="utf-8")
                with patch.object(select_simulator.subprocess, "check_output") as delete:
                    rc, _ = self.run_main([self.SCRIPT, "--cleanup", "--receipt", receipt])
                self.assertEqual(rc, 0)
                delete.assert_not_called()
        # A distinct UUID in any case deletes with normalized dashed-upper argv.
        with tempfile.TemporaryDirectory() as temp:
            receipt = str(Path(temp) / "owned.json")
            record = {"udid": self.CREATED_UDID.lower(), "name": "OpenJump-ephemeral-test1234",
                      "runtime": self.RUNTIME, "device_type": self.DEVICE_TYPE,
                      "template_udid": self.TEMPLATE_UDID.lower()}
            select_simulator.write_ownership_receipt(receipt, record)
            with patch.object(select_simulator.subprocess, "check_output",
                              return_value=b"") as delete:
                rc, _ = self.run_main([self.SCRIPT, "--cleanup", "--receipt", receipt])
            self.assertEqual(rc, 0)
            delete.assert_called_once_with(["xcrun", "simctl", "delete", self.CREATED_UDID],
                                            timeout=60)

    def test_receipt_written_before_github_output_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            github_output = str(Path(temp) / "github_is_dir")
            Path(github_output).mkdir()
            receipt = str(Path(temp) / "owned.json")
            calls = []
            argv = [self.SCRIPT, github_output, "--create-owned", "--receipt", receipt,
                    "--name", "OpenJump-ephemeral-abc12345"]
            fake = self.make_list_create_fake(calls, expected_name="OpenJump-ephemeral-abc12345")
            stdout = io.StringIO()
            with patch.object(select_simulator.subprocess, "check_output", side_effect=fake), \
                    patch.object(sys, "argv", argv), \
                    contextlib.redirect_stdout(stdout):
                with self.assertRaises(SystemExit) as raised:
                    select_simulator.main()
            self.assertEqual(raised.exception.code, 1)
            self.assertIn("UNKNOWN", stdout.getvalue())
            self.assertNotIn(receipt, stdout.getvalue())
            record = select_simulator.read_ownership_receipt(receipt)
            self.assertEqual(record["udid"], self.CREATED_UDID)
            leftovers = [name for name in os.listdir(temp) if name.startswith(".owned-simulator-")]
            self.assertEqual(leftovers, [])

    def test_receipt_collision_creates_nothing_and_overwrites_nothing(self):
        stale_udid = "DDDDDDDD-1111-2222-3333-444444444444"
        with tempfile.TemporaryDirectory() as temp:
            github_output = str(Path(temp) / "github_output")
            receipt = str(Path(temp) / "owned.json")
            stale = {"udid": stale_udid, "name": "OpenJump-ephemeral-stale123",
                     "runtime": self.RUNTIME, "device_type": self.DEVICE_TYPE,
                     "template_udid": self.TEMPLATE_UDID}
            select_simulator.write_ownership_receipt(receipt, stale)
            before = Path(receipt).read_text(encoding="utf-8")
            argv = [self.SCRIPT, github_output, "--create-owned", "--receipt", receipt,
                    "--name", "OpenJump-ephemeral-abc12345"]
            with patch.object(select_simulator.subprocess, "check_output") as discover:
                rc, stdout = self.run_main(argv)
            self.assertEqual(rc, 1)
            discover.assert_not_called()
            self.assertEqual(Path(receipt).read_text(encoding="utf-8"), before)
            self.assertFalse(Path(github_output).exists())
            self.assertIn("collision", stdout)
            self.assertNotIn(receipt, stdout)


    def test_cli_rejects_abbreviated_flags_without_side_effects(self):
        cases = [["GH", "--create-o", "--receipt", "R"],
                 ["GH", "--create-owned", "--rec", "R"],
                 ["--clean", "--receipt", "R"]]
        for args in cases:
            with self.subTest(args=args), \
                    patch.object(select_simulator.subprocess, "check_output") as command:
                with self.assertRaises(SystemExit):
                    select_simulator.parse_cli_args(args)
                command.assert_not_called()

    def test_trailing_newline_names_are_rejected_before_inventory(self):
        for suffix in ["\n", "\n\n"]:
            name = "OpenJump-ephemeral-safe" + suffix
            with self.subTest(suffix=repr(suffix)), \
                    patch.object(select_simulator.subprocess, "check_output", side_effect=[
                        self.template_listing(), (self.CREATED_UDID + "\n").encode()]) as command:
                with self.assertRaises(ValueError):
                    select_simulator.create_owned_record(name=name)
                command.assert_not_called()

    def test_validation_only_workflow_keeps_runtime_gate_and_blocks_ipa(self):
        workflow = (ROOT.parent.parent / ".github/workflows/apple-prototype.yml").read_text(
            encoding="utf-8")
        boot = workflow.split("        id: boot\n", 1)[1].split("      - name:", 1)[0]
        self.assertIn('DEVICE=\'${{ steps.simulator.outputs.udid }}\'', boot)
        self.assertIn('--timeout 480 -- xcrun simctl bootstatus "$DEVICE" -b', boot)
        self.assertNotIn("continue-on-error", boot)
        tests = workflow.split("        id: native_tests\n", 1)[1].split("      - name:", 1)[0]
        self.assertIn("if: success() && steps.boot.conclusion == 'success'", tests)
        self.assertIn('--timeout 1100 -- xcodebuild test-without-building', tests)
        self.assertIn('id=${{ steps.simulator.outputs.udid }}', tests)
        self.assertNotIn("continue-on-error", tests)
        device = workflow.split("\n  device:\n", 1)[1]
        self.assertIn("    if: ${{ false }}\n", device)
        self.assertIn("    needs: prototype\n", device)

    def test_workflow_cleanup_requires_this_job_owned_output(self):
        # Independent literal contract, not an oracle built from the helper.
        # A stale receipt or failed GITHUB_OUTPUT must not authorize deletion.
        workflow = (ROOT.parent.parent / ".github/workflows/apple-prototype.yml").read_text(
            encoding="utf-8")
        block = workflow.split("        id: owned_cleanup\n", 1)[1].split("      - name:", 1)[0]
        conditions = [line.strip() for line in block.splitlines()
                      if line.strip().startswith("if:")]
        self.assertEqual(conditions, [
            "if: always() && steps.simulator.outputs.owned == 'true'"])


if __name__ == "__main__":
    unittest.main()
