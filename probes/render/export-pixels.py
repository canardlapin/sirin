#!/usr/bin/env python3
"""Encode actual raw RGBA8 test readbacks as PNG, retaining their alpha."""
import pathlib
import re
import struct
import sys
import zlib


def chunk(name, data):
    return (struct.pack(">I", len(data)) + name + data +
            struct.pack(">I", zlib.crc32(name + data) & 0xffffffff))


for path in pathlib.Path(sys.argv[1]).glob("*.rgba"):
    match = re.search(r"-(\d+)x(\d+)\.rgba$", path.name)
    if not match:
        raise ValueError(f"missing dimensions: {path}")
    width, height = map(int, match.groups())
    raw = path.read_bytes()
    if len(raw) != width * height * 4:
        raise ValueError(f"invalid readback size: {path}")
    rows = b"".join(b"\0" + raw[y*width*4:(y+1)*width*4] for y in range(height))
    path.with_suffix(".png").write_bytes(
        b"\x89PNG\r\n\x1a\n" +
        chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)) +
        chunk(b"sRGB", b"\0") +
        chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))
