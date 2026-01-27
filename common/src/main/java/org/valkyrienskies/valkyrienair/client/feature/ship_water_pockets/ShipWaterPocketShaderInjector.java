package org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets;

import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Injects external-world water culling support into third-party chunk shaders (Embeddium).
 *
 * <p>Vanilla uses an overridden {@code rendertype_translucent} shader via resources. Embeddium uses its own chunk
 * shaders, so we patch the GLSL source at load time.
 */
public final class ShipWaterPocketShaderInjector {

    private ShipWaterPocketShaderInjector() {}

    private static final Logger LOGGER = LogManager.getLogger("ValkyrienAir ShipWaterCull");

    private static final String INJECT_MARKER = "valkyrienair:ship_water_pocket_cull";
    private static final String INJECT_MARKER_VERTEX = "valkyrienair:ship_water_pocket_cull_vertex";
    private static final String VA_PATCH_APPLIED_MARKER = "VA_PATCH_APPLIED";

    private static final String EMBEDDIUM_VERTEX_OUT_DECL = "\nout vec3 valkyrienair_WorldPos;\n";

    private static final String EMBEDDIUM_VERTEX_MAIN_INJECT = "\n    valkyrienair_WorldPos = position;\n";
    private static final String EMBEDDIUM_VERTEX_MAIN_INJECT_FALLBACK = "\n    valkyrienair_WorldPos = _vert_position + translation;\n";
    private static final String EMBEDDIUM_VERTEX_UNIFORM_CHUNK_WORLD_ORIGIN_DECL = "\nuniform vec3 ValkyrienAir_ChunkWorldOrigin;\n";

    private static final String EMBEDDIUM_FRAGMENT_IN_DECL = "\nin vec3 valkyrienair_WorldPos;\n";

    private static final Pattern GLSL_VERSION_LINE =
        Pattern.compile("(?m)^\\s*#version\\b.*$");
    private static final Pattern GLSL_OUT_ANY_LINE =
        Pattern.compile("(?m)^\\s*out\\s+\\w+\\s+\\w+\\s*;\\s*(?://.*)?$");
    private static final Pattern GLSL_IN_ANY_LINE =
        Pattern.compile("(?m)^\\s*in\\s+\\w+\\s+\\w+\\s*;\\s*(?://.*)?$");
    private static final Pattern GLSL_OUT_FRAGCOLOR_ANY_LINE =
        Pattern.compile("(?m)^\\s*out\\s+vec4\\s+\\w+\\s*;\\s*(?://.*)?$");
    private static final Pattern GLSL_GL_POSITION_LINE =
        Pattern.compile("(?m)^\\s*gl_Position\\s*=.*;\\s*$");
    private static final Pattern GLSL_GL_POSITION_VEC4_ARG =
        Pattern.compile("(?m)^\\s*gl_Position\\s*=.*\\bvec4\\s*\\(\\s*([^,\\)]+?)\\s*,\\s*1\\.0\\s*\\)\\s*;\\s*$");

