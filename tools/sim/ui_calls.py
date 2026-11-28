"""Semantic labels for indirect UI calls observed in the M30 B3 bank.

The values in this table are far pointers, not direct host addresses.  The
bank-qualified part is decoded with :mod:`bankmap`; the semantic label comes
from the call arity in the B3 functions and, where available, matching public
ATJ2127 UI function signatures. A label is useful to the host model, but it
must not be used as proof that a raw far pointer can be called directly.
"""

from __future__ import annotations

from dataclasses import dataclass

from bankmap import BankAddress, decode


@dataclass(frozen=True)
class UiCall:
    pointer: int
    bank: BankAddress
    candidates: tuple[str, ...]
    confidence: str
    note: str
    entry_evidence: str
    dispatch_policy: str


# These are the exact values loaded from the B3 literal pool at file offset
# 0x9924.  A19 contains more than one function, hence the two separate
# pointers in that bank.
OBSERVED_CALLS: dict[int, tuple[tuple[str, ...], str, str, str, str]] = {
    0x164490A1: (
        ("a17_text_provider",),
        "low",
        "three-argument resident-forwarder target; public A17 source is putstring/text-provider code",
        "external-provider-or-missing-bank",
        "lazy-only",
    ),
    0x164C90ED: (
        ("fix_switch_fontlib",),
        "high",
        "one display-mode argument in the B3 call site",
        "external-provider-or-missing-bank",
        "lazy-only",
    ),
    0x164C9001: (
        ("fixf_get_string_length",),
        "high",
        "(string descriptor, max width, result) call shape",
        "external-provider-or-missing-bank",
        "lazy-only",
    ),
    0x26089471: (
        ("arabic_uni_join",),
        "medium",
        "first one-argument Arabic/Hebrew preprocessing call",
        "validated-local-code-at-aligned-entry",
        "lazy-load-then-call",
    ),
    0x260894E9: (
        ("arabic_ascii_turn",),
        "medium",
        "second one-argument Arabic/Hebrew preprocessing call",
        "local-data-or-unresolved-provider",
        "never-direct-call",
    ),
    0x26409401: (
        ("put_string_line_sdimage",),
        "high",
        "(string descriptor, region, mode) call shape",
        "external-provider-or-missing-bank",
        "lazy-only",
    ),
    0x26109751: (
        ("m30_b04_text_renderer",),
        "medium",
        "B04 table entry; six-argument renderer decompiles with post-bank literals",
        "validated-local-code-at-aligned-entry",
        "lazy-load-then-call",
    ),
    0x261C9694: (
        ("m30_b07_lookup_dispatch",),
        "medium",
        "B07 table entry; two-argument lookup/dispatch helper decompiles locally",
        "validated-local-code-at-aligned-entry",
        "lazy-load-then-call",
    ),
    0x26209401: (
        ("m30_b08_descriptor_renderer",),
        "medium",
        "B08 table entry; local descriptor/line renderer with alignment and clipping",
        "validated-local-code-at-aligned-entry",
        "lazy-load-then-call",
    ),
}


def classify(pointer: int) -> UiCall | None:
    """Return a cautious semantic label for an observed B3 indirect call."""

    pointer &= 0xFFFFFFFF
    entry = OBSERVED_CALLS.get(pointer)
    if entry is None:
        return None
    candidates, confidence, note, entry_evidence, dispatch_policy = entry
    return UiCall(
        pointer,
        decode(pointer),
        candidates,
        confidence,
        note,
        entry_evidence,
        dispatch_policy,
    )
