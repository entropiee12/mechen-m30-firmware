"""Evidence-backed library extension to tag-reader classification.

The playlist scan chooses a reader type before parsing metadata.  This model
keeps that selector separate from the opaque reader ABI and record contents.
"""

from __future__ import annotations

from enum import IntEnum


class LibraryTagReaderType(IntEnum):
    AA = 0
    AAC = 1
    M4A_FAMILY = 2
    APE = 3
    FLAC = 4
    MPEG = 5
    OGG = 6
    WINDOWS_AUDIO = 7
    FALLBACK = 8


_READER_BY_EXTENSION = {
    "AA": LibraryTagReaderType.AA,
    "AAC": LibraryTagReaderType.AAC,
    "AAX": LibraryTagReaderType.M4A_FAMILY,
    "M4A": LibraryTagReaderType.M4A_FAMILY,
    "APE": LibraryTagReaderType.APE,
    "FLAC": LibraryTagReaderType.FLAC,
    "MP1": LibraryTagReaderType.MPEG,
    "MP2": LibraryTagReaderType.MPEG,
    "MP3": LibraryTagReaderType.MPEG,
    "OGG": LibraryTagReaderType.OGG,
    "ASF": LibraryTagReaderType.WINDOWS_AUDIO,
    "WMA": LibraryTagReaderType.WINDOWS_AUDIO,
    "WMV": LibraryTagReaderType.WINDOWS_AUDIO,
}


def tag_reader_type(extension: str) -> LibraryTagReaderType:
    """Return the recovered reader selector, or type 8 for fallback parsing."""
    return _READER_BY_EXTENSION.get(extension.upper(), LibraryTagReaderType.FALLBACK)
