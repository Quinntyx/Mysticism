package io.github.mysticism.client.spiritworld;

import io.github.mysticism.client.spiritworld.terrain.SpiritMediumField;
import java.nio.file.Files;
import java.nio.file.Path;

/** Static GL/event wiring checks, complementary to the executed production medium math tests. */
public final class SpiritTerrainDepthContractTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static String read(String path) throws Exception { return Files.readString(Path.of(path)); }
    private static String between(String text, String start, String end) {
        int a = text.indexOf(start), b = text.indexOf(end, a + start.length());
        check(a >= 0 && b > a, "callback/method range exists: " + start);
        return text.substring(a, b);
    }
    public static void main(String[] args) throws Exception {
        String shader = read("src/client/java/io/github/mysticism/client/spiritworld/ShaderManager.java");
        String start = between(shader, "WorldRenderEvents.START.register", "WorldRenderEvents.AFTER_TRANSLUCENT.register");
        String depth = between(shader, "WorldRenderEvents.AFTER_TRANSLUCENT.register", "WorldRenderEvents.END.register");
        String end = between(shader, "WorldRenderEvents.END.register", "ClientTickEvents.END_CLIENT_TICK.register");
        check(!start.contains("SpiritMediumTexture.prepare") && !start.contains("bindCurrentMedium("),
                "START must not upload/bind last frame's terrain samples");
        check(depth.contains("sceneDepth.copyDepthFrom(client.getFramebuffer())") && depth.contains("depthReady = true"),
                "capture MAIN scene depth after custom opaque mesh/entities, before Fabulous/hand clears");
        check(end.contains("if (!frameReady || sessionWorld == null) return;") && end.contains("if (!depthReady)") && end.contains("depthReady = false"),
                "postpass consumes exactly one current-world depth snapshot");
        int sampler = end.indexOf("setSamplerUniform(\"DepthSampler\", sceneDepth.getDepthAttachment())");
        int medium = end.indexOf("bindCurrentMedium(context.camera().getPos())");
        int post = end.indexOf("KUWAHARA_SHADER.render(");
        check(sampler >= 0 && sampler < medium && medium < post,
                "same-frame terrain texture/camera offset is bound alongside depth immediately before postpass");
        check(end.indexOf("main.copyDepthFrom(sceneDepth)") > post, "restore scene occlusion after postpass target clear");
        String binder = between(shader, "private static void bindCurrentMedium", "public static float effectStrength");
        check(binder.contains("SpiritMediumTexture.prepare()") && binder.contains("medium==0?0:1")
                        && binder.contains("SpiritMediumTexture.offset(camera)"),
                "absence and offset derive from the same currently prepared sample texture");

        String terrain = read("src/client/java/io/github/mysticism/client/spiritworld/terrain/SpiritTerrainClient.java");
        check(terrain.contains("WorldRenderEvents.BEFORE_ENTITIES.register(SpiritTerrainClient::render)"),
                "mesh MAIN depth is written before scene capture");
        String accept = between(terrain, "public static void accept(", "public static Optional<TerrainMeshFrame> frame()");
        check(!accept.contains("setMediumSamples") && !accept.contains("getEyePos"),
                "network acceptance cannot pin density to a stale eye position");
        String render = terrain.substring(terrain.indexOf("private static void render("));
        check(render.contains("mediumField.update(frame,camera,visibleSurfaces)") && render.contains("samples.occupancy(),samples.center()"),
                "world render refreshes uploaded model geometry/current camera without requiring a packet");
        String upload = between(terrain, "private static void upload(", "private static void drawModel(");
        check(upload.contains("modelBounds.world(anchor),cell.opacity()") && upload.contains("if(!modelBounds.empty())")
                        && upload.indexOf("visibleSurfaces=List.copyOf(surfaces)") > upload.indexOf("vbo.upload(built)"),
                "only successful nonempty model uploads publish measured density bounds");
        check(render.contains("client.getFramebuffer().beginWrite(false)") && render.contains("RenderSystem.depthMask(true)"),
                "custom terrain targets real MAIN depth");
        check(render.indexOf("b.buffer.draw(") < render.indexOf("if(drewMesh)ShaderManager.afterMeshDepth()"),
                "mesh-depth report requires an actual successful draw");
        check(render.contains("closeBuffers();dirty=false;clearMedium()") && terrain.contains("mediumField.clear();ShaderManager.clearMediumSamples()"),
                "clear/disconnect/failed upload discards density for absent geometry");
        String layers = read("src/client/java/io/github/mysticism/client/spiritworld/SpiritRenderLayers.java");
        check(layers.contains(".target(MAIN_TARGET).depthTest(LEQUAL_DEPTH_TEST).writeMaskState(ALL_MASK)"),
                "custom layer uses surface depth testing and color+depth writes, not a Fabulous side target");

        String glsl = read("src/main/resources/assets/mysticism/shaders/program/spirit_fog.fsh");
        check(glsl.contains("texelFetch(DepthSampler, pixel, 0)") && glsl.contains("min(distanceToScene, OpaqueRadius)"),
                "fog integrates only up to the scene's nearest depth, never through a near custom mesh");
        check(glsl.contains("depth >= 0.9999999 ? OpaqueRadius") && glsl.contains("endDistance - NearBubble"),
                "clear depth gets sky fog while nearby terrain retains the source bubble");
        check(SpiritMediumField.GRID == 16 && SpiritMediumField.VOXEL_SIZE == 8 && SpiritMediumField.HALF_EXTENT == 64
                        && glsl.contains("vec3(64.0)) / 8.0") && glsl.contains("ivec3(16)"),
                "CPU density volume matches shader grid/origin");
        String texture = read("src/client/java/io/github/mysticism/client/spiritworld/SpiritMediumTexture.java");
        check(texture.contains("z=(px/16)+4*(py/16),x=px%16,y=py%16")
                        && texture.contains("pending[x+16*(y+16*z)]")
                        && glsl.contains("(cell.z % 4) * 16 + cell.x, (cell.z / 4) * 16 + cell.y"),
                "CPU atlas packing and shader fetch axes agree");
        System.out.println("SpiritTerrainDepthContractTest passed: " + checks + " static checks (no live GL verification)");
    }
}
