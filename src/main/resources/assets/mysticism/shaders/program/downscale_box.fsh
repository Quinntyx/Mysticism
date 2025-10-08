#version 150
in vec2 texCoord;
out vec4 FragColor;
uniform sampler2D DiffuseSampler;

void main() {
    ivec2 srcSize = textureSize(DiffuseSampler, 0);
    vec2 texel = 1.0 / vec2(srcSize);

    // 2x2 box centered between source texels
    vec2 halfTexel = 0.5 * texel;

    vec3 c00 = texture(DiffuseSampler, clamp(texCoord + vec2(-halfTexel.x, -halfTexel.y), 0.0, 1.0)).rgb;
    vec3 c10 = texture(DiffuseSampler, clamp(texCoord + vec2( +halfTexel.x, -halfTexel.y), 0.0, 1.0)).rgb;
    vec3 c01 = texture(DiffuseSampler, clamp(texCoord + vec2(-halfTexel.x,  +halfTexel.y), 0.0, 1.0)).rgb;
    vec3 c11 = texture(DiffuseSampler, clamp(texCoord + vec2( +halfTexel.x,  +halfTexel.y), 0.0, 1.0)).rgb;

    vec3 avg = 0.25 * (c00 + c10 + c01 + c11);
    FragColor = vec4(avg, 1.0);
}
