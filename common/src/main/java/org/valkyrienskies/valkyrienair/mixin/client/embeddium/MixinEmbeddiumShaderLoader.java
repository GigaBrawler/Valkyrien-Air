package org.valkyrienskies.valkyrienair.mixin.client.embeddium;

import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.gl.shader.ShaderLoader", remap = false)
public abstract class MixinEmbeddiumShaderLoader {

    @Unique
    private static final String VA_VAR_CAM_REL_POS = "valkyrienair_CamRelPos";

    @Unique
    private static final String VA_UNIFORM_CULL_ENABLED = "ValkyrienAir_CullEnabled";

    @Unique
    private static final String VA_PATCH_TAG = "valkyrienair_water_cull_patch_v1";

    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true, require = 0)
    private static void valkyrienair$patchChunkShaders(final ResourceLocation id, final CallbackInfoReturnable<String> cir) {
        if (id == null) return;
        final String namespace = id.getNamespace();
        if (!"sodium".equals(namespace)) return;

        final String path = id.getPath();
        final String src = cir.getReturnValue();
        if (src == null || src.isEmpty()) return;
        if (src.contains(VA_PATCH_TAG)) return;

        if ("blocks/block_layer_opaque.vsh".equals(path)) {
            cir.setReturnValue(valkyrienair$patchChunkVertexShader(src));
        } else if ("blocks/block_layer_opaque.fsh".equals(path)) {
            cir.setReturnValue(valkyrienair$patchChunkFragmentShader(src));
        }
    }

    @Unique
    private static String valkyrienair$patchChunkVertexShader(final String src) {
        if (src.contains(VA_PATCH_TAG)) return src;
        if (src.contains("out vec3 " + VA_VAR_CAM_REL_POS + ";")) return src;

        final String[] lines = src.split("\n", -1);
        final java.util.ArrayList<String> outLines = new java.util.ArrayList<>(lines.length + 8);

        boolean insertedTag = false;
        boolean insertedDecl = false;
        boolean insertedAssign = false;

        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];

            if (!insertedTag && i == 0 && line.startsWith("#version")) {
                outLines.add(line);
                outLines.add("");
                outLines.add("// " + VA_PATCH_TAG);
                insertedTag = true;
                continue;
            }

            outLines.add(line);

            final String trimmed = line.trim();

            if (!insertedDecl && "out vec2 v_TexCoord;".equals(trimmed)) {
                outLines.add("");
                outLines.add("out vec3 " + VA_VAR_CAM_REL_POS + ";");
                insertedDecl = true;
                continue;
            }

            if (!insertedAssign && trimmed.startsWith("vec3 position") && trimmed.contains("_vert_position") &&
                trimmed.contains("translation") && trimmed.endsWith(";")) {
                outLines.add("");
                outLines.add("    " + VA_VAR_CAM_REL_POS + " = position;");
                insertedAssign = true;
            }
        }

        // Only return a patched shader if we successfully injected *both* the varying declaration and assignment.
        if (!insertedTag || !insertedDecl || !insertedAssign) return src;
        return String.join("\n", outLines);
    }

    @Unique
    private static String valkyrienair$patchChunkFragmentShader(final String src) {
        if (src.contains(VA_PATCH_TAG)) return src;
        if (src.contains(VA_UNIFORM_CULL_ENABLED)) return src;

        final String[] lines = src.split("\n", -1);
        final java.util.ArrayList<String> outLines = new java.util.ArrayList<>(lines.length + 512);

        boolean insertedTag = false;
        boolean insertedCamRelIn = false;
        boolean insertedUniforms = false;
        boolean insertedDiscard = false;

        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];

            if (!insertedTag && i == 0 && line.startsWith("#version")) {
                outLines.add(line);
                outLines.add("");
                outLines.add("// " + VA_PATCH_TAG);
                insertedTag = true;
                continue;
            }

            final String trimmed = line.trim();

            if (!insertedUniforms && trimmed.startsWith("void main()")) {
                // Insert our uniform + helper block before main(), so it's always at global scope.
                for (final String u : valkyrienair$chunkCullUniformsAndHelpers().split("\n", -1)) {
                    outLines.add(u);
                }
                outLines.add("");
                insertedUniforms = true;
            }

            outLines.add(line);

            if (!insertedCamRelIn && trimmed.startsWith("in vec2 v_TexCoord")) {
                outLines.add("");
                outLines.add("in vec3 " + VA_VAR_CAM_REL_POS + ";");
                insertedCamRelIn = true;
                continue;
            }

            if (!insertedDiscard && "void main() {".equals(trimmed)) {
                for (final String d : valkyrienair$chunkCullDiscardMainBlock().split("\n", -1)) {
                    outLines.add(d);
                }
                outLines.add("");
                insertedDiscard = true;
            }
        }

        // Only return a patched shader if we injected everything we rely on (avoids shader compile crashes).
        if (!insertedTag || !insertedCamRelIn || !insertedUniforms || !insertedDiscard) return src;
        final String out = String.join("\n", outLines);
        if (!out.contains(VA_UNIFORM_CULL_ENABLED)) return src;
        return out;
    }

    @Unique
    private static String valkyrienair$chunkCullUniformsAndHelpers() {
        // Ported from our patched vanilla shaders/core/rendertype_translucent.fsh.
        return String.join("\n",
            "uniform float ValkyrienAir_CullEnabled;",
            "uniform float ValkyrienAir_IsShipPass;",
            "uniform vec3 ValkyrienAir_CameraWorldPos;",
            "",
            "uniform vec4 ValkyrienAir_WaterStillUv;",
            "uniform vec4 ValkyrienAir_WaterFlowUv;",
            "uniform vec4 ValkyrienAir_WaterOverlayUv;",
            "",
            "uniform vec4 ValkyrienAir_ShipAabbMin0;",
            "uniform vec4 ValkyrienAir_ShipAabbMax0;",
            "uniform vec3 ValkyrienAir_CameraShipPos0;",
            "uniform vec4 ValkyrienAir_GridMin0;",
            "uniform vec4 ValkyrienAir_GridSize0;",
            "uniform mat4 ValkyrienAir_WorldToShip0;",
            "uniform usampler2D ValkyrienAir_AirMask0;",
            "uniform usampler2D ValkyrienAir_OccMask0;",
            "",
            "uniform vec4 ValkyrienAir_ShipAabbMin1;",
            "uniform vec4 ValkyrienAir_ShipAabbMax1;",
            "uniform vec3 ValkyrienAir_CameraShipPos1;",
            "uniform vec4 ValkyrienAir_GridMin1;",
            "uniform vec4 ValkyrienAir_GridSize1;",
            "uniform mat4 ValkyrienAir_WorldToShip1;",
            "uniform usampler2D ValkyrienAir_AirMask1;",
            "uniform usampler2D ValkyrienAir_OccMask1;",
            "",
            "uniform vec4 ValkyrienAir_ShipAabbMin2;",
            "uniform vec4 ValkyrienAir_ShipAabbMax2;",
            "uniform vec3 ValkyrienAir_CameraShipPos2;",
            "uniform vec4 ValkyrienAir_GridMin2;",
            "uniform vec4 ValkyrienAir_GridSize2;",
            "uniform mat4 ValkyrienAir_WorldToShip2;",
            "uniform usampler2D ValkyrienAir_AirMask2;",
            "uniform usampler2D ValkyrienAir_OccMask2;",
            "",
            "uniform vec4 ValkyrienAir_ShipAabbMin3;",
            "uniform vec4 ValkyrienAir_ShipAabbMax3;",
            "uniform vec3 ValkyrienAir_CameraShipPos3;",
            "uniform vec4 ValkyrienAir_GridMin3;",
            "uniform vec4 ValkyrienAir_GridSize3;",
            "uniform mat4 ValkyrienAir_WorldToShip3;",
            "uniform usampler2D ValkyrienAir_AirMask3;",
            "uniform usampler2D ValkyrienAir_OccMask3;",
            "",
            "const int VA_MASK_TEX_WIDTH_SHIFT = 12;",
            "const int VA_MASK_TEX_WIDTH_MASK = (1 << VA_MASK_TEX_WIDTH_SHIFT) - 1;",
            "",
            "const int VA_SUB = 8;",
            "const int VA_OCC_WORDS_PER_VOXEL = 16;",
            "",
            "bool va_inUv(vec2 uv, vec4 bounds) {",
            "    return uv.x >= bounds.x && uv.x <= bounds.z && uv.y >= bounds.y && uv.y <= bounds.w;",
            "}",
            "",
            "bool va_isWaterUv(vec2 uv) {",
            "    return va_inUv(uv, ValkyrienAir_WaterStillUv) ||",
            "        va_inUv(uv, ValkyrienAir_WaterFlowUv) ||",
            "        va_inUv(uv, ValkyrienAir_WaterOverlayUv);",
            "}",
            "",
            "uint va_fetchWord(usampler2D tex, int wordIndex) {",
            "    ivec2 coord = ivec2(wordIndex & VA_MASK_TEX_WIDTH_MASK, wordIndex >> VA_MASK_TEX_WIDTH_SHIFT);",
            "    return texelFetch(tex, coord, 0).r;",
            "}",
            "",
            "bool va_testAir(usampler2D airMask, int voxelIdx) {",
            "    int wordIndex = voxelIdx >> 5;",
            "    int bit = voxelIdx & 31;",
            "    uint word = va_fetchWord(airMask, wordIndex);",
            "    return ((word >> uint(bit)) & 1u) != 0u;",
            "}",
            "",
            "bool va_testOcc(usampler2D occMask, int voxelIdx, int subIdx) {",
            "    int wordIndex = voxelIdx * VA_OCC_WORDS_PER_VOXEL + (subIdx >> 5);",
            "    int bit = subIdx & 31;",
            "    uint word = va_fetchWord(occMask, wordIndex);",
            "    return ((word >> uint(bit)) & 1u) != 0u;",
            "}",
            "",
            "bool va_shouldDiscardForShip0(vec3 worldPos) {",
            "    if (ValkyrienAir_GridSize0.x <= 0.0) return false;",
            "    if (worldPos.x < ValkyrienAir_ShipAabbMin0.x || worldPos.x > ValkyrienAir_ShipAabbMax0.x) return false;",
            "    if (worldPos.y < ValkyrienAir_ShipAabbMin0.y || worldPos.y > ValkyrienAir_ShipAabbMax0.y) return false;",
            "    if (worldPos.z < ValkyrienAir_ShipAabbMin0.z || worldPos.z > ValkyrienAir_ShipAabbMax0.z) return false;",
            "",
            "    vec3 shipPos = (ValkyrienAir_WorldToShip0 * vec4(" + VA_VAR_CAM_REL_POS + ", 0.0)).xyz + ValkyrienAir_CameraShipPos0;",
            "    vec3 localPos = shipPos - ValkyrienAir_GridMin0.xyz;",
            "    vec3 size = ValkyrienAir_GridSize0.xyz;",
            "    if (localPos.x < 0.0 || localPos.y < 0.0 || localPos.z < 0.0) return false;",
            "    if (localPos.x >= size.x || localPos.y >= size.y || localPos.z >= size.z) return false;",
            "",
            "    ivec3 v = ivec3(floor(localPos));",
            "    ivec3 isize = ivec3(size);",
            "    int voxelIdx = v.x + isize.x * (v.y + isize.y * v.z);",
            "",
            "    ivec3 sv = ivec3(floor(fract(shipPos) * float(VA_SUB)));",
            "    sv = clamp(sv, ivec3(0), ivec3(VA_SUB - 1));",
            "    int subIdx = sv.x + VA_SUB * (sv.y + VA_SUB * sv.z);",
            "",
            "    if (va_testOcc(ValkyrienAir_OccMask0, voxelIdx, subIdx)) return true;",
            "    if (va_testAir(ValkyrienAir_AirMask0, voxelIdx)) return true;",
            "    return false;",
            "}",
            "",
            "bool va_shouldDiscardForShip1(vec3 worldPos) {",
            "    if (ValkyrienAir_GridSize1.x <= 0.0) return false;",
            "    if (worldPos.x < ValkyrienAir_ShipAabbMin1.x || worldPos.x > ValkyrienAir_ShipAabbMax1.x) return false;",
            "    if (worldPos.y < ValkyrienAir_ShipAabbMin1.y || worldPos.y > ValkyrienAir_ShipAabbMax1.y) return false;",
            "    if (worldPos.z < ValkyrienAir_ShipAabbMin1.z || worldPos.z > ValkyrienAir_ShipAabbMax1.z) return false;",
            "",
            "    vec3 shipPos = (ValkyrienAir_WorldToShip1 * vec4(" + VA_VAR_CAM_REL_POS + ", 0.0)).xyz + ValkyrienAir_CameraShipPos1;",
            "    vec3 localPos = shipPos - ValkyrienAir_GridMin1.xyz;",
            "    vec3 size = ValkyrienAir_GridSize1.xyz;",
            "    if (localPos.x < 0.0 || localPos.y < 0.0 || localPos.z < 0.0) return false;",
            "    if (localPos.x >= size.x || localPos.y >= size.y || localPos.z >= size.z) return false;",
            "",
            "    ivec3 v = ivec3(floor(localPos));",
            "    ivec3 isize = ivec3(size);",
            "    int voxelIdx = v.x + isize.x * (v.y + isize.y * v.z);",
            "",
            "    ivec3 sv = ivec3(floor(fract(shipPos) * float(VA_SUB)));",
            "    sv = clamp(sv, ivec3(0), ivec3(VA_SUB - 1));",
            "    int subIdx = sv.x + VA_SUB * (sv.y + VA_SUB * sv.z);",
            "",
            "    if (va_testOcc(ValkyrienAir_OccMask1, voxelIdx, subIdx)) return true;",
            "    if (va_testAir(ValkyrienAir_AirMask1, voxelIdx)) return true;",
            "    return false;",
            "}",
            "",
            "bool va_shouldDiscardForShip2(vec3 worldPos) {",
            "    if (ValkyrienAir_GridSize2.x <= 0.0) return false;",
            "    if (worldPos.x < ValkyrienAir_ShipAabbMin2.x || worldPos.x > ValkyrienAir_ShipAabbMax2.x) return false;",
            "    if (worldPos.y < ValkyrienAir_ShipAabbMin2.y || worldPos.y > ValkyrienAir_ShipAabbMax2.y) return false;",
            "    if (worldPos.z < ValkyrienAir_ShipAabbMin2.z || worldPos.z > ValkyrienAir_ShipAabbMax2.z) return false;",
            "",
            "    vec3 shipPos = (ValkyrienAir_WorldToShip2 * vec4(" + VA_VAR_CAM_REL_POS + ", 0.0)).xyz + ValkyrienAir_CameraShipPos2;",
            "    vec3 localPos = shipPos - ValkyrienAir_GridMin2.xyz;",
            "    vec3 size = ValkyrienAir_GridSize2.xyz;",
            "    if (localPos.x < 0.0 || localPos.y < 0.0 || localPos.z < 0.0) return false;",
            "    if (localPos.x >= size.x || localPos.y >= size.y || localPos.z >= size.z) return false;",
            "",
            "    ivec3 v = ivec3(floor(localPos));",
            "    ivec3 isize = ivec3(size);",
            "    int voxelIdx = v.x + isize.x * (v.y + isize.y * v.z);",
            "",
            "    ivec3 sv = ivec3(floor(fract(shipPos) * float(VA_SUB)));",
            "    sv = clamp(sv, ivec3(0), ivec3(VA_SUB - 1));",
            "    int subIdx = sv.x + VA_SUB * (sv.y + VA_SUB * sv.z);",
            "",
            "    if (va_testOcc(ValkyrienAir_OccMask2, voxelIdx, subIdx)) return true;",
            "    if (va_testAir(ValkyrienAir_AirMask2, voxelIdx)) return true;",
            "    return false;",
            "}",
            "",
            "bool va_shouldDiscardForShip3(vec3 worldPos) {",
            "    if (ValkyrienAir_GridSize3.x <= 0.0) return false;",
            "    if (worldPos.x < ValkyrienAir_ShipAabbMin3.x || worldPos.x > ValkyrienAir_ShipAabbMax3.x) return false;",
            "    if (worldPos.y < ValkyrienAir_ShipAabbMin3.y || worldPos.y > ValkyrienAir_ShipAabbMax3.y) return false;",
            "    if (worldPos.z < ValkyrienAir_ShipAabbMin3.z || worldPos.z > ValkyrienAir_ShipAabbMax3.z) return false;",
            "",
            "    vec3 shipPos = (ValkyrienAir_WorldToShip3 * vec4(" + VA_VAR_CAM_REL_POS + ", 0.0)).xyz + ValkyrienAir_CameraShipPos3;",
            "    vec3 localPos = shipPos - ValkyrienAir_GridMin3.xyz;",
            "    vec3 size = ValkyrienAir_GridSize3.xyz;",
            "    if (localPos.x < 0.0 || localPos.y < 0.0 || localPos.z < 0.0) return false;",
            "    if (localPos.x >= size.x || localPos.y >= size.y || localPos.z >= size.z) return false;",
            "",
            "    ivec3 v = ivec3(floor(localPos));",
            "    ivec3 isize = ivec3(size);",
            "    int voxelIdx = v.x + isize.x * (v.y + isize.y * v.z);",
            "",
            "    ivec3 sv = ivec3(floor(fract(shipPos) * float(VA_SUB)));",
            "    sv = clamp(sv, ivec3(0), ivec3(VA_SUB - 1));",
            "    int subIdx = sv.x + VA_SUB * (sv.y + VA_SUB * sv.z);",
            "",
            "    if (va_testOcc(ValkyrienAir_OccMask3, voxelIdx, subIdx)) return true;",
            "    if (va_testAir(ValkyrienAir_AirMask3, voxelIdx)) return true;",
            "    return false;",
            "}"
        );
    }

    @Unique
    private static String valkyrienair$chunkCullDiscardMainBlock() {
        return String.join("\n",
            "    if (ValkyrienAir_CullEnabled > 0.5 && ValkyrienAir_IsShipPass < 0.5 && va_isWaterUv(v_TexCoord)) {",
            "        vec3 worldPos = " + VA_VAR_CAM_REL_POS + " + ValkyrienAir_CameraWorldPos;",
            "        if (va_shouldDiscardForShip0(worldPos) || va_shouldDiscardForShip1(worldPos) ||",
            "            va_shouldDiscardForShip2(worldPos) || va_shouldDiscardForShip3(worldPos)) {",
            "            discard;",
            "        }",
            "    }"
        );
    }
}
