#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec4 normal;

in vec3 valkyrienair_CamRelPos;

out vec4 fragColor;

uniform float ValkyrienAir_CullEnabled;
uniform float ValkyrienAir_IsShipPass;
uniform vec3 ValkyrienAir_CameraWorldPos;

uniform vec4 ValkyrienAir_WaterStillUv;
uniform vec4 ValkyrienAir_WaterFlowUv;
uniform vec4 ValkyrienAir_WaterOverlayUv;
uniform float ValkyrienAir_ShipWaterTintEnabled;
uniform vec3 ValkyrienAir_ShipWaterTint;
uniform float ValkyrienAir_ShipUnderwaterViewEnabled;
uniform float ValkyrienAir_ShipPassCoordsAreShipSpace;
uniform float ValkyrienAir_CameraInWater;

uniform vec4 ValkyrienAir_ShipAabbMin0;
uniform vec4 ValkyrienAir_ShipAabbMax0;
uniform vec3 ValkyrienAir_CameraShipPos0;
uniform vec4 ValkyrienAir_GridMin0;
uniform vec4 ValkyrienAir_GridSize0;
uniform mat4 ValkyrienAir_WorldToShip0;
uniform usampler2D ValkyrienAir_AirMask0;
uniform usampler2D ValkyrienAir_OccMask0;

uniform vec4 ValkyrienAir_ShipAabbMin1;
uniform vec4 ValkyrienAir_ShipAabbMax1;
uniform vec3 ValkyrienAir_CameraShipPos1;
uniform vec4 ValkyrienAir_GridMin1;
uniform vec4 ValkyrienAir_GridSize1;
uniform mat4 ValkyrienAir_WorldToShip1;
uniform usampler2D ValkyrienAir_AirMask1;
uniform usampler2D ValkyrienAir_OccMask1;

uniform vec4 ValkyrienAir_ShipAabbMin2;
uniform vec4 ValkyrienAir_ShipAabbMax2;
uniform vec3 ValkyrienAir_CameraShipPos2;
uniform vec4 ValkyrienAir_GridMin2;
uniform vec4 ValkyrienAir_GridSize2;
uniform mat4 ValkyrienAir_WorldToShip2;
uniform usampler2D ValkyrienAir_AirMask2;
uniform usampler2D ValkyrienAir_OccMask2;

uniform vec4 ValkyrienAir_ShipAabbMin3;
uniform vec4 ValkyrienAir_ShipAabbMax3;
uniform vec3 ValkyrienAir_CameraShipPos3;
uniform vec4 ValkyrienAir_GridMin3;
uniform vec4 ValkyrienAir_GridSize3;
uniform mat4 ValkyrienAir_WorldToShip3;
uniform usampler2D ValkyrienAir_AirMask3;
uniform usampler2D ValkyrienAir_OccMask3;

uniform usampler2D ValkyrienAir_ShipUnderwaterViewMask;

const int VA_MASK_TEX_WIDTH_SHIFT = 12;
const int VA_MASK_TEX_WIDTH_MASK = (1 << VA_MASK_TEX_WIDTH_SHIFT) - 1;

const int VA_SUB = 8;
const int VA_OCC_WORDS_PER_VOXEL = 16;

bool va_inUv(vec2 uv, vec4 bounds) {
    return uv.x >= bounds.x && uv.x <= bounds.z && uv.y >= bounds.y && uv.y <= bounds.w;
}

bool va_isWaterUv(vec2 uv) {
    return va_inUv(uv, ValkyrienAir_WaterStillUv) ||
        va_inUv(uv, ValkyrienAir_WaterFlowUv) ||
        va_inUv(uv, ValkyrienAir_WaterOverlayUv);
}

uint va_fetchWord(usampler2D tex, int wordIndex) {
    ivec2 coord = ivec2(wordIndex & VA_MASK_TEX_WIDTH_MASK, wordIndex >> VA_MASK_TEX_WIDTH_SHIFT);
    return texelFetch(tex, coord, 0).r;
}

bool va_testAir(usampler2D airMask, int voxelIdx) {
    int wordIndex = voxelIdx >> 5;
    int bit = voxelIdx & 31;
    uint word = va_fetchWord(airMask, wordIndex);
    return ((word >> uint(bit)) & 1u) != 0u;
}

bool va_testShipUnderwaterView(usampler2D mask, int voxelIdx) {
    int wordIndex = voxelIdx >> 5;
    int bit = voxelIdx & 31;
    uint word = va_fetchWord(mask, wordIndex);
    return ((word >> uint(bit)) & 1u) != 0u;
}

bool va_testOcc(usampler2D occMask, int voxelIdx, int subIdx) {
    int wordIndex = voxelIdx * VA_OCC_WORDS_PER_VOXEL + (subIdx >> 5);
    int bit = subIdx & 31;
    uint word = va_fetchWord(occMask, wordIndex);
    return ((word >> uint(bit)) & 1u) != 0u;
}

