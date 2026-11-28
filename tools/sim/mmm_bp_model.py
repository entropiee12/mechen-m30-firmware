"""Non-executing model of the recovered ``mmm_bp.al`` buffer boundary.

This models only observable control contracts from the decompilation.  It is
not an audio renderer and deliberately does not invent sample attenuation
coefficients.
"""

from __future__ import annotations

from dataclasses import dataclass


REFILL_BLOCK_BYTES = 0x200
FADE_MODES = {2: "slow", 3: "fast"}
FADE_PHASE_INTERVAL = 60
FADE_LEVEL_STEP = 1000


@dataclass(frozen=True)
class RingReadPlan:
    """A bounded, possibly wrapped read from the decoder ring."""

    requested: int
    first_offset: int
    first_length: int
    second_offset: int | None
    second_length: int


@dataclass(frozen=True)
class FadeStepPlan:
    """One control-step result without executing the audio callback."""

    mode: int
    step_count: int
    phase: int
    level: int
    callback_due: bool


def plan_ring_read(
    read_offset: int,
    capacity: int,
    available: int,
    requested: int,
) -> RingReadPlan:
    """Return the two-copy plan used by the ring reader.

    The recovered reader accepts a request only when it is no larger than the
    available count, then splits it at the physical ring end.  It does not
    read or mutate a buffer here.
    """
    if capacity <= 0:
        raise ValueError("ring capacity must be positive")
    if not 0 <= read_offset < capacity:
        raise ValueError("read offset must be inside the ring")
    if not 0 <= available <= capacity:
        raise ValueError("available count must fit the ring")
    if not 0 <= requested <= available:
        raise ValueError("requested bytes exceed the available count")
    first_length = min(requested, capacity - read_offset)
    second_length = requested - first_length
    return RingReadPlan(
        requested=requested,
        first_offset=read_offset,
        first_length=first_length,
        second_offset=0 if second_length else None,
        second_length=second_length,
    )


def read_ring_bytes(
    source: bytes | bytearray,
    read_offset: int,
    available: int,
    requested: int,
) -> bytes:
    """Read a planned decoder-ring payload without interpreting its samples."""
    plan = plan_ring_read(read_offset, len(source), available, requested)
    first = source[plan.first_offset : plan.first_offset + plan.first_length]
    second = source[: plan.second_length]
    return bytes(first + second)


def fade_mode_name(mode: int) -> str:
    """Return the recovered diagnostic/state name for a fade mode."""
    return FADE_MODES.get(mode, "inactive")


def advance_fade_step(
    mode: int,
    step_count: int,
    boundary_value: int,
    base_level: int,
) -> FadeStepPlan:
    """Apply the recovered 60-step fade phase without rendering audio.

    ``boundary_value`` is the opaque state word compared with 100 by the
    firmware. The host model preserves the caller-provided base level and
    exposes only the proven +/-1000-per-60-step arithmetic.
    """
    if mode < 0:
        raise ValueError("fade mode must be non-negative")
    if not 0 <= step_count <= 0xFFFF:
        raise ValueError("fade step count must fit an unsigned 16-bit field")
    if not 0 <= boundary_value <= 0xFFFF:
        raise ValueError("fade boundary value must fit an unsigned 16-bit field")

    target_mode = 2 if boundary_value < 100 else 3
    continuing = mode == target_mode
    next_step = step_count + 1 if continuing else 0
    phase = next_step // FADE_PHASE_INTERVAL
    direction = -1 if target_mode == 2 else 1

    return FadeStepPlan(
        mode=target_mode,
        step_count=next_step,
        phase=phase,
        level=base_level + direction * phase * FADE_LEVEL_STEP,
        callback_due=continuing and next_step % FADE_PHASE_INTERVAL == 0,
    )
