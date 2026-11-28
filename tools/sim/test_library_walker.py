import unittest

from library_walker import DirectoryNode, song_folders, walk_target


class LibraryWalkerTests(unittest.TestCase):
    def test_depth_first_order_and_record_skip_are_preserved(self):
        root = DirectoryNode(
            "ROOT",
            children=(
                DirectoryNode("Album", song_count=1, children=(DirectoryNode("Disc", 2),)),
                DirectoryNode("RECORD", song_count=9),
                DirectoryNode("Mix", song_count=3),
            ),
        )
        self.assertEqual([node.name for node in song_folders(root)], ["Album", "Disc", "Mix"])

    def test_children_beyond_layer_eight_are_not_walked(self):
        node = DirectoryNode("root")
        for depth in range(1, 11):
            node = DirectoryNode(f"d{depth}", song_count=1, children=(node,))

        names = [item.name for item in song_folders(node)]
        self.assertIn("d10", names)
        self.assertIn("d3", names)
        self.assertIn("d2", names)
        self.assertNotIn("d1", names)

    def test_target_zero_counts_and_missing_target_returns_zero(self):
        root = DirectoryNode("root", children=(DirectoryNode("one", 1), DirectoryNode("two", 1)))
        self.assertEqual(walk_target(root, 0), 2)
        self.assertEqual(walk_target(root, 2), 2)
        self.assertEqual(walk_target(root, 3), 0)


if __name__ == "__main__":
    unittest.main()
