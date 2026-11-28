#!/usr/bin/env python3
"""Check the Ghidra-decompiled bit-banged DAC-bus protocol shape.

This validates the recovered driver code shape, not electrical behavior or a
specific codec register map. Input is the temporary output from DumpC.java.
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path


FUNCTION_RE = re.compile(
    r"^// ===== (FUN_[0-9a-fA-F]+) @ [^\n]+\n(.*?)(?=^// ===== |\Z)",
    re.MULTILINE | re.DOTALL,
)


def functions_from(path: Path) -> dict[str, str]:
    return dict(FUNCTION_RE.findall(path.read_text(encoding="utf-8")))


def _calls(body: str) -> list[str]:
    calls = [call.split("(", 1)[0].strip() for call in
             re.findall(r"\bFUN_[0-9a-fA-F]+\s*\(", body)]
    return calls[1:]  # The first occurrence is the function definition.


def _exact_calls(body: str, expected: tuple[str, ...], label: str) -> None:
    calls = _calls(body)
    if calls != list(expected):
        raise ValueError(f"{label}: expected calls {expected}, saw {calls}")


def _assignments(body: str, variable: str) -> list[str]:
    return re.findall(rf"\b{re.escape(variable)}\s*=(?!=)\s*([^;]+);", body)


def _returns(body: str) -> list[tuple[str, int]]:
    return [(match.group(1).strip(), match.start())
            for match in re.finditer(r"\breturn\s*([^;]*);", body)]


def _require_terminal_return(body: str, expression: str, label: str, after: int) -> None:
    returns = _returns(body)
    if len(returns) != 1 or returns[0][0] != expression or returns[0][1] < after:
        raise ValueError(f"{label} must have one reachable terminal return after its required operations")


def _reject_conditionals(body: str, label: str, *, allow_loop_and: bool = False) -> None:
    keywords = r"\b(if|switch|goto)\b" if allow_loop_and else r"\b(if|for|while|switch|goto)\b"
    logical_ops = r"\?|\|\|" if allow_loop_and else r"\?|\|\||&&"
    if re.search(keywords, body) or re.search(logical_ops, body):
        raise ValueError(f"{label} must not guard or conditionally skip the verified operations")


def _single_masked_store(
    body: str, register_offset: str, mask_offset: str, level: str, label: str
) -> None:
    stores = re.findall(
        rf"^\s*\*\*\(uint\s*\*\*\)\(param_1\s*\+\s*{re.escape(register_offset)}\)\s*=\s*(.*?);",
        body, re.MULTILINE | re.DOTALL,
    )
    register = rf"\*\*\(uint\s*\*\*\)\(param_1\s*\+\s*{re.escape(register_offset)}\)"
    mask = rf"\*\(uint\s*\*\)\(param_1\s*\+\s*{re.escape(mask_offset)}\)"
    expression = rf"{mask}\s*\|\s*{register}" if level == "high" else rf"{register}\s*&\s*~{mask}"
    if len(stores) != 1 or not re.fullmatch(expression, stores[0].strip() if stores else ""):
        raise ValueError(f"{label} must only drive the masked line {level}")
    _reject_conditionals(body, label)
    store_end = re.search(
        rf"^\s*\*\*\(uint\s*\*\*\)\(param_1\s*\+\s*{re.escape(register_offset)}\)\s*=\s*.*?;",
        body, re.MULTILINE | re.DOTALL,
    )
    calls = list(re.finditer(r"\bFUN_[0-9a-fA-F]+\s*\(", body))
    last_operation = max(
        store_end.end() if store_end else len(body),
        calls[-1].start() if calls else 0,
    )
    _require_terminal_return(body, "", label, last_operation)


def _require(pattern: str, body: str, label: str) -> None:
    if not re.search(pattern, body, re.DOTALL):
        raise ValueError(label)


def verify(path: Path) -> None:
    functions = functions_from(path)
    required = (
        "FUN_bfc18fe0",
        "FUN_bfc19028",
        "FUN_bfc18db8",
        "FUN_bfc18de4",
        "FUN_bfc18e10",
        "FUN_bfc18e3c",
        "FUN_bfc18f68",
        "FUN_bfc191d4",
        "FUN_bfc192e0",
        "FUN_bfc18fa0",
        "FUN_bfc19330",
        "FUN_bfc19114",
    )
    missing = [name for name in required if name not in functions]
    if missing:
        raise ValueError("missing Ghidra function bodies: " + ", ".join(missing))

    start = functions["FUN_bfc18fe0"]
    _exact_calls(start, (
        "FUN_bfc18db8", "FUN_bfc18e10", "FUN_bfc18de4", "FUN_bfc18e3c",
    ), "start condition")
    _reject_conditionals(start, "start condition")
    start_calls = list(re.finditer(r"\bFUN_[0-9a-fA-F]+\s*\(", start))
    _require_terminal_return(start, "", "start condition", start_calls[-1].start() if start_calls else len(start))
    stop = functions["FUN_bfc19028"]
    _exact_calls(stop, (
        "FUN_bfc18de4", "FUN_bfc18e10", "FUN_bfc18db8",
    ), "stop condition")
    _reject_conditionals(stop, "stop condition")
    stop_calls = list(re.finditer(r"\bFUN_[0-9a-fA-F]+\s*\(", stop))
    _require_terminal_return(stop, "", "stop condition", stop_calls[-1].start() if stop_calls else len(stop))

    _single_masked_store(functions["FUN_bfc18db8"], "0x18", "0x1c", "high", "data-high helper")
    _single_masked_store(functions["FUN_bfc18de4"], "0x18", "0x1c", "low", "data-low helper")
    _single_masked_store(functions["FUN_bfc18e10"], "8", "0xc", "high", "clock-high helper")
    _single_masked_store(functions["FUN_bfc18e3c"], "8", "0xc", "low", "clock-low helper")
    for name, label in (
        ("FUN_bfc18db8", "data-high helper"),
        ("FUN_bfc18de4", "data-low helper"),
        ("FUN_bfc18e10", "clock-high helper"),
        ("FUN_bfc18e3c", "clock-low helper"),
    ):
        _exact_calls(functions[name], ("FUN_bfc18d90",), label)

    bit_index = functions["FUN_bfc18f68"]
    if not re.search(
        r"bStack_10\s*=\s*0\s*;.*?while\s*\(\(bStack_10\s*<\s*0x20\s*&&\s*"
        r"\(\(param_1\s*>>\s*\(bStack_10\s*&\s*0x1f\)\s*&\s*1\)\s*==\s*0\)\)\)\s*\{\s*"
        r"bStack_10\s*=\s*bStack_10\s*\+\s*1\s*;\s*\}.*?return\s+bStack_10\s*;",
        bit_index,
        re.DOTALL,
    ):
        raise ValueError("GPIO mask bit-index helper must return the least significant set-bit index")
    _reject_conditionals(bit_index, "GPIO mask bit-index helper", allow_loop_and=True)
    if len(re.findall(r"\bwhile\s*\(", bit_index)) != 1:
        raise ValueError("GPIO mask bit-index helper must contain exactly one scan loop")
    bit_loop = bit_index.find("while")
    bit_loop_end = bit_index.find("\n  }", bit_loop)
    if bit_loop_end < 0:
        raise ValueError("GPIO mask bit-index helper scan loop must close before its return")
    _require_terminal_return(bit_index, "bStack_10", "GPIO mask bit-index helper", bit_loop_end)

    sampler = functions["FUN_bfc18fa0"]
    if not re.search(
        r"uVar3\s*=\s*\*\*\(uint\s*\*\*\)\(param_1\s*\+\s*0x18\)\s*;\s*"
        r"uVar2\s*=\s*\*\(uint\s*\*\)\(param_1\s*\+\s*0x1c\)\s*;\s*"
        r"bVar1\s*=\s*FUN_bfc18f68\(\*\(undefined4\s*\*\)\(param_1\s*\+\s*0x1c\)\)\s*;\s*"
        r"return\s*\(uVar2\s*&\s*uVar3\)\s*>>\s*\(bVar1\s*&\s*0x1f\)\s*&\s*1\s*;",
        sampler,
        re.DOTALL,
    ):
        raise ValueError("GPIO sampler must return the selected masked input bit")
    _reject_conditionals(sampler, "GPIO sampler")
    _exact_calls(sampler, ("FUN_bfc18f68",), "GPIO sampler")
    sampler_calls = list(re.finditer(r"\bFUN_[0-9a-fA-F]+\s*\(", sampler))
    _require_terminal_return(
        sampler,
        "(uVar2 & uVar3) >> (bVar1 & 0x1f) & 1",
        "GPIO sampler",
        sampler_calls[-1].start() if sampler_calls else len(sampler),
    )

    transmit = functions["FUN_bfc191d4"]
    if not re.search(
        r"for\s*\(\s*bStack_10\s*=\s*0\s*;\s*bStack_10\s*<\s*8\s*;\s*"
        r"bStack_10\s*=\s*bStack_10\s*\+\s*1\s*\).*?"
        r"\(uint\)param_1\s*<<\s*\(bStack_10\s*&\s*0x1f\)\s*&\s*0x80",
        transmit,
        re.DOTALL,
    ):
        raise ValueError("transmit: expected eight bit-7-to-bit-0 tests")
    if _assignments(transmit, "param_1"):
        raise ValueError("transmit: input byte must not be modified before bit output")
    _require(
        r"if\s*\(\s*\(\(uint\)param_1\s*<<\s*\(bStack_10\s*&\s*0x1f\)\s*&\s*0x80\)\s*==\s*0\s*\)\s*\{\s*"
        r"FUN_bfc18de4\([^;]*;\s*\}\s*else\s*\{\s*"
        r"FUN_bfc18db8\([^;]*;\s*\}",
        transmit,
        "transmit: zero bits must drive low and one bits must drive high",
    )
    _exact_calls(transmit, (
        "FUN_bfc18de4", "FUN_bfc18db8", "FUN_bfc18e10", "FUN_bfc18e3c",
    ), "transmit data/clock")

    ack = functions["FUN_bfc19330"]
    if _assignments(ack, "cVar1") != ["FUN_bfc18fa0(param_1)"]:
        raise ValueError("ACK sampler must preserve the sampled bus bit")
    if not re.search(r"return\s+cVar1\s*==\s*'\\0'\s*;", ack):
        raise ValueError("ACK sampler must report success for a low sampled bit")
    _exact_calls(ack, (
        "FUN_bfc18e88", "FUN_bfc18ea8", "FUN_bfc18d90", "FUN_bfc18e10",
        "FUN_bfc18fa0", "FUN_bfc18d90", "FUN_bfc18e3c", "FUN_bfc18e68",
        "FUN_bfc18de4", "FUN_bfc18ec8",
    ), "ACK sample/clock sequence")

    ack_nack = functions["FUN_bfc192e0"]
    if _assignments(ack_nack, "param_1"):
        raise ValueError("read ACK/NACK: selector must not be modified before level selection")
    _require(
        r"if\s*\(\s*param_1\s*==\s*'\\0'\s*\)\s*\{\s*"
        r"FUN_bfc18de4\([^;]*;\s*\}\s*else\s*\{\s*"
        r"FUN_bfc18db8\([^;]*;\s*\}",
        ack_nack,
        "read ACK/NACK: zero must drive low and nonzero must drive high",
    )
    _exact_calls(ack_nack, (
        "FUN_bfc18de4", "FUN_bfc18db8", "FUN_bfc18e10", "FUN_bfc18e3c",
    ), "read ACK/NACK data/clock")

    receive = functions["FUN_bfc19114"]
    if "FUN_bfc192e0(uStack_f" not in receive:
        raise ValueError("receive: expected per-byte ACK/NACK helper")
    receive_count_init = re.findall(
        r"^\s*cStackX_8\s*=(?!=)\s*([^;]+);", receive, re.MULTILINE
    )
    if (
        receive_count_init != ["param_3"]
        or not re.search(
            r"for\s*\(\s*;\s*cStackX_8\s*!=\s*'\\0'\s*;\s*"
            r"cStackX_8\s*=\s*cStackX_8\s*\+\s*-1\s*\)",
            receive,
        )
    ):
        raise ValueError("receive: byte counter must start at requested length and decrement by one")
    final_nack = re.search(
        r"if\s*\(\s*cStackX_8\s*==\s*'\\x01'\s*\)\s*\{([^{}]*)\}(.*?)"
        r"FUN_bfc192e0\(\s*uStack_f\s*,",
        receive, re.DOTALL,
    )
    if (
        not final_nack
        or not re.fullmatch(r"\s*uStack_f\s*=\s*1\s*;\s*", final_nack.group(1))
        or re.search(r"\buStack_f\s*=", final_nack.group(2))
        or re.findall(r"\buStack_f\s*=\s*([^;]+);", receive) != ["0", "1"]
    ):
        raise ValueError("receive: final byte must remain high-level NACK; earlier bytes low ACK")
    _exact_calls(receive, (
        "FUN_bfc18d30", "FUN_bfc18fe0", "FUN_bfc191d4", "FUN_bfc19330",
        "FUN_bfc19240", "FUN_bfc192e0", "FUN_bfc19028",
    ), "receive ACK/NACK sequence")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("decomp", type=Path)
    args = parser.parse_args()
    verify(args.decomp)
    print("DAC PROTOCOL SHAPE PASS two-wire-start-stop msb-first ack-low final-nack")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
