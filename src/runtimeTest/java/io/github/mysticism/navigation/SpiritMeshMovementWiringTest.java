package io.github.mysticism.navigation;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.objectweb.asm.*;

/** Static wiring checks against the actual mapped Minecraft bytecode used by this build.
 * These catch target/descriptor/registration drift, not a substitute for a Fabric launch. */
public final class SpiritMeshMovementWiringTest {
    private static int checks;
    private static final String OWNER = "net/minecraft/server/network/ServerPlayerEntity";
    private static final String POSE = "updatePositionAndAngles";
    private static final String DESCRIPTOR = "(DDDFF)V";
    private static final String TARGET = "L" + OWNER + ";" + POSE + DESCRIPTOR;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static InputStream resource(String path) {
        var stream = SpiritMeshMovementWiringTest.class.getClassLoader().getResourceAsStream(path);
        if (stream == null) throw new AssertionError("Missing " + path);
        return stream;
    }
    public static void main(String[] args) throws Exception {
        try (var stream = resource("mysticism.mixins.json")) {
            check(new String(stream.readAllBytes(), StandardCharsets.UTF_8).contains("\"SpiritMeshMovementAcceptanceMixin\""), "movement acceptance mixin is registered");
        }
        int[] calls = new int[2];
        try (var stream = resource("net/minecraft/server/network/ServerPlayNetworkHandler.class")) {
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals("onPlayerMove")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        boolean swept;
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                            if (owner.equals(OWNER) && name.equals("move")) swept = true;
                            if (owner.equals(OWNER) && name.equals(POSE) && descriptor.equals(DESCRIPTOR)) {
                                calls[0]++;
                                if (swept) calls[1]++;
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        check(calls[0] == 2 && calls[1] == 1, "vanilla has vehicle pose write plus one post-sweep acceptance write");
        boolean[] wired = new boolean[5];
        try (var stream = resource("io/github/mysticism/mixin/SpiritMeshMovementAcceptanceMixin.class")) {
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals("mysticism$acceptSweptPose")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                            if (!descriptor.endsWith("/Redirect;")) return null;
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override public AnnotationVisitor visitArray(String name) {
                                    if (!name.equals("method")) return null;
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override public void visit(String name, Object value) { wired[0] |= "onPlayerMove".equals(value); }
                                    };
                                }
                                @Override public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override public void visit(String name, Object value) {
                                            wired[1] |= name.equals("target") && TARGET.equals(value);
                                            wired[4] |= name.equals("ordinal") && Integer.valueOf(1).equals(value);
                                        }
                                    };
                                }
                            };
                        }
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                            wired[2] |= owner.equals("io/github/mysticism/navigation/SpiritMovementAcceptance") && name.equals("accept");
                            wired[3] |= name.equals("requestTeleport") && descriptor.equals(DESCRIPTOR);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        check(wired[0] && wired[1] && wired[4], "redirect targets only the real post-sweep vanilla packet pose write, not the vehicle write");
        check(wired[2] && wired[3], "redirect uses tested acceptance and an authoritative mismatch correction");
        var destinationCalls = new java.util.ArrayList<String>();
        try (var stream = resource("io/github/mysticism/navigation/SpiritNavigationService.class")) {
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!name.equals("explicitDestinationChange")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                            destinationCalls.add(name);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        check(destinationCalls.contains("cancelCapture")
                        && destinationCalls.indexOf("cancelCapture") < destinationCalls.indexOf("invalidateMotionPrediction"),
                "destination API itself invalidates delayed captures before starting the new destination epoch");
        check(destinationCalls.contains("endSupportApproach") && destinationCalls.contains("endApproach"),
                "destination API cancels both current-support and captured-landing trajectories");
        System.out.println("SpiritMeshMovementWiringTest: " + checks + " checks passed");
    }
}
