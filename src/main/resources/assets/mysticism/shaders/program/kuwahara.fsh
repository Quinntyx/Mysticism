#version 150

in vec2 texCoord;
out vec4 FragColor;

uniform sampler2D DiffuseSampler;

// ===== Tunables =====
const int   WINDOW_W = 11;
const int   WINDOW_H = 5;
const float KERNEL_WIDTH  = 9.0;
const float KERNEL_HEIGHT = 3.0;

// Resolution-independent density: target long edge in “virtual pixels”
const float REF_LONG_EDGE = 520.0;

// Posterization (0.0 disables)
const float DEPTH_PER_CHANNEL = 0.0;

// Contrast
const float CONTRAST = 1.18;

// Soft-blend power (1 = very soft, 3–4 = closer to hard min)
const float SOFT_POWER = 2.0;

// Jitter/rotation to break grid locking
const float JITTER_AMPLITUDE = 0.35;  // in fractions of a virtual texel (0..~0.5)
const float MAX_ROT_RADIANS  = 0.10;  // ~6 degrees
// =====================

const int REGION_W = (WINDOW_W + 1) / 2;
const int REGION_H = (WINDOW_H + 1) / 2;
const int REGION_N = REGION_W * REGION_H;

// cheap hash → 2 randoms in [0,1)
vec2 hash2(ivec2 p){
    // 32-bit mix
    uint x = uint(p.x), y = uint(p.y);
    x ^= y + 0x9e3779b9u + (x<<6) + (x>>2);
    y ^= x + 0x85ebca6bu + (y<<6) + (y>>2);
    // two independent hashes
    float a = float((x ^ (x>>16)) & 0x00FFFFFFu) / 16777215.0;
    float b = float((y ^ (y>>16)) & 0x00FFFFFFu) / 16777215.0;
    return vec2(a,b);
}

vec3 applyContrast(vec3 c, float k){ return (c - 0.5) * k + 0.5; }

vec4 samplePacked(vec2 uv){
    uv = clamp(uv, vec2(0.0), vec2(1.0));
    vec3 rgb = texture(DiffuseSampler, uv).rgb;
    if (DEPTH_PER_CHANNEL > 0.0){
        rgb = floor(rgb * DEPTH_PER_CHANNEL) / DEPTH_PER_CHANNEL;
    }
    float V = max(rgb.r, max(rgb.g, rgb.b)); // HSV Value without full conversion
    return vec4(rgb, V);
}

float regionMeanValue(vec4 vals[REGION_N]){
    float s = 0.0; for (int i=0;i<REGION_N;++i) s += vals[i].a; return s/float(REGION_N);
}
vec3 regionMean(vec4 vals[REGION_N]){
    vec3 s = vec3(0.0); for (int i=0;i<REGION_N;++i) s += vals[i].rgb; return s/float(REGION_N);
}
float regionVariance(vec4 vals[REGION_N], float meanVal){
    float acc = 0.0; for (int i=0;i<REGION_N;++i){ float d = vals[i].a - meanVal; acc += d*d; }
    return acc / float(REGION_N);
}

vec3 kuwaharaDown_virtual_soft(vec2 uv){
    // virtual grid
    ivec2 full = textureSize(DiffuseSampler, 0);
    float longEdge = float(max(full.x, full.y));
    float scale    = max(longEdge / REF_LONG_EDGE, 1.0);      // don’t upscale
    vec2  lowRes   = vec2(max(1.0, floor(float(full.x)/scale)),
    max(1.0, floor(float(full.y)/scale)));
    vec2  vTexel   = 1.0 / lowRes;

    // integer cell + stable jitter/rotation per cell
    ivec2 cell = ivec2(floor(uv * lowRes));
    vec2  rnd  = hash2(cell);
    vec2  jitter = (rnd - 0.5) * (JITTER_AMPLITUDE * vTexel * 2.0); // [-amp..amp] * vTexel
    float ang = (rnd.x - 0.5) * 2.0 * MAX_ROT_RADIANS;

    // center of cell + jitter
    vec2 uvC = (vec2(cell) + 0.5) * vTexel + jitter;

    // rotated step basis in virtual texels
    float ca = cos(ang), sa = sin(ang);
    vec2 stepX = vec2(ca, -sa) * (KERNEL_WIDTH  * vTexel.x);
    vec2 stepY = vec2(sa,  ca) * (KERNEL_HEIGHT * vTexel.y);

    vec4 reg[REGION_N];

    // down-left: (-x, +y)
    for (int y=0; y<REGION_H; ++y)
    for (int x=0; x<REGION_W; ++x){
        vec2 off = -float(x)*stepX + float(y)*stepY;
        reg[y*REGION_W + x] = samplePacked(uvC + off);
    }
    float mC = regionMeanValue(reg);
    vec3  cC = regionMean(reg);
    float vC = regionVariance(reg, mC);

    // down-right: (+x, +y)
    for (int y=0; y<REGION_H; ++y)
    for (int x=0; x<REGION_W; ++x){
        vec2 off =  float(x)*stepX + float(y)*stepY;
        reg[y*REGION_W + x] = samplePacked(uvC + off);
    }
    float mD = regionMeanValue(reg);
    vec3  cD = regionMean(reg);
    float vD = regionVariance(reg, mD);

    // soft inverse-variance blend (no hard seam)
    float wC = pow(1.0 / (1e-4 + vC), SOFT_POWER);
    float wD = pow(1.0 / (1e-4 + vD), SOFT_POWER);
    return (wC*cC + wD*cD) / (wC + wD);
}

void main(){
    vec3 col = kuwaharaDown_virtual_soft(texCoord);
    col = applyContrast(col, CONTRAST);
    FragColor = vec4(col, 1.0);
}