    private static final Pattern EMBEDDIUM_VSH_OUT_TEXCOORD_LINE =
        Pattern.compile("(?m)^\\s*out\\s+vec2\\s+v_TexCoord\\s*;\\s*(?://.*)?$");
    private static final Pattern EMBEDDIUM_VSH_OUT_COLOR_LINE =
        Pattern.compile("(?m)^\\s*out\\s+vec4\\s+v_Color\\s*;\\s*(?://.*)?$");
    private static final Pattern EMBEDDIUM_VSH_POSITION_LINE =
        Pattern.compile("(?m)^\\s*vec3\\s+position\\s*=\\s*_vert_position\\s*\\+\\s*translation\\s*;\\s*$");
    private static final Pattern EMBEDDIUM_VSH_POSITION_WORLD_LINE =
        Pattern.compile("(?m)^\\s*vec3\\s+position\\s*=\\s*_vert_position\\s*\\+\\s*translation\\s*;\\s*$");
    private static final Pattern EMBEDDIUM_VSH_TRANSLATION_LINE =
        Pattern.compile("(?m)^\\s*vec3\\s+translation\\s*=\\s*.*;\\s*$");
    private static final Pattern EMBEDDIUM_VSH_TRANSLATION_RHS =
        Pattern.compile("(?m)^\\s*vec3\\s+translation\\s*=\\s*(.*?);\\s*$");
    private static final Pattern EMBEDDIUM_VSH_UNIFORM_REGION_OFFSET =
        Pattern.compile("(?m)^\\s*uniform\\s+\\w+\\s+u_RegionOffset\\s*;\\s*(?://.*)?$");
    private static final Pattern EMBEDDIUM_VSH_VERT_INIT_LINE =
        Pattern.compile("(?m)^\\s*_vert_init\\s*\\(\\s*\\)\\s*;\\s*(?://.*)?$");
    private static final Pattern EMBEDDIUM_FSH_IN_TEXCOORD_LINE =
        Pattern.compile("(?m)^\\s*in\\s+vec2\\s+v_TexCoord\\s*;\\s*(?://.*)?$");
    private static final Pattern EMBEDDIUM_FSH_IN_COLOR_LINE =
        Pattern.compile("(?m)^\\s*in\\s+vec4\\s+v_Color\\s*;\\s*(?://.*)?$");
    private static final Pattern EMBEDDIUM_FSH_OUT_FRAGCOLOR_LINE =
        Pattern.compile("(?m)^\\s*out\\s+vec4\\s+fragColor\\s*;\\s*(?://.*)?$");
    private static final Pattern EMBEDDIUM_FSH_FRAGCOLOR_ASSIGN_LINE =
        Pattern.compile("(?m)^\\s*fragColor\\s*=.*;\\s*(?://.*)?$");
    private static final Pattern EMBEDDIUM_MAIN_SIGNATURE =
        Pattern.compile("(?s)\\bvoid\\s+main\\s*\\(\\s*\\)\\s*\\{");

