package org.valkyrienskies.valkyrienair.mixin.client.embeddium;

import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

    @Unique
    private static final Logger VA_LOGGER = LogManager.getLogger("ValkyrienAir EmbeddiumWaterCull");

    @Unique
    private static final java.util.Set<String> VA_LOGGED_SHADER_IDS =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    @Unique
    private static final java.util.Set<String> VA_PATCHED_VERTEX_PATHS =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    @Unique
    private static boolean valkyrienair$debugLogShaderIds() {
        return Boolean.getBoolean("valkyrienair.debugEmbeddiumShaderIds");
    }

    @Inject(method = "getShaderSource", at = @At("RETURN"), cancellable = true, require = 0)
    private static void valkyrienair$patchChunkShaders(final ResourceLocation id, final CallbackInfoReturnable<String> cir) {
        if (id == null) return;
        final String namespace = id.getNamespace();
        if (!"sodium".equals(namespace)) return;

        final String path = id.getPath();
        final String src = cir.getReturnValue();
        if (src == null || src.isEmpty()) return;

        final boolean debugLog = valkyrienair$debugLogShaderIds();
        final String debugKey = namespace + ":" + path;

        // Sodium/Embeddium may use different base shader names across versions (or via resource packs).
        // Patch any "block layer" shader that matches our simple injection heuristics.
        if (path.startsWith("blocks/block_layer_") && path.endsWith(".vsh")) {
            final boolean alreadyPatched =
                src.contains(VA_PATCH_TAG) || src.contains("out vec3 " + VA_VAR_CAM_REL_POS + ";");
            final String out = alreadyPatched ? src : valkyrienair$patchChunkVertexShader(src);
            final boolean patched = alreadyPatched || out != src;
            if (patched) {
                VA_PATCHED_VERTEX_PATHS.add(path);
            }
            if (debugLog && VA_LOGGED_SHADER_IDS.add(debugKey)) {
                VA_LOGGER.info("ShaderLoader.getShaderSource {} (vertex) patched={}", debugKey, patched);
            }
            cir.setReturnValue(out);
        } else if (path.startsWith("blocks/block_layer_") && path.endsWith(".fsh")) {
            final String vertexPath = path.substring(0, path.length() - 4) + ".vsh";
            if (!VA_PATCHED_VERTEX_PATHS.contains(vertexPath)) {
                if (debugLog && VA_LOGGED_SHADER_IDS.add(debugKey)) {
                    VA_LOGGER.info("ShaderLoader.getShaderSource {} (fragment) skipped: vertex not patched ({})", debugKey, vertexPath);
                }
                return;
            }

            final String out = valkyrienair$patchChunkFragmentShader(src);
            if (debugLog && VA_LOGGED_SHADER_IDS.add(debugKey)) {
                VA_LOGGER.info("ShaderLoader.getShaderSource {} (fragment) patched={}", debugKey, out != src);
            }
            cir.setReturnValue(out);
        } else if (debugLog && VA_LOGGED_SHADER_IDS.add(debugKey)) {
            VA_LOGGER.info("ShaderLoader.getShaderSource {} (unpatched)", debugKey);
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
            final String trimmed = line.trim();

            if (!insertedTag && trimmed.startsWith("#version")) {
                outLines.add(line);
                outLines.add("");
                outLines.add("// " + VA_PATCH_TAG);
                insertedTag = true;
                continue;
            }

            outLines.add(line);

            if (!insertedDecl && trimmed.startsWith("out vec2 v_TexCoord")) {
                outLines.add("");
                outLines.add("out vec3 " + VA_VAR_CAM_REL_POS + ";");
                insertedDecl = true;
                continue;
            }

            if (!insertedAssign && trimmed.startsWith("vec3 position") && trimmed.contains("_vert_position") &&
                trimmed.contains("translation") && trimmed.contains("=")) {
                outLines.add("");
                outLines.add("    " + VA_VAR_CAM_REL_POS + " = position;");
                insertedAssign = true;
            }

            if (!insertedAssign && trimmed.contains("gl_Position") && trimmed.contains("vec4(") && trimmed.contains(", 1.0")) {
                final int vec4Idx = trimmed.indexOf("vec4(");
                final int exprStart = vec4Idx >= 0 ? vec4Idx + 5 : -1;
                final int commaIdx = exprStart >= 0 ? trimmed.indexOf(',', exprStart) : -1;
                if (commaIdx > exprStart) {
                    final String expr = trimmed.substring(exprStart, commaIdx).trim();
                    if (!expr.isEmpty()) {
                        outLines.add("");
                        outLines.add("    " + VA_VAR_CAM_REL_POS + " = " + expr + ";");
                        insertedAssign = true;
                    }
                }
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
            final String trimmed = line.trim();

            if (!insertedTag && trimmed.startsWith("#version")) {
                outLines.add(line);
                outLines.add("");
                outLines.add("// " + VA_PATCH_TAG);
                insertedTag = true;
                continue;
            }

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
            "uniform vec4 ValkyrienAir_GridMin0;",
            "uniform vec4 ValkyrienAir_GridSize0;",
            "uniform mat4 ValkyrienAir_WorldToShip0;",
            "uniform usampler2D ValkyrienAir_AirMask0;",
            "uniform usampler2D ValkyrienAir_OccMask0;",
            "",
            "uniform vec4 ValkyrienAir_ShipAabbMin1;",
            "uniform vec4 ValkyrienAir_ShipAabbMax1;",
            "uniform vec4 ValkyrienAir_GridMin1;",
            "uniform vec4 ValkyrienAir_GridSize1;",
            "uniform mat4 ValkyrienAir_WorldToShip1;",
            "uniform usampler2D ValkyrienAir_AirMask1;",
            "uniform usampler2D ValkyrienAir_OccMask1;",
            "",
            "uniform vec4 ValkyrienAir_ShipAabbMin2;",
            "uniform vec4 ValkyrienAir_ShipAabbMax2;",
            "uniform vec4 ValkyrienAir_GridMin2;",
            "uniform vec4 ValkyrienAir_GridSize2;",
            "uniform mat4 ValkyrienAir_WorldToShip2;",
            "uniform usampler2D ValkyrienAir_AirMask2;",
            "uniform usampler2D ValkyrienAir_OccMask2;",
            "",
            "uniform vec4 ValkyrienAir_ShipAabbMin3;",
            "uniform vec4 ValkyrienAir_ShipAabbMax3;",
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
            "bool va_inAabb(vec3 worldPos, vec4 aabbMin, vec4 aabbMax) {",
            "    return all(greaterThanEqual(worldPos, aabbMin.xyz)) && all(lessThanEqual(worldPos, aabbMax.xyz));",
            "}",
            "",
            "bool va_inAnyShipAabb(vec3 worldPos) {",
            "    if (ValkyrienAir_GridSize0.x > 0.0 && va_inAabb(worldPos, ValkyrienAir_ShipAabbMin0, ValkyrienAir_ShipAabbMax0)) return true;",
            "    if (ValkyrienAir_GridSize1.x > 0.0 && va_inAabb(worldPos, ValkyrienAir_ShipAabbMin1, ValkyrienAir_ShipAabbMax1)) return true;",
            "    if (ValkyrienAir_GridSize2.x > 0.0 && va_inAabb(worldPos, ValkyrienAir_ShipAabbMin2, ValkyrienAir_ShipAabbMax2)) return true;",
            "    if (ValkyrienAir_GridSize3.x > 0.0 && va_inAabb(worldPos, ValkyrienAir_ShipAabbMin3, ValkyrienAir_ShipAabbMax3)) return true;",
            "    return false;",
            "}",
            "",
            "bool va_shouldDiscardForShip0(vec3 worldPos) {",
            "    if (ValkyrienAir_GridSize0.x <= 0.0) return false;",
            "    if (!va_inAabb(worldPos, ValkyrienAir_ShipAabbMin0, ValkyrienAir_ShipAabbMax0)) return false;",
            "",
            "    vec3 shipPos = (ValkyrienAir_WorldToShip0 * vec4(worldPos, 1.0)).xyz;",
            "    vec3 localPos = shipPos - ValkyrienAir_GridMin0.xyz;",
            "    vec3 size = ValkyrienAir_GridSize0.xyz;",
            "    if (any(lessThan(localPos, vec3(0.0)))) return false;",
            "    if (any(greaterThanEqual(localPos, size))) return false;",
            "",
            "    ivec3 v = ivec3(floor(localPos));",
            "    ivec3 isize = ivec3(size);",
            "    int voxelIdx = v.x + isize.x * (v.y + isize.y * v.z);",
            "",
            "    ivec3 sv = ivec3(floor(fract(localPos) * float(VA_SUB)));",
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
            "    if (!va_inAabb(worldPos, ValkyrienAir_ShipAabbMin1, ValkyrienAir_ShipAabbMax1)) return false;",
            "",
            "    vec3 shipPos = (ValkyrienAir_WorldToShip1 * vec4(worldPos, 1.0)).xyz;",
            "    vec3 localPos = shipPos - ValkyrienAir_GridMin1.xyz;",
            "    vec3 size = ValkyrienAir_GridSize1.xyz;",
            "    if (any(lessThan(localPos, vec3(0.0)))) return false;",
            "    if (any(greaterThanEqual(localPos, size))) return false;",
            "",
            "    ivec3 v = ivec3(floor(localPos));",
            "    ivec3 isize = ivec3(size);",
            "    int voxelIdx = v.x + isize.x * (v.y + isize.y * v.z);",
            "",
            "    ivec3 sv = ivec3(floor(fract(localPos) * float(VA_SUB)));",
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
            "    if (!va_inAabb(worldPos, ValkyrienAir_ShipAabbMin2, ValkyrienAir_ShipAabbMax2)) return false;",
            "",
            "    vec3 shipPos = (ValkyrienAir_WorldToShip2 * vec4(worldPos, 1.0)).xyz;",
            "    vec3 localPos = shipPos - ValkyrienAir_GridMin2.xyz;",
            "    vec3 size = ValkyrienAir_GridSize2.xyz;",
            "    if (any(lessThan(localPos, vec3(0.0)))) return false;",
            "    if (any(greaterThanEqual(localPos, size))) return false;",
            "",
            "    ivec3 v = ivec3(floor(localPos));",
            "    ivec3 isize = ivec3(size);",
            "    int voxelIdx = v.x + isize.x * (v.y + isize.y * v.z);",
            "",
            "    ivec3 sv = ivec3(floor(fract(localPos) * float(VA_SUB)));",
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
            "    if (!va_inAabb(worldPos, ValkyrienAir_ShipAabbMin3, ValkyrienAir_ShipAabbMax3)) return false;",
            "",
            "    vec3 shipPos = (ValkyrienAir_WorldToShip3 * vec4(worldPos, 1.0)).xyz;",
            "    vec3 localPos = shipPos - ValkyrienAir_GridMin3.xyz;",
            "    vec3 size = ValkyrienAir_GridSize3.xyz;",
            "    if (any(lessThan(localPos, vec3(0.0)))) return false;",
            "    if (any(greaterThanEqual(localPos, size))) return false;",
            "",
            "    ivec3 v = ivec3(floor(localPos));",
            "    ivec3 isize = ivec3(size);",
            "    int voxelIdx = v.x + isize.x * (v.y + isize.y * v.z);",
            "",
            "    ivec3 sv = ivec3(floor(fract(localPos) * float(VA_SUB)));",
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
