#version 150

uniform vec2 GuiSize;
uniform float Phase;
in vec2 guiPosition;
out vec4 fragColor;

void main() {
    // Quantize in logical GUI pixels, so sparkles stay crisp at every GUI scale.
    vec2 pixel = floor(guiPosition / 2.0) * 2.0;
    uvec2 cell = uvec2(floor(pixel / 18.0));
    uint h = (cell.x * 73428767u) ^ (cell.y * 912931u);
    h ^= h >> 13u;
    vec2 offset = 2.0 * vec2(h % 7u, (h >> 8u) % 7u);
    vec2 inCell = mod(pixel, 18.0);
    float sparkle = (inCell.x == offset.x && inCell.y == offset.y) ? 1.0 : 0.0;

    float y = clamp(guiPosition.y / max(GuiSize.y, 1.0), 0.0, 1.0);
    vec3 base = mix(vec3(33.0, 18.0, 46.0), vec3(16.0, 13.0, 27.0), y) / 255.0;
    // Phase repeats after 96 units. Freeze at zero for reduced motion.
    float band = mod(pixel.x + pixel.y - Phase * 2.0, 192.0);
    float glow = (band < 28.0 ? 38.0 : 10.0) / 255.0;
    // GuiSize.x also limits edge output, and remains an active linked uniform.
    float inside = step(0.0, guiPosition.x) * (1.0 - step(GuiSize.x, guiPosition.x));
    fragColor = vec4(mix(base, vec3(188.0, 140.0, 223.0) / 255.0, sparkle * glow * inside), 1.0);
}
