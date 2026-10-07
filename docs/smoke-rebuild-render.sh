#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v glslangValidator >/dev/null || { echo 'SKIP: cached GLSL validator unavailable'; exit 77; }
glsl=src/client/resources/assets/mysticism/shaders/core/guidebook_shimmer
glslangValidator -S vert "$glsl.vsh"
glslangValidator -S frag "$glsl.fsh"
for name in spirit_fog spirit_transition; do
  glslangValidator -S frag "src/main/resources/assets/mysticism/shaders/program/$name.fsh"
done
echo 'PASS: offline GLSL syntax only. No live driver link, framebuffer, visuals, input or FPS proof.'
