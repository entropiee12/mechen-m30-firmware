# M30 DAC driver: static boundary

`drv_dac.drv` is the low-level codec/DAC bus helper. It is not the audio
decoder and it does not contain the user-visible fade curve. The result below
comes from Ghidra 12.2 and raw `yqhx` bytes; no hardware bus trace was taken.

## Reproducible checks

```sh
PYTHONPATH=tools/emu python3 tools/emu/dac_binary_probe.py \
  analysis/community/fw/drv_dac.drv
bash tools/ghidra/run_dac_probe.sh analysis/community/fw/drv_dac.drv
bash tools/emu/run_qemu_dac_probe.sh analysis/community/fw/drv_dac.drv
```

The driver SHA-256 is identical in the local community, stock, first-pass,
V1.101.11, V1.101.12, and V5 extractions:

```text
a3d21100a8fd324a77c08aaecf444ddc4f66ace56bd9dfbdc228e508ef956a33
```

## Container and functions

The image has one resident payload:

| Field | Value |
|---|---:|
| file offset | `0x200` |
| length | `0x800` |
| destination | `0xbfc18d00` |
| BSS | `0x9fc16090`, `0x16c` bytes |
| entry field | `0xbfc18d08` |
| static functions | 27 |

The decompiled functions expose a small GPIO/serial-bus abstraction. A device
context passed to the helpers contains register pointers and masks at offsets
`+0x04`, `+0x08`, `+0x0c`, `+0x10`, `+0x14`, `+0x18`, and `+0x1c`.

Observed operations:

- `FUN_bfc18db8` / `FUN_bfc18de4`: set/clear the masked output at context
  `+0x18`.
- `FUN_bfc18e10` / `FUN_bfc18e3c`: set/clear the masked output at `+0x08`.
- `FUN_bfc18e68` / `FUN_bfc18e88`: set/clear the masked output at `+0x14`.
- `FUN_bfc18ee8` / `FUN_bfc18f08`: set/clear the masked output at `+0x04`.
- `FUN_bfc18f68` and `FUN_bfc18fa0`: find the first set bit and read one
  masked bus bit.
- `FUN_bfc191d4`: shifts one byte out, eight bits, using the set/clear and
  delay helpers.
- `FUN_bfc19240`: samples eight bus bits into one byte.
- `FUN_bfc19060`: sends a byte sequence while polling the bus-ready helper.
- `FUN_bfc19114`: receives a byte sequence.

The entry-adjacent functions `FUN_bfc18d08` and `FUN_bfc18d1c` return zero;
`FUN_bfc18d30` and `FUN_bfc18d70` perform line setup/teardown around the
context. The `drv_dac` name is therefore consistent with a codec control bus,
not with PCM decoding.

## Two-wire protocol shape

A targeted Ghidra decompile of the actual resident code narrows the bus
contract beyond byte transfers:

- `FUN_bfc18fe0` drives the context's `+0x18` masked line high, drives the
  `+0x08` masked line high, then takes the first line low followed by the
  second. This is the canonical SDA/SCL ordering of an I2C START condition.
- `FUN_bfc19028` takes the first line low, raises the second, then raises the
  first: the corresponding I2C STOP ordering.
- `FUN_bfc191d4` tests bits 7 through 0 and toggles the second line for each
  bit, so transmitted bytes are MSB-first. `FUN_bfc19240` samples eight bits
  and shifts them into the same MSB-first byte order.
- `FUN_bfc19330` releases/reconfigures the sampled line, raises the clock,
  samples the data bit, and returns success when that bit is zero. The writer
  uses that result as its ACK check. In the receive path,
  `FUN_bfc192e0` drives the data line low for ACK and high for NACK;
  `FUN_bfc19114` selects NACK for the final received byte.

Taken together, the real helper code has a **bit-banged I2C-shaped control
protocol** with MSB-first bytes and active-low ACK. The address/data line
identities here describe the helper context's two masked lines; there is no
board trace proving physical pin names, voltage, timing, pull-ups, or the
codec's register/address map. The machine-readable shape check runs as part of
`tools/ghidra/run_dac_probe.sh`; it checks the low/high helper semantics and
condition-to-level mapping for ACK, NACK, and the final receive byte. The QEMU
probe separately confirms that the unchanged helpers execute through their
byte-transfer paths with synthetic GPIO RAM.

## Consequence for the requested playback work

This driver now establishes the serial protocol shape, but it still does not
tell us which device address or register controls volume, mute, ramping, or
fade-out. Those meanings require tracing callers and the codec register
tables. The DSP files (`ad*.dsp`) remain separate and unexamined; they should
not be treated as solved by this driver analysis.

No firmware patch is proposed. The safe next step is to locate callers of the
DAC context/API in the resident audio path and correlate their arguments with
the existing fade/volume state machine. Without that caller trace, changing
these bit operations would risk silencing the device or leaving the codec
bus in an invalid state.

## Caller search boundary

The local extracted-artifact search does not reveal a direct caller pointer for
the DAC helper set. The `drv_dac.drv` payload is byte-identical in all six
local images, while `config.ap` contains the driver filenames in its resource
area and reaches them through generic configuration/service calls. A scan for
the little-endian resident entry and helper addresses across the extracted
`analysis` tree (excluding the container-level `dec.bin` copy) finds the
expected driver/container occurrences but no second `.ap`, `.bin`, DSP, or
other driver payload that directly names those helper addresses.

This narrows the useful search boundary to an indirect resident table, fixed
ROM, or other audio caller. It does **not** prove that the DAC driver is unused,
nor does it identify codec registers. The scan is reproducible with:

```sh
PYTHONPATH=tools/emu python3 tools/emu/dac_caller_scan.py \
  analysis --exclude-name dec.bin
```

The result should be treated as negative evidence only. Reconstructing a
caller from guessed service IDs would be lower confidence than the existing
byte-transfer probe and is not a safe firmware patch target.

## Host byte boundary

`tools/sim/dac_bus.py` now exposes the narrow byte-level slice proven by these
helpers: complete byte sequences can be queued, sent, and received, and the
eight-bits-per-byte transfer count is available for traces. It is still a
byte-level host contract; it does not yet model the recovered START/STOP,
bit-order, ACK/NACK, electrical GPIO, bus-ready timeout, codec register
address, volume/mute behavior, or PCM/audio timing.

## Real-code QEMU boundary

`tools/emu/run_qemu_dac_probe.sh` executes the exact resident `0x800`-byte
payload in QEMU/Malta. The probe uses two copies of the unchanged payload: the
normal copy is the direct entry target, while a second copy at the translated
`0x8fc18d00` region preserves the original MIPS16 helper-call jump region.
This is a probe linker arrangement, not a firmware rewrite.

The probe passed the six local `drv_dac.drv` members (`community`, `stock`,
`first-pass-verify`, `V1.101.11`, `V1.101.12`, and `V5`) and verifies:

- `FUN_bfc191d4` completes an eight-bit transmit for `0xa5`;
- `FUN_bfc19060` completes a command plus two-byte transfer when the synthetic
  GPIO context has a zero ready mask;
- `FUN_bfc19240` returns `0xff` for an all-high sampled mask and `0x00` for an
  all-low mask.

The zero ready mask is deliberate: the public helper drives the same synthetic
RAM word that it later polls, so a nonzero mask would invent an electrical
feedback model. The result proves byte-level control flow and context access,
not codec register meanings, GPIO timing, PCM/DAC output, or hardware
compatibility.
