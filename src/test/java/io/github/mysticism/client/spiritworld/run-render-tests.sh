#!/usr/bin/env bash
# No installs/new dependencies. Existing mapped Minecraft + JOML/runtime jars are required.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../../../../../../.." && pwd)"
python3 "$root/scripts/check-spirit-render.py"
if [[ -z "${MYSTICISM_MINECRAFT_CLASSPATH:-}" ]]; then
    printf '%s\n' 'Render math NOT RUN: set MYSTICISM_MINECRAFT_CLASSPATH to existing mapped Yarn1.21.1/JOML/runtime jars.'
    exit 0
fi
classes="$(mktemp -d "${TMPDIR:-/tmp}/mysticism-render-tests.XXXXXXXX")"
javac --release 21 -proc:none -cp "$MYSTICISM_MINECRAFT_CLASSPATH" -d "$classes" \
    "$root/src/main/java/io/github/mysticism/landmark/BorderDither.java" \
    "$root/src/main/java/io/github/mysticism/landmark/FogHorizons.java" \
    "$root/src/main/java/io/github/mysticism/vector/EmbeddingSpace.java" \
    "$root/src/main/java/io/github/mysticism/vector/Vec384f.java" \
    "$root/src/main/java/io/github/mysticism/vector/Basis384f.java" \
    "$root/src/main/java/io/github/mysticism/vector/Projection384f.java" \
    "$root/src/client/java/io/github/mysticism/client/spiritworld/SpiritRenderSettings.java" \
    "$root/src/client/java/io/github/mysticism/client/spiritworld/SpiritGlyphFrame.java" \
    "$root/src/test/java/io/github/mysticism/client/spiritworld/SpiritRenderMathSelfTest.java"
java -cp "$classes:$MYSTICISM_MINECRAFT_CLASSPATH" io.github.mysticism.client.spiritworld.SpiritRenderMathSelfTest
printf 'Self-test class output retained at %s\n' "$classes"
