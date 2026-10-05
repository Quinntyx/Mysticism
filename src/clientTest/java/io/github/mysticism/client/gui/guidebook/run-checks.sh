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

# Isolated vanilla-contract fixtures: compile the actual lifecycle base and shimmer,
# never all client sources against fixtures. Output is separate from production classes.
fixtures="$root/src/clientTest/java/io/github/mysticism/client/gui/guidebook/fixtures"
lifecycle="$root/build/guidebook-lifecycle-checks"
mkdir -p "$lifecycle"
javac --release 21 -encoding UTF-8 -d "$lifecycle" \
    "$fixtures/net/minecraft/text/Text.java" "$fixtures/net/minecraft/util/Util.java" \
    "$fixtures/net/minecraft/client/gui/Element.java" "$fixtures/net/minecraft/client/gui/DrawContext.java" \
    "$fixtures/net/minecraft/client/gui/screen/Screen.java" \
    "$pkg/GuidebookScreen.java" "$pkg/GuidebookShimmer.java" \
    "$root/src/clientTest/java/io/github/mysticism/client/gui/guidebook/GuidebookLifecycleChecks.java"
java -ea -cp "$lifecycle" io.github.mysticism.client.gui.guidebook.GuidebookLifecycleChecks "$root"