	    private static final String EMBEDDIUM_FRAGMENT_DECLS = """

        // %s
        // %s
        uniform float ValkyrienAir_CullEnabled;
        uniform float ValkyrienAir_IsShipPass;
        uniform vec3 ValkyrienAir_CameraWorldPos;

        uniform vec4 ValkyrienAir_WaterStillUv;
        uniform vec4 ValkyrienAir_WaterFlowUv;
        uniform vec4 ValkyrienAir_WaterOverlayUv;
        uniform float ValkyrienAir_ShipWaterTintEnabled;
        uniform vec3 ValkyrienAir_ShipWaterTint;

	        uniform vec4 ValkyrienAir_ShipAabbMin0;
	        uniform vec4 ValkyrienAir_ShipAabbMax0;
	        uniform vec4 ValkyrienAir_GridSize0;
            uniform vec4 ValkyrienAir_CullFluidStillUv0;
            uniform vec4 ValkyrienAir_CullFluidFlowUv0;
            uniform vec4 ValkyrienAir_CullFluidOverlayUv0;
	        uniform mat4 ValkyrienAir_WorldToShip0;
	        uniform usampler2D ValkyrienAir_AirMask0;
	        uniform usampler2D ValkyrienAir_OccMask0;

	        uniform vec4 ValkyrienAir_ShipAabbMin1;
	        uniform vec4 ValkyrienAir_ShipAabbMax1;
	        uniform vec4 ValkyrienAir_GridSize1;
            uniform vec4 ValkyrienAir_CullFluidStillUv1;
            uniform vec4 ValkyrienAir_CullFluidFlowUv1;
            uniform vec4 ValkyrienAir_CullFluidOverlayUv1;
	        uniform mat4 ValkyrienAir_WorldToShip1;
	        uniform usampler2D ValkyrienAir_AirMask1;
	        uniform usampler2D ValkyrienAir_OccMask1;

	        uniform vec4 ValkyrienAir_ShipAabbMin2;
	        uniform vec4 ValkyrienAir_ShipAabbMax2;
	        uniform vec4 ValkyrienAir_GridSize2;
            uniform vec4 ValkyrienAir_CullFluidStillUv2;
            uniform vec4 ValkyrienAir_CullFluidFlowUv2;
            uniform vec4 ValkyrienAir_CullFluidOverlayUv2;
	        uniform mat4 ValkyrienAir_WorldToShip2;
	        uniform usampler2D ValkyrienAir_AirMask2;
	        uniform usampler2D ValkyrienAir_OccMask2;

	        uniform vec4 ValkyrienAir_ShipAabbMin3;
	        uniform vec4 ValkyrienAir_ShipAabbMax3;
	        uniform vec4 ValkyrienAir_GridSize3;
            uniform vec4 ValkyrienAir_CullFluidStillUv3;
            uniform vec4 ValkyrienAir_CullFluidFlowUv3;
            uniform vec4 ValkyrienAir_CullFluidOverlayUv3;
	        uniform mat4 ValkyrienAir_WorldToShip3;
	        uniform usampler2D ValkyrienAir_AirMask3;
	        uniform usampler2D ValkyrienAir_OccMask3;

        const int VA_MASK_TEX_WIDTH_SHIFT = 12;
        const int VA_MASK_TEX_WIDTH_MASK = (1 << VA_MASK_TEX_WIDTH_SHIFT) - 1;

        const int VA_SUB = 8;
        const int VA_OCC_WORDS_PER_VOXEL = 16;
        const float VA_WORLD_SAMPLE_EPS = 0.0001;

        bool va_inUv(vec2 uv, vec4 bounds) {
            return uv.x >= bounds.x && uv.x <= bounds.z && uv.y >= bounds.y && uv.y <= bounds.w;
        }

        bool va_isWaterUv(vec2 uv) {
            return va_inUv(uv, ValkyrienAir_WaterStillUv) ||
                va_inUv(uv, ValkyrienAir_WaterFlowUv) ||
                va_inUv(uv, ValkyrienAir_WaterOverlayUv);
        }

        bool va_isCullFluidUv(vec2 uv, vec4 stillUv, vec4 flowUv, vec4 overlayUv) {
            return va_inUv(uv, stillUv) || va_inUv(uv, flowUv) || va_inUv(uv, overlayUv);
        }

        bool va_isAnyCullFluidUv(vec2 uv) {
            return
                va_isCullFluidUv(uv, ValkyrienAir_CullFluidStillUv0, ValkyrienAir_CullFluidFlowUv0, ValkyrienAir_CullFluidOverlayUv0) ||
                va_isCullFluidUv(uv, ValkyrienAir_CullFluidStillUv1, ValkyrienAir_CullFluidFlowUv1, ValkyrienAir_CullFluidOverlayUv1) ||
                va_isCullFluidUv(uv, ValkyrienAir_CullFluidStillUv2, ValkyrienAir_CullFluidFlowUv2, ValkyrienAir_CullFluidOverlayUv2) ||
                va_isCullFluidUv(uv, ValkyrienAir_CullFluidStillUv3, ValkyrienAir_CullFluidFlowUv3, ValkyrienAir_CullFluidOverlayUv3);
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

	        bool va_testOcc(usampler2D occMask, int voxelIdx, int subIdx) {
	            int wordIndex = voxelIdx * VA_OCC_WORDS_PER_VOXEL + (subIdx >> 5);
	            int bit = subIdx & 31;
	            uint word = va_fetchWord(occMask, wordIndex);
	            return ((word >> uint(bit)) & 1u) != 0u;
	        }

	        bool va_shouldDiscardForShip0(vec3 worldPos) {
                if (!va_isCullFluidUv(v_TexCoord, ValkyrienAir_CullFluidStillUv0, ValkyrienAir_CullFluidFlowUv0, ValkyrienAir_CullFluidOverlayUv0)) return false;
	            if (ValkyrienAir_GridSize0.x <= 0.0) return false;
	            if (worldPos.x < ValkyrienAir_ShipAabbMin0.x || worldPos.x > ValkyrienAir_ShipAabbMax0.x) return false;
	            if (worldPos.y < ValkyrienAir_ShipAabbMin0.y || worldPos.y > ValkyrienAir_ShipAabbMax0.y) return false;
	            if (worldPos.z < ValkyrienAir_ShipAabbMin0.z || worldPos.z > ValkyrienAir_ShipAabbMax0.z) return false;

	            // Embeddium path: compute ship-space from world-space directly. Java uploads WorldToShip pre-biased by
	            // -GridMin, so this yields local grid coordinates directly (GridMin == 0 in practice).
	            vec3 shipPos = (ValkyrienAir_WorldToShip0 * vec4(worldPos, 1.0)).xyz;
	            vec3 localPos = shipPos;
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
                if (!va_isCullFluidUv(v_TexCoord, ValkyrienAir_CullFluidStillUv1, ValkyrienAir_CullFluidFlowUv1, ValkyrienAir_CullFluidOverlayUv1)) return false;
	            if (ValkyrienAir_GridSize1.x <= 0.0) return false;
	            if (worldPos.x < ValkyrienAir_ShipAabbMin1.x || worldPos.x > ValkyrienAir_ShipAabbMax1.x) return false;
	            if (worldPos.y < ValkyrienAir_ShipAabbMin1.y || worldPos.y > ValkyrienAir_ShipAabbMax1.y) return false;
	            if (worldPos.z < ValkyrienAir_ShipAabbMin1.z || worldPos.z > ValkyrienAir_ShipAabbMax1.z) return false;

	            vec3 shipPos = (ValkyrienAir_WorldToShip1 * vec4(worldPos, 1.0)).xyz;
	            vec3 localPos = shipPos;
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
                if (!va_isCullFluidUv(v_TexCoord, ValkyrienAir_CullFluidStillUv2, ValkyrienAir_CullFluidFlowUv2, ValkyrienAir_CullFluidOverlayUv2)) return false;
	            if (ValkyrienAir_GridSize2.x <= 0.0) return false;
	            if (worldPos.x < ValkyrienAir_ShipAabbMin2.x || worldPos.x > ValkyrienAir_ShipAabbMax2.x) return false;
	            if (worldPos.y < ValkyrienAir_ShipAabbMin2.y || worldPos.y > ValkyrienAir_ShipAabbMax2.y) return false;
	            if (worldPos.z < ValkyrienAir_ShipAabbMin2.z || worldPos.z > ValkyrienAir_ShipAabbMax2.z) return false;

	            vec3 shipPos = (ValkyrienAir_WorldToShip2 * vec4(worldPos, 1.0)).xyz;
	            vec3 localPos = shipPos;
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
                if (!va_isCullFluidUv(v_TexCoord, ValkyrienAir_CullFluidStillUv3, ValkyrienAir_CullFluidFlowUv3, ValkyrienAir_CullFluidOverlayUv3)) return false;
	            if (ValkyrienAir_GridSize3.x <= 0.0) return false;
	            if (worldPos.x < ValkyrienAir_ShipAabbMin3.x || worldPos.x > ValkyrienAir_ShipAabbMax3.x) return false;
	            if (worldPos.y < ValkyrienAir_ShipAabbMin3.y || worldPos.y > ValkyrienAir_ShipAabbMax3.y) return false;
	            if (worldPos.z < ValkyrienAir_ShipAabbMin3.z || worldPos.z > ValkyrienAir_ShipAabbMax3.z) return false;

	            vec3 shipPos = (ValkyrienAir_WorldToShip3 * vec4(worldPos, 1.0)).xyz;
	            vec3 localPos = shipPos;
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
	        """.formatted(INJECT_MARKER, VA_PATCH_APPLIED_MARKER);

