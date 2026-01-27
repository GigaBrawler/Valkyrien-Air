package org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.shaders.Uniform;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.primitives.AABBdc;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.LoadedShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.valkyrienair.config.ValkyrienAirConfig;
import org.valkyrienskies.valkyrienair.feature.ship_water_pockets.ShipWaterPocketManager;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Updates uniforms/samplers for the patched {@code rendertype_translucent} shader to cull *world* fluid surfaces inside
 * ship interiors (air pockets) without affecting ship-rendered fluids.
 */
public final class ShipWaterPocketExternalWaterCull {

    private ShipWaterPocketExternalWaterCull() {}

    private static final Logger LOGGER = LogManager.getLogger("ValkyrienAir ShipWaterCull");

    private static final int MAX_SHIPS = 4;
    private static final int SUB = 8;
    private static final int OCC_WORDS_PER_VOXEL = (SUB * SUB * SUB) / 32; // 512 bits / 32 = 16

    // Must match shader constants (power-of-two).
    private static final int MASK_TEX_WIDTH = 4096;
    private static final int MASK_TEX_WIDTH_MASK = MASK_TEX_WIDTH - 1;
    private static final int MASK_TEX_WIDTH_SHIFT = 12;

    // Texture units 0/1 are used by Embeddium chunk shaders (block + light).
    //
    // Use 2 (right after block/light). We still clamp to the GlStateManager-tracked texture unit range to avoid
    // crashes (see GLSTATEMANAGER_SAFE_TEXTURE_UNITS).
    private static final int BASE_MASK_TEX_UNIT = 2;
    // Minecraft/GlStateManager in 1.20.1 tracks a fixed small texture-unit array; exceeding it can crash (see AIOOBE in
    // GlStateManager._bindTexture). Keep our internal usage within this cap.
    private static final int GLSTATEMANAGER_SAFE_TEXTURE_UNITS = 12;

    private static final ResourceLocation WATER_STILL = new ResourceLocation("minecraft", "block/water_still");
    private static final ResourceLocation WATER_FLOW = new ResourceLocation("minecraft", "block/water_flow");
    private static final ResourceLocation WATER_OVERLAY = new ResourceLocation("minecraft", "block/water_overlay");

    private static final ResourceLocation LAVA_STILL = new ResourceLocation("minecraft", "block/lava_still");
    private static final ResourceLocation LAVA_FLOW = new ResourceLocation("minecraft", "block/lava_flow");

    private static final float INVALID_U0 = 1.0f;
    private static final float INVALID_V0 = 1.0f;
    private static final float INVALID_U1 = 0.0f;
    private static final float INVALID_V1 = 0.0f;

    private static final class CullFluidUvBounds {
        private final float stillU0;
        private final float stillV0;
        private final float stillU1;
        private final float stillV1;
        private final float flowU0;
        private final float flowV0;
        private final float flowU1;
        private final float flowV1;
        private final float overlayU0;
        private final float overlayV0;
        private final float overlayU1;
        private final float overlayV1;

        private CullFluidUvBounds(
            final float stillU0, final float stillV0, final float stillU1, final float stillV1,
            final float flowU0, final float flowV0, final float flowU1, final float flowV1,
            final float overlayU0, final float overlayV0, final float overlayU1, final float overlayV1
        ) {
            this.stillU0 = stillU0;
            this.stillV0 = stillV0;
            this.stillU1 = stillU1;
            this.stillV1 = stillV1;
            this.flowU0 = flowU0;
            this.flowV0 = flowV0;
            this.flowU1 = flowU1;
            this.flowV1 = flowV1;
            this.overlayU0 = overlayU0;
            this.overlayV0 = overlayV0;
            this.overlayU1 = overlayU1;
            this.overlayV1 = overlayV1;
        }
    }

    private static final CullFluidUvBounds INVALID_CULL_FLUID_UVS =
        new CullFluidUvBounds(
            INVALID_U0, INVALID_V0, INVALID_U1, INVALID_V1,
            INVALID_U0, INVALID_V0, INVALID_U1, INVALID_V1,
            INVALID_U0, INVALID_V0, INVALID_U1, INVALID_V1
        );

    private static final Map<Fluid, CullFluidUvBounds> CULL_FLUID_UV_CACHE = new HashMap<>();
    private static int cullFluidUvLastAtlasTexId = 0;
    private static boolean loggedCullFluidUvLookupFailed = false;

    private static boolean forgeFluidTexturesChecked = false;
    private static Method forgeFluidExtOf = null;
    private static Method forgeGetStill0 = null;
    private static Method forgeGetFlow0 = null;
    private static Method forgeGetOverlay0 = null;
    private static Method forgeGetStill3 = null;
    private static Method forgeGetFlow3 = null;
    private static Method forgeGetOverlay3 = null;

    private static boolean fabricFluidTexturesChecked = false;
    private static Object fabricFluidRenderHandlerRegistry = null;
    private static Method fabricRegistryGetHandler = null;
    private static Method fabricHandlerGetSprites = null;

    private static final Matrix4f IDENTITY_MAT4 = new Matrix4f();

    private static ClientLevel lastLevel = null;

    private static final class ShipMasks {
        private final long shipId;
        private long geometryRevision;

        private int minX;
        private int minY;
        private int minZ;
        private int sizeX;
        private int sizeY;
        private int sizeZ;

        private int occTexId;
        private int occTexHeight;

        private int airTexId;
        private int airTexHeight;
        private long lastAirUploadKey = Long.MIN_VALUE;

        private final Matrix4f worldToShip = new Matrix4f();

        private int[] occData;
        private IntBuffer occBuffer;

        private int[] airData;
        private IntBuffer airBuffer;

        private ShipMasks(final long shipId) {
            this.shipId = shipId;
        }

        private void close() {
            if (occTexId != 0) {
                TextureUtil.releaseTextureId(occTexId);
                occTexId = 0;
            }
            if (airTexId != 0) {
                TextureUtil.releaseTextureId(airTexId);
                airTexId = 0;
            }
        }
    }

    private static final Map<Long, ShipMasks> SHIP_MASKS = new HashMap<>();

    private static final class ProgramHandles {
        private final int programId;
        private boolean supported = false;
        private boolean embeddiumChunkProgram = false;

        private int regionOffsetLoc = -1;
        private int blockTexLoc = -1;

        private int cullEnabledLoc = -1;
        private int isShipPassLoc = -1;
        private int cameraWorldPosLoc = -1;
        private int waterStillUvLoc = -1;
        private int waterFlowUvLoc = -1;
        private int waterOverlayUvLoc = -1;
        private int shipWaterTintEnabledLoc = -1;
        private int shipWaterTintLoc = -1;
        private int chunkWorldOriginLoc = -1;

        private int maxMaskSlots = MAX_SHIPS;
        private int maxSafeTextureUnits = GLSTATEMANAGER_SAFE_TEXTURE_UNITS;

        private final int[] shipAabbMinLoc = new int[MAX_SHIPS];
        private final int[] shipAabbMaxLoc = new int[MAX_SHIPS];
        private final int[] cameraShipPosLoc = new int[MAX_SHIPS];
        private final int[] gridMinLoc = new int[MAX_SHIPS];
        private final int[] gridSizeLoc = new int[MAX_SHIPS];
        private final int[] cullFluidStillUvLoc = new int[MAX_SHIPS];
        private final int[] cullFluidFlowUvLoc = new int[MAX_SHIPS];
        private final int[] cullFluidOverlayUvLoc = new int[MAX_SHIPS];
        private final int[] worldToShipLoc = new int[MAX_SHIPS];
        private final int[] airMaskLoc = new int[MAX_SHIPS];
        private final int[] occMaskLoc = new int[MAX_SHIPS];
        private final boolean[] shipSlotSupported = new boolean[MAX_SHIPS];

        private ProgramHandles(final int programId) {
            this.programId = programId;
        }
    }

    private static final Int2ObjectOpenHashMap<ProgramHandles> PROGRAM_HANDLES = new Int2ObjectOpenHashMap<>();
    private static boolean programEverSupported = false;
    private static final ThreadLocal<FloatBuffer> MATRIX_BUFFER = ThreadLocal.withInitial(() -> BufferUtils.createFloatBuffer(16));

