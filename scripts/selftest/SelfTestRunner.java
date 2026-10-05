package io.github.mysticism.build;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;

/** Deterministic, framework-free runner; exceptions/assertions propagate as failures. */
public final class SelfTestRunner {
    private SelfTestRunner() {}

    public static void main(String[] roots) throws Exception {
        if (!SelfTestRunner.class.desiredAssertionStatus()) {
            throw new IllegalStateException("Self-tests must run with -ea");
        }
        var names = new TreeSet<String>();
        for (String root : roots) {
            Path directory = Path.of(root);
            if (!Files.isDirectory(directory)) continue;
            try (var files = Files.walk(directory)) {
                files.filter(Files::isRegularFile).map(directory::relativize)
                        .map(Path::toString).filter(n -> n.endsWith("Test.class") && !n.contains("$"))
                        .map(n -> n.substring(0, n.length() - 6).replace('/', '.').replace('\\', '.'))
                        .forEach(names::add);
            }
        }
        int count = 0;
        for (String name : names) {
            Class<?> test = Class.forName(name, false, SelfTestRunner.class.getClassLoader());
            var main = test.getMethod("main", String[].class);
            if (!Modifier.isStatic(main.getModifiers()) || main.getReturnType() != void.class) {
                throw new IllegalStateException(name + " must expose public static void main(String[])");
            }
            if (!test.desiredAssertionStatus()) {
                throw new IllegalStateException("Assertions disabled for " + name);
            }
            System.out.println("Running " + name);
            try {
                main.invoke(null, (Object) new String[0]);
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof Exception exception) throw exception;
                if (cause instanceof Error error) throw error;
                throw failure;
            }
            count++;
        }
        // A build-only branch may legitimately have no feature tests yet. Report it explicitly.
        System.out.println("Feature self-tests passed: " + count + " main-based classes");
    }
}
