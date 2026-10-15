#version 150
uniform sampler2D DiffuseSampler;
uniform sampler2D OriginalSampler;
uniform sampler2D DepthSampler;
uniform mat4 InverseViewProjection;
uniform float EffectStrength;
uniform int RestoreSourceFog;
uniform float SourceFogStart;
uniform float SourceFogEnd;
uniform int SourceFogShape;
uniform vec4 SourceFogColor;
in vec2 texCoord;
out vec4 fragColor;

vec3 originalWithSourceFog(vec3 original) {
    // A saved entry scene already has vanilla fog. Clear depth is the vanilla sky
    // backdrop: SKY fog was never bypassed and must not acquire terrain fog here.
    if (RestoreSourceFog == 0) return original;
    ivec2 size = textureSize(DepthSampler, 0);
    ivec2 pixel = clamp(ivec2(texCoord * vec2(size)), ivec2(0), size - ivec2(1));
    float depth = texelFetch(DepthSampler, pixel, 0).r;
    if (depth >= 0.9999999) return original;
    vec4 position = InverseViewProjection * vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec3 offset = position.xyz / position.w;
    // Minecraft fog.glsl: source terrain may use a cylinder, not a ray-length sphere.
    float distance = SourceFogShape == 0 ? length(offset) : max(length(offset.xz), abs(offset.y));
    if (distance <= SourceFogStart) return original;
    float amount = distance < SourceFogEnd ? smoothstep(SourceFogStart, SourceFogEnd, distance) : 1.0;
    return mix(original, SourceFogColor.rgb, amount * SourceFogColor.a);
}

void main() {
    vec3 original = originalWithSourceFog(texture(OriginalSampler, texCoord).rgb);
    vec3 transformed = texture(DiffuseSampler, texCoord).rgb;
    // Source fog belongs ONLY to original; transformed already has one volumetric integration.
    fragColor = vec4(mix(original, transformed, clamp(EffectStrength, 0.0, 1.0)), 1.0);
}