bool va_shouldDiscardForShip0(vec3 worldPos) {
    if (ValkyrienAir_GridSize0.x <= 0.0) return false;
    if (worldPos.x < ValkyrienAir_ShipAabbMin0.x || worldPos.x > ValkyrienAir_ShipAabbMax0.x) return false;
    if (worldPos.y < ValkyrienAir_ShipAabbMin0.y || worldPos.y > ValkyrienAir_ShipAabbMax0.y) return false;
    if (worldPos.z < ValkyrienAir_ShipAabbMin0.z || worldPos.z > ValkyrienAir_ShipAabbMax0.z) return false;

    vec3 shipPos = (ValkyrienAir_WorldToShip0 * vec4(valkyrienair_CamRelPos, 0.0)).xyz + ValkyrienAir_CameraShipPos0;
    vec3 localPos = shipPos - ValkyrienAir_GridMin0.xyz;
    vec3 size = ValkyrienAir_GridSize0.xyz;
    if (localPos.x < 0.0 || localPos.y < 0.0 || localPos.z < 0.0) return false;
    if (localPos.x >= size.x || localPos.y >= size.y || localPos.z >= size.z) return false;

    ivec3 v = ivec3(floor(localPos));
    ivec3 isize = ivec3(size);
    int voxelIdx = v.x + isize.x * (v.y + isize.y * v.z);

    ivec3 sv = ivec3(floor(fract(shipPos) * float(VA_SUB)));
    sv = clamp(sv, ivec3(0), ivec3(VA_SUB - 1));
    int subIdx = sv.x + VA_SUB * (sv.y + VA_SUB * sv.z);

    if (va_testOcc(ValkyrienAir_OccMask0, voxelIdx, subIdx)) return true;
    if (va_testAir(ValkyrienAir_AirMask0, voxelIdx)) return true;
    return false;
}

bool va_shouldDiscardForShip1(vec3 worldPos) {
    if (ValkyrienAir_GridSize1.x <= 0.0) return false;
    if (worldPos.x < ValkyrienAir_ShipAabbMin1.x || worldPos.x > ValkyrienAir_ShipAabbMax1.x) return false;
    if (worldPos.y < ValkyrienAir_ShipAabbMin1.y || worldPos.y > ValkyrienAir_ShipAabbMax1.y) return false;
    if (worldPos.z < ValkyrienAir_ShipAabbMin1.z || worldPos.z > ValkyrienAir_ShipAabbMax1.z) return false;

    vec3 shipPos = (ValkyrienAir_WorldToShip1 * vec4(valkyrienair_CamRelPos, 0.0)).xyz + ValkyrienAir_CameraShipPos1;
    vec3 localPos = shipPos - ValkyrienAir_GridMin1.xyz;
    vec3 size = ValkyrienAir_GridSize1.xyz;
    if (localPos.x < 0.0 || localPos.y < 0.0 || localPos.z < 0.0) return false;
    if (localPos.x >= size.x || localPos.y >= size.y || localPos.z >= size.z) return false;

    ivec3 v = ivec3(floor(localPos));
    ivec3 isize = ivec3(size);
    int voxelIdx = v.x + isize.x * (v.y + isize.y * v.z);

    ivec3 sv = ivec3(floor(fract(shipPos) * float(VA_SUB)));
    sv = clamp(sv, ivec3(0), ivec3(VA_SUB - 1));
    int subIdx = sv.x + VA_SUB * (sv.y + VA_SUB * sv.z);

    if (va_testOcc(ValkyrienAir_OccMask1, voxelIdx, subIdx)) return true;
    if (va_testAir(ValkyrienAir_AirMask1, voxelIdx)) return true;
    return false;
}

bool va_shouldDiscardForShip2(vec3 worldPos) {
    if (ValkyrienAir_GridSize2.x <= 0.0) return false;
    if (worldPos.x < ValkyrienAir_ShipAabbMin2.x || worldPos.x > ValkyrienAir_ShipAabbMax2.x) return false;
    if (worldPos.y < ValkyrienAir_ShipAabbMin2.y || worldPos.y > ValkyrienAir_ShipAabbMax2.y) return false;
    if (worldPos.z < ValkyrienAir_ShipAabbMin2.z || worldPos.z > ValkyrienAir_ShipAabbMax2.z) return false;

    vec3 shipPos = (ValkyrienAir_WorldToShip2 * vec4(valkyrienair_CamRelPos, 0.0)).xyz + ValkyrienAir_CameraShipPos2;
    vec3 localPos = shipPos - ValkyrienAir_GridMin2.xyz;
    vec3 size = ValkyrienAir_GridSize2.xyz;
    if (localPos.x < 0.0 || localPos.y < 0.0 || localPos.z < 0.0) return false;
    if (localPos.x >= size.x || localPos.y >= size.y || localPos.z >= size.z) return false;

    ivec3 v = ivec3(floor(localPos));
    ivec3 isize = ivec3(size);
    int voxelIdx = v.x + isize.x * (v.y + isize.y * v.z);

    ivec3 sv = ivec3(floor(fract(shipPos) * float(VA_SUB)));
    sv = clamp(sv, ivec3(0), ivec3(VA_SUB - 1));
    int subIdx = sv.x + VA_SUB * (sv.y + VA_SUB * sv.z);

    if (va_testOcc(ValkyrienAir_OccMask2, voxelIdx, subIdx)) return true;
    if (va_testAir(ValkyrienAir_AirMask2, voxelIdx)) return true;
    return false;
}

