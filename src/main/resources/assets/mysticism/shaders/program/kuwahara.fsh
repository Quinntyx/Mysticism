#version 150

uniform sampler2D DiffuseSampler;

in vec2 uv;
out vec4 FragColor;

void main() {
    vec4 originalColor = texture(DiffuseSampler, uv);
    FragColor = vec4(originalColor.r, 0.0, 0.0, originalColor.a);
}