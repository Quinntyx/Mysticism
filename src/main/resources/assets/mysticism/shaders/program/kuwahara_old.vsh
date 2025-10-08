#version 150

in vec3 Position;
in vec2 UV;
in vec4 Color;

out vec2 fragUV;
out vec4 fragColor;

uniform mat4 ProjMat;

void main() {
    gl_Position = ProjMat * vec4(Position, 1.0);
    fragUV = UV;
    fragColor = Color;
}