bool va_shouldDiscardForShip3(vec3 worldPos) {
    if (ValkyrienAir_GridSize3.x <= 0.0) return false;
    if (worldPos.x < ValkyrienAir_ShipAabbMin3.x || worldPos.x > ValkyrienAir_ShipAabbMax3.x) return false;
    if (worldPos.y < ValkyrienAir_ShipAabbMin3.y || worldPos.y > ValkyrienAir_ShipAabbMax3.y) return false;
    if (worldPos.z < ValkyrienAir_ShipAabbMin3.z || worldPos.z > ValkyrienAir_ShipAabbMax3.z) return false;

    vec3 shipPos = (ValkyrienAir_WorldToShip3 * vec4(valkyrienair_CamRelPos, 0.0)).xyz + ValkyrienAir_CameraShipPos3;
    vec3 localPos = shipPos - ValkyrienAir_GridMin3.xyz;
    vec3 size = ValkyrienAir_GridSize3.xyz;
    if (localPos.x < 0.0 || localPos.y < 0.0 || localPos.z < 0.0) return false;
    if (localPos.x >= size.x || localPos.y >= size.y || localPos.z >= size.z) return false;

    ivec3 v = ivec3(floor(localPos));
    ivec3 isize = ivec3(size);
    int voxelIdx = v.x + isize.x * (v.y + isize.y * v.z);

    ivec3 sv = ivec3(floor(fract(shipPos) * float(VA_SUB)));
    sv = clamp(sv, ivec3(0), ivec3(VA_SUB - 1));
    int subIdx = sv.x + VA_SUB * (sv.y + VA_SUB * sv.z);

    if (va_testOcc(ValkyrienAir_OccMask3, voxelIdx, subIdx)) return true;
    if (va_testAir(ValkyrienAir_AirMask3, voxelIdx)) return true;
    return false;
}

void main() {
    if (ValkyrienAir_CullEnabled > 0.5 && ValkyrienAir_IsShipPass < 0.5 && va_isWaterUv(texCoord0)) {
        vec3 worldPos = valkyrienair_CamRelPos + ValkyrienAir_CameraWorldPos;
        if (va_shouldDiscardForShip0(worldPos) || va_shouldDiscardForShip1(worldPos) ||
            va_shouldDiscardForShip2(worldPos) || va_shouldDiscardForShip3(worldPos)) {
            discard;
        }
    }

    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (ValkyrienAir_ShipWaterTintEnabled > 0.5 && va_isWaterUv(texCoord0)) {
        color.rgb *= ValkyrienAir_ShipWaterTint;
    }

    // Ship underwater-view tint for transparent ship blocks (glass windows, etc.).
    //
    // When the camera is in air (e.g. inside an underwater air pocket), vanilla does not apply underwater fog to what
    // you see through glass. Instead of changing the player's screen/camera overlay, we tint the ship's transparent
    // blocks that are adjacent to outside world water to mimic that underwater diffusion.
    if (ValkyrienAir_ShipUnderwaterViewEnabled > 0.5 && ValkyrienAir_CameraInWater < 0.5 && !va_isWaterUv(texCoord0)) {
        if (ValkyrienAir_GridSize0.x > 0.0) {
            vec3 shipPos;
            if (ValkyrienAir_ShipPassCoordsAreShipSpace > 0.5) {
                // Sodium/Embeddium ship rendering feeds ship-space camera-relative positions.
                shipPos = valkyrienair_CamRelPos + ValkyrienAir_CameraShipPos0;
            } else {
                // Vanilla ship rendering uses world-space camera-relative positions.
                shipPos = (ValkyrienAir_WorldToShip0 * vec4(valkyrienair_CamRelPos, 0.0)).xyz + ValkyrienAir_CameraShipPos0;
            }

            vec3 localPos = shipPos - ValkyrienAir_GridMin0.xyz;
            vec3 size = ValkyrienAir_GridSize0.xyz;
            if (localPos.x >= 0.0 && localPos.y >= 0.0 && localPos.z >= 0.0 &&
                localPos.x < size.x && localPos.y < size.y && localPos.z < size.z) {

                ivec3 v = ivec3(floor(localPos));
                ivec3 isize = ivec3(size);
                int voxelIdx = v.x + isize.x * (v.y + isize.y * v.z);

                if (va_testShipUnderwaterView(ValkyrienAir_ShipUnderwaterViewMask, voxelIdx)) {
                    float t = clamp((vertexDistance - 2.0) / 18.0, 0.0, 1.0);
                    float strength = mix(0.35, 0.70, t);
                    vec3 waterFog = mix(ValkyrienAir_ShipWaterTint, vec3(0.0, 0.2, 0.4), 0.35);

                    color.rgb = mix(color.rgb, waterFog, strength);
                    color.a = min(1.0, color.a + strength * 0.25);
                }
            }
        }
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