    private static final class ShaderHandles {
        private ShaderInstance shader;
        private boolean supported;

        private Uniform cullEnabled;
        private Uniform isShipPass;
        private Uniform cameraWorldPos;
        private Uniform waterStillUv;
        private Uniform waterFlowUv;
        private Uniform waterOverlayUv;
        private Uniform shipWaterTintEnabled;
        private Uniform shipWaterTint;

        private final Uniform[] shipAabbMin = new Uniform[MAX_SHIPS];
        private final Uniform[] shipAabbMax = new Uniform[MAX_SHIPS];
        private final Uniform[] cameraShipPos = new Uniform[MAX_SHIPS];
        private final Uniform[] gridMin = new Uniform[MAX_SHIPS];
        private final Uniform[] gridSize = new Uniform[MAX_SHIPS];
        private final Uniform[] cullFluidStillUv = new Uniform[MAX_SHIPS];
        private final Uniform[] cullFluidFlowUv = new Uniform[MAX_SHIPS];
        private final Uniform[] cullFluidOverlayUv = new Uniform[MAX_SHIPS];
        private final Uniform[] worldToShip = new Uniform[MAX_SHIPS];
    }

    private static final ShaderHandles SHADER = new ShaderHandles();

    private static boolean everEnabled = false;
    private static boolean shaderEverSupported = false;
    private static boolean loggedEmbeddiumProgramMissingUniforms = false;

    public static void clear() {
        SHADER.shader = null;
        SHADER.supported = false;

        everEnabled = false;
        shaderEverSupported = false;
        programEverSupported = false;
        PROGRAM_HANDLES.clear();

        for (final ShipMasks masks : SHIP_MASKS.values()) {
            masks.close();
        }
        SHIP_MASKS.clear();

        CULL_FLUID_UV_CACHE.clear();
        cullFluidUvLastAtlasTexId = 0;
        loggedCullFluidUvLookupFailed = false;

        lastLevel = null;
    }

    public static boolean isShaderCullingActive() {
        if (!ValkyrienAirConfig.getEnableShipWaterPockets()) return false;
        return (shaderEverSupported || programEverSupported) && everEnabled;
    }

    public static void setupForWorldTranslucentPass(final ShaderInstance shader, final ClientLevel level, final Camera camera) {
        if (level == null || camera == null) return;
        final Vec3 cameraPos = camera.getPosition();
        setupForWorldTranslucentPass(shader, level, cameraPos.x, cameraPos.y, cameraPos.z);
    }

    public static void setupForWorldTranslucentPass(final ShaderInstance shader, final ClientLevel level,
        final double cameraX, final double cameraY, final double cameraZ) {
        if (level == null) return;

        RenderSystem.assertOnRenderThread();

        if (lastLevel != level) {
            clear();
            lastLevel = level;
        }

        bindShaderHandles(shader);
        if (!SHADER.supported) return;

        if (!ValkyrienAirConfig.getEnableShipWaterPockets()) {
            disable(shader);
            return;
        }

        SHADER.cullEnabled.set(1.0f);
        SHADER.cullEnabled.upload();
        everEnabled = true;

        setShipPass(shader, false);

        final Vec3 cameraPos = new Vec3(cameraX, cameraY, cameraZ);
        updateCameraAndWaterUv(cameraPos);

        final List<LoadedShip> ships = selectClosestShips(level, cameraPos, MAX_SHIPS);
        updateShipUniformsAndMasks(level, ships, cameraX, cameraY, cameraZ);
    }

