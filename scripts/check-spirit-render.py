#!/usr/bin/env python3
"""Dependency-free resource/source contracts. Not an OpenGL/runtime substitute."""
import json
import pathlib
import re
import shutil
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/mysticism/shaders"
CLIENT = ROOT / "src/client/java/io/github/mysticism/client/spiritworld"
checks = 0


def check(condition, message):
    global checks
    checks += 1
    if not condition:
        raise AssertionError(message)


chain = json.loads((ASSETS / "post/kuwahara.json").read_text())
passes = chain["passes"]
check(chain["targets"] == ["fogged", "painted", "saturated"], "exact bounded targets")
check([p["name"] for p in passes] == ["mysticism:spirit_fog", "mysticism:kuwahara", "mysticism:spirit_saturation", "blit"], "fog integrated ONCE before independent painterly and saturation")
previous = "minecraft:main"
for p in passes:
    check(p["intarget"] == previous, "continuous Satin graph")
    check(p["intarget"] != p["outtarget"], "no framebuffer feedback")
    check("auxtargets" not in p, "scene depth is bound dynamically after snapshot, not stale aux texture")
    previous = p["outtarget"]
check(previous == "minecraft:main", "output composited back to scene")

for name in ["spirit_fog", "kuwahara", "spirit_saturation"]:
    descriptor = json.loads((ASSETS / f"program/{name}.json").read_text())
    source = (ASSETS / f"program/{name}.fsh").read_text()
    check(descriptor["vertex"] == "blit", "vanilla fullscreen vertex interface")
    check(descriptor["fragment"] == f"mysticism:{name}", "fragment resource ID")
    declared = dict((n, t) for t, n in re.findall(r"uniform\s+(\w+)\s+(\w+)\s*;", source))
    sampler_names = {s["name"] for s in descriptor["samplers"]}
    check(sampler_names == {n for n, t in declared.items() if t == "sampler2D"}, "samplers match live GLSL")
    uniforms = {u["name"]: u for u in descriptor["uniforms"]}
    check({"ProjMat", "OutSize"} <= uniforms.keys(), "blit vertex uniforms")
    for variable, type_name in declared.items():
        if type_name == "sampler2D":
            continue
        check(variable in uniforms, f"uniform {variable} described")
        expected_type, count = {"mat4": ("matrix4x4", 16), "vec3": ("float", 3), "float": ("float", 1), "int": ("int", 1)}[type_name]
        check((uniforms[variable]["type"], uniforms[variable]["count"]) == (expected_type, count), "uniform type/count")
        check(len(uniforms[variable]["values"]) == count, "uniform initial values count")
    check("blend" not in descriptor, "intermediate passes replace output, not double-alpha blend")

fog = (ASSETS / "program/spirit_fog.fsh").read_text()
check("InverseViewProjection * vec4" in fog and "depth * 2.0 - 1.0" in fog, "nonlinear scene depth unprojection")
check("texelFetch(DepthSampler" in fog, "nearest terrain/glyph depth")
check("depth >= 0.9999999 ? OpaqueRadius" in fog, "clear sky is marched, not skipped")
check("i < 64" in fog and "clamp(FogSteps, 8, 64)" in fog, "bounded participating medium integration")
check("CameraModulo + direction" in fog and "exp(-sigma * stepLength)" in fog, "world-space Beer-Lambert medium")
check("return 1.0 +" in fog, "strictly positive density floor")
check("scattering, 1.0)" in fog, "clear alpha-zero sky still composes visible fog")

painterly = (ASSETS / "program/kuwahara.fsh").read_text()
check("clamp(KernelRadius, 1, 3)" in painterly, "quality-bounded unique neighborhood")
check("y = -3; y <= 3" in painterly and "x = -3; x <= 3" in painterly, "49 unique taps maximum")
check("centerDepth >= 0.9999999" in painterly, "sky silhouette guard")
check("random" not in re.sub(r"//[^\n]*", "", painterly), "coherent kernel, no tap jitter")

manager = (CLIENT / "ShaderManager.java").read_text()
check("WorldRenderEvents.START.register" in manager, "capture actual world matrices before fog")
check("WorldRenderEvents.AFTER_TRANSLUCENT.register" in manager and "WorldRenderEvents.END.register" in manager, "snapshot before Fabulous composite, render at WorldRenderer.RETURN before hand")
check("ShaderEffectRenderCallback.EVENT.register" not in manager, "Satin callback is too late: after hand depth clear")
check(manager.index("sceneDepth.copyDepthFrom(client.getFramebuffer())") < manager.index("setSamplerUniform(\"DepthSampler\"") < manager.index("KUWAHARA_SHADER.render(context.tickCounter()"), "snapshot/bind/render order")
check(manager.index("main.copyDepthFrom(sceneDepth)") > manager.index("KUWAHARA_SHADER.render(context.tickCounter()"), "restore main depth after vanilla blit clears it")
check("textureWidth != main.textureWidth" in manager and "textureHeight != main.textureHeight" in manager, "resize follows framebuffer pixels, not UI scale")
check("!fogHookSeen" in manager and "refusing double-fog" in manager, "missing parent hook is visible, not double-fogged")
check("draw.drawTextWithShadow" in manager and "LOGGER.error" in manager, "failure diagnostics visible and logged")
check("sceneDepth.delete()" in manager and "KUWAHARA_SHADER.release()" in manager, "exit/shutdown resources released")

glyphs = (CLIENT / "SpiritItemProjectionRenderer.java").read_text()
check("WorldRenderEvents.AFTER_ENTITIES.register" in glyphs and "LAST.register" not in glyphs, "glyphs before scene snapshot, after solid terrain")
check("RenderSystem.enableDepthTest()" in glyphs and "RenderSystem.depthMask(true)" in glyphs, "glyph terrain occlusion and scene depth write")
check("beginWrite(false)" in glyphs and "immediate.draw()" in glyphs, "direct main target and immediate flush")
check("MAX_GLYPHS" in glyphs and "ids.sort(String::compareTo)" in glyphs, "bounded stable glyph enumeration")
check("EmbeddingSpace.requireCurrent(vector)" in glyphs, "current pinned profile only")
check("frozenBasis = ClientSpiritCache.playerLatentBasis.clone()" in glyphs and "glyphs.get(id)" in glyphs, "freeze session frame, retain ID positions")
check("camera.getPos()" not in glyphs, "no camera-relative semantic anchor")
check("applySpiritFog" in (ROOT / "src/client/java/io/github/mysticism/client/mixin/SpiritBackgroundRendererMixin.java").read_text(), "parent hook supplied")
check("depthMask(false)" in (CLIENT / "SpiritSkybox.java").read_text(), "custom sky preserves clear depth")
for retired in ["SpiritFogVoxels.java", "SpiritWorldRenderer.java"]:
    source = (CLIENT / retired).read_text()
    check("WorldRenderEvents" not in source, "retired analytic fog/glyph callbacks")
for path in CLIENT.glob("*.java"):
    if path.name == "ClientSpiritCache.java":
        continue
    check(not re.search(r"EmbeddingEngine|EmbeddingService|Files\.read|\.join\(", path.read_text()), "no model/IO/wait in render path")

validator = shutil.which("glslangValidator")
if validator:
    for name in ["spirit_fog", "kuwahara", "spirit_saturation"]:
        subprocess.run([validator, "-S", "frag", str(ASSETS / f"program/{name}.fsh")], check=True)
    print("GLSL 150 fragment validation: 3 passed")
else:
    print("GLSL validation NOT RUN: glslangValidator not installed")
print(f"Spirit resource/order contracts: {checks} checks passed (static, no GPU/runtime)")
