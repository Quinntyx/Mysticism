# Spirit Atlas integration contract

## Delivered

An original, dependency-free vanilla `Screen`: positioned prerequisite graph, cursor-anchored 0.5–2× zoom, drag/WASD panning, clickable vanilla item icons, keyboard selection, read-only vanilla item inspection, wrapped/paginated entry sheets, display-only shaped recipes, tooltips, bounded back history, resizing and resource-pack reload support. No Thaumcraft assets, code or text are used.

The purple background is a procedural **CPU-rendered pixel shimmer**, not a registered GPU shader. Its gradient and pixels share one managed `DrawContext.draw(Runnable)` batch; there are two boundary flushes rather than one flush per pixel (1,620 pixels at 1920×1080, GUI scale 1). This deliberately uses vanilla `DrawContext` batches and touches neither world post-processing nor shader state. Motion can be disabled with the top-row button; the static purple fallback and preference last for the client session. Live GPU safety could not be verified, so no speculative core shader was introduced.

All documentation is readable. Prerequisites gate only the local “Mark studied” button on the last sheet; marks last until this atlas instance closes. They are **not server-authoritative research, world state or unlocks**. Operational/mixed/future labels distinguish existing commands and vanilla mechanics from proposed caves, landmarks, octrees and persona integration. No embeddings or mutable component vectors are read, normalized, blended or retained.

## Registration / downstream hooks

All implementation classes are in `io.github.mysticism.client.gui.guidebook`, in the **client source set**. Nothing references them from common/server code.

* `GuidebookClient.init(): void` — client-thread initialization, idempotent. The only existing-file change is its fully-qualified call in `MysticismClient.onInitializeClient()`. It registers the resource listener, rebindable **J** key under the Mysticism controls category and the **client-only `/spiritguide`** command. The command defers opening until the end-of-client tick so chat can close.
* `GuidebookClient.open(MinecraftClient): void` — client-thread helper for optional client item-use/network glue **after `init()`**. Opens only when a player exists and no other screen is open. It never registers an item or sends a packet. No common initializer hookup is required and no guidebook item is added.
* `new SpiritGuidebookScreen(Screen parent)` — public screen constructor, nullable parent; `MinecraftClient.setScreen(...)` opens it. Closing returns to that parent. `shouldPause()` is false.
* `GuidebookLoader.RESOURCE` — `mysticism:guidebook/spirit.json`.
* `GuidebookLoader.snapshot(): Snapshot` — immutable record `(Guidebook book, long revision, boolean failed)`, atomically published through a volatile reference. Valid loads replace the complete book and increase revision; invalid/missing resources log a warning, retain the previous valid book and set `failed=true`. Initial failure yields a readable error entry. Screens update on revision changes and retain compatible study/history IDs. No network/API claim of gameplay completion is made.
* `GuidebookLoader.loadBundled(): void` loads the packaged UTF-8 fallback. The Fabric synchronous client-resource listener overrides it from active resource packs.
* `GuidebookJson.read(Reader): Guidebook` — bounded decoder (262,144 characters); does **not** close the caller's reader. Parse/shape errors throw unchecked exceptions; oversized input/reader failures throw `IOException`. Call `.validate(Predicate<String> knownItem)` before publication. Loader validation checks actual `Registries.ITEM` identifiers.
* `Guidebook.validate(...)` returns the validated same immutable record, or rejects invalid identifiers, translation keys, coordinates, item IDs, page limits, missing links/parents, duplicate IDs/parents and cyclic prerequisites.
* `Guidebook.entry(String)` throws `IllegalArgumentException` for an unknown ID. `readyToStudy(String, Set<String>)` checks all direct study prerequisites, not gameplay unlocks.
* `GuidebookViewport` — logical GUI coordinates only: `screen = origin + pan + world * zoom`; `toWorld` is the inverse. `zoomAt(factor, cursorX, cursorY, originX, originY)` preserves the world point under the cursor and clamps zoom. `pan(dx,dy)` and `center(worldX,worldY)` ignore non-finite updates. Screen resize changes origin, not stored pan/zoom.
* `GuidebookPagination.pack(rows, capacity, heightFunction)` preserves order and returns immutable page/row lists; capacity and each row height must be positive, and oversized rows are rejected. Empty input produces one empty page. `clamp(page,count)` returns a safe zero-based sheet index.

