"""Host model of the ``dae_cfg`` shared-memory flow from boot to DSP selection.

The flow joins separately verified pieces (static decompile, p-code where
noted):

1. ``config.ap`` ``FUN_40dd4fe8`` writes ``dae+2/+3`` from ``state+0x18``
   (``state+0x18 == 1`` gives ``+2=1, +3=0``, anything else ``+2=0, +3=1``) and
   ``FUN_40dd5034`` registers the 0xf0-byte copy as shared-memory id 3
   (kernel op 0x47).  Only ``config.ap`` references ``FUN_40dd4fe8``.
2. ``config.ap`` ``FUN_40054f38`` (end of ``FUN_40054f84``) writes the RAM
   settings block (``0x9fc15654``, id 0, 0x38 bytes) and the RAM DAE image
   (``0x9fc15700``, id ``0xf0000``, 0xf0 bytes) to flash; the DAE image already
   carries ``+2/+3`` from step 1.  ``mengine.ap`` init ``FUN_60050d00`` reads
   the same two ids back: the settings block to ``0x9fc15d9c`` and the DAE
   block to its local structure ``0x9fc15dd4``.  So MEngine's local ``+2`` is
   config's ``+2`` unless something else rewrote the flash block (the flash
   read/write services are ROM and assumed to return what was written).
3. ``mengine.ap`` builder ``FUN_601d0d58`` copies the local structure to the
   pointer from kernel op 0x49 (id 3).  It runs from the ``0x303`` handler
   ``FUN_601d0f30`` (after storing the four payload bytes at ``0x9fc15cc8``)
   and from ``_play`` (``FUN_60190eec``), directly before ``AOUT_OPEN`` and
   ``PLAY``.  Its gate is byte ``+0x18`` of the settings block copy at
   ``0x9fc15d9c`` (``0x9fc15db4``), which is the byte ``config.ap`` seeds from
   item ``0x96``, and local ``+0x0c != 1``; it is not the ``eq_type`` byte.  Its
   selector comes from ``0x9fc15cc9``.  After the copy it sends mmm_mp command
   ``0x1f`` (module ``0x0c`` op ``0x12``, ``a0 == 1``), which hands the shared
   memory to ``aud_dev``.
4. ``music.ap`` ``FUN_40094dd4`` re-sends the stored EQ selector as message
   ``0x303`` before the file and play requests unless the engine-state byte at
   ``0x9fc1a1c8`` is 1.
5. ``mmm_mp.al`` ``FUN_68312d00`` (called from its PLAY command ``0x19``)
   chooses the pp DSP from shared byte ``+2``: zero gives ``adppext.dsp``;
   nonzero gives ``adppdrc.dsp`` at or below 48000 Hz and ``audiopp.dsp``
   above it.  ``mmm_pp.al`` holds a simpler variant (nonzero gives
   ``audiopp.dsp``).  It shares its resident base with ``mmm_mp.al``.  Both
   members are present in the extracted corpus; MEngine requests
   ``mmm_mp.al`` by name, while the loader path for ``mmm_pp.al`` is unknown.

Unproven inputs, kept explicit instead of guessed:

* ``engine_active`` defaults to ``state_flag != 0`` (the persisted ``+0x18``
  byte both applications read).  Pass a value to model a different flash
  content.
* ``mengine_local_structure`` defaults to the DAE image config saved to flash
  (the initial shared memory).  Pass bytes to model a rewritten block.
* The selector byte at ``0x9fc15cc9`` is zero until a ``0x303`` message sets it
  (the mengine BSS is cleared at load; assumption that nothing wrote it first).
* The ROM dispatcher mapping of kernel ops 0x47..0x49 and the semantics of the
  ROM flash read/write services.

This models bytes in shared memory and file names only.  It says nothing about
the audible effect of any DSP.
"""

from __future__ import annotations

from collections.abc import Sequence

from auddev_dae_update import AudDevDaeState, apply_dae_update
from dsp_model import (
    DAE_CONFIG_BYTES,
    DAE_EQ_MAX_SAMPLE_RATE_HZ,
    MENGINE_FLAG_CLEAR_DSP_INDEX,
    MENGINE_FLAG_SET_DSP_INDEX,
    MMM_MP_DSP_NAMES,
)
from kernel_ops import KernelShmRegistry
from m30_model import (
    FIRMWARE_EQ_PRESETS,
    FirmwareEqSliderSession,
    build_firmware_eq_structure,
)


SHM_ID = 3
SHM_BASE_POINTER = 0x10001000
ADPPDRC_INDEX = 15


