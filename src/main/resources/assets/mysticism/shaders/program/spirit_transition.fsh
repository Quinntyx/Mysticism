#version 150
uniform sampler2D DiffuseSampler;
uniform sampler2D OriginalSampler;
uniform float EffectStrength;
in vec2 texCoord;
out vec4 fragColor;
void main() {
    vec3 original = texture(OriginalSampler, texCoord).rgb;
    vec3 transformed = texture(DiffuseSampler, texCoord).rgb;
    fragColor = vec4(mix(original, transformed, clamp(EffectStrength, 0.0, 1.0)), 1.0);
}
