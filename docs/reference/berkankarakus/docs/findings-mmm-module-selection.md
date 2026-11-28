# `mmm_mp.al` versus `mmm_pp.al`: module-selection evidence

Static, read-only examination of the extracted stock and community `fw/` trees (99 regular-file members each), the MEngine callsite, and the existing kernel-loader decompile. `VERIFIED` describes observed bytes or a cited decompile; `INFERENCE` describes the likely runtime interpretation; `UNKNOWN` marks a missing link. No firmware was executed or changed.

## Result

- **VERIFIED:** Both trees contain **both** `mmm_mp.al` and `mmm_pp.al`. The statement in [findings-kernel-op-table.md](findings-kernel-op-table.md) that “no corpus member names `mmm_pp`” is false for these extracted `fw/` trees. The two modules are byte-identical across stock and community, respectively.
- **VERIFIED (prior Ghidra decompile, with the literal and service pointer checked against current bytes):** The MEngine path `FUN_60051278` → `FUN_60051034` passes `(&"mmm_mp.al", 1, 0, 2)` to the tagged resident entry `0xbfc11cc9` (code address `0xbfc11cc8`). The filename is at `mengine.ap` file offset `0x144c`, mapped to `0x6005114c`; the service pointer is at file offset `0x146c`, mapped to `0x6005116c`. This holds for both AP variants. The prior decompile identifies the call inside `FUN_60051278` at `0x6005130e`; see [the named-module-request evidence](findings-mmm-bp-context-init-boundary.md#named-module-request-in-mengineap).
- **INFERENCE:** This is the MEngine music path's request to load `mmm_mp.al`. It is stronger than a filename occurrence because the decompiled caller passes that string to a resident service. The body of `0xbfc11cc8` is unavailable, so successful open, load, initialization and meanings of `(1, 0, 2)` are **UNKNOWN**.
- **VERIFIED within the bounded scan:** No bytes spelling `mmm_pp.al` or `MMM_PP  AL` occur in any of the 99 regular-file `fw/` members of either tree. The only exact lowercase `mmm_mp.al` occurrence in each tree is `mengine.ap+0x144c`; neither uppercase DOS spelling occurs. Thus this scan finds no direct named caller for `mmm_pp.al`.
- **UNKNOWN:** Whether `mmm_pp.al` is ever loaded. Its presence proves that it is supplied in the extracted corpus, while the absent literal does **not** prove it is dead: names can be constructed, read from package metadata, selected by ROM, or passed through a path outside these `fw/` members. No feature-specific `mmm_pp.al` selector or runtime trace was established here.

## Input identity and layout

All hashes are SHA-256 of the named files, checked with `shasum -a 256` in this run.

| Member | Stock | Community |
| --- | --- | --- |
| `mmm_mp.al` | `57b541ad791637e30135db6c114da98d5680b5f0907ffabf74bf57fa4977a199` | `57b541ad791637e30135db6c114da98d5680b5f0907ffabf74bf57fa4977a199` |
| `mmm_pp.al` | `9a7a6c2215b2594e2ef816b8a3e592968921afff7747f3cadbd6e71bf74047d6` | `9a7a6c2215b2594e2ef816b8a3e592968921afff7747f3cadbd6e71bf74047d6` |
| `mengine.ap` | `1340bac3cb68043f7a9651ee7959c2f502e6759c7debe0626f07ef9231896d4d` | `b569605e9f66ef94023cf82d665ae119f6bbb7ed0c9d8f5e6c5be867ce91be49` |
| `kernel.drv` | `58f9d0c62bdbdab7cfeee1c5e65bbe8b88ee962f8eecd3dd0515d1a1056d8bd6` | `58f9d0c62bdbdab7cfeee1c5e65bbe8b88ee962f8eecd3dd0515d1a1056d8bd6` |

**VERIFIED bytes:** Both `.al` files have `yqhx` at header offset `+4`. `mmm_mp.al` declares a first segment `(file offset 0x400, length 0x454, destination 0xbfc13500)`, BSS `(length 0x1e0, address 0x9fc13b04)`, and tagged entry `0xbfc13865`. `mmm_pp.al` declares `(0x400, 0x56c, 0xbfc13500)`, BSS `(0xd0, 0x9fc13c00)`, and tagged entry `0xbfc138c1`. The first segment destinations overlap; **INFERENCE:** the modules are alternative residents at that address, not two simultaneously intact images. These header fields alone do not show which path chooses either one.

## Loader boundary

**VERIFIED decompile:** [Kernel bank B0 loader evidence](evidence/kernel-op-table-2026-11-27/kernel-bank-b0-a2-loaders.c) (SHA-256 `dc21dcce8f179ce53f8ec12a2b8e6eed8313d6ecb559729c3779d86215286317`) shows `FUN_00009578` and `FUN_000096a0` accepting a filename parameter, opening it through an indirect service, checking a `0x44`-byte image header, copying declared segments, and recording slot data. The second also compares against `stub.al`. [The kernel finding](findings-kernel-op-table.md#operations-03-the-apcodecimage-loaders-verified-decompile-rom-helpers-inference) treats these as plausible codec/mmm-library operations. **UNKNOWN:** The resident `0xbfc11cc8` body and its exact link to either bank B0 operation are absent, so the MEngine call cannot be assigned a particular kernel operation from this evidence. The host `ResidentDriverLoader` in `tools/emu/driver_loader.py` is explicitly a synthetic materializer and does not resolve firmware path selection.

## Commands and bounds

The first code lookup was CodeGraph `codegraph_explore` with query `mmm_mp.al mmm_pp.al module loader callsites which path selects loads these AL modules`, followed by `mmm_mp mmm_pp load filename loader mode firmware kernel decompile`; neither returned a firmware name selector. The named MEngine callsite above comes from the existing Ghidra decompile, not a new Ghidra run. Its published reproduction is `LoadAP.java` followed by `DumpRefsTo.java 0x6005114c`, `DumpRefsTo.java 0x60051034`, and `DumpAt.java 0x60051034,0x60051278` (see linked finding).

Commands run for the current byte checks, from the repository root:

```sh
shasum -a 256 analysis/stock/fw/mmm_mp.al analysis/stock/fw/mmm_pp.al analysis/stock/fw/mengine.ap analysis/stock/fw/kernel.drv analysis/community/fw/mmm_mp.al analysis/community/fw/mmm_pp.al analysis/community/fw/mengine.ap analysis/community/fw/kernel.drv docs/evidence/kernel-op-table-2026-11-27/kernel-bank-b0-a2-loaders.c
python3 - <<'PY'
from pathlib import Path
for corpus in ('stock', 'community'):
    root = Path('analysis') / corpus / 'fw'
    files = sorted(p for p in root.iterdir() if p.is_file())
    print(corpus, len(files))
    for p in files:
        data = p.read_bytes()
        for name in (b'mmm_mp.al', b'mmm_pp.al', b'MMM_MP  AL', b'MMM_PP  AL'):
            start = 0
            while (at := data.find(name, start)) >= 0:
                print(corpus, p.name, name.decode('ascii'), hex(at))
                start = at + 1
PY
```

The scan covers exact, case-sensitive ASCII lowercase names and uppercase space-padded DOS names in regular files directly under each extracted `fw/` tree. It does not cover other case patterns, encoded/computed strings, package records outside `fw/`, ROM, dynamic dispatch, or live execution. The file contents were read through `analysis/{stock,community}/fw` symlinks; neither tree was modified.