Records defensively copy every schema list (book entries, prerequisites, pages, blocks and recipe slots). Item inspection defensively copies its `ItemStack`. Internal study marks, selection, motion and history are local UI state only.

## Resource/page schema (version 1)

Bundled files:

* `src/client/resources/assets/mysticism/guidebook/spirit.json`
* `src/client/resources/assets/mysticism/lang/en_us.json`

Resources use the existing `mysticism` namespace with new `guidebook.mysticism.*` translation keys. Existing misplaced language files remain untouched.

All objects have **exact** fields; no executable command/link blocks or arbitrary textures exist:

```json
{
  "version": 1,
  "entries": [{
    "id": "example", "title": "guidebook.mysticism.example.title",
    "category": "guidebook.mysticism.category.basics",
    "x": 0, "y": 0, "icon": "minecraft:book", "status": "OPERATIONAL",
    "parents": [],
    "pages": [{"title": "guidebook.mysticism.example.page", "blocks": [
      {"type": "paragraph", "text": "guidebook.mysticism.example.body"},
      {"type": "item", "text": "guidebook.mysticism.example.item", "item": "minecraft:book", "target": ""},
      {"type": "recipe", "text": "guidebook.mysticism.example.recipe",
       "ingredients": ["", "minecraft:iron_ingot", "", "minecraft:iron_ingot", "minecraft:redstone", "minecraft:iron_ingot", "", "minecraft:iron_ingot", ""],
       "output": "minecraft:compass", "target": ""}
    ]}]
  }]
}
```

Bounds: 1–256 entries; ID `[a-z0-9_./-]{1,64}`; translation key `[a-z0-9_.-]{1,160}`; finite coordinates with absolute value ≤10,000; ≤32 distinct prerequisites; 1–64 pages per entry; 1–128 blocks per page. Item IDs must be namespaced and registry-valid. Recipe slots are exactly nine row-major entries; `""` is an empty slot and an entirely empty grid is invalid. Nonempty `target` must name an existing entry. Empty targets follow another entry sharing the clicked icon, if available, or open vanilla item inspection. Recipes are static documentation, **not recipe-manager queries or crafting actions**.

Statuses are `OPERATIONAL`, `MIXED`, `PLANNED`. Bundled entry IDs: `crossing` (root), `attunement` (parent `crossing`), `echoes` and `growing` (parent `attunement`), `compass` (parent `crossing`). `compass` contains the vanilla shaped recipe; other entries explain actual controls and clearly labeled future targets.

To integrate future server progression, introduce an explicit, server-confirmed contract rather than reinterpreting `readyToStudy` or `studied` as authoritative completion. Keep model-tagged embeddings separate; this implementation has no vector API.

## Controls

J or `/spiritguide` opens when no competing screen is present. Drag/WASD pans; wheel/+− zooms; arrows select and recenter a tree node; Enter opens it; Home returns to/centers the tree. On pages, wheel, Left/Right or Page Up/Down turns sheets. Back, Backspace or page right-click follows history (maximum 64 entries). Tab and Enter/Space operate vanilla buttons/item icons; Escape/Done closes. Wrapping is cached per entry, content revision and body dimensions; resize reflows and clamps pagination without resetting selection/tree transforms.

## Deterministic validation

Run with Java 21:

```sh
bash src/clientTest/java/io/github/mysticism/client/gui/guidebook/run-checks.sh
```

Uses only Java and the Gson jar already supplied by Minecraft/Gradle. Set `GSON_JAR=/absolute/path/to/existing/gson.jar` if not cached. The runner never downloads anything. Checks live outside default `src/test`: the project's split client source set is not on the default test compile classpath, and changing build configuration is outside this feature's scope. A parent can wire this executable main into CI without JUnit or new dependencies.

Tests cover 1,000 seeded transform/anchoring cases, extreme and invalid zoom inputs, resize origins, bounded/overflow-safe pagination, order preservation, defensive copies, duplicate/missing/cyclic prerequisites, status/coordinate/item/schema constraints, oversized resources, invalid block/link overrides and bundled translation/command references. The standalone schema/math run now passes **11,253 assertions**. A second executable suite passes **57 lifecycle/draw-count assertions**, compiling the actual production `GuidebookScreen` and `GuidebookShimmer` against minimal vanilla-behavior fixtures. Those fixtures model late background rendering, per-fill flushes outside managed callbacks, keyboard focus, and mouse focus assignment after callbacks; they are test-only and never included in the release artifact. They are not a substitute for a live Minecraft GUI test.

