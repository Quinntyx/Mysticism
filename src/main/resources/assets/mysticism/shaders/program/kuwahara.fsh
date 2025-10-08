#version 150

in vec2 texCoord;
out vec4 FragColor;

uniform sampler2D DiffuseSampler;

// ===== Tunables =====
const int   WINDOW_W = 11;
const int   WINDOW_H = 5;
const float KERNEL_WIDTH  = 9.0;
const float KERNEL_HEIGHT = 3.0;

const float DEPTH_PER_CHANNEL = 64.0;
const float CONTRAST = 1.2;

// Target long-edge “virtual resolution” (approx density)
const float REF_LONG_EDGE = 480.0;
// =====================

const int REGION_W = (WINDOW_W + 1) / 2;
const int REGION_H = (WINDOW_H + 1) / 2;
const int REGION_N = REGION_W * REGION_H;

// --- HSV Value helper (unchanged) ---
vec3 rgb2hsv(vec3 c){
    vec4 K = vec4(0.0, -1.0/3.0, 2.0/3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y)/(6.0*d + e)), d/(q.x + e), q.x);
}

vec3 applyContrast(vec3 c, float k){ return (c - 0.5) * k + 0.5; }

// Sample source, optional posterize; pack Value in .a
vec4 samplePacked(vec2 uv){
    uv = clamp(uv, vec2(0.0), vec2(1.0));
    vec3 rgb = texture(DiffuseSampler, uv).rgb;
    if (DEPTH_PER_CHANNEL > 0.0) {
        rgb = floor(rgb * DEPTH_PER_CHANNEL) / DEPTH_PER_CHANNEL;
    }
    float V = rgb2hsv(rgb).z;
    return vec4(rgb, V);
}

float regionMeanValue(vec4 vals[REGION_N]){
    float sum = 0.0;
    for (int i = 0; i < REGION_N; ++i) sum += vals[i].a;
    return sum / float(REGION_N);
}
vec3 regionMean(vec4 vals[REGION_N]){
    vec3 sum = vec3(0.0);
    for (int i = 0; i < REGION_N; ++i) sum += vals[i].rgb;
    return sum / float(REGION_N);
}
// Compare variances (no sqrt needed)
float regionVariance(vec4 vals[REGION_N], float meanVal){
    float acc = 0.0;
    for (int i = 0; i < REGION_N; ++i){ float d = vals[i].a - meanVal; acc += d*d; }
    return acc / float(REGION_N);
}

// --- Downward directional Kuwahara operating in a VIRTUAL low-res grid ---
vec3 kuwaharaDown_virtual(vec2 uv){
    // Compute virtual grid from current framebuffer size and target density
    ivec2 full = textureSize(DiffuseSampler, 0);
    float longEdge = float(max(full.x, full.y));
    float scale    = max(longEdge / REF_LONG_EDGE, 1.0); // never upscale
    vec2  lowRes   = vec2(max(1.0, floor(float(full.x) / scale)),
    max(1.0, floor(float(full.y) / scale)));

    // Size of one VIRTUAL texel in normalized UV
    vec2 vTexel = 1.0 / lowRes;

    // Snap current UV to the CENTER of its virtual texel
    vec2 uv0 = (floor(uv * lowRes) + 0.5) * vTexel;

    // Kernel step measured in VIRTUAL texels (resolution-independent)
    vec2 stepXY = vec2(KERNEL_WIDTH * vTexel.x, KERNEL_HEIGHT * vTexel.y);

    vec4 regVals[REGION_N];

    // Down-left (x<=0,y>=0)
    for (int y = 0; y < REGION_H; ++y)
    for (int x = 0; x < REGION_W; ++x){
        vec2 off = vec2(-float(x)*stepXY.x, float(y)*stepXY.y);
        regVals[y*REGION_W + x] = samplePacked(uv0 + off);
    }
    float mC = regionMeanValue(regVals);
    vec3  cC = regionMean(regVals);
    float vC = regionVariance(regVals, mC);

    // Down-right (x>=0,y>=0)
    for (int y = 0; y < REGION_H; ++y)
    for (int x = 0; x < REGION_W; ++x){
        vec2 off = vec2(float(x)*stepXY.x, float(y)*stepXY.y);
        regVals[y*REGION_W + x] = samplePacked(uv0 + off);
    }
    float mD = regionMeanValue(regVals);
    vec3  cD = regionMean(regVals);
    float vD = regionVariance(regVals, mD);

    return (vC <= vD) ? cC : cD;
}

void main(){
    vec3 col = kuwaharaDown_virtual(texCoord);
    col = applyContrast(col, CONTRAST);
    FragColor = vec4(col, 1.0);
}
