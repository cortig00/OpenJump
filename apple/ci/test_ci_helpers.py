import shlex
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

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


if __name__ == "__main__":
    unittest.main()