    public static void setupForWorldTranslucentPassProgram(final int programId, final ClientLevel level,
        final double cameraX, final double cameraY, final double cameraZ) {
        if (programId == 0 || level == null) return;

        RenderSystem.assertOnRenderThread();

        if (lastLevel != level) {
            clear();
            lastLevel = level;
        }

        final ProgramHandles handles = bindProgramHandles(programId);
        if (handles == null || !handles.supported) {
            if (!loggedEmbeddiumProgramMissingUniforms && handles != null && handles.embeddiumChunkProgram &&
                ValkyrienAirConfig.getEnableShipWaterPockets()) {
                loggedEmbeddiumProgramMissingUniforms = true;
                if (handles.cullEnabledLoc < 0) {
                    LOGGER.warn("Embeddium chunk shader program {} is missing ValkyrienAir uniforms; water culling is inactive (shader injection not applied?)",
                        programId);
                } else {
                    LOGGER.warn("Embeddium chunk shader program {} has incomplete ValkyrienAir uniforms; water culling is inactive",
                        programId);
                }
            }
            return;
        }

        if (!ValkyrienAirConfig.getEnableShipWaterPockets()) {
            disableProgram(programId);
            return;
        }

        if (handles.cullEnabledLoc >= 0) {
            GL20.glUniform1f(handles.cullEnabledLoc, 1.0f);
        }
        everEnabled = true;

        // Default to world rendering. The caller is expected to update this via setShipPassProgram().
        if (handles.isShipPassLoc >= 0) {
            GL20.glUniform1f(handles.isShipPassLoc, 0.0f);
        }

        final Vec3 cameraPos = new Vec3(cameraX, cameraY, cameraZ);
        if (handles.chunkWorldOriginLoc >= 0) {
            GL20.glUniform3f(handles.chunkWorldOriginLoc, (float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z);
        }
        updateCameraAndWaterUvProgram(handles, cameraPos);

        final List<LoadedShip> ships = selectClosestShips(level, cameraPos, MAX_SHIPS);
        updateShipUniformsAndMasksProgram(handles, level, ships, cameraX, cameraY, cameraZ);
    }

    public static void disableProgram(final int programId) {
        if (programId == 0) return;
        RenderSystem.assertOnRenderThread();

        final ProgramHandles handles = bindProgramHandles(programId);
        if (handles == null) return;

        if (handles.cullEnabledLoc >= 0) {
            GL20.glUniform1f(handles.cullEnabledLoc, 0.0f);
        }
        if (handles.isShipPassLoc >= 0) {
            GL20.glUniform1f(handles.isShipPassLoc, 0.0f);
        }
        if (handles.shipWaterTintEnabledLoc >= 0) {
            GL20.glUniform1f(handles.shipWaterTintEnabledLoc, 0.0f);
        }
        if (handles.shipWaterTintLoc >= 0) {
            GL20.glUniform3f(handles.shipWaterTintLoc, 1.0f, 1.0f, 1.0f);
        }
    }

    public static void setShipPassProgram(final int programId, final boolean shipPass) {
        if (programId == 0) return;
        RenderSystem.assertOnRenderThread();

        final ProgramHandles handles = bindProgramHandles(programId);
        if (handles == null || handles.isShipPassLoc < 0) return;

        if (handles.isShipPassLoc >= 0) {
            GL20.glUniform1f(handles.isShipPassLoc, shipPass ? 1.0f : 0.0f);
        }
    }

    public static void disable(final ShaderInstance shader) {
        if (shader == null) return;
        RenderSystem.assertOnRenderThread();

        bindShaderHandles(shader);
        if (!SHADER.supported || SHADER.cullEnabled == null) return;

        SHADER.cullEnabled.set(0.0f);
        SHADER.cullEnabled.upload();

        if (SHADER.isShipPass != null) {
            SHADER.isShipPass.set(0.0f);
            SHADER.isShipPass.upload();
        }

        if (SHADER.shipWaterTintEnabled != null) {
            SHADER.shipWaterTintEnabled.set(0.0f);
            SHADER.shipWaterTintEnabled.upload();
        }
        if (SHADER.shipWaterTint != null) {
            SHADER.shipWaterTint.set(1.0f, 1.0f, 1.0f);
            SHADER.shipWaterTint.upload();
        }
    }

    public static void setShipPass(final ShaderInstance shader, final boolean shipPass) {
        if (shader == null) return;
        if (!SHIP_MASKS.isEmpty()) {
            // Ensure handles are bound if someone calls setShipPass() before setup.
            bindShaderHandles(shader);
        }
        if (!SHADER.supported || SHADER.isShipPass == null) return;

        SHADER.isShipPass.set(shipPass ? 1.0f : 0.0f);
        SHADER.isShipPass.upload();
    }

    public static void setShipWaterTintEnabled(final ShaderInstance shader, final boolean enabled) {
        if (shader == null) return;
        if (!SHIP_MASKS.isEmpty()) {
            bindShaderHandles(shader);
        }
        if (!SHADER.supported || SHADER.shipWaterTintEnabled == null) return;

        SHADER.shipWaterTintEnabled.set(enabled ? 1.0f : 0.0f);
        SHADER.shipWaterTintEnabled.upload();
    }

    public static void setShipWaterTint(final ShaderInstance shader, final int rgb) {
        if (shader == null) return;
        if (!SHIP_MASKS.isEmpty()) {
            bindShaderHandles(shader);
        }
        if (!SHADER.supported || SHADER.shipWaterTint == null) return;

        final float r = ((rgb >> 16) & 0xFF) / 255.0f;
        final float g = ((rgb >> 8) & 0xFF) / 255.0f;
        final float b = (rgb & 0xFF) / 255.0f;
        SHADER.shipWaterTint.set(r, g, b);
        SHADER.shipWaterTint.upload();
    }

    public static void setShipWaterTintEnabledProgram(final int programId, final boolean enabled) {
        if (programId == 0) return;
        RenderSystem.assertOnRenderThread();

        final ProgramHandles handles = bindProgramHandles(programId);
        if (handles == null || handles.shipWaterTintEnabledLoc < 0) return;
        GL20.glUniform1f(handles.shipWaterTintEnabledLoc, enabled ? 1.0f : 0.0f);
    }

    public static void setShipWaterTintProgram(final int programId, final int rgb) {
        if (programId == 0) return;
        RenderSystem.assertOnRenderThread();

        final ProgramHandles handles = bindProgramHandles(programId);
        if (handles == null || handles.shipWaterTintLoc < 0) return;

        final float r = ((rgb >> 16) & 0xFF) / 255.0f;
        final float g = ((rgb >> 8) & 0xFF) / 255.0f;
        final float b = (rgb & 0xFF) / 255.0f;
        GL20.glUniform3f(handles.shipWaterTintLoc, r, g, b);
    }

    private static void bindShaderHandles(final ShaderInstance shader) {
        if (shader == null) {
            SHADER.shader = null;
            SHADER.supported = false;
            return;
        }
        if (SHADER.shader == shader) return;

        SHADER.shader = shader;
        SHADER.supported = false;

        // Detect whether the rendertype_translucent shader has been patched by checking for our enable uniform.
        SHADER.cullEnabled = shader.getUniform("ValkyrienAir_CullEnabled");
        if (SHADER.cullEnabled == null) return;

        SHADER.isShipPass = shader.getUniform("ValkyrienAir_IsShipPass");
        SHADER.cameraWorldPos = shader.getUniform("ValkyrienAir_CameraWorldPos");
        SHADER.waterStillUv = shader.getUniform("ValkyrienAir_WaterStillUv");
        SHADER.waterFlowUv = shader.getUniform("ValkyrienAir_WaterFlowUv");
        SHADER.waterOverlayUv = shader.getUniform("ValkyrienAir_WaterOverlayUv");
        SHADER.shipWaterTintEnabled = shader.getUniform("ValkyrienAir_ShipWaterTintEnabled");
        SHADER.shipWaterTint = shader.getUniform("ValkyrienAir_ShipWaterTint");
        if (SHADER.isShipPass == null || SHADER.cameraWorldPos == null || SHADER.waterStillUv == null ||
            SHADER.waterFlowUv == null || SHADER.waterOverlayUv == null) {
            return;
        }

        for (int i = 0; i < MAX_SHIPS; i++) {
            SHADER.shipAabbMin[i] = shader.getUniform("ValkyrienAir_ShipAabbMin" + i);
            SHADER.shipAabbMax[i] = shader.getUniform("ValkyrienAir_ShipAabbMax" + i);
            SHADER.cameraShipPos[i] = shader.getUniform("ValkyrienAir_CameraShipPos" + i);
            SHADER.gridMin[i] = shader.getUniform("ValkyrienAir_GridMin" + i);
            SHADER.gridSize[i] = shader.getUniform("ValkyrienAir_GridSize" + i);
            SHADER.cullFluidStillUv[i] = shader.getUniform("ValkyrienAir_CullFluidStillUv" + i);
            SHADER.cullFluidFlowUv[i] = shader.getUniform("ValkyrienAir_CullFluidFlowUv" + i);
            SHADER.cullFluidOverlayUv[i] = shader.getUniform("ValkyrienAir_CullFluidOverlayUv" + i);
            SHADER.worldToShip[i] = shader.getUniform("ValkyrienAir_WorldToShip" + i);
            if (SHADER.shipAabbMin[i] == null || SHADER.shipAabbMax[i] == null || SHADER.gridMin[i] == null ||
                SHADER.gridSize[i] == null || SHADER.cullFluidStillUv[i] == null || SHADER.cullFluidFlowUv[i] == null ||
                SHADER.cullFluidOverlayUv[i] == null || SHADER.worldToShip[i] == null || SHADER.cameraShipPos[i] == null) {
                return;
            }
        }

        SHADER.supported = true;
        shaderEverSupported = true;
    }

    private static ProgramHandles bindProgramHandles(final int programId) {
        ProgramHandles handles = PROGRAM_HANDLES.get(programId);
        if (handles != null) return handles;

        handles = new ProgramHandles(programId);

        final int maxCombined = GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        final int maxSafeUnits = Math.min(maxCombined, GLSTATEMANAGER_SAFE_TEXTURE_UNITS);
        handles.maxSafeTextureUnits = maxSafeUnits;
        final int availableUnits = maxSafeUnits - BASE_MASK_TEX_UNIT;
        handles.maxMaskSlots = Math.max(0, Math.min(MAX_SHIPS, Math.max(0, availableUnits) / 2));

        handles.regionOffsetLoc = GL20.glGetUniformLocation(programId, "u_RegionOffset");
        handles.blockTexLoc = GL20.glGetUniformLocation(programId, "u_BlockTex");
        final boolean looksLikeEmbeddiumChunkProgram = handles.regionOffsetLoc >= 0 || handles.blockTexLoc >= 0;
        handles.embeddiumChunkProgram = looksLikeEmbeddiumChunkProgram;

        handles.cullEnabledLoc = GL20.glGetUniformLocation(programId, "ValkyrienAir_CullEnabled");
        if (handles.cullEnabledLoc < 0) {
            PROGRAM_HANDLES.put(programId, handles);
            return handles;
        }

        handles.isShipPassLoc = GL20.glGetUniformLocation(programId, "ValkyrienAir_IsShipPass");
        handles.cameraWorldPosLoc = GL20.glGetUniformLocation(programId, "ValkyrienAir_CameraWorldPos");
        handles.waterStillUvLoc = GL20.glGetUniformLocation(programId, "ValkyrienAir_WaterStillUv");
        handles.waterFlowUvLoc = GL20.glGetUniformLocation(programId, "ValkyrienAir_WaterFlowUv");
        handles.waterOverlayUvLoc = GL20.glGetUniformLocation(programId, "ValkyrienAir_WaterOverlayUv");
        handles.shipWaterTintEnabledLoc = GL20.glGetUniformLocation(programId, "ValkyrienAir_ShipWaterTintEnabled");
        handles.shipWaterTintLoc = GL20.glGetUniformLocation(programId, "ValkyrienAir_ShipWaterTint");
        handles.chunkWorldOriginLoc = GL20.glGetUniformLocation(programId, "ValkyrienAir_ChunkWorldOrigin");

		        for (int i = 0; i < MAX_SHIPS; i++) {
		            handles.shipAabbMinLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_ShipAabbMin" + i);
		            handles.shipAabbMaxLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_ShipAabbMax" + i);
		            handles.cameraShipPosLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_CameraShipPos" + i);
		            handles.gridMinLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_GridMin" + i);
		            handles.gridSizeLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_GridSize" + i);
		            handles.cullFluidStillUvLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_CullFluidStillUv" + i);
		            handles.cullFluidFlowUvLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_CullFluidFlowUv" + i);
		            handles.cullFluidOverlayUvLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_CullFluidOverlayUv" + i);
		            handles.worldToShipLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_WorldToShip" + i);
		            handles.airMaskLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_AirMask" + i);
		            handles.occMaskLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_OccMask" + i);

		            handles.shipSlotSupported[i] =
		                i < handles.maxMaskSlots &&
		                    handles.shipAabbMinLoc[i] >= 0 &&
		                    handles.shipAabbMaxLoc[i] >= 0 &&
		                    handles.gridSizeLoc[i] >= 0 &&
		                    handles.cullFluidStillUvLoc[i] >= 0 &&
		                    handles.cullFluidFlowUvLoc[i] >= 0 &&
		                    handles.cullFluidOverlayUvLoc[i] >= 0 &&
		                    handles.worldToShipLoc[i] >= 0 &&
		                    handles.airMaskLoc[i] >= 0 &&
		                    handles.occMaskLoc[i] >= 0;
		        }

        final boolean requiredOk =
            looksLikeEmbeddiumChunkProgram &&
                handles.cullEnabledLoc >= 0 &&
                handles.isShipPassLoc >= 0 &&
                handles.cameraWorldPosLoc >= 0 &&
                handles.maxMaskSlots > 0 &&
                handles.shipSlotSupported[0];

        // Candidate Embeddium chunk programs: allow partial operation even if some uniforms are optimized out.
        // Slot 0 must have the required uniforms; other slots and core uniforms are optional.
        handles.supported = requiredOk;

        programEverSupported |= handles.supported;
        PROGRAM_HANDLES.put(programId, handles);
        return handles;
    }

    private static void updateCameraAndWaterUv(final Vec3 cameraPos) {
        SHADER.cameraWorldPos.set((float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z);
        SHADER.cameraWorldPos.upload();

        final Function<ResourceLocation, TextureAtlasSprite> atlas =
            Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS);
        final TextureAtlasSprite still = atlas.apply(WATER_STILL);
        final TextureAtlasSprite flow = atlas.apply(WATER_FLOW);
        final TextureAtlasSprite overlay = atlas.apply(WATER_OVERLAY);

        SHADER.waterStillUv.set(still.getU0(), still.getV0(), still.getU1(), still.getV1());
        SHADER.waterFlowUv.set(flow.getU0(), flow.getV0(), flow.getU1(), flow.getV1());
        SHADER.waterOverlayUv.set(overlay.getU0(), overlay.getV0(), overlay.getU1(), overlay.getV1());

        SHADER.waterStillUv.upload();
        SHADER.waterFlowUv.upload();
        SHADER.waterOverlayUv.upload();
    }

    private static void updateCameraAndWaterUvProgram(final ProgramHandles handles, final Vec3 cameraPos) {
        if (handles.cameraWorldPosLoc >= 0) {
            GL20.glUniform3f(handles.cameraWorldPosLoc, (float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z);
        }

        final Function<ResourceLocation, TextureAtlasSprite> atlas =
            Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS);
        final TextureAtlasSprite still = atlas.apply(WATER_STILL);
        final TextureAtlasSprite flow = atlas.apply(WATER_FLOW);
        final TextureAtlasSprite overlay = atlas.apply(WATER_OVERLAY);

        if (handles.waterStillUvLoc >= 0) {
            GL20.glUniform4f(handles.waterStillUvLoc, still.getU0(), still.getV0(), still.getU1(), still.getV1());
        }
        if (handles.waterFlowUvLoc >= 0) {
            GL20.glUniform4f(handles.waterFlowUvLoc, flow.getU0(), flow.getV0(), flow.getU1(), flow.getV1());
        }
        if (handles.waterOverlayUvLoc >= 0) {
            GL20.glUniform4f(handles.waterOverlayUvLoc, overlay.getU0(), overlay.getV0(), overlay.getU1(), overlay.getV1());
        }
    }

    private static CullFluidUvBounds getCullFluidUvBounds(final ClientLevel level, final Fluid fluid) {
        if (level == null || fluid == null) return INVALID_CULL_FLUID_UVS;

        final Minecraft mc = Minecraft.getInstance();
        final AbstractTexture atlasTexture = mc.getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS);
        final int atlasTexId = atlasTexture != null ? atlasTexture.getId() : 0;
        if (atlasTexId != cullFluidUvLastAtlasTexId) {
            CULL_FLUID_UV_CACHE.clear();
            cullFluidUvLastAtlasTexId = atlasTexId;
        }

        final Fluid canonical = fluid instanceof final FlowingFluid flowing ? flowing.getSource() : fluid;
        final CullFluidUvBounds cached = CULL_FLUID_UV_CACHE.get(canonical);
        if (cached != null) return cached;

        final CullFluidUvBounds computed = computeCullFluidUvBounds(level, canonical);
        CULL_FLUID_UV_CACHE.put(canonical, computed);
        return computed;
    }

