#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../../../../../../.." && pwd)"
classes="$(mktemp -d "${TMPDIR:-/tmp}/mysticism-activity-tests.XXXXXXXX")"
main="$root/src/main/java/io/github/mysticism"
base=("$main/vector/EmbeddingSpace.java" "$main/vector/Vec384f.java" "$main/vector/Basis384f.java" "$main/activity/ActivityMath.java" "$main/activity/TraversalSteering.java")
javac --release 21 -proc:none -d "$classes" "${base[@]}" "$root/src/test/java/io/github/mysticism/activity/ActivitySelfTest.java"
java -cp "$classes" io.github.mysticism.activity.ActivitySelfTest
if [[ -n "${MYSTICISM_MINECRAFT_CLASSPATH:-}" ]]; then
  sources=("$main"/landmark/*.java "$main"/component/*.java "$main/activity/LandmarkActivityState.java" "$main/activity/LandmarkInfluence.java" "$main/activity/LandmarkMerge.java" "$main/activity/SpiritActivityService.java" "$main"/activity/mixin/*.java)
  for source in "$main"/embedding/*.java; do [[ "$source" == */IndexGeneration.java ]] || sources+=("$source"); done
  profile="${MYSTICISM_PROFILE_SOURCE:-$main/landmark/extract/LandmarkProfiles.java}"
  sources+=("$profile")
  terrain="${MYSTICISM_TERRAIN_SOURCE:-$main/dimension/spiritworld/terrain}"
  if [[ -f "$terrain/SpiritTerrainService.java" ]]; then
    sources+=("$terrain"/*.java "$main/dimension/spiritworld/SpiritBasisEvolver.java")
  else
    echo 'Terrain-linked evolver compile NOT RUN: provide the real terrain service source directory.'
  fi
  javac --release 21 -proc:none -sourcepath '' -cp "$classes:$MYSTICISM_MINECRAFT_CLASSPATH" -d "$classes" "${sources[@]}" "$root/src/test/java/io/github/mysticism/activity/ActivityPersistenceTest.java" "$root/src/test/java/io/github/mysticism/activity/ActivityAdapterTest.java" "$root/src/test/java/io/github/mysticism/activity/ActivityMergeTest.java"
  java -cp "$classes:$MYSTICISM_MINECRAFT_CLASSPATH" io.github.mysticism.activity.ActivityPersistenceTest
  java -cp "$classes:$MYSTICISM_MINECRAFT_CLASSPATH" io.github.mysticism.activity.ActivityAdapterTest
  java -cp "$classes:$MYSTICISM_MINECRAFT_CLASSPATH" io.github.mysticism.activity.ActivityMergeTest
else
  echo 'Production adapters/persistence NOT RUN: set MYSTICISM_MINECRAFT_CLASSPATH to existing mapped Minecraft, Fabric, CCA and runtime jars.'
fi
echo "Test classes retained at $classes"
