# `kernel.drv` operation table and related decompiles (2026-11-27)

Read-only static evidence for [`findings-kernel-op-table.md`](../../findings-kernel-op-table.md).
The decompiles come from Ghidra 12.2 headless with `tools/ghidra/MapSegments.java`
and `DumpAt.java`; literal-pool symbols such as `_UNK_00009108` are unresolved
in the output and were resolved separately from the file bytes (see the finding
for the resolved values). Mid-function entries in the resident part of
`kernel-bank-a6-shm-task-resident.c` (`FUN_bfc087a4` and neighbors) are not
function boundaries and show unresolved registers.

## Inputs

| Member | SHA-256 |
| --- | --- |
| `analysis/community/fw/kernel.drv` | `58f9d0c62bdbdab7cfeee1c5e65bbe8b88ee962f8eecd3dd0515d1a1056d8bd6` |
| `analysis/community/fw/mmm_mp.al` | `57b541ad791637e30135db6c114da98d5680b5f0907ffabf74bf57fa4977a199` |
| `analysis/community/fw/mengine.ap` | `b569605e9f66ef94023cf82d665ae119f6bbb7ed0c9d8f5e6c5be867ce91be49` |
| `analysis/community/fw/music.ap` | `662e379602707a9c4b0dcd6b94b591dd77c2b5f7a557fcb03b8c2a5c9b1baac3` |

## Files

| File | Content | SHA-256 |
| --- | --- | --- |
| [`kernel-op-table.txt`](kernel-op-table.txt) | 77 entries: index, hex, pointer, class, PD196 SDK name (a hint only) | `acf3e72f91c8a6945e65c1b13096ef7b84e893bea5a66010eb56e476d01ffbf2` |
| [`kernel-bank-a6-shm-task-resident.c`](kernel-bank-a6-shm-task-resident.c) | ops `0x43`, `0x44`, `0x47`, `0x48`, `0x49` (bank A6), `0x4a` (bank B0 `0x9944`), resident `0xbfc08770/0xbfc087a4` | `0b421b0fe9bad4c0b4db24c50b0185fd6961dae11c4d73e7000700ffde67f4f8` |
| [`kernel-bank-b0-a2-loaders.c`](kernel-bank-b0-a2-loaders.c) | loaders at `0x9408`, `0x9578`, `0x96a0` and `0x9808` plus related output | `dc21dcce8f179ce53f8ec12a2b8e6eed8313d6ecb559729c3779d86215286317` |
| [`mmm_mp-dsp-start.c`](mmm_mp-dsp-start.c) | `mmm_mp.al` `FUN_68312d00`, the PLAY-time DSP start | `04da0074d48397f1b05feac7aab16192c8ecefc9ba601e0a6b3fa7b15b999d91` |
| [`mengine-builder-and-0x303-handler.c`](mengine-builder-and-0x303-handler.c) | `mengine.ap` `FUN_601d0d58` and `FUN_601d0f30` | `04d171d033d071c57deae7af9d2847d1601d6c62c224d1ac6c284dec3821fe0a` |
| [`music-start-eq.c`](music-start-eq.c) | `music.ap` `FUN_40094dd4`, `FUN_40094d10`, `FUN_40094d4c` and neighbors | `c2dfd097d92d39c3d31e42e660a680cbcd5a8ad9ac297d9dd22ef35694b7db99` |

## Reproduction

```sh
PYTHONPATH=tools/sim python3 -m unittest test_kernel_ops test_driver_bank_table
bash tools/ghidra/run_kernel_shm_ops_probe.sh
bash tools/ghidra/run_config_to_mengine_dae_chain_probe.sh
```

`tools/ghidra/CallersOf.java`, `FindA3Ops.java`, `FindImm.java` and
`FindImmStore.java` were used to find call sites and immediates.
