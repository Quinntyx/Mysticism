#!/usr/bin/env bash
set -euo pipefail
root=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
if [[ -z "${GSON_JAR:-}" ]]; then
    shopt -s nullglob
    jars=("${GRADLE_USER_HOME:-$HOME/.gradle}"/caches/modules-2/files-2.1/com.google.code.gson/gson/2.{11.0,10.1}/*/*.jar)
    if (( ${#jars[@]} == 0 )); then
        printf '%s\n' 'Set GSON_JAR to an existing Minecraft Gson jar; this runner does not download dependencies.' >&2
        exit 1
    fi
    GSON_JAR=${jars[0]}
fi
out="$root/build/guidebook-checks"
pkg="$root/src/client/java/io/github/mysticism/client/gui/guidebook"
mkdir -p "$out"
javac --release 21 -encoding UTF-8 -cp "$GSON_JAR" -d "$out" \
    "$pkg/Guidebook.java" "$pkg/GuidebookJson.java" "$pkg/GuidebookViewport.java" "$pkg/GuidebookPagination.java" \
    "$root/src/clientTest/java/io/github/mysticism/client/gui/guidebook/GuidebookChecks.java"
java -ea -cp "$out:$GSON_JAR" io.github.mysticism.client.gui.guidebook.GuidebookChecks "$root"
