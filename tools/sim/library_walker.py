"""Host model of the verified stock library directory walker."""

from __future__ import annotations

from dataclasses import dataclass


MAX_DIRECTORY_DEPTH = 8
RECORD_FOLDER_NAME = "RECORD"


@dataclass(frozen=True)
class DirectoryNode:
    """A directory summary used by the walker contract."""

    name: str
    song_count: int = 0
    children: tuple["DirectoryNode", ...] = ()

    def __post_init__(self) -> None:
        if self.song_count < 0:
            raise ValueError("song_count must be non-negative")


def song_folders(root: DirectoryNode) -> tuple[DirectoryNode, ...]:
    """Return song-containing folders in the stock depth-first order."""
    ordered: list[DirectoryNode] = []

    def visit(node: DirectoryNode, depth: int) -> None:
        if node.name.casefold() == RECORD_FOLDER_NAME.casefold():
            return

        if node.song_count > 0:
            ordered.append(node)

        if depth >= MAX_DIRECTORY_DEPTH:
            return

        for child in node.children:
            visit(child, depth + 1)

    visit(root, 0)
    return tuple(ordered)


def walk_target(root: DirectoryNode, target: int) -> int:
    """Mirror the walker return value for target zero or one-based targets."""
    if target < 0:
        raise ValueError("target must be non-negative")

    count = len(song_folders(root))
    if target == 0:
        return count

    return target if target <= count else 0
