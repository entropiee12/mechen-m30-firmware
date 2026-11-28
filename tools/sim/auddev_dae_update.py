"""Host model of the ``aud_dev`` table-index-18 DAE update (``a0 == 1`` path).

Source: ``analysis/community/fw/aud_dev.drv`` SHA-256
``3349287198faaa90a503353673674d5628e85711db9ee16217db5a803484f09c``, bank 5
function at window ``0x9400``.  The values below were measured by
``tools/ghidra/run_auddev_idx18_rate_probe.sh`` and
``run_auddev_idx18_drc_probe.sh`` (MIPS16 p-code, synthetic ROM copy service):

* the rate gate is ``48000 < rate`` in two signed ``slt`` compares (``0x9556``
  and ``0x9620``);
* the EQ flag ``cfg+0x3a`` is set to 1 only when the DAE band flag ``+7`` is 1,
  the flag is still 0 and the rate is at most 48000; ``+7 != 1`` clears it;
* ``cfg+0x40`` is written from ``dae+0x0c`` (``0x96ec``) and cleared again by
  the rate branch (``0x962e``) when the rate is above 48000;
* ``cfg+0x28`` becomes 1 at or below 48000 Hz and is cleared above it.

Only the bounded case measured by the probes is modeled: neutral adjustment
words ``dae+0xc2..+0xcb``, no speaker-compensation file (``dae+8 != 1``), and
``cfg+0x28 == 0`` on entry.  The other paths call ROM services or adjust band
gains; this module raises instead of guessing for them.  It describes aud_dev's
internal bytes only, not DSP output.
"""

from __future__ import annotations

from dataclasses import dataclass

from dsp_model import DAE_CONFIG_BYTES, DAE_EQ_MAX_SAMPLE_RATE_HZ


BAND_BYTES = 0x80
BAND_SOURCE_OFFSET = 0x10


@dataclass(frozen=True)
class AudDevDaeState:
    """The aud_dev configuration fields the update touches."""

    eq_flag_3a: int = 0
    busy_28: int = 0
    drc_flag_40: int = 0
    bands: bytes = bytes(BAND_BYTES)

    def __post_init__(self) -> None:
        if len(self.bands) != BAND_BYTES:
            raise ValueError("aud_dev band copy must be exactly 0x80 bytes")


def apply_dae_update(
    state: AudDevDaeState,
    dae_configuration: bytes,
    sample_rate_hz: int,
) -> AudDevDaeState:
    """Return the state after one ``a0 == 1`` update at the given sample rate."""
    if len(dae_configuration) != DAE_CONFIG_BYTES:
        raise ValueError("DAE configuration must be exactly 0xf0 bytes")

    if sample_rate_hz <= 0:
        raise ValueError("sample rate must be a positive integer")

    if state.busy_28 != 0:
        raise NotImplementedError("the busy path calls a ROM delay service")

    if dae_configuration[0x08] == 1:
        raise NotImplementedError("the speaker-compensation path reads a file")

    if any(dae_configuration[0xC2:0xCC]):
        raise NotImplementedError("band gain adjustments are not modeled")

    eq_flag = state.eq_flag_3a
    bands = state.bands
    if dae_configuration[0x07] == 1:
        bands = bytes(
            dae_configuration[BAND_SOURCE_OFFSET : BAND_SOURCE_OFFSET + BAND_BYTES]
        )
        if eq_flag == 0 and sample_rate_hz <= DAE_EQ_MAX_SAMPLE_RATE_HZ:
            eq_flag = 1
    else:
        eq_flag = 0

    drc_flag = dae_configuration[0x0C]
    busy = 0
    if sample_rate_hz > DAE_EQ_MAX_SAMPLE_RATE_HZ:
        eq_flag = 0
        drc_flag = 0
    else:
        busy = 1

    return AudDevDaeState(eq_flag, busy, drc_flag, bands)
