package io.github.mysticism.client.spiritworld;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Packaged registration/bytecode/mapped-target contracts, not a live Mixin launch. */
public final class SpiritPickingContractSelfTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static ClassNode bytecode(String name) throws Exception {
        var stream = SpiritPickingContractSelfTest.class.getClassLoader().getResourceAsStream(name + ".class");
        check(stream != null, "Missing class " + name);
        try (stream) {
            var node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        }
    }
    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values != null) for (int i = 0; i < annotation.values.size(); i += 2)
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        return null;
    }
    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    private static boolean calls(MethodNode method, String owner, String name) {
        for (var instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return true;
        return false;
    }
    private static void hook(String mixin, String hookName, String target, String targetMethod,
            String targetDescriptor, String at, boolean cancellable) throws Exception {
        var node = bytecode("io/github/mysticism/client/mixin/" + mixin);
        MethodNode hook = method(node, hookName);
        var annotations = new ArrayList<AnnotationNode>();
        if (hook.visibleAnnotations != null) annotations.addAll(hook.visibleAnnotations);
        if (hook.invisibleAnnotations != null) annotations.addAll(hook.invisibleAnnotations);
        var inject = annotations.stream().filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;"))
                .findFirst().orElseThrow();
        check(value(inject, "method").equals(List.of(targetMethod)) ||
                value(inject, "method").equals(List.of(targetMethod + targetDescriptor)), "Exact target selector " + mixin);
        var ats = (List<?>) value(inject, "at");
        check(ats.size() == 1 && at.equals(value((AnnotationNode) ats.getFirst(), "value")), "Injection phase " + mixin);
        check(Boolean.TRUE.equals(value(inject, "cancellable")) == cancellable, "Cancellation contract " + mixin);
        var mappedTarget = bytecode(target);
        check(mappedTarget.methods.stream().anyMatch(m -> m.name.equals(targetMethod) && m.desc.equals(targetDescriptor)),
                "Mapped target descriptor must exist " + targetMethod);
        if (cancellable) check(calls(hook, "org/spongepowered/asm/mixin/injection/callback/CallbackInfo", "cancel"),
                "Hook must actually cancel, not only declare cancellable");
    }
    public static void main(String[] args) throws Exception {
        var loader = SpiritPickingContractSelfTest.class.getClassLoader();
        try (var reader = new InputStreamReader(loader.getResourceAsStream("mysticism.client.mixins.json"), StandardCharsets.UTF_8)) {
            var config = JsonParser.parseReader(reader).getAsJsonObject();
            check(config.get("required").getAsBoolean(), "Client hooks must not silently disappear");
            check(config.getAsJsonObject("injectors").get("defaultRequire").getAsInt() == 1, "Missing targets fail visibly");
            for (String expected : List.of("SpiritPickingMixin", "SpiritCrosshairHudMixin", "SpiritNativeSceneMixin", "SpiritInteractionMixin")) {
                int count = 0;
                for (var entry : config.getAsJsonArray("client")) if (entry.getAsString().equals(expected)) count++;
                check(count == 1, "Hook registered exactly once: " + expected);
            }
        }
        hook("SpiritPickingMixin", "mysticism$skipCarrierPicking", "net/minecraft/client/render/GameRenderer",
                "updateCrosshairTarget", "(F)V", "HEAD", true);
        hook("SpiritNativeSceneMixin", "mysticism$hideCarrierOutline", "net/minecraft/client/render/WorldRenderer",
                "drawBlockOutline", "(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;" +
                        "Lnet/minecraft/entity/Entity;DDDLnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)V", "HEAD", true);
        hook("SpiritCrosshairHudMixin", "mysticism$touchHint", "net/minecraft/client/gui/hud/InGameHud",
                "renderCrosshair", "(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V", "RETURN", false);
        var picking = method(bytecode("io/github/mysticism/client/mixin/SpiritPickingMixin"), "mysticism$skipCarrierPicking");
        check(calls(picking, "io/github/mysticism/client/spiritworld/SpiritCrosshair", "inSpiritWorld"),
                "Picking suppression is dimension-scoped, not dependent on navigation packet readiness");
        check(calls(picking, "io/github/mysticism/client/spiritworld/SpiritCrosshair", "clearCarrierTarget"),
                "Cancellation must replace stale hit state");
        var crosshair = bytecode("io/github/mysticism/client/spiritworld/SpiritCrosshair");
        var clear = method(crosshair, "clearCarrierTarget");
        for (String field : List.of("targetedEntity", "crosshairTarget")) {
            boolean found = false;
            for (var instruction : clear.instructions) if (instruction instanceof FieldInsnNode write
                    && write.getOpcode() == Opcodes.PUTFIELD && write.name.equals(field)) found = true;
            check(found, "Reset stale " + field);
        }
        boolean fakeEntityHit = false;
        for (var m : crosshair.methods) for (var instruction : m.instructions)
            if (instruction instanceof TypeInsnNode type && type.desc.equals("net/minecraft/util/hit/EntityHitResult")) fakeEntityHit = true;
        check(!fakeEntityHit, "Render-only peers must not be exposed as vanilla entity hits");
        var semantic = bytecode("io/github/mysticism/client/spiritworld/SpiritSemanticEntityRenderer");
        check(calls(method(semantic, "tryTouch"), semantic.name, "touchTarget"), "Click uses shared read-only target query");
        check(calls(method(crosshair, "drawTouchHint"), semantic.name, "touchTarget"), "HUD uses the same target query");
        check(calls(method(semantic, "touchTarget"), "io/github/mysticism/client/spiritworld/SpiritTouchPicking", "pick"),
                "Both paths use tested projected geometry, not a carrier raycast");
        check(!calls(method(semantic, "touchTarget"), "net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking", "send"),
                "Looking at a peer must not send touch packets");
        System.out.println("SpiritPickingContractSelfTest: " + checks + " checks passed (offline contracts only)");
    }
}
