#version 150
uniform sampler2D DiffuseSampler;
uniform float Saturation;
in vec2 texCoord;
out vec4 fragColor;
void main() {
    vec4 scene = texture(DiffuseSampler, texCoord);
    float luminance = dot(scene.rgb, vec3(0.2126, 0.7152, 0.0722));
    fragColor = vec4(mix(vec3(luminance), scene.rgb, clamp(Saturation, 0.0, 1.0)), scene.a);
}
