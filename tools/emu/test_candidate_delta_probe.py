import tempfile
import unittest
from pathlib import Path

from candidate_delta_probe import build_manifest, verify_exact_delta, write_manifest


class CandidateDeltaProbeTests(unittest.TestCase):
    def test_accepts_exact_same_size_member_delta(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            baseline = root / "baseline"
            candidate = root / "candidate"
            baseline.mkdir()
            candidate.mkdir()
            (baseline / "music.sty").write_bytes(b"stock")
            (candidate / "music.sty").write_bytes(b"patch")
            (baseline / "setting.sty").write_bytes(b"same")
            (candidate / "setting.sty").write_bytes(b"same")

            changed = verify_exact_delta(baseline, candidate, ("music.sty",))

        self.assertEqual(changed, ("music.sty",))

    def test_rejects_unexpected_changed_member(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            baseline = root / "baseline"
            candidate = root / "candidate"
            baseline.mkdir()
            candidate.mkdir()
            (baseline / "music.sty").write_bytes(b"stock")
            (candidate / "music.sty").write_bytes(b"patch")

            with self.assertRaisesRegex(AssertionError, "unexpected member delta"):
                verify_exact_delta(baseline, candidate, ())

    def test_rejects_member_set_change(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            baseline = root / "baseline"
            candidate = root / "candidate"
            baseline.mkdir()
            candidate.mkdir()
            (baseline / "music.sty").write_bytes(b"same")
            (candidate / "music.sty").write_bytes(b"same")
            (candidate / "setting.sty").write_bytes(b"extra")

            with self.assertRaisesRegex(AssertionError, "member set changed"):
                verify_exact_delta(baseline, candidate, ())

    def test_rejects_size_change_by_default(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            baseline = root / "baseline"
            candidate = root / "candidate"
            baseline.mkdir()
            candidate.mkdir()
            (baseline / "music.sty").write_bytes(b"stock")
            (candidate / "music.sty").write_bytes(b"larger")

            with self.assertRaisesRegex(AssertionError, "member size changed"):
                verify_exact_delta(baseline, candidate, ("music.sty",))

    def test_manifest_records_hashes_sizes_and_non_flashable_scope(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            baseline = root / "baseline"
            candidate = root / "candidate"
            baseline.mkdir()
            candidate.mkdir()
            (baseline / "music.sty").write_bytes(b"stock")
            (candidate / "music.sty").write_bytes(b"patch")
            (baseline / "setting.sty").write_bytes(b"same")
            (candidate / "setting.sty").write_bytes(b"same")

            manifest = build_manifest(baseline, candidate, ("music.sty",))
            output = root / "manifest.json"
            written = write_manifest(baseline, candidate, ("music.sty",), output)

        self.assertEqual(manifest, written)
        self.assertFalse(manifest["flashable_hex"])
        self.assertEqual(manifest["changed_members"], ["music.sty"])
        self.assertEqual(manifest["member_count"], 2)
        self.assertEqual(manifest["members"]["music.sty"]["baseline_size"], 5)
        self.assertNotEqual(
            manifest["members"]["music.sty"]["baseline_sha256"],
            manifest["members"]["music.sty"]["candidate_sha256"],
        )


if __name__ == "__main__":
    unittest.main()
