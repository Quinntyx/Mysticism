package io.github.mysticism.build;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Positive and negative fixtures for packaging failures previously present in the build. */
public final class PackagingSelfTest {
    private PackagingSelfTest() {}

    public static void main(String[] args) throws Exception {
        if (!PackagingSelfTest.class.desiredAssertionStatus()) throw new IllegalStateException("Run with -ea");
        var good = fixture();
        ProductionJarVerifier.verify(archive(good), "1.0-test");
        expectFailure(() -> ProductionJarVerifier.verify(archive(good), "wrong-version"), "Wrong mod version");
        for (String missing : new String[]{"LICENSE_mysticism.txt", "assets/mysticism/lang/en_us.json",
                "mysticism.client.mixins.json", "io/github/mysticism/client/MysticismClient.class", "META-INF/jars/library.jar"}) {
            var broken = new LinkedHashMap<>(good);
            broken.remove(missing);
            expectFailure(() -> ProductionJarVerifier.verify(archive(broken), "1.0-test"), "Missing production resource");
        }
        for (String forbidden : new String[]{"assets/mysticism/en_us.json", ".DS_Store",
                "ai/djl/Model.class", "META-INF/services/ai.djl.repository.zoo.ZooProvider", "../escape"}) {
            var broken = new LinkedHashMap<>(good);
            broken.put(forbidden, new byte[0]);
            expectFailure(() -> ProductionJarVerifier.verify(archive(broken), "1.0-test"),
                    forbidden.endsWith("en_us.json") ? "Language file outside" :
                            forbidden.equals(".DS_Store") ? "Finder metadata" :
                                    forbidden.startsWith("../") ? "Unsafe archive path" : "Local model runtime");
        }
        var missingIcon = new LinkedHashMap<>(good);
        missingIcon.put("fabric.mod.json", text(new String(good.get("fabric.mod.json"), StandardCharsets.UTF_8)
                .replace("\"id\":", "\"icon\":\"assets/mysticism/missing.png\",\"id\":")));
        expectFailure(() -> ProductionJarVerifier.verify(archive(missingIcon), "1.0-test"), "Missing production resource");
        var unexpanded = new LinkedHashMap<>(good);
        unexpanded.put("fabric.mod.json", text(new String(good.get("fabric.mod.json"), StandardCharsets.UTF_8)
                .replace("1.0-test", "${version}")));
        expectFailure(() -> ProductionJarVerifier.verify(archive(unexpanded), "1.0-test"), "Unexpanded Fabric metadata");
        var nestedRuntime = new LinkedHashMap<>(good);
        nestedRuntime.put("META-INF/jars/library.jar", archive(Map.of("ai/djl/Model.class", classFixture("java/lang/Object", 65))));
        expectFailure(() -> ProductionJarVerifier.verify(archive(nestedRuntime), "1.0-test"), "Local model runtime");
        ProductionJarVerifier.verifyClass(classFixture("net/minecraft/class_310", 65));
        ProductionJarVerifier.verifyClass(classFixture("[Lnet/minecraft/class_2338;", 65));
        for (String stable : ProductionJarVerifier.STABLE_MINECRAFT_NAMES) {
            ProductionJarVerifier.verifyClass(classFixture(stable, 65));
        }
        expectFailure(() -> ProductionJarVerifier.verifyClass(classFixture("net/minecraft/server/MinecraftServer$WorldLoading", 65)),
                "Unremapped Minecraft class reference");
        expectFailure(() -> ProductionJarVerifier.verifyClass(classFixture("net/minecraft/client/MinecraftClient", 65)),
                "Unremapped Minecraft class reference");
        expectFailure(() -> ProductionJarVerifier.verifyClass(classFixture("java/lang/Object", 66)), "Unsupported/preview class version");
        expectFailure(() -> ProductionJarVerifier.verifyClass(classFixture("ai/djl/Model", 65)), "Local model runtime class reference");
        expectFailure(() -> ProductionJarVerifier.verify(new byte[0], "1.0-test"), "Empty or invalid jar");
        System.out.println("Packaging self-tests passed (valid jar, stable intermediary names, plus 19 rejection cases)");
        RunnerSelfTest.run();
    }

    private static Map<String, byte[]> fixture() throws Exception {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("fabric.mod.json", text("""
                {"id":"mysticism","version":"1.0-test","license":"MIT",
                 "depends":{"java":">=21"},"mixins":["mysticism.client.mixins.json"],
                 "jars":[{"file":"META-INF/jars/library.jar"}]}
                """));
        entries.put("LICENSE_mysticism.txt", text("Permission is hereby granted"));
        entries.put("assets/mysticism/lang/en_us.json", text("{}"));
        entries.put("mysticism.client.mixins.json", text("{}"));
        entries.put("io/github/mysticism/Mysticism.class", classFixture("net/minecraft/class_3218", 65));
        entries.put("io/github/mysticism/client/MysticismClient.class", classFixture("net/minecraft/class_310", 65));
        entries.put("META-INF/jars/library.jar", archive(Map.of("library.properties", text("version=1"))));
        return entries;
    }

    private static byte[] classFixture(String name, int major) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(output)) {
            data.writeInt(0xCAFEBABE);
            data.writeShort(0);
            data.writeShort(major);
            data.writeShort(3);
            data.writeByte(1);
            data.writeUTF(name);
            data.writeByte(7);
            data.writeShort(1);
        }
        return output.toByteArray();
    }

    private static byte[] archive(Map<String, byte[]> entries) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var entry : entries.entrySet()) {
                var item = new ZipEntry(entry.getKey());
                item.setTime(0);
                zip.putNextEntry(item);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void expectFailure(Checked action, String message) throws Exception {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains(message)) throw new AssertionError("Wrong rejection", expected);
            return;
        }
        throw new AssertionError("Accepted invalid artifact: " + message);
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }
}