    private static CullFluidUvBounds computeCullFluidUvBounds(final ClientLevel level, final Fluid fluid) {
        if (fluid == null) return INVALID_CULL_FLUID_UVS;

        final Function<ResourceLocation, TextureAtlasSprite> atlas =
            Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS);

        TextureAtlasSprite stillSprite = null;
        TextureAtlasSprite flowSprite = null;
        TextureAtlasSprite overlaySprite = null;

        // Vanilla fast-paths (no reflection).
        if (fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER) {
            stillSprite = atlas.apply(WATER_STILL);
            flowSprite = atlas.apply(WATER_FLOW);
            overlaySprite = atlas.apply(WATER_OVERLAY);
        } else if (fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA) {
            stillSprite = atlas.apply(LAVA_STILL);
            flowSprite = atlas.apply(LAVA_FLOW);
        } else {
            final FluidState state = fluid.defaultFluidState();

            // Forge: query fluid render properties for texture IDs (supports modded fluids).
            final ResourceLocation[] forge = queryForgeFluidTextures(level, fluid, state);
            if (forge != null) {
                if (forge[0] != null) stillSprite = atlas.apply(forge[0]);
                if (forge[1] != null) flowSprite = atlas.apply(forge[1]);
                if (forge[2] != null) overlaySprite = atlas.apply(forge[2]);
            } else {
                // Fabric: query fluid render handler sprites (best-effort).
                final TextureAtlasSprite[] sprites = queryFabricFluidSprites(level, fluid, state);
                if (sprites != null) {
                    if (sprites.length > 0) stillSprite = sprites[0];
                    if (sprites.length > 1) flowSprite = sprites[1];
                    if (sprites.length > 2) overlaySprite = sprites[2];
                }
            }
        }

