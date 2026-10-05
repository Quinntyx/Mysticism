package io.github.mysticism.build;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;

/** Exercises discovery, ordering, assertions and failure propagation in separate JVMs. */
final class RunnerSelfTest {
    private RunnerSelfTest() {}

    static void run() throws Exception {
        Path fixtures = Files.createTempDirectory("mysticism-runner-selftest-");
        Path passing = compile(fixtures.resolve("passing"), new String[][]{
                {"ZzzSelfTest", "if (!ZzzSelfTest.class.desiredAssertionStatus()) throw new AssertionError(\"disabled\");"},
                {"AaaTest", "assert true;"}});
        Result good = launch(passing, true);
        require(good.exit == 0, "Runner failed passing tests: " + good.output);
        int first = good.output.indexOf("Running fixture.AaaTest");
        int second = good.output.indexOf("Running fixture.ZzzSelfTest");
        require(first >= 0 && second > first && good.output.contains("Feature self-tests passed: 2"),
                "Non-deterministic discovery/order");
        Result disabled = launch(passing, false);
        require(disabled.exit != 0 && disabled.output.contains("must run with -ea"), "Disabled assertions accepted");
        Path failing = compile(fixtures.resolve("failing"), new String[][]{
                {"BrokenTest", "throw new AssertionError(\"expected-fixture-failure\");"}});
        Result bad = launch(failing, true);
        require(bad.exit != 0 && bad.output.contains("expected-fixture-failure"), "Test failure was swallowed");
        Path missingMain = compile(fixtures.resolve("missing-main"), new String[][]{{"NoMainTest", null}});
        Result malformed = launch(missingMain, true);
        require(malformed.exit != 0 && malformed.output.contains("NoSuchMethodException"), "Missing main was skipped");
        Path empty = Files.createDirectories(fixtures.resolve("empty"));
        Result none = launch(empty, true);
        require(none.exit == 0 && none.output.contains("Feature self-tests passed: 0"), "Empty suite not reported");
        System.out.println("Self-test runner checks passed (discovery, ordering, -ea, failures, missing main, empty suite)");
    }

    private static Path compile(Path directory, String[][] classes) throws Exception {
        Files.createDirectories(directory);
        var options = new ArrayList<String>();
        options.addAll(java.util.List.of("--release", "21", "-encoding", "UTF-8", "-d", directory.toString()));
        for (String[] fixture : classes) {
            Path source = directory.resolve(fixture[0] + ".java");
            String body = fixture[1] == null ? "" : "public static void main(String[] args) { " + fixture[1] + " }";
            Files.writeString(source, "package fixture; public final class " + fixture[0] + " { " + body + " }", StandardCharsets.UTF_8);
            options.add(source.toString());
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        require(compiler != null, "A JDK, not a JRE, is required");
        require(compiler.run(null, null, null, options.toArray(String[]::new)) == 0, "Fixture compilation failed");
        return directory;
    }

    private static Result launch(Path classes, boolean assertions) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        if (assertions) command.add("-ea");
        command.addAll(java.util.List.of("-cp", System.getProperty("java.class.path")
                        + System.getProperty("path.separator") + classes,
                SelfTestRunner.class.getName(), classes.toString()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Runner fixture timed out");
        }
        return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Result(int exit, String output) {}
}