        private static final String EMBEDDIUM_FRAGMENT_TINT_INJECT = """
                if (ValkyrienAir_ShipWaterTintEnabled > 0.5 && va_isWaterUv(v_TexCoord)) {
                    fragColor.rgb *= ValkyrienAir_ShipWaterTint;
                }

            """;

	    private static final String EMBEDDIUM_FRAGMENT_MAIN_INJECT = """
	            if (ValkyrienAir_CullEnabled > 0.5 && ValkyrienAir_IsShipPass < 0.5 && va_isAnyCullFluidUv(v_TexCoord)) {
	                // Sample slightly inside the water volume (below the surface) so we test the water block itself.
	                vec3 camRelPos = valkyrienair_WorldPos + vec3(0.0, -VA_WORLD_SAMPLE_EPS, 0.0);
	                vec3 worldPos = camRelPos + ValkyrienAir_CameraWorldPos;
	                if (va_shouldDiscardForShip0(worldPos) || va_shouldDiscardForShip1(worldPos) ||
	                    va_shouldDiscardForShip2(worldPos) || va_shouldDiscardForShip3(worldPos)) {
	                    discard;
	                }
	            }

	        """;

    private static boolean loggedEmbeddiumVertexPatchFailed = false;
    private static boolean loggedEmbeddiumFragmentPatchFailed = false;