def mmm_mp_pp_dsp_name(shared_memory: bytes, sample_rate_hz: int) -> str:
    """The pp DSP that ``mmm_mp.al`` ``FUN_68312d00`` asks the loader to open."""
    if len(shared_memory) != DAE_CONFIG_BYTES:
        raise ValueError("shared memory must be exactly 0xf0 bytes")

    if sample_rate_hz <= 0:
        raise ValueError("sample rate must be a positive integer")

    if shared_memory[2] == 0:
        return MMM_MP_DSP_NAMES[MENGINE_FLAG_CLEAR_DSP_INDEX]

    if sample_rate_hz <= DAE_EQ_MAX_SAMPLE_RATE_HZ:
        return MMM_MP_DSP_NAMES[ADPPDRC_INDEX]

    return MMM_MP_DSP_NAMES[MENGINE_FLAG_SET_DSP_INDEX]


def mmm_pp_pp_dsp_name(shared_memory: bytes) -> str:
    """The pp DSP that ``mmm_pp.al`` ``FUN_68013258`` asks the loader to open."""
    if len(shared_memory) != DAE_CONFIG_BYTES:
        raise ValueError("shared memory must be exactly 0xf0 bytes")

    index = (
        MENGINE_FLAG_SET_DSP_INDEX
        if shared_memory[2] != 0
        else MENGINE_FLAG_CLEAR_DSP_INDEX
    )
    return MMM_MP_DSP_NAMES[index]


class DaeSharedMemoryFlow:
    """Shared-memory state touched by config.ap, MEngine and mmm_mp."""

    def __init__(
        self,
        *,
        state_flag: int,
        dae_configuration: bytes,
        engine_active: bool | None = None,
        mengine_local_structure: bytes | None = None,
        registry: KernelShmRegistry | None = None,
    ) -> None:
        if len(dae_configuration) != DAE_CONFIG_BYTES:
            raise ValueError("DAE configuration must be exactly 0xf0 bytes")

        if not 0 <= state_flag <= 0xFF:
            raise ValueError("state+0x18 must fit one byte")

        self.registry = registry if registry is not None else KernelShmRegistry()
        self.engine_active = (
            state_flag != 0 if engine_active is None else engine_active
        )
        self.selector_byte = 0
        self.eq_type_byte = 0
        self._custom: Sequence[int] | None = None
        self._shm = bytearray(dae_configuration)
        self._apply_config_flag(state_flag)
        # FUN_40054f38 saves the image after +2/+3 were set; MEngine reads it back.
        if mengine_local_structure is None:
            mengine_local_structure = bytes(self._shm)

        if len(mengine_local_structure) != DAE_CONFIG_BYTES:
            raise ValueError("MEngine local structure must be exactly 0xf0 bytes")

        self.local_structure = bytes(mengine_local_structure)
        if self.registry.create(SHM_ID, SHM_BASE_POINTER, DAE_CONFIG_BYTES) != 0:
            raise RuntimeError("shared-memory registry has no free slot")

    @property
    def shared_memory(self) -> bytes:
        return bytes(self._shm)

    @property
    def shared_pointer(self) -> int:
        return self.registry.attach(SHM_ID)

    def eq_message(
        self,
        eq_type: int,
        selector: int,
        custom: Sequence[int] | None = None,
    ) -> bool:
        """Deliver message 0x303 and run the builder.  True when it copied."""
        if not 0 <= eq_type <= 0xFF or not 0 <= selector <= 0xFF:
            raise ValueError("eq_type and selector must each fit one byte")

        self.eq_type_byte = eq_type
        self.selector_byte = selector
        if custom is not None:
            self._custom = tuple(custom)

        return self._run_builder()

    def music_start(
        self,
        stored_selector: int,
        *,
        engine_playing: bool,
        custom: Sequence[int] | None = None,
    ) -> bool:
        """Model FUN_40094dd4 re-sending the stored selector at application start."""
        if engine_playing:
            return False

        # FUN_40094d4c: selectors 0..7 pick a wrapper, anything else is Off.
        if 0 <= stored_selector < len(FIRMWARE_EQ_PRESETS):
            preset = FIRMWARE_EQ_PRESETS[stored_selector]
            return self.eq_message(preset.eq_type, preset.selector, custom)

        return self.eq_message(0, 0)

    def play(self, sample_rate_hz: int) -> str:
        """Model ``_play``: builder, then mmm_mp PLAY's pp DSP selection."""
        self._run_builder()
        return mmm_mp_pp_dsp_name(bytes(self._shm), sample_rate_hz)

    def _run_builder(self) -> bool:
        # The gate is the active flag, not eq_type; the local +0x0c guard is
        # checked inside the builder model.
        result = build_firmware_eq_structure(
            self.selector_byte,
            1 if self.engine_active else 0,
            self.local_structure,
            self._custom,
        )
        if not result.built:
            return False

        if self.shared_pointer == 0:
            raise RuntimeError("shared-memory id 3 is not registered")

        self.local_structure = result.structure
        self._shm[:] = self.local_structure
        return True

    def _apply_config_flag(self, state_flag: int) -> None:
        if state_flag == 1:
            self._shm[2] = 1
            self._shm[3] = 0
        else:
            self._shm[2] = 0
            self._shm[3] = 1


