#version 150

// This is the projection matrix that PostEffectProcessor provides.
uniform mat4 ProjMat;

in vec3 Position;
in vec2 UV;

out vec2 uv;

void main() {
    // Transform the incoming quad's vertex position by the projection matrix.
    // This correctly scales and positions it to fill the entire screen.
    gl_Position = ProjMat * vec4(Position, 1.0);

    // Pass the texture coordinates through to the fragment shader.
    uv = UV;
}