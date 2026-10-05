#!/usr/bin/env python3
"""Run pure pipeline regressions with Java 21 and EXISTING cached Gson/DJL API jars.
Never resolves dependencies, installs software, invokes Gradle, or downloads models.
MYSTICISM_TEST_CLASSPATH may supply an explicit existing Gson + DJL API classpath.
"""
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
classpath = os.environ.get("MYSTICISM_TEST_CLASSPATH")
if not classpath:
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
    jars = []
    for group, artifact in [("com.google.code.gson", "gson"), ("ai.djl", "api")]:
        found = sorted((cache / group / artifact).glob("*/*/*.jar"))
        if not found:
            raise SystemExit(f"Missing existing {artifact} jar; supply MYSTICISM_TEST_CLASSPATH. Nothing installed.")
        jars.append(str(found[-1]))
    classpath = os.pathsep.join(jars)
output = root / "build/embedding-tests"
output.mkdir(parents=True, exist_ok=True)
base = root / "src/main/java/io/github/mysticism"
sources = sorted((base / "vector").glob("*.java"))
sources = [p for p in sources if p.name not in {"Projection384f.java"}]
sources += [p for p in sorted((base / "embedding").glob("*.java")) if p.name not in {"EmbeddingNbt.java", "IndexGeneration.java"}]
sources.append(root / "src/test/java/io/github/mysticism/embedding/EmbeddingPipelineTest.java")
subprocess.run(["javac", "--release", "21", "--add-modules", "jdk.httpserver", "-cp", classpath,
                "-d", str(output), *map(str, sources)], cwd=root, check=True)
subprocess.run(["java", "--add-modules", "jdk.httpserver", "-cp", str(output) + os.pathsep + classpath,
                "io.github.mysticism.embedding.EmbeddingPipelineTest"], cwd=root, check=True)
