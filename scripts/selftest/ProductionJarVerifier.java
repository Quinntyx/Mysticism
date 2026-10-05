package io.github.mysticism.build;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

/** JDK-only checks on the actual remapJar output, including Jar-in-Jar contents. */
public final class ProductionJarVerifier {
    private static final int MAX_ENTRY_BYTES = 64 * 1024 * 1024;
    // Minecraft 1.21.1 preserves these names in intermediary. Derived from the
    // official common/client jars and net.fabricmc:intermediary:1.21.1:v2.
    static final java.util.Set<String> STABLE_MINECRAFT_NAMES = java.util.Set.of(
            "net/minecraft/client/ClientBrandRetriever",
            "net/minecraft/client/main/Main",
            "net/minecraft/client/main/Main$1",
            "net/minecraft/client/main/Main$2",
            "net/minecraft/data/Main",
            "net/minecraft/obfuscate/DontObfuscate",
            "net/minecraft/server/Main",
            "net/minecraft/server/Main$1",
            "net/minecraft/server/MinecraftServer",
            "net/minecraft/server/MinecraftServer$1",
            "net/minecraft/server/MinecraftServer$class_6414",
            "net/minecraft/server/MinecraftServer$class_6414$1",
            "net/minecraft/server/MinecraftServer$class_6897",
            "net/minecraft/server/MinecraftServer$class_7460",
            "net/minecraft/util/profiling/jfr/event/ChunkGenerationEvent",
            "net/minecraft/util/profiling/jfr/event/ChunkGenerationEvent$class_6602",
            "net/minecraft/util/profiling/jfr/event/ChunkRegionReadEvent",
            "net/minecraft/util/profiling/jfr/event/ChunkRegionWriteEvent",
            "net/minecraft/util/profiling/jfr/event/NetworkSummaryEvent",
            "net/minecraft/util/profiling/jfr/event/NetworkSummaryEvent$class_6778",
            "net/minecraft/util/profiling/jfr/event/NetworkSummaryEvent$class_6779",
            "net/minecraft/util/profiling/jfr/event/PacketReceivedEvent",
            "net/minecraft/util/profiling/jfr/event/PacketSentEvent",
            "net/minecraft/util/profiling/jfr/event/ServerTickTimeEvent",
            "net/minecraft/util/profiling/jfr/event/ServerTickTimeEvent$class_6601",
            "net/minecraft/util/profiling/jfr/event/WorldLoadFinishedEvent");
    private ProductionJarVerifier() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("Usage: ProductionJarVerifier <jar> <version>");
        verify(Files.readAllBytes(Path.of(args[0])), args[1]);
        System.out.println("Production jar verified: " + args[0]);
    }

    static void verify(byte[] jar, String version) throws IOException {
        Map<String, byte[]> entries = inspectArchive(jar, 0);
        String metadata = text(required(entries, "fabric.mod.json"));
        require(!metadata.contains("${"), "Unexpanded Fabric metadata");
        require(field(metadata, "id").equals("mysticism"), "Wrong mod ID");
        require(field(metadata, "version").equals(version), "Wrong mod version");
        require(field(metadata, "license").equals("MIT"), "Wrong license metadata");
        require(text(required(entries, "LICENSE_mysticism.txt")).contains("Permission is hereby granted"),
                "Missing MIT license text");
        required(entries, "assets/mysticism/lang/en_us.json");
        require(!entries.containsKey("assets/mysticism/en_us.json"), "Language file outside lang/");
        required(entries, "io/github/mysticism/Mysticism.class");
        required(entries, "io/github/mysticism/client/MysticismClient.class");

        var strings = Pattern.compile("\"([^\"]+)\"").matcher(metadata);
        while (strings.find()) {
            String value = strings.group(1);
            if (value.endsWith(".mixins.json") || value.startsWith("META-INF/jars/")) required(entries, value);
            if (value.startsWith("io.github.mysticism.")) required(entries, value.replace('.', '/') + ".class");
        }
        var icon = Pattern.compile("\"icon\"\\s*:\\s*\"([^\"]+)\"").matcher(metadata);
        if (icon.find()) required(entries, icon.group(1));
    }

    private static Map<String, byte[]> inspectArchive(byte[] bytes, int depth) throws IOException {
        require(depth <= 4, "Excessive nested jars");
        var entries = new HashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                require(!name.startsWith("/") && !name.contains("../"), "Unsafe archive path: " + name);
                require(!name.endsWith(".DS_Store"), "Finder metadata in artifact: " + name);
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                require(!lower.contains("ai/djl/") && !lower.contains("ai.djl")
                        && !lower.contains("pytorch") && !lower.contains("tokenizers"),
                        "Local model runtime in production jar: " + name);
                byte[] content = zip.readNBytes(MAX_ENTRY_BYTES + 1);
                require(content.length <= MAX_ENTRY_BYTES, "Oversized archive entry: " + name);
                require(entries.put(name, content) == null, "Duplicate archive entry: " + name);
                if (name.endsWith(".class")) verifyClass(content);
                if (name.endsWith(".jar")) inspectArchive(content, depth + 1);
            }
        }
        require(!entries.isEmpty(), "Empty or invalid jar");
        return entries;
    }

    // Check class references, not arbitrary string literals: production Minecraft
    // references must use intermediary names, and Java bytecode must run on Java 21.
    static void verifyClass(byte[] bytes) throws IOException {
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            require(input.readInt() == 0xCAFEBABE, "Invalid class magic");
            int minor = input.readUnsignedShort();
            int major = input.readUnsignedShort();
            require(major <= 65 && minor != 65535, "Unsupported/preview class version: " + major + "." + minor);
            int size = input.readUnsignedShort();
            String[] utf8 = new String[size];
            int[] classes = new int[size];
            int[] descriptors = new int[size];
            for (int i = 1; i < size; i++) {
                switch (input.readUnsignedByte()) {
                    case 1 -> utf8[i] = input.readUTF();
                    case 7 -> classes[i] = input.readUnsignedShort();
                    case 12 -> { // CONSTANT_NameAndType: field or method descriptor
                        input.readUnsignedShort();
                        descriptors[i] = input.readUnsignedShort();
                    }
                    case 16 -> descriptors[i] = input.readUnsignedShort(); // CONSTANT_MethodType
                    case 3, 4, 9, 10, 11, 17, 18 -> input.skipNBytes(4);
                    case 5, 6 -> { input.skipNBytes(8); i++; }
                    case 8, 19, 20 -> input.skipNBytes(2);
                    case 15 -> input.skipNBytes(3);
                    default -> throw new IOException("Unknown class constant pool tag");
                }
            }
            for (int index : classes) {
                if (index == 0) continue;
                String name = utf8At(utf8, index);
                if (name.startsWith("[")) verifyDescriptor(name);
                else verifyReference(name);
            }
            // Only descriptor-bearing structures are inspected. CONSTANT_String and
            // unrelated UTF-8 entries may legitimately contain named class literals.
            for (int index : descriptors) {
                if (index != 0) verifyDescriptor(utf8At(utf8, index));
            }
            input.skipNBytes(6); // access_flags, this_class, super_class
            input.skipNBytes(2L * input.readUnsignedShort()); // interfaces
            verifyMembers(input, utf8); // fields
            verifyMembers(input, utf8); // methods
            skipAttributes(input);
            require(input.read() == -1, "Trailing class file data");
        }
    }

    private static String utf8At(String[] utf8, int index) {
        require(index > 0 && index < utf8.length && utf8[index] != null, "Invalid UTF-8 reference");
        return utf8[index];
    }

    private static void verifyReference(String name) {
        require(!name.startsWith("ai/djl/"), "Local model runtime class reference: " + name);
        if (name.startsWith("net/minecraft/")) {
            require(STABLE_MINECRAFT_NAMES.contains(name) || name.matches("net/minecraft/class_[0-9]+(\\$.*)?"),
                    "Unremapped Minecraft class reference: " + name);
        }
    }

    private static void verifyDescriptor(String descriptor) {
        require(!descriptor.isEmpty(), "Empty class descriptor");
        int offset = 0;
        if (descriptor.charAt(0) == '(') {
            offset = 1;
            while (offset < descriptor.length() && descriptor.charAt(offset) != ')') {
                offset = verifyType(descriptor, offset, false);
            }
            require(offset < descriptor.length(), "Unterminated method descriptor");
            offset = verifyType(descriptor, offset + 1, true);
        } else {
            offset = verifyType(descriptor, offset, false);
        }
        require(offset == descriptor.length(), "Trailing class descriptor data");
    }

    private static int verifyType(String descriptor, int offset, boolean allowVoid) {
        int dimensions = 0;
        while (offset < descriptor.length() && descriptor.charAt(offset) == '[') {
            require(++dimensions <= 255, "Excessive array dimensions");
            offset++;
        }
        require(offset < descriptor.length(), "Incomplete class descriptor");
        char type = descriptor.charAt(offset++);
        if (type == 'L') {
            int end = descriptor.indexOf(';', offset);
            require(end > offset, "Unterminated or empty object descriptor");
            verifyReference(descriptor.substring(offset, end));
            return end + 1;
        }
        require("BCDFIJSZ".indexOf(type) >= 0 || (type == 'V' && allowVoid && dimensions == 0),
                "Invalid descriptor type: " + type);
        return offset;
    }

    private static void verifyMembers(DataInputStream input, String[] utf8) throws IOException {
        int count = input.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            input.readUnsignedShort(); // access_flags
            utf8At(utf8, input.readUnsignedShort()); // name_index
            verifyDescriptor(utf8At(utf8, input.readUnsignedShort()));
            skipAttributes(input);
        }
    }

    private static void skipAttributes(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            input.readUnsignedShort(); // attribute_name_index
            input.skipNBytes(Integer.toUnsignedLong(input.readInt()));
        }
    }

    private static byte[] required(Map<String, byte[]> entries, String name) {
        byte[] result = entries.get(name);
        require(result != null, "Missing production resource: " + name);
        return result;
    }

    private static String field(String json, String key) {
        var match = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        require(match.find(), "Missing metadata field: " + key);
        return match.group(1);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
