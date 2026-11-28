# CodeGraph workflow

CodeGraph is useful here as a change-impact map for the host-side emulator,
binary-analysis helpers, and test models. It is not a replacement for Ghidra:
raw `.ap`, `.drv`, ROM, HEX, and other binary payloads are not decompiled or
indexed by the repository's current CodeGraph project.

## What it covers

The current project index covers the Python, C, and Java sources under the
repository. In particular, it can connect:

- emulator state and dispatch code in `tools/emu/`;
- ATJ2127 peripheral contracts in `tools/sim/`;
- MIPS patch and validation helpers in `tools/`, and
- the host-side regression tests that exercise those components.

The firmware itself still needs the existing Ghidra scripts, byte-level
validators, and MIPS/QEMU harnesses. A CodeGraph result must not be treated as
evidence that an address exists in a binary or that hardware behavior has been
verified.

## Repeatable checks

From the repository root:

```sh
codegraph sync .
codegraph status
codegraph impact LazyBankRuntime
codegraph impact UiDispatcher
```

Run `impact` before changing an emulator or analysis symbol. Use the affected
test list as a review checklist, then run the relevant host and static suites.
For a broader relationship search, use `codegraph explore` with the symbol
names involved in the change.

The local `.codegraph/` database is generated state and is intentionally not
part of the firmware repository. If it is absent or stale, regenerate it with
`codegraph sync .`; the source tree remains authoritative.

## Current role in the M30 work

CodeGraph is a safety net around the hybrid emulator. It helps find callers,
tests, and neighboring analysis code before a change is committed. Ghidra
remains authoritative for recovered addresses and control flow; the byte-level
probes remain authoritative for firmware layout; and QEMU remains authoritative
only for harnesses that can actually execute with the available target runtime.
