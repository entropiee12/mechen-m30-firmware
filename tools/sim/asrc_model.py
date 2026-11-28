"""Evidence-backed end-of-track branch from the M30 music application.

The accepted V1.101.11/community delta adds mode 4 to the existing advance
path.  This module models that control-flow boundary only; it does not claim
to drain an ASRC queue or produce audible samples.
"""

from __future__ import annotations


END_OF_TRACK_MODE = 3
COMMUNITY_DRAIN_MODE = 4


def uses_end_of_track_advance_path(mode: int, *, drain_fix: bool = False) -> bool:
    """Return whether the firmware branch reaches the existing advance path."""
    if not 0 <= mode <= 0xFF:
        raise ValueError("playback mode must fit an unsigned byte")

    if mode == END_OF_TRACK_MODE:
        return True

    if drain_fix and mode == COMMUNITY_DRAIN_MODE:
        return True

    return False