## Independent review fixes

Both concrete screens now inherit the package-private `GuidebookScreen`. Its final no-op `renderBackground` prevents vanilla blur/overlay from running after atlas content; each concrete renderer already paints its own background exactly once. Widget rebuilds clear focus and dragging before removing children. Mouse dispatch discards detached focus after vanilla assigns a clicked child, and key dispatch rejects detached focus before invoking it. Live-child focus remains intact. The study callback captures its entry ID and rechecks current entry, prerequisites and final-sheet eligibility.

The lifecycle suite reproduces study → Home → Enter and inspector pagination with keyboard and mouse callbacks, resize/reload rebuilds, and managed shimmer flushing at five GUI sizes with motion on/off. Four separately compiled mutations (late background, missing rebuild focus clearing, missing post-mouse clearing, unbatched pixels) were all rejected by behavioral assertions.

`/latent log show` and `/latent log hide` only change the current logging flag. The common `LatentCommands.tickActionbar(MinecraftServer)` has no server-tick registration; the entry now states that periodic actionbar output is unavailable and recommends `/latent show` for an immediate report. If periodic output is desired, the **parent/common owner** must register that existing method on an appropriate server-tick callback and update this text when operational. No common initializer was changed.

Review verification reran the unchanged build (configuration failure, exit 1) and the external Loom 1.10.5 override (`compileClientJava guidebookCompileCheck remapJar`, successful, exit 0). `DrawContext.draw(Runnable)` is deprecated in Yarn 1.21.1 but remains its supported managed-drawing API; compilation reports that deprecation, not an error. The new remapped artifact contains **24 guidebook classes**, including the lifecycle base, with remapped Screen inheritance and no test fixtures.

## Build verification / parent action required

* Supplied Gradle 8.13 with the unchanged project fails configuration: the current `fabric-loom:1.11-SNAPSHOT` resolves to a release requiring Gradle ≥8.14. No Gradle installation or build-file modification was performed.
* For verification only, `/tmp/mysticism-guidebook-check.init.gradle` overrides Loom to `1.10.5` via `pluginManagement.resolutionStrategy`. With that external override, `compileClientJava` and the separately registered guidebook-package compile passed; `build` passed in 58 seconds. Client compilation emits existing missing `org.apiguardian.api.API$Status` annotation warnings (97), not compilation errors. Gradle's default `test` task reports **NO-SOURCE**: the actual deterministic assertions were run by the standalone runner above.
* **Packaging blocker:** the existing `shadowJar` uses the same classifier/output filename as the remapped artifact and runs later during `build`. Its resulting 213 MB jar contains **no client classes or client resources**, including the atlas. This is a baseline build/source-set configuration problem; fixing build configuration is owned by the parent, not this feature. Do not ship that jar as though the guidebook were included.
* A separate `remapJar --rerun-tasks` with the same external override succeeds. Original-implementation ZIP inspection verified all **23 guidebook class files** (now **24** after the review lifecycle fix), both atlas resource files and the intermediary `net/minecraft/class_437` superclass reference (not named `Screen`). The dev jar also contains the complete atlas. Running `build` again can overwrite the correct remapped jar with the incomplete shadow jar; the parent must resolve the output collision and incorporate client output when producing the intended shaded release.
* Static compiled-common-class scanning found **zero guidebook references**; all entrypoint glue is client-only. This supports server class separation but is not a live dedicated-server boot test.
* Live client interaction, narrator/tooltips across drivers, GUI-scale/language/reload behavior, dedicated-server boot, native DJL portability and a shaded production runtime were **not exercised**. Starting the game may initialize embedding/model downloads; no model weights were downloaded or runtime initialization triggered for these checks. The feature adds no service providers, native libraries or dependencies.

Successful compilation/remapping is not an assertion of live GUI/driver/server behavior. The actual build failure, compatibility-only successes and remaining packaging problem are distinct outcomes.
