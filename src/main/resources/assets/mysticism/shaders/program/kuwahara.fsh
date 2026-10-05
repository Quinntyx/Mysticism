#version 150
uniform sampler2D DiffuseSampler;
uniform sampler2D DepthSampler;
uniform int KernelRadius;
in vec2 texCoord;
out vec4 fragColor;

void main() {
    int radius = clamp(KernelRadius, 1, 3);
    ivec2 size = textureSize(DiffuseSampler, 0);
    vec2 texel = 1.0 / vec2(size);
    // Coherent axis-aligned neighborhood: 9/25/49 unique taps, shared by four quadrants.
    // No virtual-grid snapping, random rotations, duplicated quadrants or per-pixel tap arrays.
    vec3 sum[4];
    float squareSum[4];
    float weight[4];
    for (int q = 0; q < 4; ++q) { sum[q] = vec3(0.0); squareSum[q] = 0.0; weight[q] = 0.0; }
    float centerDepth = texture(DepthSampler, texCoord).r;
    for (int y = -3; y <= 3; ++y) {
        if (abs(y) > radius) continue;
        for (int x = -3; x <= 3; ++x) {
            if (abs(x) > radius) continue;
            vec2 uv = clamp(texCoord + vec2(x, y) * texel, 0.5 * texel, 1.0 - 0.5 * texel);
            float sampleDepth = texture(DepthSampler, uv).r;
            // Never mix clear-sky color across terrain/glyph silhouettes. Other large depth jumps
            // are rejected conservatively; this isn't an unbounded bilateral search.
            if ((centerDepth >= 0.9999999) != (sampleDepth >= 0.9999999)) continue;
            if (abs(centerDepth - sampleDepth) > 0.0015) continue;
            vec3 c = texture(DiffuseSampler, uv).rgb;
            float value = dot(c, vec3(0.2126, 0.7152, 0.0722));
            for (int q = 0; q < 4; ++q) {
                bool memberX = (q == 0 || q == 2) ? x <= 0 : x >= 0;
                bool memberY = q < 2 ? y <= 0 : y >= 0;
                if (memberX && memberY) {
                    sum[q] += c; squareSum[q] += value * value; weight[q] += 1.0;
                }
            }
        }
    }
    vec3 result = vec3(0.0);
    float total = 0.0;
    for (int q = 0; q < 4; ++q) {
        vec3 mean = sum[q] / max(weight[q], 1.0);
        float value = dot(mean, vec3(0.2126, 0.7152, 0.0722));
        float variance = max(0.0, squareSum[q] / max(weight[q], 1.0) - value * value);
        float w = 1.0 / (0.001 + variance);
        result += mean * w; total += w;
    }
    fragColor = vec4(result / max(total, 0.00001), texture(DiffuseSampler, texCoord).a);
}
