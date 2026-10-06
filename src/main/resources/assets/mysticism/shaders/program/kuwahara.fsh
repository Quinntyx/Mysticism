#version 150
uniform sampler2D DiffuseSampler;
uniform sampler2D DepthSampler;
uniform int KernelRadius;
in vec2 texCoord;
out vec4 fragColor;

void main() {
    int radius = clamp(KernelRadius, 1, 3);
    ivec2 size = textureSize(DiffuseSampler, 0);
    ivec2 center = clamp(ivec2(texCoord * vec2(size)), ivec2(0), size - ivec2(1));
    // Coherent axis-aligned neighborhood: 9/25/49 unique taps, shared by four quadrants.
    // No virtual-grid snapping, random rotations, duplicated quadrants or per-pixel tap arrays.
    vec3 sum[4];
    float squareSum[4];
    float weight[4];
    for (int q = 0; q < 4; ++q) { sum[q] = vec3(0.0); squareSum[q] = 0.0; weight[q] = 0.0; }
    float centerDepth = texelFetch(DepthSampler, center, 0).r;
    for (int y = -3; y <= 3; ++y) {
        if (abs(y) > radius) continue;
        for (int x = -3; x <= 3; ++x) {
            if (abs(x) > radius) continue;
            ivec2 pixel = clamp(center + ivec2(x, y), ivec2(0), size - ivec2(1));
            float sampleDepth = texelFetch(DepthSampler, pixel, 0).r;
            // Never spread background glyph color onto a foreground wall. A symmetric window-
            // depth threshold alone admits a 12-block glyph through a 10-block wall (<0.0015).
            // Conservative foreground-only gather also rejects clear-sky silhouettes. Sloping
            // surfaces may lose some taps, but the center always keeps every quadrant nonempty.
            if ((centerDepth >= 0.9999999) != (sampleDepth >= 0.9999999)) continue;
            if (sampleDepth > centerDepth || abs(centerDepth - sampleDepth) > 0.0015) continue;
            vec3 c = texelFetch(DiffuseSampler, pixel, 0).rgb;
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
    fragColor = vec4(result / max(total, 0.00001), texelFetch(DiffuseSampler, center, 0).a);
}