class FirmwareEqAudioSession:
    """Join the Custom slider producer, MEngine builder, and music DSP choice.

    The slider screen submits each successful edit immediately.  Confirm only
    changes the selector, while cancel restores the UI/settings copy without
    sending a compensating message.  The shared DAE image therefore retains
    the last previewed curve after cancel, matching the p-code observation.
    """

    def __init__(
        self,
        *,
        state_flag: int,
        dae_configuration: bytes,
        custom_gains: Sequence[int],
        engine_active: bool | None = None,
        mengine_local_structure: bytes | None = None,
        registry: KernelShmRegistry | None = None,
    ) -> None:
        self.flow = DaeSharedMemoryFlow(
            state_flag=state_flag,
            dae_configuration=dae_configuration,
            engine_active=engine_active,
            mengine_local_structure=mengine_local_structure,
            registry=registry,
        )
        self.slider = FirmwareEqSliderSession(custom_gains)
        self.last_dsp_name: str | None = None
        self.last_auddev_state: AudDevDaeState | None = None
        self.last_auddev_input_source: str | None = None

    def edit_custom(self, band_index: int, event: int) -> bool:
        changed = self.slider.edit(band_index, event)

        if not changed:
            return False

        self.flow.eq_message(7, 7, self.slider.custom_gains)

        return True

    def confirm_custom(self) -> None:
        self.slider.confirm()

    def cancel_custom(self) -> None:
        self.slider.cancel()

    def persist_on_mainmenu_exit(self) -> None:
        self.slider.persist_on_mainmenu_exit()

    def music_start(self, stored_selector: int, *, engine_playing: bool) -> bool:
        return self.flow.music_start(
            stored_selector,
            engine_playing=engine_playing,
            custom=self.slider.custom_gains,
        )

    def play(
        self,
        sample_rate_hz: int,
        *,
        auddev_input: AudDevDaeState | None = None,
    ) -> str:
        self.last_dsp_name = self.flow.play(sample_rate_hz)
        input_state = auddev_input if auddev_input is not None else AudDevDaeState()
        self.last_auddev_input_source = (
            "caller-provided" if auddev_input is not None else "fresh-default"
        )
        self.last_auddev_state = apply_dae_update(
            input_state,
            self.flow.shared_memory,
            sample_rate_hz,
        )

        return self.last_dsp_name

    def trace(self) -> dict[str, object]:
        auddev = self.last_auddev_state

        return {
            "engine_active": self.flow.engine_active,
            "eq_type": self.flow.eq_type_byte,
            "selector": self.flow.selector_byte,
            "custom_gains": list(self.slider.custom_gains),
            "persisted_gains": list(self.slider.persisted_gains),
            "last_submitted_gains": (
                list(self.slider.last_submitted_gains)
                if self.slider.last_submitted_gains is not None
                else None
            ),
            "producer_updates": self.slider.producer_updates,
            "shared_pointer": f"0x{self.flow.shared_pointer:08x}",
            "shared_plus_2": self.flow.shared_memory[2],
            "shared_plus_7": self.flow.shared_memory[7],
            "last_dsp_name": self.last_dsp_name,
            "auddev": (
                {
                    "eq_flag_3a": auddev.eq_flag_3a,
                    "busy_28": auddev.busy_28,
                    "drc_flag_40": auddev.drc_flag_40,
                    "band_bytes": len(auddev.bands),
                    "input": self.last_auddev_input_source,
                }
                if auddev is not None
                else None
            ),
            "provenance": (
                "host composition of p-code-verified producer/builder/name-selection; "
                "aud_dev input defaults to a fresh non-busy state; ROM services and "
                "DSP effects are not executed"
            ),
        }
