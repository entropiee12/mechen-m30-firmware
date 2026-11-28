import unittest
from pathlib import Path

from usound_fifo_binary_probe import verify


ROOT = Path(__file__).resolve().parents[2]


class UsoundFifoBinaryProbeTests(unittest.TestCase):
    def test_local_corpora_keep_the_same_word_port_pool(self) -> None:
        for path in sorted((ROOT / "analysis").glob("*/fw/usound.drv")):
            with self.subTest(path=path.parent.parent.name):
                verify(path)


if __name__ == "__main__":
    unittest.main()
