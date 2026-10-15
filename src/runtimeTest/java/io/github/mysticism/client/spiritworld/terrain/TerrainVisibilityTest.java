package io.github.mysticism.client.spiritworld.terrain;

import java.util.List;
import net.minecraft.client.render.Frustum;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.SourceInterpreter;

/** Executes the actual renderer culling policy and checks its compiled effective-distance wiring.
 * No Minecraft client, GL context, live server or model inference is started. */
public final class TerrainVisibilityTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        effectiveDistance();
        boundsAndCamera();
        frustum();
        renderWiring();
        System.out.println("TerrainVisibilityTest: " + checks + " checks passed (actual client culling; no game/GPU launch)");
    }

    private static void effectiveDistance() {
        Box landmark = new Box(90, 0, 0, 94, 4, 4);
        check(TerrainVisibility.isVisible(landmark, Vec3d.ZERO, 8, null), "90-block landmark visible at eight chunks, not fixed 64 blocks");
        check(!TerrainVisibility.isVisible(landmark, Vec3d.ZERO, 4, null), "reduced effective view distance takes effect immediately");
        check(TerrainVisibility.isVisible(landmark, Vec3d.ZERO, 8, null), "increased effective view distance needs no new frame/upload");
        for (int chunks = 2; chunks <= 32; chunks++) {
            double radius = chunks * 16.0;
            check(TerrainVisibility.isVisible(new Box(radius, 0, 0, radius + 4, 4, 4), Vec3d.ZERO, chunks, null), "horizon boundary included at " + chunks);
            check(!TerrainVisibility.isVisible(new Box(radius + .01, 0, 0, radius + 4, 4, 4), Vec3d.ZERO, chunks, null), "horizon bounded at " + chunks);
        }
        check(!TerrainVisibility.isVisible(landmark, Vec3d.ZERO, 0, null), "zero distance does not accidentally unbound culling");
        check(!TerrainVisibility.isVisible(landmark, Vec3d.ZERO, -1, null), "negative distance does not square into a positive range");
        check(TerrainVisibility.isVisible(landmark, Vec3d.ZERO, Integer.MAX_VALUE, null), "distance conversion cannot overflow integer block units");
    }

    private static void boundsAndCamera() {
        for (Vec3d camera : List.of(Vec3d.ZERO, new Vec3d(10000, -400, -20000))) {
            for (Vec3d direction : List.of(new Vec3d(1,0,0), new Vec3d(-1,0,0), new Vec3d(0,1,0),
                    new Vec3d(0,-1,0), new Vec3d(0,0,1), new Vec3d(0,0,-1))) {
                Vec3d center = camera.add(direction.multiply(92));
                Box box = new Box(center.x-2, center.y-2, center.z-2, center.x+2, center.y+2, center.z+2);
                check(TerrainVisibility.isVisible(box, camera, 8, null), "projected/carrier translation and signed axis cannot shrink view distance");
                check(!TerrainVisibility.isVisible(box, camera, 4, null), "nearest bound is still beyond a smaller view distance");
            }
        }
        check(TerrainVisibility.isVisible(new Box(90,-1,-400,500,1,400), Vec3d.ZERO, 8, null), "overflow VBO straddling horizon uses nearest bounds, not far center");
        check(TerrainVisibility.isVisible(new Box(-500,-500,-500,500,500,500), Vec3d.ZERO, 2, null), "camera inside a batch stays visible");
        check(TerrainVisibility.isVisible(new Box(90,0,90,94,4,94), Vec3d.ZERO, 8, null), "near diagonal within sphere retained");
        check(!TerrainVisibility.isVisible(new Box(100,0,100,104,4,104), Vec3d.ZERO, 8, null), "distance remains radial, not unbounded diagonal cube");
    }

    private static void frustum() {
        var rejecting = new Frustum(new Matrix4f(), new Matrix4f()) {
            @Override public boolean isVisible(Box bounds) { return false; }
        };
        Box landmark = new Box(90,0,0,94,4,4);
        check(!TerrainVisibility.isVisible(landmark, Vec3d.ZERO, 8, rejecting), "render-distance increase does not bypass frustum culling");
        var identity = new Frustum(new Matrix4f(), new Matrix4f());
        check(TerrainVisibility.isVisible(new Box(-.25,-.25,-.25,.25,.25,.25), Vec3d.ZERO, 8, identity), "real frustum accepts contained bounds");
        check(!TerrainVisibility.isVisible(landmark, Vec3d.ZERO, 8, identity), "real frustum rejects bounds outside view");
    }

    private static void renderWiring() throws Exception {
        String owner = "io/github/mysticism/client/spiritworld/terrain/SpiritTerrainClient";
        ClassNode client = new ClassNode();
        // Read bytecode rather than initializing SpiritTerrainClient's RenderLayer/GL dependencies.
        try (var stream = TerrainVisibilityTest.class.getResourceAsStream("/" + owner + ".class")) {
            check(stream != null, "production client renderer class packaged");
            new ClassReader(stream).accept(client, 0);
        }
        var render = client.methods.stream().filter(m -> m.name.equals("render")).findFirst().orElseThrow();
        MethodInsnNode effective = null, decision = null;
        for (var instruction : render.instructions) if (instruction instanceof MethodInsnNode call) {
            if (call.owner.equals("net/minecraft/client/option/GameOptions") && call.name.equals("getClampedViewDistance")) {
                check(effective == null, "effective client/server distance read once per frame");
                effective = call;
            }
            if (call.owner.equals("io/github/mysticism/client/spiritworld/terrain/TerrainVisibility") && call.name.equals("isVisible")) {
                check(decision == null, "actual draw loop uses shared culling decision");
                decision = call;
            }
        }
        check(effective != null && decision != null, "render must read effective distance and invoke the tested visibility policy");
        var frames = new Analyzer<>(new SourceInterpreter()).analyze(client.name, render);
        var frame = frames[render.instructions.indexOf(decision)];
        // isVisible(Box, Vec3d, int, Frustum): trace the integer argument through its local store.
        var argument = frame.getStack(frame.getStackSize() - 2);
        check(argument.insns.size() == 1, "distance argument has one producer");
        var producer = argument.insns.iterator().next();
        check(producer instanceof VarInsnNode && producer.getOpcode() == Opcodes.ILOAD, "distance read from per-frame local");
        var load = (VarInsnNode) producer;
        var local = frames[render.instructions.indexOf(load)].getLocal(load.var);
        check(local.insns.size() == 1, "distance local is not overwritten/merged with a fixed cap");
        var store = local.insns.iterator().next();
        check(store.getOpcode() == Opcodes.ISTORE, "per-frame distance local store");
        var beforeStore = frames[render.instructions.indexOf(store)];
        check(beforeStore.getStack(beforeStore.getStackSize() - 1).insns.contains(effective),
                "visibility receives actual effective options distance, not a test-only or fixed radius");
    }
}
