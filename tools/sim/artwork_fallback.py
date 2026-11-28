"""Bounded community JPEG artwork-fallback policy."""

from __future__ import annotations

from dataclasses import dataclass


MISSING_ARTWORK_STATUS = 0xFF
MAX_ARTWORK_DIMENSION = 150
MAX_ARTWORK_BYTES = 45_000
MAX_JPEG_BLOCKS = 0x1000
SUPPORTED_JPEG_COMPONENTS = frozenset({1, 3})


@dataclass(frozen=True)
class ArtworkFallbackPlan:
    """Decision at the fallback boundary without decoding JPEG data."""

    attempted: bool
    accepted: bool
    output_bytes: int | None
    reason: str


def plan_community_jpeg_fallback(
    decoder_status: int,
    width: int,
    height: int,
    components: int,
    block_count: int,
) -> ArtworkFallbackPlan:
    """Apply the recovered fallback bounds to one artwork candidate."""
    if not 0 <= decoder_status <= 0xFF:
        raise ValueError("decoder status must fit an unsigned byte")
    if width < 0 or height < 0:
        raise ValueError("artwork dimensions must be non-negative")
    if components < 0 or block_count < 0:
        raise ValueError("JPEG metadata values must be non-negative")

    if decoder_status != MISSING_ARTWORK_STATUS:
        return ArtworkFallbackPlan(
            attempted=False,
            accepted=False,
            output_bytes=None,
            reason="decoder-did-not-report-missing-artwork",
        )

    if not 1 <= width <= MAX_ARTWORK_DIMENSION:
        return ArtworkFallbackPlan(True, False, None, "width-out-of-range")

    if not 1 <= height <= MAX_ARTWORK_DIMENSION:
        return ArtworkFallbackPlan(True, False, None, "height-out-of-range")

    if components not in SUPPORTED_JPEG_COMPONENTS:
        return ArtworkFallbackPlan(True, False, None, "unsupported-component-count")

    if block_count == 0:
        return ArtworkFallbackPlan(True, False, None, "empty-JPEG-block-count")

    if block_count > MAX_JPEG_BLOCKS:
        return ArtworkFallbackPlan(True, False, None, "JPEG-block-limit-exceeded")

    output_bytes = width * height * 2
    if output_bytes > MAX_ARTWORK_BYTES:
        return ArtworkFallbackPlan(True, False, None, "output-buffer-limit-exceeded")

    return ArtworkFallbackPlan(True, True, output_bytes, "bounded-JPEG-fallback")
