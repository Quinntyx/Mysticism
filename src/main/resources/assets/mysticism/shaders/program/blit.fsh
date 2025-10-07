#version 150

uniform sampler2D DiffuseSampler;

in vec2 uv;
out vec4 FragColor;

void main() {
    FragColor = texture(DiffuseSampler, uv);
}