#version 150

in vec3 Position;
in vec2 UV; // Let's use the standard 'UV' name for the input attribute
out vec2 uv; // This MUST match the 'in' variable in the fragment shader

void main() {
    gl_Position = vec4(Position, 1.0);
    uv = UV; // Pass the texture coordinate through
}