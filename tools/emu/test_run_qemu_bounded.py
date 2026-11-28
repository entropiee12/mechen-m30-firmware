import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / "tools/emu/run_qemu_bounded.py"


class RunQemuBoundedTests(unittest.TestCase):
    def test_timeout_terminates_and_waits_for_child(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            child_pid = directory / "child.pid"
            output = directory / "stdout.bin"
            code = (
                "import os,time; "
                f"open({str(child_pid)!r}, 'w').write(str(os.getpid())); "
                "time.sleep(30)"
            )
            result = subprocess.run(
                [sys.executable, str(RUNNER), "--timeout", "0.2", "--stdout",
                 str(output), "--", sys.executable, "-c", code],
                text=True, capture_output=True, check=False,
            )
            self.assertEqual(result.returncode, 124, result.stderr)
            self.assertIn("child terminated and waited", result.stderr)
            pid = int(child_pid.read_text())
            with self.assertRaises(ProcessLookupError):
                os.kill(pid, 0)

    def test_success_captures_stdout_and_exit_status(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "stdout.bin"
            result = subprocess.run(
                [sys.executable, str(RUNNER), "--timeout", "2", "--stdout",
                 str(output), "--", sys.executable, "-c", "print('done')"],
                text=True, capture_output=True, check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(output.read_text(), "done\n")


if __name__ == "__main__":
    unittest.main()
