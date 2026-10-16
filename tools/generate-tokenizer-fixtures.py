#!/usr/bin/env python3
"""Regenerate reference-only fixtures using an already installed HF tokenizers.

No installation/download. Not part of the build or Minecraft runtime.
Usage: python generate-tokenizer-fixtures.py /path/to/pinned/tokenizer.json
"""
import hashlib
import json
from pathlib import Path
import random
import sys
import tokenizers

EXPECTED = "3a56def25aa40facc030ea8b0b87f3688e4b3c39eb8b45d5702b3a1300fe2a20"
if tokenizers.__version__ != "0.23.2":
    raise ValueError("Reference fixtures require already installed tokenizers 0.23.2")
source = Path(sys.argv[1])
assert hashlib.sha256(source.read_bytes()).hexdigest() == EXPECTED
reference = tokenizers.Tokenizer.from_file(str(source))
reference.no_padding()
reference.no_truncation()
texts = [
    "", "oak log", "search_document: block minecraft:oak_log. minecraft oak log.",
    "search_document: cave limestone moss water underground.",
    "search_query: where is the forest?", "Hello HELLO hello Café café",
    "  oak\tlog\r\n spruce\u00a0birch\u2003stone\u0085water  ",
    "e\u0301 é A\u030a Å ﬁ ﬃ ① Ⅳ ＡＢＣ １２３ ℌ ™ ㎏",
    "cafe\u0301\u0327 a\u034f\u030a", "\u0000\u0001\u001e\u007f\u200b\u200d\ufeff",
    "汉语 中文 日本語 한국어 ภาษาไทย", "العربية עברית हिन्दी বাংলা தமிழ் తెలుగు",
    "Привет мир! Ελληνικά Türkçe Straße", "🦊🌲🏔️👨‍👩‍👧‍👦𠀀\U0010ffff\U0010fffe",
    "\U0010ffff\U0010fffe \U0010ffff\U0010fffe", "a▁b ▁ ▁oak▁log",
    "<s><pad></s><unk><mask>", "oak  <mask>log", "a\u00a0\t<mask>b",
    "＜ｓ＞ <S> <mask><mask> </s><s> ", "search_document: oak <s> log",
    "punctuation: foo_bar/baz.minecraft:stone; 123.45 -0.7 #hash 🐈",
    "Å\u030a ㍑ ㌖ ㍍ ㌎ µ μ ς σ İ ı ß",
    "한\u1161\u11a8 \u1100\u1161\u11a8", "क्षि क्\u200dषि क्\u200cषि",
    "Z\u0300\u0301\u0302\u0303\u0304\u0305\u0306\u0307\u0308",
]
rng = random.Random(20261012)
alphabet = list("oak log stone MINECRAFT_-:/.,123éßÅÅﬁ①▁") + [
    "\t", "\n", "\u00a0", "\u2003", "\u0085", "\u200b", "\u0301", "\u0327",
    "\u034f", "\u200d", "木", "水", "山", "🌲", "🦊", "\U0010ffff",
    "\U0010fffe", "א", "م", "ค", "ก", "्", "ि", "क", "ᄀ", "ᅡ", "ᆨ",
]
for _ in range(300):
    texts.append("".join(rng.choice(alphabet) for _ in range(rng.randrange(1, 100))))
# Exercise unknown Unicode scalars, Unicode whitespace and charsmap rewrites.
for _ in range(300):
    scalars = []
    for _ in range(rng.randrange(1, 50)):
        cp = rng.randrange(0x110000)
        if not 0xD800 <= cp <= 0xDFFF:
            scalars.append(chr(cp))
    texts.append("".join(scalars))
# Model-specific compatibility normalization, often missed by NFKC-only replacements.
for _ in range(400):
    ranges = [(0x2100, 0x2180), (0x2460, 0x2500), (0x3200, 0x3400),
              (0xFB00, 0xFB50), (0xFF00, 0xFFF0), (0x1D400, 0x1D800)]
    text = []
    for _ in range(rng.randrange(1, 20)):
        low, high = rng.choice(ranges)
        text.append(chr(rng.randrange(low, high)))
        text.append(rng.choice(["", "\u0301", "\u030a", "\u0327", " ", "\t"]))
    texts.append("".join(text))
# New Unicode marks expose JDK 21's older grapheme tables. The model's
# under-six-UTF8-byte grapheme rewrite is deliberately NOT ordinary NFKC.
for prefix in ["\u00a0", "¨", "ª", "¯", "²", "³", "´", "µ", "¸", "¹", "º", "¼", "½", "¾", "Ĳ", "ĳ", "Ŀ", "ŀ", "ŉ", "ſ", "Å", "ﬁ"]:
    for mark in ["\u0897", "\u0cf3", "\u0ece", "\U00011f00", "\U00011f36", "\U0001e4ec"]:
        texts.extend([prefix + mark, prefix + mark + "\u0301"])
# The official Unicode 17 boundary cases are also real HF token-ID references.
ucd_cases = Path(__file__).resolve().parents[1] / "src/runtimeTest/resources/nomic-v2-grapheme-break-test.txt"
assert hashlib.sha256(ucd_cases.read_bytes()).hexdigest() == "e2d134d2c52919bace503ebb6a551c1855fe1a1faec18478c78fff254a1793ec"
for line in ucd_cases.read_text().splitlines():
    parts = line.split("#", 1)[0].split()
    if parts:
        texts.append("".join(chr(int(cp, 16)) for cp in parts if cp not in ["÷", "×"]))
# Context limits are measured after the task prefix and BOS/EOS, not characters.
texts.extend(["oak " * 255, "search_document: " + "oak " * 253])
cases = [{"text": text, "ids": reference.encode(text).ids} for text in texts]
assert all(len(case["ids"]) <= 512 for case in cases)
destination = Path(__file__).resolve().parents[1] / "src/runtimeTest/resources/nomic-v2-tokenizer-golden.json"
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(json.dumps({"tokenizerSha256": EXPECTED, "reference": "huggingface/tokenizers " + tokenizers.__version__, "cases": cases}, ensure_ascii=True, indent=2) + "\n")
print(f"Wrote {len(cases)} golden cases from {tokenizers.__version__}: {destination}")