    private static boolean isEmbeddiumBlockLayerShader(final String identifierPath, final String extension) {
        if (identifierPath == null) return false;
        if (!identifierPath.endsWith(extension)) return false;

        // Normalize older "shaders/blocks/..." identifiers to "blocks/...".
        String path = identifierPath;
        final int shadersIdx = path.indexOf("shaders/");
        if (shadersIdx >= 0) {
            path = path.substring(shadersIdx + "shaders/".length());
        }
        // Keep existing block_layer_* coverage, plus a small allow-list for known Embeddium chunk-shader families.
        return path.startsWith("blocks/block_layer_") || path.startsWith("blocks/fluid_layer_");
    }

    public static String injectSodiumShader(final ResourceLocation identifier, final String source) {
        if (source == null) return null;
        if (source.contains(INJECT_MARKER) || source.contains("ValkyrienAir_CullEnabled") || source.contains("valkyrienair_WorldPos")) {
            return source;
        }
        if (identifier == null) return source;

        final String path = identifier.getPath();
        if (path == null) return source;

        // Accept both modern ("blocks/...") and older ("shaders/blocks/...") Embeddium shader identifiers.
        if (isEmbeddiumBlockLayerShader(path, ".vsh")) {
            final String patched = injectEmbeddiumVertexShader(source);
            return patched;
        }
        if (isEmbeddiumBlockLayerShader(path, ".fsh")) {
            final String patched = injectEmbeddiumFragmentShader(source);
            return patched;
        }

        return source;
    }