        return new CullFluidUvBounds(
            stillSprite != null ? stillSprite.getU0() : INVALID_U0,
            stillSprite != null ? stillSprite.getV0() : INVALID_V0,
            stillSprite != null ? stillSprite.getU1() : INVALID_U1,
            stillSprite != null ? stillSprite.getV1() : INVALID_V1,
            flowSprite != null ? flowSprite.getU0() : INVALID_U0,
            flowSprite != null ? flowSprite.getV0() : INVALID_V0,
            flowSprite != null ? flowSprite.getU1() : INVALID_U1,
            flowSprite != null ? flowSprite.getV1() : INVALID_V1,
            overlaySprite != null ? overlaySprite.getU0() : INVALID_U0,
            overlaySprite != null ? overlaySprite.getV0() : INVALID_V0,
            overlaySprite != null ? overlaySprite.getU1() : INVALID_U1,
            overlaySprite != null ? overlaySprite.getV1() : INVALID_V1
        );
    }

    private static ResourceLocation[] queryForgeFluidTextures(final ClientLevel level, final Fluid fluid, final FluidState fluidState) {
        if (!ensureForgeFluidTextureAccess()) return null;
        try {
            final Object ext = forgeFluidExtOf.invoke(null, fluid);
            if (ext == null) return null;

            final ResourceLocation still = invokeTexture(ext, forgeGetStill0, forgeGetStill3, fluidState, level);
            final ResourceLocation flow = invokeTexture(ext, forgeGetFlow0, forgeGetFlow3, fluidState, level);
            final ResourceLocation overlay = invokeTexture(ext, forgeGetOverlay0, forgeGetOverlay3, fluidState, level);

            if (still == null && flow == null && overlay == null) return null;
            return new ResourceLocation[] {still, flow, overlay};
        } catch (final Throwable ignored) {
            return null;
        }
    }

    private static boolean ensureForgeFluidTextureAccess() {
        if (forgeFluidTexturesChecked) return forgeFluidExtOf != null;
        forgeFluidTexturesChecked = true;
        try {
            final Class<?> extClass = Class.forName("net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions");
            forgeFluidExtOf = extClass.getMethod("of", Fluid.class);

            forgeGetStill0 = findMethod(extClass, "getStillTexture");
            forgeGetFlow0 = findMethod(extClass, "getFlowingTexture");
            forgeGetOverlay0 = findMethod(extClass, "getOverlayTexture");

            forgeGetStill3 = findMethod(extClass, "getStillTexture", FluidState.class, BlockAndTintGetter.class, BlockPos.class);
            forgeGetFlow3 = findMethod(extClass, "getFlowingTexture", FluidState.class, BlockAndTintGetter.class, BlockPos.class);
            forgeGetOverlay3 = findMethod(extClass, "getOverlayTexture", FluidState.class, BlockAndTintGetter.class, BlockPos.class);
            return true;
        } catch (final ClassNotFoundException ignored) {
            return false;
        } catch (final Throwable t) {
            if (!loggedCullFluidUvLookupFailed) {
                loggedCullFluidUvLookupFailed = true;
                LOGGER.warn("Failed to query Forge fluid render textures for culling; some modded fluids may not be culled.", t);
            }
            return false;
        }
    }

    private static TextureAtlasSprite[] queryFabricFluidSprites(final ClientLevel level, final Fluid fluid, final FluidState fluidState) {
        if (!ensureFabricFluidTextureAccess()) return null;
        try {
            final Object handler = fabricRegistryGetHandler.invoke(fabricFluidRenderHandlerRegistry, fluid);
            if (handler == null) return null;
            return (TextureAtlasSprite[]) fabricHandlerGetSprites.invoke(handler, level, BlockPos.ZERO, fluidState);
        } catch (final Throwable ignored) {
            return null;
        }
    }

    private static boolean ensureFabricFluidTextureAccess() {
        if (fabricFluidTexturesChecked) return fabricFluidRenderHandlerRegistry != null;
        fabricFluidTexturesChecked = true;
        try {
            final Class<?> registryClass = Class.forName("net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry");
            final Field instanceField = registryClass.getField("INSTANCE");
            fabricFluidRenderHandlerRegistry = instanceField.get(null);
            fabricRegistryGetHandler = registryClass.getMethod("get", Fluid.class);

            final Class<?> handlerClass = Class.forName("net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler");
            fabricHandlerGetSprites = handlerClass.getMethod(
                "getFluidSprites",
                net.minecraft.world.level.BlockAndTintGetter.class,
                BlockPos.class,
                FluidState.class
            );

            return true;
        } catch (final ClassNotFoundException ignored) {
            return false;
        } catch (final Throwable t) {
            if (!loggedCullFluidUvLookupFailed) {
                loggedCullFluidUvLookupFailed = true;
                LOGGER.warn("Failed to query Fabric fluid sprites for culling; some modded fluids may not be culled.", t);
            }
            return false;
        }
    }

    private static List<LoadedShip> selectClosestShips(final ClientLevel level, final Vec3 cameraPos, final int maxCount) {
        final List<LoadedShip> candidates = new ArrayList<>();
        for (final LoadedShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            candidates.add(ship);
        }

        candidates.sort(Comparator.comparingDouble(ship -> distanceSqToShipAabb(cameraPos, ship)));
        if (candidates.size() > maxCount) {
            return candidates.subList(0, maxCount);
        }
        return candidates;
    }

    private static double distanceSqToShipAabb(final Vec3 cameraPos, final LoadedShip ship) {
        final AABBdc shipWorldAabbDc = getShipWorldAabb(ship).orElse(null);
        if (shipWorldAabbDc == null) return Double.POSITIVE_INFINITY;

        final double closestX = Mth.clamp(cameraPos.x, shipWorldAabbDc.minX(), shipWorldAabbDc.maxX());
        final double closestY = Mth.clamp(cameraPos.y, shipWorldAabbDc.minY(), shipWorldAabbDc.maxY());
        final double closestZ = Mth.clamp(cameraPos.z, shipWorldAabbDc.minZ(), shipWorldAabbDc.maxZ());
        final double dx = closestX - cameraPos.x;
        final double dy = closestY - cameraPos.y;
        final double dz = closestZ - cameraPos.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static Optional<AABBdc> getShipWorldAabb(final LoadedShip ship) {
        if (ship instanceof final ClientShip clientShip) {
            return Optional.ofNullable(clientShip.getRenderAABB());
        }
        return Optional.ofNullable(ship.getWorldAABB());
    }

    private static ShipTransform getShipTransform(final LoadedShip ship) {
        if (ship instanceof final ClientShip clientShip) {
            return clientShip.getRenderTransform();
        }
        return ship.getShipTransform();
    }

    private static void updateShipUniformsAndMasks(final ClientLevel level, final List<LoadedShip> ships,
        final double cameraX, final double cameraY, final double cameraZ) {
        final long gameTime = level.getGameTime();

        for (int slot = 0; slot < MAX_SHIPS; slot++) {
            if (slot >= ships.size()) {
                disableShipSlot(slot);
                continue;
            }

            final LoadedShip ship = ships.get(slot);
            final long shipId = ship.getId();

            final AABBdc shipWorldAabbDc = getShipWorldAabb(ship).orElse(null);
            if (shipWorldAabbDc == null) {
                disableShipSlot(slot);
                continue;
            }

            final ShipWaterPocketManager.ClientWaterReachableSnapshot snapshot =
                ShipWaterPocketManager.getClientWaterReachableSnapshot(level, shipId);
            if (snapshot == null) {
                disableShipSlot(slot);
                continue;
            }

            final ShipMasks masks = SHIP_MASKS.computeIfAbsent(shipId, ShipMasks::new);

            final int minX = snapshot.getMinX();
            final int minY = snapshot.getMinY();
            final int minZ = snapshot.getMinZ();
            final int sizeX = snapshot.getSizeX();
            final int sizeY = snapshot.getSizeY();
            final int sizeZ = snapshot.getSizeZ();
            final long geometryRevision = snapshot.getGeometryRevision();

            final boolean boundsChanged =
                masks.minX != minX || masks.minY != minY || masks.minZ != minZ ||
                    masks.sizeX != sizeX || masks.sizeY != sizeY || masks.sizeZ != sizeZ;

            if (boundsChanged || masks.geometryRevision != geometryRevision) {
                rebuildOccMask(level, masks, minX, minY, minZ, sizeX, sizeY, sizeZ, geometryRevision);
            }

            final ShipTransform shipTransform = getShipTransform(ship);
            final Matrix4dc worldToShip = shipTransform.getWorldToShip();
            final double biasedM30 = worldToShip.m30() - (double) minX;
            final double biasedM31 = worldToShip.m31() - (double) minY;
            final double biasedM32 = worldToShip.m32() - (double) minZ;
            final long airKey = computeAirKey(geometryRevision, worldToShip, biasedM30, biasedM31, biasedM32);

            updateAirMask(level, masks, snapshot, shipTransform, gameTime, airKey);

            final CullFluidUvBounds cullUv = getCullFluidUvBounds(level, snapshot.getFloodFluid());
            SHADER.cullFluidStillUv[slot].set(cullUv.stillU0, cullUv.stillV0, cullUv.stillU1, cullUv.stillV1);
            SHADER.cullFluidFlowUv[slot].set(cullUv.flowU0, cullUv.flowV0, cullUv.flowU1, cullUv.flowV1);
            SHADER.cullFluidOverlayUv[slot].set(cullUv.overlayU0, cullUv.overlayV0, cullUv.overlayU1, cullUv.overlayV1);

            // Bind samplers for this slot.
            SHADER.shader.setSampler("ValkyrienAir_AirMask" + slot, masks.airTexId);
            SHADER.shader.setSampler("ValkyrienAir_OccMask" + slot, masks.occTexId);

            // Upload slot uniforms.
            SHADER.shipAabbMin[slot].set((float) shipWorldAabbDc.minX(), (float) shipWorldAabbDc.minY(), (float) shipWorldAabbDc.minZ(), 0.0f);
            SHADER.shipAabbMax[slot].set((float) shipWorldAabbDc.maxX(), (float) shipWorldAabbDc.maxY(), (float) shipWorldAabbDc.maxZ(), 0.0f);

            // IMPORTANT: Shipyard positions can be very large (depending on VS2's shipyard layout), which quickly
            // exceeds float integer precision. If we upload absolute ship-space coordinates and then do
            // `localPos = shipPos - gridMin` in the shader, the subtraction can lose multiple blocks of precision.
            //
            // To keep the shader math stable, we pre-bias worldToShip by -gridMin on the CPU (using doubles) and
            // then upload gridMin = 0 so the shader operates in a small local coordinate system.
            SHADER.gridMin[slot].set(0.0f, 0.0f, 0.0f, 0.0f);
            SHADER.gridSize[slot].set((float) sizeX, (float) sizeY, (float) sizeZ, 0.0f);
            masks.worldToShip.set(
                (float) worldToShip.m00(), (float) worldToShip.m01(), (float) worldToShip.m02(), (float) worldToShip.m03(),
                (float) worldToShip.m10(), (float) worldToShip.m11(), (float) worldToShip.m12(), (float) worldToShip.m13(),
                (float) worldToShip.m20(), (float) worldToShip.m21(), (float) worldToShip.m22(), (float) worldToShip.m23(),
                (float) biasedM30, (float) biasedM31, (float) biasedM32, (float) worldToShip.m33()
            );
            SHADER.worldToShip[slot].set(masks.worldToShip);

            final double camShipX = worldToShip.m00() * cameraX + worldToShip.m10() * cameraY + worldToShip.m20() * cameraZ + biasedM30;
            final double camShipY = worldToShip.m01() * cameraX + worldToShip.m11() * cameraY + worldToShip.m21() * cameraZ + biasedM31;
            final double camShipZ = worldToShip.m02() * cameraX + worldToShip.m12() * cameraY + worldToShip.m22() * cameraZ + biasedM32;
            SHADER.cameraShipPos[slot].set((float) camShipX, (float) camShipY, (float) camShipZ);

            SHADER.shipAabbMin[slot].upload();
            SHADER.shipAabbMax[slot].upload();
            SHADER.cameraShipPos[slot].upload();
            SHADER.gridMin[slot].upload();
            SHADER.gridSize[slot].upload();
            SHADER.cullFluidStillUv[slot].upload();
            SHADER.cullFluidFlowUv[slot].upload();
            SHADER.cullFluidOverlayUv[slot].upload();
            SHADER.worldToShip[slot].upload();
        }

        // Clear stale mask entries for ships that are no longer loaded to avoid leaks.
        // (Ship IDs can be reused across levels; lastLevel guards that case.)
        final LongOpenHashSet loadedIds = new LongOpenHashSet();
        for (final LoadedShip loadedShip : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            loadedIds.add(loadedShip.getId());
        }
        SHIP_MASKS.entrySet().removeIf(entry -> {
            if (loadedIds.contains(entry.getKey())) return false;
            entry.getValue().close();
            return true;
        });
    }

    private static void updateShipUniformsAndMasksProgram(final ProgramHandles handles, final ClientLevel level,
        final List<LoadedShip> ships, final double cameraX, final double cameraY, final double cameraZ) {
        final long gameTime = level.getGameTime();

        for (int slot = 0; slot < MAX_SHIPS; slot++) {
            if (!handles.shipSlotSupported[slot]) {
                // If this slot is unusable due to texture-unit limits (or missing uniforms), ensure it is disabled so
                // the shader's slot-N helpers early-out on GridSizeN <= 0.
                disableShipSlotProgram(handles, slot);
                continue;
            }
            if (slot >= ships.size()) {
                disableShipSlotProgram(handles, slot);
                continue;
            }

            final LoadedShip ship = ships.get(slot);
            final long shipId = ship.getId();

            final AABBdc shipWorldAabbDc = getShipWorldAabb(ship).orElse(null);
            if (shipWorldAabbDc == null) {
                disableShipSlotProgram(handles, slot);
                continue;
            }

            final ShipWaterPocketManager.ClientWaterReachableSnapshot snapshot =
                ShipWaterPocketManager.getClientWaterReachableSnapshot(level, shipId);
            if (snapshot == null) {
                disableShipSlotProgram(handles, slot);
                continue;
            }

            final ShipMasks masks = SHIP_MASKS.computeIfAbsent(shipId, ShipMasks::new);

            final int minX = snapshot.getMinX();
            final int minY = snapshot.getMinY();
            final int minZ = snapshot.getMinZ();
            final int sizeX = snapshot.getSizeX();
            final int sizeY = snapshot.getSizeY();
            final int sizeZ = snapshot.getSizeZ();
            final long geometryRevision = snapshot.getGeometryRevision();

            final boolean boundsChanged =
                masks.minX != minX || masks.minY != minY || masks.minZ != minZ ||
                    masks.sizeX != sizeX || masks.sizeY != sizeY || masks.sizeZ != sizeZ;

            if (boundsChanged || masks.geometryRevision != geometryRevision) {
                rebuildOccMask(level, masks, minX, minY, minZ, sizeX, sizeY, sizeZ, geometryRevision);
            }

            final ShipTransform shipTransform = getShipTransform(ship);
            final Matrix4dc worldToShip = shipTransform.getWorldToShip();
            final double biasedM30 = worldToShip.m30() - (double) minX;
            final double biasedM31 = worldToShip.m31() - (double) minY;
            final double biasedM32 = worldToShip.m32() - (double) minZ;
            final long airKey = computeAirKey(geometryRevision, worldToShip, biasedM30, biasedM31, biasedM32);

            updateAirMask(level, masks, snapshot, shipTransform, gameTime, airKey);

            final CullFluidUvBounds cullUv = getCullFluidUvBounds(level, snapshot.getFloodFluid());
            if (handles.cullFluidStillUvLoc[slot] >= 0) {
                GL20.glUniform4f(handles.cullFluidStillUvLoc[slot], cullUv.stillU0, cullUv.stillV0, cullUv.stillU1, cullUv.stillV1);
            }
            if (handles.cullFluidFlowUvLoc[slot] >= 0) {
                GL20.glUniform4f(handles.cullFluidFlowUvLoc[slot], cullUv.flowU0, cullUv.flowV0, cullUv.flowU1, cullUv.flowV1);
            }
            if (handles.cullFluidOverlayUvLoc[slot] >= 0) {
                GL20.glUniform4f(handles.cullFluidOverlayUvLoc[slot], cullUv.overlayU0, cullUv.overlayV0, cullUv.overlayU1, cullUv.overlayV1);
            }

            bindProgramMaskTextures(handles, slot, masks.airTexId, masks.occTexId);

            // Slot uniforms.
            if (handles.shipAabbMinLoc[slot] >= 0) {
                GL20.glUniform4f(handles.shipAabbMinLoc[slot], (float) shipWorldAabbDc.minX(), (float) shipWorldAabbDc.minY(),
                    (float) shipWorldAabbDc.minZ(), 0.0f);
            }
            if (handles.shipAabbMaxLoc[slot] >= 0) {
                GL20.glUniform4f(handles.shipAabbMaxLoc[slot], (float) shipWorldAabbDc.maxX(), (float) shipWorldAabbDc.maxY(),
                    (float) shipWorldAabbDc.maxZ(), 0.0f);
            }

            // See comment in updateShipUniformsAndMasks() for precision rationale.
            if (handles.gridMinLoc[slot] >= 0) {
                GL20.glUniform4f(handles.gridMinLoc[slot], 0.0f, 0.0f, 0.0f, 0.0f);
            }
            if (handles.gridSizeLoc[slot] >= 0) {
                GL20.glUniform4f(handles.gridSizeLoc[slot], (float) sizeX, (float) sizeY, (float) sizeZ, 0.0f);
            }

            masks.worldToShip.set(
                (float) worldToShip.m00(), (float) worldToShip.m01(), (float) worldToShip.m02(), (float) worldToShip.m03(),
                (float) worldToShip.m10(), (float) worldToShip.m11(), (float) worldToShip.m12(), (float) worldToShip.m13(),
                (float) worldToShip.m20(), (float) worldToShip.m21(), (float) worldToShip.m22(), (float) worldToShip.m23(),
                (float) biasedM30, (float) biasedM31, (float) biasedM32, (float) worldToShip.m33()
            );
            uploadMatrixUniform(handles.worldToShipLoc[slot], masks.worldToShip);

            final double camShipX = worldToShip.m00() * cameraX + worldToShip.m10() * cameraY + worldToShip.m20() * cameraZ + biasedM30;
            final double camShipY = worldToShip.m01() * cameraX + worldToShip.m11() * cameraY + worldToShip.m21() * cameraZ + biasedM31;
            final double camShipZ = worldToShip.m02() * cameraX + worldToShip.m12() * cameraY + worldToShip.m22() * cameraZ + biasedM32;
            if (handles.cameraShipPosLoc[slot] >= 0) {
                GL20.glUniform3f(handles.cameraShipPosLoc[slot], (float) camShipX, (float) camShipY, (float) camShipZ);
            }
        }

        // Clear stale mask entries for ships that are no longer loaded to avoid leaks.
        final LongOpenHashSet loadedIds = new LongOpenHashSet();
        for (final LoadedShip loadedShip : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            loadedIds.add(loadedShip.getId());
        }
        SHIP_MASKS.entrySet().removeIf(entry -> {
            if (loadedIds.contains(entry.getKey())) return false;
            entry.getValue().close();
            return true;
        });
    }

    private static void disableShipSlot(final int slot) {
        SHADER.shader.setSampler("ValkyrienAir_AirMask" + slot, 0);
        SHADER.shader.setSampler("ValkyrienAir_OccMask" + slot, 0);

        SHADER.shipAabbMin[slot].set(0.0f, 0.0f, 0.0f, 0.0f);
        SHADER.shipAabbMax[slot].set(-1.0f, -1.0f, -1.0f, 0.0f);
        SHADER.cameraShipPos[slot].set(0.0f, 0.0f, 0.0f);
        SHADER.gridMin[slot].set(0.0f, 0.0f, 0.0f, 0.0f);
        SHADER.gridSize[slot].set(0.0f, 0.0f, 0.0f, 0.0f);
        SHADER.cullFluidStillUv[slot].set(1.0f, 1.0f, 0.0f, 0.0f);
        SHADER.cullFluidFlowUv[slot].set(1.0f, 1.0f, 0.0f, 0.0f);
        SHADER.cullFluidOverlayUv[slot].set(1.0f, 1.0f, 0.0f, 0.0f);
        SHADER.worldToShip[slot].set(IDENTITY_MAT4);

        SHADER.shipAabbMin[slot].upload();
        SHADER.shipAabbMax[slot].upload();
        SHADER.cameraShipPos[slot].upload();
        SHADER.gridMin[slot].upload();
        SHADER.gridSize[slot].upload();
        SHADER.cullFluidStillUv[slot].upload();
        SHADER.cullFluidFlowUv[slot].upload();
        SHADER.cullFluidOverlayUv[slot].upload();
        SHADER.worldToShip[slot].upload();
    }

    private static void disableShipSlotProgram(final ProgramHandles handles, final int slot) {
        if (handles.shipAabbMinLoc[slot] >= 0) {
            GL20.glUniform4f(handles.shipAabbMinLoc[slot], 0.0f, 0.0f, 0.0f, 0.0f);
        }
        if (handles.shipAabbMaxLoc[slot] >= 0) {
            GL20.glUniform4f(handles.shipAabbMaxLoc[slot], -1.0f, -1.0f, -1.0f, 0.0f);
        }
        if (handles.cameraShipPosLoc[slot] >= 0) {
            GL20.glUniform3f(handles.cameraShipPosLoc[slot], 0.0f, 0.0f, 0.0f);
        }
        if (handles.gridMinLoc[slot] >= 0) {
            GL20.glUniform4f(handles.gridMinLoc[slot], 0.0f, 0.0f, 0.0f, 0.0f);
        }
        if (handles.gridSizeLoc[slot] >= 0) {
            GL20.glUniform4f(handles.gridSizeLoc[slot], 0.0f, 0.0f, 0.0f, 0.0f);
        }
        if (handles.cullFluidStillUvLoc[slot] >= 0) {
            GL20.glUniform4f(handles.cullFluidStillUvLoc[slot], 1.0f, 1.0f, 0.0f, 0.0f);
        }
        if (handles.cullFluidFlowUvLoc[slot] >= 0) {
            GL20.glUniform4f(handles.cullFluidFlowUvLoc[slot], 1.0f, 1.0f, 0.0f, 0.0f);
        }
        if (handles.cullFluidOverlayUvLoc[slot] >= 0) {
            GL20.glUniform4f(handles.cullFluidOverlayUvLoc[slot], 1.0f, 1.0f, 0.0f, 0.0f);
        }
        uploadMatrixUniform(handles.worldToShipLoc[slot], IDENTITY_MAT4);

        if (slot < handles.maxMaskSlots) {
            bindProgramMaskTextures(handles, slot, 0, 0);
        }
    }

    private static void bindProgramMaskTextures(final ProgramHandles handles, final int slot, final int airTexId, final int occTexId) {
        if (handles == null) return;
        if (slot < 0 || slot >= handles.maxMaskSlots) return;
        final int airUnit = BASE_MASK_TEX_UNIT + slot * 2;
        final int occUnit = airUnit + 1;

        if (handles.airMaskLoc[slot] >= 0) {
            GL20.glUniform1i(handles.airMaskLoc[slot], airUnit);
        }
        if (handles.occMaskLoc[slot] >= 0) {
            GL20.glUniform1i(handles.occMaskLoc[slot], occUnit);
        }

        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + airUnit);
        GlStateManager._bindTexture(airTexId);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + occUnit);
        GlStateManager._bindTexture(occTexId);

        // Avoid surprising other render code by leaving the active texture on a high unit.
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    }

    private static void uploadMatrixUniform(final int location, final Matrix4f matrix) {
        if (location < 0) return;
        final FloatBuffer buffer = MATRIX_BUFFER.get();
        buffer.clear();
        matrix.get(buffer);
        // JOML's Matrix4f#get(FloatBuffer) writes via absolute puts and does NOT advance the buffer position.
        // LWJGL uses the buffer's remaining() to determine how many values to upload.
        buffer.position(16);
        buffer.flip();
        GL20.glUniformMatrix4fv(location, false, buffer);
    }

    private static void rebuildOccMask(final ClientLevel level, final ShipMasks masks, final int minX, final int minY,
        final int minZ, final int sizeX, final int sizeY, final int sizeZ, final long geometryRevision) {
        masks.geometryRevision = geometryRevision;
        masks.minX = minX;
        masks.minY = minY;
        masks.minZ = minZ;
        masks.sizeX = sizeX;
        masks.sizeY = sizeY;
        masks.sizeZ = sizeZ;

        final int volume = sizeX * sizeY * sizeZ;
        final int wordCount = volume * OCC_WORDS_PER_VOXEL;
        final int height = Math.max(1, (wordCount + MASK_TEX_WIDTH - 1) / MASK_TEX_WIDTH);

        if (masks.occTexId != 0 && masks.occTexHeight != height) {
            TextureUtil.releaseTextureId(masks.occTexId);
            masks.occTexId = 0;
        }
        masks.occTexId = ensureIntTexture(masks.occTexId, MASK_TEX_WIDTH, height);
        masks.occTexHeight = height;

        final int capacity = MASK_TEX_WIDTH * height;
        if (masks.occData == null || masks.occData.length != capacity) {
            masks.occData = new int[capacity];
            masks.occBuffer = BufferUtils.createIntBuffer(capacity);
        } else {
            java.util.Arrays.fill(masks.occData, 0);
        }

        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int lz = 0; lz < sizeZ; lz++) {
            for (int ly = 0; ly < sizeY; ly++) {
                for (int lx = 0; lx < sizeX; lx++) {
                    pos.set(minX + lx, minY + ly, minZ + lz);
                    final BlockState state = level.getBlockState(pos);

                    VoxelShape shape = state.getOcclusionShape(level, pos);
                    if (shape.isEmpty()) {
                        shape = state.getCollisionShape(level, pos);
                        if (shape.isEmpty()) continue;
                    }

                    final int voxelIdx = lx + sizeX * (ly + sizeY * lz);
                    final int wordBase = voxelIdx * OCC_WORDS_PER_VOXEL;

                    shape.toAabbs().forEach(box -> {
                        final int x0 = Mth.clamp((int) Math.floor(box.minX * SUB), 0, SUB);
                        final int x1 = Mth.clamp((int) Math.ceil(box.maxX * SUB), 0, SUB);
                        final int y0 = Mth.clamp((int) Math.floor(box.minY * SUB), 0, SUB);
                        final int y1 = Mth.clamp((int) Math.ceil(box.maxY * SUB), 0, SUB);
                        final int z0 = Mth.clamp((int) Math.floor(box.minZ * SUB), 0, SUB);
                        final int z1 = Mth.clamp((int) Math.ceil(box.maxZ * SUB), 0, SUB);

                        for (int sz = z0; sz < z1; sz++) {
                            for (int sy = y0; sy < y1; sy++) {
                                for (int sx = x0; sx < x1; sx++) {
                                    final int subIdx = sx + SUB * (sy + SUB * sz);
                                    final int wordIdx = wordBase + (subIdx >> 5);
                                    final int bit = subIdx & 31;

                                    final int texWordIdx = wordIdx;
                                    final int texIdx = (texWordIdx & MASK_TEX_WIDTH_MASK) + (texWordIdx >> MASK_TEX_WIDTH_SHIFT) * MASK_TEX_WIDTH;
                                    if (texIdx < 0 || texIdx >= masks.occData.length) continue;
                                    masks.occData[texIdx] |= (1 << bit);
                                }
                            }
                        }
                    });
                }
            }
        }

        masks.occBuffer.clear();
        masks.occBuffer.put(masks.occData);
        masks.occBuffer.flip();

        uploadIntTexture(masks.occTexId, MASK_TEX_WIDTH, masks.occTexHeight, masks.occBuffer);
    }

    private static final double AIR_KEY_TRANS_Q = 4.0; // 1/4 block increments
    private static final double AIR_KEY_ROT_Q = 256.0; // coarse rotation quantization

    private static long computeAirKey(final long geometryRevision, final Matrix4dc worldToShip,
        final double biasedM30, final double biasedM31, final double biasedM32) {
        long h = 0xcbf29ce484222325L; // FNV-1a 64-bit offset basis
        h = fnv1a(h, (int) geometryRevision);
        h = fnv1a(h, (int) (geometryRevision >>> 32));

        h = fnv1a(h, quantizeRot(worldToShip.m00()));
        h = fnv1a(h, quantizeRot(worldToShip.m01()));
        h = fnv1a(h, quantizeRot(worldToShip.m02()));
        h = fnv1a(h, quantizeRot(worldToShip.m10()));
        h = fnv1a(h, quantizeRot(worldToShip.m11()));
        h = fnv1a(h, quantizeRot(worldToShip.m12()));
        h = fnv1a(h, quantizeRot(worldToShip.m20()));
        h = fnv1a(h, quantizeRot(worldToShip.m21()));
        h = fnv1a(h, quantizeRot(worldToShip.m22()));

        h = fnv1a(h, quantizeTrans(biasedM30));
        h = fnv1a(h, quantizeTrans(biasedM31));
        h = fnv1a(h, quantizeTrans(biasedM32));

        return h;
    }

    private static long fnv1a(long h, final int v) {
        h ^= (v & 0xffffffffL);
        h *= 0x100000001b3L;
        return h;
    }

    private static int quantizeRot(final double v) {
        return (int) Math.round(v * AIR_KEY_ROT_Q);
    }

    private static int quantizeTrans(final double v) {
        return (int) Math.round(v * AIR_KEY_TRANS_Q);
    }

    private static void updateAirMask(final ClientLevel level, final ShipMasks masks,
        final ShipWaterPocketManager.ClientWaterReachableSnapshot snapshot, final ShipTransform shipTransform,
        final long gameTime, final long airKey) {
        final int sizeX = masks.sizeX;
        final int sizeY = masks.sizeY;
        final int sizeZ = masks.sizeZ;
        final int volume = sizeX * sizeY * sizeZ;

        final int wordCount = (volume + 31) >> 5;
        final int height = Math.max(1, (wordCount + MASK_TEX_WIDTH - 1) / MASK_TEX_WIDTH);

        if (masks.airTexId != 0 && masks.airTexHeight != height) {
            TextureUtil.releaseTextureId(masks.airTexId);
            masks.airTexId = 0;
        }
        masks.airTexId = ensureIntTexture(masks.airTexId, MASK_TEX_WIDTH, height);
        masks.airTexHeight = height;

        final long interiorKey = snapshot.getGeometryRevision();
        if (masks.lastAirUploadKey == interiorKey && masks.airTexId != 0 && masks.airTexHeight == height) return;
        masks.lastAirUploadKey = interiorKey;

        final int capacity = MASK_TEX_WIDTH * height;
        if (masks.airData == null || masks.airData.length != capacity) {
            masks.airData = new int[capacity];
            masks.airBuffer = BufferUtils.createIntBuffer(capacity);
        } else {
            java.util.Arrays.fill(masks.airData, 0);
        }

        final BitSet interior = snapshot.getInterior();
        if (interior != null) {
            // Cull world-fluid surfaces inside ship interior volumes (including flooded interiors).
            for (int idx = interior.nextSetBit(0); idx >= 0; idx = interior.nextSetBit(idx + 1)) {
                final int wordIdx = idx >> 5;
                final int bit = idx & 31;

                final int texIdx = (wordIdx & MASK_TEX_WIDTH_MASK) + (wordIdx >> MASK_TEX_WIDTH_SHIFT) * MASK_TEX_WIDTH;
                if (texIdx < 0 || texIdx >= masks.airData.length) continue;
                masks.airData[texIdx] |= (1 << bit);
            }
        }

        masks.airBuffer.clear();
        masks.airBuffer.put(masks.airData);
        masks.airBuffer.flip();

        uploadIntTexture(masks.airTexId, MASK_TEX_WIDTH, masks.airTexHeight, masks.airBuffer);
    }

    private static Method findMethod(final Class<?> owner, final String name, final Class<?>... params) {
        try {
            return owner.getMethod(name, params);
        } catch (final NoSuchMethodException ignored) {
            return null;
        } catch (final Throwable t) {
            return null;
        }
    }

    private static ResourceLocation invokeTexture(final Object ext, final Method noArgs, final Method withState,
        final FluidState fluidState, final ClientLevel level) throws Exception {
        if (noArgs != null) {
            final Object v = noArgs.invoke(ext);
            if (v instanceof final ResourceLocation rl) return rl;
        }
        if (withState != null && level != null) {
            final Object v = withState.invoke(ext, fluidState, level, BlockPos.ZERO);
            if (v instanceof final ResourceLocation rl) return rl;
        }
        return null;
    }

    private static int ensureIntTexture(final int existingId, final int width, final int height) {
        final int prevBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        final int prevUnpackAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        try {
            if (existingId != 0) {
                GlStateManager._bindTexture(existingId);
                return existingId;
            }

            final int id = TextureUtil.generateTextureId();
            GlStateManager._bindTexture(id);

            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32UI, width, height, 0, GL30.GL_RED_INTEGER,
                GL11.GL_UNSIGNED_INT, (IntBuffer) null);

            return id;
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevUnpackAlignment);
            GlStateManager._bindTexture(prevBinding);
        }
    }

    private static void uploadIntTexture(final int texId, final int width, final int height, final IntBuffer data) {
        final int prevBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        final int prevUnpackAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        try {
            GlStateManager._bindTexture(texId);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, data);
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevUnpackAlignment);
            GlStateManager._bindTexture(prevBinding);
        }
    }
}
