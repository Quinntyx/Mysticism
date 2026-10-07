#version 150
uniform sampler2D DiffuseSampler;
uniform sampler2D DepthSampler;
uniform sampler2D OccupancySampler;
uniform int HasOccupancy;
uniform vec3 OccupancyCameraOffset;
uniform float NearBubble;
uniform mat4 InverseViewProjection;
uniform vec3 CameraModulo;
uniform float OpaqueRadius;
uniform float MinExtinction;
uniform int FogSteps;
in vec2 texCoord;
out vec4 fragColor;

const float TAU = 6.28318530718;
const float PERIOD = 4096.0;
const vec3 FOG_COLOR = vec3(0.75, 0.68, 0.95);

// Depth is nonlinear OpenGL window depth. The captured matrix includes world view rotation.
vec3 sceneOffset(vec2 uv, float depth) {
    vec4 position = InverseViewProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return position.xyz / position.w;
}

// Continuous, periodic world-space medium (integer wave numbers prevent wrap discontinuities).
// A strictly positive extinction floor proves T(64) <= 0.001, even in the thinnest region.
float density(vec3 p) {
    vec3 phase = p * (TAU / PERIOD);
    return 1.0 + 0.5 * (0.5 + 0.5 * sin(phase.x * 83.0 + phase.y * 29.0)
                                    * sin(phase.z * 61.0 - phase.y * 17.0));
}

// Terrain-published occupied voxels, not generated on render/tick. Nearest packed atlas.
float occupancy(vec3 offset) {
    if (HasOccupancy == 0) return 0.0;
    ivec3 cell = ivec3(floor((offset + OccupancyCameraOffset + vec3(64.0)) / 8.0));
    if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, ivec3(16)))) return 0.0;
    ivec2 pixel = ivec2((cell.z % 4) * 16 + cell.x, (cell.z / 4) * 16 + cell.y);
    return texelFetch(OccupancySampler, pixel, 0).r;
}

void main() {
    vec4 scene = texture(DiffuseSampler, texCoord);
    // texelFetch avoids interpolation of unrelated near/far surfaces at silhouettes.
    ivec2 size = textureSize(DepthSampler, 0);
    ivec2 pixel = clamp(ivec2(texCoord * vec2(size)), ivec2(0), size - ivec2(1));
    float depth = texelFetch(DepthSampler, pixel, 0).r;
    vec3 endpoint = sceneOffset(texCoord, depth >= 0.9999999 ? 1.0 : depth);
    float distanceToScene = length(endpoint);
    vec3 direction = endpoint / max(distanceToScene, 0.00001);
    // Clear depth is SKY, not empty/no-fog. March it to the shared opaque horizon.
    float endDistance = depth >= 0.9999999 ? OpaqueRadius : min(distanceToScene, OpaqueRadius);
    float lengthToMarch = max(0.0, endDistance - NearBubble);
    int count = clamp(FogSteps, 8, 64);
    float stepLength = lengthToMarch / float(count);
    float transmittance = 1.0;
    vec3 scattering = vec3(0.0);
    for (int i = 0; i < 64; ++i) {
        if (i >= count || transmittance <= 0.001) break;
        vec3 offset = direction * (NearBubble + (float(i) + 0.5) * stepLength);
        vec3 worldSample = CameraModulo + offset;
        float occupied = occupancy(offset);
        float sigma = MinExtinction * (density(worldSample) + 0.7 * occupied);
        float segmentTransmittance = exp(-sigma * stepLength);
        // In-scattering varies throughout the world-space participating medium, not at the endpoint.
        float light = 0.94 + 0.06 * sin(worldSample.y * (TAU * 7.0 / PERIOD));
        scattering += transmittance * (1.0 - segmentTransmittance) * FOG_COLOR * light;
        transmittance *= segmentTransmittance;
    }
    // Opaque scene composition must also cover clear-color pixels (whose main alpha may be 0).
    fragColor = vec4(scene.rgb * transmittance + scattering, 1.0);
}