    private static String injectEmbeddiumVertexShader(final String source) {
        String out = source;

        if (!out.contains(VA_PATCH_APPLIED_MARKER)) {
            out = insertAfterFirstRegexLine(out, GLSL_VERSION_LINE, "\n// " + VA_PATCH_APPLIED_MARKER + "\n");
        }

        // The injection points here must tolerate indentation and line-ending differences across Embeddium builds.
        out = insertAfterFirstRegexLine(out, EMBEDDIUM_VSH_OUT_TEXCOORD_LINE, EMBEDDIUM_VERTEX_OUT_DECL);
        if (!out.contains("out vec3 valkyrienair_WorldPos;")) {
            out = insertAfterFirstRegexLine(out, EMBEDDIUM_VSH_OUT_COLOR_LINE, EMBEDDIUM_VERTEX_OUT_DECL);
        }
        if (!out.contains("out vec3 valkyrienair_WorldPos;")) {
            out = insertAfterFirstRegexLine(out, GLSL_OUT_ANY_LINE, EMBEDDIUM_VERTEX_OUT_DECL);
        }
        if (!out.contains("out vec3 valkyrienair_WorldPos;")) {
            out = insertAfterFirstRegexLine(out, GLSL_VERSION_LINE, "\n// " + INJECT_MARKER_VERTEX + "\n" + EMBEDDIUM_VERTEX_OUT_DECL);
        }

        if (!out.contains("valkyrienair_WorldPos =")) {
            final boolean hasRegionOffsetUniform = EMBEDDIUM_VSH_UNIFORM_REGION_OFFSET.matcher(out).find();
            if (hasRegionOffsetUniform) {
                final String translationRhs = findFirstRegexLineGroup(out, EMBEDDIUM_VSH_TRANSLATION_RHS, 1);
                final boolean translationIncludesRegionOffset =
                    translationRhs != null && translationRhs.contains("u_RegionOffset");

                final String injection;
                if (translationIncludesRegionOffset) {
                    injection = "\n    valkyrienair_WorldPos = _vert_position + translation;\n";
                } else {
                    injection = "\n    valkyrienair_WorldPos = position + vec3(u_RegionOffset);\n";
                }
                out = insertAfterFirstRegexLine(out, EMBEDDIUM_VSH_POSITION_LINE, injection);
            }
        }

        if (!out.contains("valkyrienair_WorldPos =")) {
            // Fallback: use translation if present (may be world or camera-relative depending on shader variant).
            out = insertAfterFirstRegexLine(out, EMBEDDIUM_VSH_TRANSLATION_LINE,
                "\n    valkyrienair_WorldPos = _vert_position + translation;\n");
        }

        if (!out.contains("valkyrienair_WorldPos =")) {
            // Last resort: introduce an explicit world-origin addend we can upload from Java.
            if (!out.contains("uniform vec3 ValkyrienAir_ChunkWorldOrigin;")) {
                out = insertAfterFirstRegexLine(out, GLSL_VERSION_LINE, EMBEDDIUM_VERTEX_UNIFORM_CHUNK_WORLD_ORIGIN_DECL);
            }
            out = insertAfterFirstRegexLine(out, EMBEDDIUM_VSH_VERT_INIT_LINE,
                "\n    valkyrienair_WorldPos = _vert_position + ValkyrienAir_ChunkWorldOrigin;\n");
            if (!out.contains("valkyrienair_WorldPos =")) {
                out = insertAfterFirstRegexLine(out, EMBEDDIUM_MAIN_SIGNATURE,
                    "\n    valkyrienair_WorldPos = _vert_position + ValkyrienAir_ChunkWorldOrigin;\n");
            }
        }

        if (!out.contains("out vec3 valkyrienair_WorldPos;") || !out.contains("valkyrienair_WorldPos =")) {
            if (!loggedEmbeddiumVertexPatchFailed) {
                loggedEmbeddiumVertexPatchFailed = true;
                LOGGER.warn("Failed to fully patch Embeddium vertex shader for ship water culling; WorldPos output may be invalid");
            }
        }

        return out;
    }

