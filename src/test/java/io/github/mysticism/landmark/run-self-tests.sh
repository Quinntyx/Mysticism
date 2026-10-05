#!/usr/bin/env bash
# Pure checks need only Java21; optional existing mapped Minecraft/runtime CP enables disk-NBT checks.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../../../../../.." && pwd)"
package="$root/src/main/java/io/github/mysticism/landmark"
classes="$(mktemp -d "${TMPDIR:-/tmp}/mysticism-landmark-tests.XXXXXXXX")"
sources=("$root/src/main/java/io/github/mysticism/vector/Vec384f.java"
         "$root/src/test/java/io/github/mysticism/landmark/LandmarkCoreSelfTest.java")
for source in "$package"/*.java; do
    case "$source" in */LandmarkNbt.java|*/LandmarkStore.java) ;;
        *) sources+=("$source");; esac
done
javac --release 21 -proc:none -Xlint:all -d "$classes" "${sources[@]}"
java -cp "$classes" io.github.mysticism.landmark.LandmarkCoreSelfTest
if [[ -n "${MYSTICISM_MINECRAFT_CLASSPATH:-}" ]]; then
    javac --release 21 -proc:none -Xlint:all,-path -cp "$classes:$MYSTICISM_MINECRAFT_CLASSPATH" -d "$classes" \
        "$package/LandmarkNbt.java" "$package/LandmarkStore.java" \
        "$root/src/test/java/io/github/mysticism/landmark/LandmarkPersistenceSelfTest.java"
    java -cp "$classes:$MYSTICISM_MINECRAFT_CLASSPATH" io.github.mysticism.landmark.LandmarkPersistenceSelfTest
else
    printf '%s\n' 'Persistence checks NOT RUN: set MYSTICISM_MINECRAFT_CLASSPATH to existing mapped Minecraft/runtime jars.'
fi
printf 'Self-test class output retained at %s\n' "$classes"
