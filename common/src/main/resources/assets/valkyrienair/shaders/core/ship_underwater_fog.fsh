#version 150

uniform sampler2D OpaqueDepthTex;
uniform sampler2D EntryDepthTex;
uniform sampler2D EntryMaskTex;
uniform sampler2D UnderwaterOverlayTex;

uniform mat4 ProjInv;
uniform vec3 WaterFogColor;
uniform float Time;
uniform vec2 ScreenSize;
uniform float FarDepth01;
uniform float OpaqueDepthValid;

in vec2 texCoord;

out vec4 fragColor;

vec3 reconstructViewPos(vec2 uv, float depth01) {
    vec4 ndc = vec4(uv * 2.0 - 1.0, depth01 * 2.0 - 1.0, 1.0);
    vec4 view = ProjInv * ndc;
    float w = view.w;
    // Preserve sign while avoiding division by ~0.
    float safeW = (abs(w) > 1e-6) ? w : ((w < 0.0) ? -1e-6 : 1e-6);
    return view.xyz / safeW;
}

void main() {
    vec4 entrySample = texture(EntryMaskTex, texCoord);
    float entryMask = entrySample.a;
    if (entryMask <= 0.001) {
        fragColor = vec4(0.0);
        return;
    }

    float entryDepth = texture(EntryDepthTex, texCoord).r;
    float opaqueDepth = texture(OpaqueDepthTex, texCoord).r;
    if (OpaqueDepthValid < 0.5) {
        opaqueDepth = FarDepth01;
    }

    float entryDist = length(reconstructViewPos(texCoord, entryDepth));
    float sceneDist = length(reconstructViewPos(texCoord, opaqueDepth));

    float waterLen = max(sceneDist - entryDist, 0.0);

    // Tuned to match vanilla-ish underwater fog density.
    float fogDensity = 0.06;
    float fogAmount = clamp(1.0 - exp(-waterLen * fogDensity), 0.0, 1.0);

    // Add a subtle animated underwater-overlay texture, similar to vanilla.
    vec2 px = texCoord * ScreenSize;
    vec2 overlayUv = (px / 64.0) + vec2(Time * 0.02, Time * 0.01);
    float overlay = texture(UnderwaterOverlayTex, overlayUv).r;
    float overlayMod = mix(0.85, 1.15, overlay);

    // Output only the fog contribution (alpha-blended over the scene).
    vec3 color = WaterFogColor * mix(1.0, 0.75, overlay * 0.18);
    fragColor = vec4(color, clamp(fogAmount * overlayMod, 0.0, 1.0) * entryMask);
}
