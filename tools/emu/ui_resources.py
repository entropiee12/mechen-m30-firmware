"""Immutable MCG/RU3 metadata exposed to the host UI emulator."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from ui_resource_probe import (
    MCG_MEMBERS,
    STY_MEMBERS,
    parse_mcg,
    parse_sty,
)


@dataclass(frozen=True)
class McgNode:
    """One structurally verified MCG node record with opaque name bytes."""

    record_number: int
    index: int
    node_id: int
    layer: int
    raw_name: bytes


@dataclass(frozen=True)
class McgTree:
    """A parsed menu tree without assigning UI meaning to node IDs."""

    path: Path
    size: int
    sha256: str
    nodes: tuple[McgNode, ...]

    @classmethod
    def load(cls, path: Path) -> "McgTree":
        size, sha256, records = parse_mcg(path)
        nodes = tuple(McgNode(*record) for record in records)
        return cls(path, size, sha256, nodes)

    def nodes_with_id(self, node_id: int) -> tuple[McgNode, ...]:
        """Return matching records without assuming IDs are application names."""
        return tuple(node for node in self.nodes if node.node_id == node_id)


@dataclass(frozen=True)
class Ru3Group:
    """One bounded RU3 group pointer/count pair."""

    address: int
    count: int


@dataclass(frozen=True)
class Ru3Resource:
    """A parsed RU3 style table and its verified group metadata."""

    path: Path
    size: int
    sha256: str
    style_address: int
    style_begin: int
    groups: tuple[Ru3Group, ...]

    @classmethod
    def load(cls, path: Path) -> "Ru3Resource":
        size, sha256, style_address, style_begin, records = parse_sty(path)
        groups = tuple(Ru3Group(*record) for record in records)
        return cls(path, size, sha256, style_address, style_begin, groups)

    def group(self, index: int) -> Ru3Group:
        if not 0 <= index < len(self.groups):
            raise IndexError("RU3 group index is outside the resource")
        return self.groups[index]


@dataclass(frozen=True)
class UiResourceCatalog:
    """All structurally verified menu/style members for one firmware tree."""

    firmware_directory: Path
    mcg: tuple[McgTree, ...]
    sty: tuple[Ru3Resource, ...]

    @classmethod
    def load(cls, firmware_directory: Path) -> "UiResourceCatalog":
        if not firmware_directory.is_dir():
            raise ValueError(f"firmware directory does not exist: {firmware_directory}")
        mcg = tuple(
            McgTree.load(firmware_directory / member)
            for member in MCG_MEMBERS
        )
        sty = tuple(
            Ru3Resource.load(firmware_directory / member)
            for member in STY_MEMBERS
        )
        return cls(firmware_directory, mcg, sty)

    def mcg_member(self, name: str) -> McgTree:
        for member in self.mcg:
            if member.path.name == name:
                return member
        raise KeyError(f"unknown MCG member: {name}")

    def sty_member(self, name: str) -> Ru3Resource:
        for member in self.sty:
            if member.path.name == name:
                return member
        raise KeyError(f"unknown RU3 member: {name}")

