import json
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
        help_text = ("Usage: list [-j|--json] [-e|--enc] "
                     "[devices|devicetypes|runtimes|pairs] [<search term>|available]\n")
        responses = [help_text, json.dumps(devices), json.dumps(runtimes),
                     "Xcode 26.0.1\nBuild version 17A400\n" + secret,
                     "state = running\npid = 123\nenvironment = " + secret]

        def probe(command, timeout):
            self.assertLessEqual(timeout, diagnose_simulator.COMMAND_SECONDS)
            return {"command": command, "exit_code": 0, "timed_out": False,
                    "stdout_capture_truncated": False, "stdout_capture_complete": True,
                    "stderr_drain_complete": True, "stderr_discarded_bytes": 0,
                    "status": "unknown"}, responses.pop(0)

        with patch.object(diagnose_simulator, "run_probe", side_effect=probe) as run, \
                patch.dict(diagnose_simulator.os.environ, {"DEVELOPER_DIR": secret}):
            report = diagnose_simulator.collect(self.UDID, "iPhone", self.RUNTIME, 501)
        commands = [call.args[0] for call in run.call_args_list]
        self.assertEqual(len(commands), 5)
        self.assertTrue(all("boot" not in cmd and "bootstatus" not in cmd for cmd in commands))
        self.assertEqual(commands[0], ["xcrun", "simctl", "help", "list"])
        self.assertEqual(commands[1], ["xcrun", "simctl", "list", "-j", "devices", self.UDID])
        self.assertEqual(commands[2], ["xcrun", "simctl", "list", "-j", "runtimes", self.RUNTIME])
        self.assertEqual(commands[-1], ["launchctl", "print",
                         "gui/501/com.apple.CoreSimulator.CoreSimulatorService"])
        self.assertEqual(report["probes"][1]["data"]["name"], "iPhone 17Pro")
        self.assertEqual(report["probes"][1]["data"]["udid"], self.UDID)
        self.assertEqual(report["probes"][2]["data"]["identifier"], self.RUNTIME)
        self.assertEqual(report["probes"][0]["data"], {
            "device_filter_advertised": True, "runtime_filter_advertised": True})
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
        help_output = "usage: simctl list [devices|runtimes]"
        responses = [help_output, "Xcode 26.0.1\nBuild version 17A400\n", ""]

        def probe(command, timeout):
            return ({"command": command, "exit_code": 0, "timed_out": False,
                     "stdout_capture_truncated": False, "stdout_capture_complete": True,
                     "stderr_drain_complete": True, "stderr_discarded_bytes": 0,
                     "status": "unknown"}, responses.pop(0))

        with patch.object(diagnose_simulator, "run_probe", side_effect=probe) as run:
            report = diagnose_simulator.collect(self.UDID, "iPhone", self.RUNTIME, 501)
        commands = [call.args[0] for call in run.call_args_list]
        self.assertEqual(commands, [["xcrun", "simctl", "help", "list"],
                                    ["xcodebuild", "-version"],
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
                      "timed_out": False, "status": "unknown"}
            with patch.object(diagnose_simulator, "run_probe",
                              side_effect=lambda *args: (dict(result), raw)):
                report = diagnose_simulator.collect(self.UDID, "iPhone", self.RUNTIME, 501)
            self.assertTrue(all(p["status"] == "unknown" and p["data"] == {}
                                for p in report["probes"]))
            self.assertNotIn(raw, json.dumps(report))

    def test_total_budget_exhaustion_skips_all_commands(self):
        with patch.object(diagnose_simulator.time, "monotonic", side_effect=[0] + [40] * 6):
            with patch.object(diagnose_simulator, "run_probe") as run:
                report = diagnose_simulator.collect(self.UDID, "iPhone", self.RUNTIME, 501)
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


if __name__ == "__main__":
    unittest.main()
