#!/usr/bin/env bash
# Java21-only graph/profile checks; existing mapped Minecraft CP additionally enables disk checks.
# Uses no sibling sources, model server, downloads, build changes or new dependencies.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../../../../../../.." && pwd)"
package="$root/src/main/java/io/github/mysticism/landmark"
classes="$(mktemp -d "${TMPDIR:-/tmp}/mysticism-extraction-tests.XXXXXXXX")"
sources=("$root/src/main/java/io/github/mysticism/vector/Vec384f.java"
         "$root/src/main/java/io/github/mysticism/vector/EmbeddingSpace.java"
         "$root/src/main/java/io/github/mysticism/embedding/EmbeddingProfile.java"
         "$package/extract/ExtractionGraph.java" "$package/extract/LandmarkProfiles.java"
         "$package/extract/ObservationFingerprints.java"
         "$root/src/test/java/io/github/mysticism/landmark/extract/ExtractionSelfTest.java")
for source in "$package"/*.java; do
    case "$source" in */LandmarkNbt.java|*/LandmarkStore.java) ;;
        *) sources+=("$source");; esac
done
javac --release 21 -proc:none -Xlint:all -d "$classes" "${sources[@]}"
java -cp "$classes" io.github.mysticism.landmark.extract.ExtractionSelfTest
if [[ -n "${MYSTICISM_MINECRAFT_CLASSPATH:-}" ]]; then
    javac --release 21 -proc:none -Xlint:all,-path -cp "$classes:$MYSTICISM_MINECRAFT_CLASSPATH" -d "$classes" \
        "$package/LandmarkNbt.java" "$package/LandmarkStore.java" "$package/extract/ExtractionJournal.java" \
        "$root/src/test/java/io/github/mysticism/landmark/extract/ExtractionPersistenceSelfTest.java"
    java -cp "$classes:$MYSTICISM_MINECRAFT_CLASSPATH" io.github.mysticism.landmark.extract.ExtractionPersistenceSelfTest
else
    printf '%s\n' 'Disk checks NOT RUN: set MYSTICISM_MINECRAFT_CLASSPATH to existing mapped Minecraft/runtime jars.'
fi
printf 'Self-test class output retained at %s\n' "$classes"
