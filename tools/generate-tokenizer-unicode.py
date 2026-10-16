#!/usr/bin/env python3
"""Generate Unicode 17 grapheme properties from already provisioned UCD files.

No downloads, installation or build-time execution. Usage:
python3 tools/generate-tokenizer-unicode.py /path/to/ucd-files
"""
import gzip
import hashlib
from pathlib import Path
import sys

source = Path(sys.argv[1])
expected = {
    "GraphemeBreakProperty.txt": "d6b51d1d2ae5c33b451b7ed994b48f1f4dc62b2272a5831e7fd418514a6bae89",
    "emoji-data.txt": "2cb2bb9455cda83e8481541ecf5b6dfda66a3bb89efa3fa7c5297eccf607b72b",
    "DerivedCoreProperties.txt": "24c7fed1195c482faaefd5c1e7eb821c5ee1fb6de07ecdbaa64b56a99da22c08",
    "GraphemeBreakTest.txt": "e2d134d2c52919bace503ebb6a551c1855fe1a1faec18478c78fff254a1793ec",
    "LICENSE.txt": "e7a93b009565cfce55919a381437ac4db883e9da2126fa28b91d12732bc53d96",
}
for name, digest in expected.items():
    if hashlib.sha256((source / name).read_bytes()).hexdigest() != digest:
        raise ValueError("Unexpected Unicode 17 input: " + name)

properties = bytearray(0x110000)
categories = {name: i for i, name in enumerate([
    "Other", "CR", "LF", "Control", "Extend", "ZWJ", "Regional_Indicator",
    "Prepend", "SpacingMark", "L", "V", "T", "LV", "LVT",
])}

def entries(name):
    for line in (source / name).read_text().splitlines():
        fields = [field.strip() for field in line.split("#", 1)[0].split(";")]
        if len(fields) < 2:
            continue
        span = fields[0].split("..")
        yield int(span[0], 16), int(span[-1], 16), fields[1:]

for low, high, fields in entries("GraphemeBreakProperty.txt"):
    properties[low:high + 1] = bytes([categories[fields[0]]]) * (high - low + 1)
for low, high, fields in entries("emoji-data.txt"):
    if fields[0] == "Extended_Pictographic":
        for cp in range(low, high + 1):
            properties[cp] |= 16
for low, high, fields in entries("DerivedCoreProperties.txt"):
    if fields[0] == "InCB":
        flag = {"Consonant": 32, "Linker": 64, "Extend": 128}[fields[1]]
        for cp in range(low, high + 1):
            properties[cp] |= flag

root = Path(__file__).resolve().parents[1]
assets = root / "src/main/resources/assets/mysticism/embedding"
assets.mkdir(parents=True, exist_ok=True)
packed = gzip.compress(properties, mtime=0)
(assets / "unicode-17-graphemes.bin.gz").write_bytes(packed)
(assets / "LICENSE-unicode.txt").write_bytes((source / "LICENSE.txt").read_bytes())
fixture = root / "src/runtimeTest/resources/nomic-v2-grapheme-break-test.txt"
fixture.write_bytes((source / "GraphemeBreakTest.txt").read_bytes())
print("Unicode 17 property table:", len(properties), "bytes; gzip", len(packed),
      "bytes; SHA256", hashlib.sha256(properties).hexdigest())