    private static String injectEmbeddiumFragmentShader(final String source) {
        String out = source;

        if (!out.contains(VA_PATCH_APPLIED_MARKER)) {
            out = insertAfterFirstRegexLine(out, GLSL_VERSION_LINE, "\n// " + VA_PATCH_APPLIED_MARKER + "\n");
        }

        out = insertAfterFirstRegexLine(out, EMBEDDIUM_FSH_IN_TEXCOORD_LINE, EMBEDDIUM_FRAGMENT_IN_DECL);
        if (!out.contains("in vec3 valkyrienair_WorldPos;")) {
            out = insertAfterFirstRegexLine(out, EMBEDDIUM_FSH_IN_COLOR_LINE, EMBEDDIUM_FRAGMENT_IN_DECL);
        }
        if (!out.contains("in vec3 valkyrienair_WorldPos;")) {
            out = insertAfterFirstRegexLine(out, GLSL_IN_ANY_LINE, EMBEDDIUM_FRAGMENT_IN_DECL);
        }

        // Insert declarations/helpers before main.
        out = insertAfterFirstRegexLine(out, EMBEDDIUM_FSH_OUT_FRAGCOLOR_LINE, "\n" + EMBEDDIUM_FRAGMENT_DECLS + "\n");
        if (!out.contains(INJECT_MARKER)) {
            out = insertAfterFirstRegexLine(out, GLSL_OUT_FRAGCOLOR_ANY_LINE, "\n" + EMBEDDIUM_FRAGMENT_DECLS + "\n");
        }
        if (!out.contains(INJECT_MARKER)) {
            // Last resort: insert just before main().
            out = insertBeforeFirstRegex(out, EMBEDDIUM_MAIN_SIGNATURE, "\n" + EMBEDDIUM_FRAGMENT_DECLS + "\n");
        }

        // Inject cull check at the top of main().
        out = insertAfterFirstRegex(out, EMBEDDIUM_MAIN_SIGNATURE, EMBEDDIUM_FRAGMENT_MAIN_INJECT);

        // Inject water tint after the fragment color is assigned.
        out = insertAfterFirstRegexLine(out, EMBEDDIUM_FSH_FRAGCOLOR_ASSIGN_LINE, EMBEDDIUM_FRAGMENT_TINT_INJECT);

        if (!out.contains(INJECT_MARKER) || !out.contains("in vec3 valkyrienair_WorldPos;")) {
            if (!loggedEmbeddiumFragmentPatchFailed) {
                loggedEmbeddiumFragmentPatchFailed = true;
                LOGGER.warn("Failed to fully patch Embeddium fragment shader for ship water culling; culling may be inactive");
            }
        }

        return out;
    }

    private static String insertAfterFirstLine(final String source, final String lineStart, final String insert) {
        if (source == null || insert == null) return source;
        final int idx = source.indexOf(lineStart);
        if (idx < 0) return source;
        final int end = source.indexOf('\n', idx);
        if (end < 0) return source;
        return source.substring(0, end + 1) + insert + source.substring(end + 1);
    }

    private static String insertAfterFirst(final String source, final String needle, final String insert) {
        if (source == null || needle == null || needle.isEmpty() || insert == null) return source;
        final int idx = source.indexOf(needle);
        if (idx < 0) return source;
        return source.substring(0, idx + needle.length()) + insert + source.substring(idx + needle.length());
    }

    private static String insertAfterFirstRegexLine(final String source, final Pattern linePattern, final String insert) {
        if (source == null || linePattern == null || insert == null) return source;
        final Matcher m = linePattern.matcher(source);
        if (!m.find()) return source;

        int pos = m.end();
        if (pos < source.length()) {
            final char c = source.charAt(pos);
            if (c == '\r') {
                pos++;
                if (pos < source.length() && source.charAt(pos) == '\n') pos++;
            } else if (c == '\n') {
                pos++;
            }
        }

        return source.substring(0, pos) + insert + source.substring(pos);
    }

    private static String insertAfterFirstRegex(final String source, final Pattern pattern, final String insert) {
        if (source == null || pattern == null || insert == null) return source;
        final Matcher m = pattern.matcher(source);
        if (!m.find()) return source;
        final int pos = m.end();
        return source.substring(0, pos) + insert + source.substring(pos);
    }

    private static String insertBeforeFirstRegex(final String source, final Pattern pattern, final String insert) {
        if (source == null || pattern == null || insert == null) return source;
        final Matcher m = pattern.matcher(source);
        if (!m.find()) return source;
        final int pos = m.start();
        return source.substring(0, pos) + insert + source.substring(pos);
    }

    private static String findFirstRegexLineGroup(final String source, final Pattern linePattern, final int group) {
        if (source == null || linePattern == null) return null;
        final Matcher m = linePattern.matcher(source);
        if (!m.find()) return null;
        try {
            return m.group(group);
        } catch (final Exception ignored) {
            return null;
        }
    }
}
