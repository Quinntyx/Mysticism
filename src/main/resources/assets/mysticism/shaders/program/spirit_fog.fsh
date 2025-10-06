#version 150
uniform sampler2D DiffuseSampler;
uniform sampler2D DepthSampler;   // bound from json with name "Depth"
uniform sampler2D ParamsSampler;  // 2x1 texture we update each frame
uniform vec2 OutSize;

// From engine (MC binds these):
uniform mat4 ProjMat;
uniform mat4 InvProjMat;
uniform mat4 InvViewMat;

in vec2 texCoord;
out vec4 fragColor;

// unpack helpers (if you changed packing, update this)
vec4 readPixel(int x) {
    // We stored only the top byte of each float for simplicity.
    // For smoother control, you can store real floats across multiple pixels.
    vec2 uv = vec2((float(x)+0.5)/2.0, 0.5);
    return texture(ParamsSampler, uv);
}

vec3 reconstructViewRay(vec2 uv) {
    vec4 ndc  = vec4(uv*2.0-1.0, 1.0, 1.0);
    vec4 view = InvProjMat * ndc;
    return normalize(view.xyz / view.w);
}

float hash3(vec3 p){ return fract(sin(dot(p, vec3(127.1,311.7,74.7))) * 43758.5453); }
float noise3(vec3 p){
    vec3 i=floor(p), f=fract(p), u=f*f*(3.0-2.0*f);
    float n000=hash3(i+vec3(0,0,0)), n100=hash3(i+vec3(1,0,0));
    float n010=hash3(i+vec3(0,1,0)), n110=hash3(i+vec3(1,1,0));
    float n001=hash3(i+vec3(0,0,1)), n101=hash3(i+vec3(1,0,1));
    float n011=hash3(i+vec3(0,1,1)), n111=hash3(i+vec3(1,1,1));
    float nx00=mix(n000,n100,u.x), nx10=mix(n010,n110,u.x);
    float nx01=mix(n001,n101,u.x), nx11=mix(n011,n111,u.x);
    float nxy0=mix(nx00,nx10,u.y), nxy1=mix(nx01,nx11,u.y);
    return mix(nxy0,nxy1,u.z);
}
float fbm(vec3 p){
    float a=0.5,f=0.0;
    for(int i=0;i<5;i++){ f+=a*noise3(p); p*=2.02; a*=0.5; }
    return f;
}

void main() {
    vec2 uv = texCoord;
    vec4 scene = texture(DiffuseSampler, uv);
    float depth = texture(DepthSampler, uv).r;
    if (depth <= 0.0) { fragColor = scene; return; }

    // read packed params (coarse; replace with higher-precision packing if desired)
    vec4 p0 = readPixel(0);
    vec4 p1 = readPixel(1);

    // remap bytes back to [0,1] floats we can use as parameters (coarse control)
    float fogScale = max(1.0, p0.r * 255.0);   // ~[1..255]
    float iTime    = p0.g * 255.0;
    float density  = p0.b * 0.5;               // tune scale to taste
    float steps    = max(8.0, p0.a * 255.0);

    float speed    = p1.r;
    float maxDist  = max(1.0, p1.g * 255.0);

    vec3 ray = reconstructViewRay(uv);
    float dist = depth * maxDist;
    vec3 camWorld = (InvViewMat * vec4(0,0,0,1)).xyz;      // camera position from matrix
    vec3 worldPos = camWorld + ray * dist;

    // 3D slice coordinates in your (i,j,k) subspace: (Δworld)/fogScale
    vec3 q0 = (worldPos - camWorld) / fogScale;

    int STEPS = int(steps + 0.5);
    float stepLen = dist / float(STEPS);
    float trans = 1.0;

    for (int s=0; s<128; s++) {
        if (s >= STEPS || trans < 0.01) break;
        vec3 q = q0 * (float(s)/float(STEPS)) + vec3(0.0, iTime*0.1*speed, 0.0);
        float d = fbm(q);
        float dens = max(d - 0.5, 0.0) * density;
        float absorb = dens * stepLen;
        trans *= exp(-absorb);
    }

    vec3 fogCol = vec3(0.75, 0.68, 0.95);
    vec3 outRgb = mix(fogCol, scene.rgb, clamp(trans, 0.0, 1.0));
    fragColor = vec4(outRgb, 1.0);
}
