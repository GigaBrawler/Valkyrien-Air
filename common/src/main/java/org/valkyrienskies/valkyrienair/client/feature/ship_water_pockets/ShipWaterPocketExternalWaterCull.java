package org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.shaders.Uniform;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
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
 * Updates uniforms/samplers for the patched {@code rendertype_translucent} shader to cull *world* water surfaces inside
 * ship interiors (air pockets) without affecting ship-rendered water.
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

    private static int fluidMaskTexId = 0;
    private static int fluidMaskWidth = 0;
    private static int fluidMaskHeight = 0;
    private static int fluidMaskLastAtlasTexId = 0;
    private static byte[] fluidMaskData = null;
    private static ByteBuffer fluidMaskBuffer = null;
    private static boolean loggedFluidMaskBuildFailed = false;

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
        private int fluidMaskLoc = -1;
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

        if (fluidMaskTexId != 0) {
            TextureUtil.releaseTextureId(fluidMaskTexId);
            fluidMaskTexId = 0;
        }
        fluidMaskWidth = 0;
        fluidMaskHeight = 0;
        fluidMaskLastAtlasTexId = 0;
        fluidMaskData = null;
        fluidMaskBuffer = null;
        loggedFluidMaskBuildFailed = false;

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
        SHADER.shader.setSampler("ValkyrienAir_FluidMask", ensureFluidMaskTexture(level));
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
        bindProgramFluidMaskTexture(handles, ensureFluidMaskTexture(level));
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
            SHADER.worldToShip[i] = shader.getUniform("ValkyrienAir_WorldToShip" + i);
            if (SHADER.shipAabbMin[i] == null || SHADER.shipAabbMax[i] == null || SHADER.gridMin[i] == null ||
                SHADER.gridSize[i] == null || SHADER.worldToShip[i] == null || SHADER.cameraShipPos[i] == null) {
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
        final int availableUnitsForShipMasks = Math.max(0, availableUnits - 1); // Reserve 1 unit for the fluid mask.
        handles.maxMaskSlots = Math.max(0, Math.min(MAX_SHIPS, availableUnitsForShipMasks / 2));

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
        handles.fluidMaskLoc = GL20.glGetUniformLocation(programId, "ValkyrienAir_FluidMask");
        handles.shipWaterTintEnabledLoc = GL20.glGetUniformLocation(programId, "ValkyrienAir_ShipWaterTintEnabled");
        handles.shipWaterTintLoc = GL20.glGetUniformLocation(programId, "ValkyrienAir_ShipWaterTint");
        handles.chunkWorldOriginLoc = GL20.glGetUniformLocation(programId, "ValkyrienAir_ChunkWorldOrigin");

		        for (int i = 0; i < MAX_SHIPS; i++) {
		            handles.shipAabbMinLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_ShipAabbMin" + i);
		            handles.shipAabbMaxLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_ShipAabbMax" + i);
		            handles.cameraShipPosLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_CameraShipPos" + i);
		            handles.gridMinLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_GridMin" + i);
		            handles.gridSizeLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_GridSize" + i);
		            handles.worldToShipLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_WorldToShip" + i);
		            handles.airMaskLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_AirMask" + i);
		            handles.occMaskLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_OccMask" + i);

		            handles.shipSlotSupported[i] =
		                i < handles.maxMaskSlots &&
		                    handles.shipAabbMinLoc[i] >= 0 &&
		                    handles.shipAabbMaxLoc[i] >= 0 &&
		                    handles.gridSizeLoc[i] >= 0 &&
		                    handles.worldToShipLoc[i] >= 0 &&
		                    handles.airMaskLoc[i] >= 0 &&
		                    handles.occMaskLoc[i] >= 0;
		        }

        final boolean requiredOk =
            looksLikeEmbeddiumChunkProgram &&
                handles.cullEnabledLoc >= 0 &&
                handles.isShipPassLoc >= 0 &&
                handles.cameraWorldPosLoc >= 0 &&
                handles.fluidMaskLoc >= 0 &&
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
        SHADER.worldToShip[slot].set(IDENTITY_MAT4);

        SHADER.shipAabbMin[slot].upload();
        SHADER.shipAabbMax[slot].upload();
        SHADER.cameraShipPos[slot].upload();
        SHADER.gridMin[slot].upload();
        SHADER.gridSize[slot].upload();
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

    private static void bindProgramFluidMaskTexture(final ProgramHandles handles, final int fluidMaskTexId) {
        if (handles == null) return;
        if (handles.fluidMaskLoc < 0) return;

        // Bind after the per-ship (air/occ) units.
        final int fluidUnit = BASE_MASK_TEX_UNIT + handles.maxMaskSlots * 2;
        if (fluidUnit < 0 || fluidUnit >= handles.maxSafeTextureUnits) return;

        GL20.glUniform1i(handles.fluidMaskLoc, fluidUnit);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + fluidUnit);
        GlStateManager._bindTexture(fluidMaskTexId);
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
            // Cull world-water surfaces inside ship interior volumes (including flooded interiors).
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

    private static int ensureFluidMaskTexture(final ClientLevel level) {
        if (level == null) return 0;

        final Minecraft mc = Minecraft.getInstance();
        final AbstractTexture atlasTexture = mc.getTextureManager().getTexture(InventoryMenu.BLOCK_ATLAS);
        if (atlasTexture == null) return 0;

        final int atlasTexId = atlasTexture.getId();
        if (atlasTexId == 0) return 0;

        int atlasWidth = 0;
        int atlasHeight = 0;

        final int prevBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GlStateManager._bindTexture(atlasTexId);
            atlasWidth = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            atlasHeight = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        } finally {
            GlStateManager._bindTexture(prevBinding);
        }

        if (atlasWidth <= 0 || atlasHeight <= 0) return 0;

        final boolean needsRebuild =
            fluidMaskTexId == 0 ||
                fluidMaskWidth != atlasWidth ||
                fluidMaskHeight != atlasHeight ||
                fluidMaskLastAtlasTexId != atlasTexId;

        if (!needsRebuild) return fluidMaskTexId;

        if (fluidMaskTexId != 0 && (fluidMaskWidth != atlasWidth || fluidMaskHeight != atlasHeight)) {
            TextureUtil.releaseTextureId(fluidMaskTexId);
            fluidMaskTexId = 0;
        }

        fluidMaskTexId = ensureByteTexture(fluidMaskTexId, atlasWidth, atlasHeight);
        fluidMaskWidth = atlasWidth;
        fluidMaskHeight = atlasHeight;
        fluidMaskLastAtlasTexId = atlasTexId;

        final int capacity = atlasWidth * atlasHeight;
        if (fluidMaskData == null || fluidMaskData.length != capacity) {
            fluidMaskData = new byte[capacity];
            fluidMaskBuffer = BufferUtils.createByteBuffer(capacity);
        } else {
            Arrays.fill(fluidMaskData, (byte) 0);
        }

        final Function<ResourceLocation, TextureAtlasSprite> atlas = mc.getTextureAtlas(InventoryMenu.BLOCK_ATLAS);
        final List<TextureAtlasSprite> sprites = new ArrayList<>();

        // Best-effort: gather all fluid sprites (modded + vanilla). Never hard-fail the whole mask because one fluid is
        // broken; per-fluid failures are ignored.
        collectFluidSpritesForge(level, atlas, sprites);
        collectFluidSpritesFabric(level, sprites);

        // Always include vanilla fluids so culling still works even if modded sprite discovery fails.
        sprites.add(atlas.apply(WATER_STILL));
        sprites.add(atlas.apply(WATER_FLOW));
        sprites.add(atlas.apply(WATER_OVERLAY));
        sprites.add(atlas.apply(LAVA_STILL));
        sprites.add(atlas.apply(LAVA_FLOW));

        final Set<ResourceLocation> seenSprites = new HashSet<>();
        for (final TextureAtlasSprite sprite : sprites) {
            if (sprite == null) continue;
            final ResourceLocation name = sprite.contents().name();
            if (!seenSprites.add(name)) continue;

            final int x0 = sprite.getX();
            final int y0 = sprite.getY();
            final int w = sprite.contents().width();
            final int h = sprite.contents().height();

            if (w <= 0 || h <= 0) continue;
            if (x0 < 0 || y0 < 0) continue;

            final int x1 = Math.min(atlasWidth, x0 + w);
            final int y1 = Math.min(atlasHeight, y0 + h);
            if (x1 <= x0 || y1 <= y0) continue;

            for (int y = y0; y < y1; y++) {
                final int rowBase = y * atlasWidth;
                Arrays.fill(fluidMaskData, rowBase + x0, rowBase + x1, (byte) 0xFF);
            }
        }

        fluidMaskBuffer.clear();
        fluidMaskBuffer.put(fluidMaskData);
        fluidMaskBuffer.flip();

        uploadByteTexture(fluidMaskTexId, atlasWidth, atlasHeight, fluidMaskBuffer);
        return fluidMaskTexId;
    }

    private static boolean collectFluidSpritesForge(final ClientLevel level, final Function<ResourceLocation, TextureAtlasSprite> atlas,
        final List<TextureAtlasSprite> outSprites) {
        try {
            final Class<?> extClass = Class.forName("net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions");
            final Method of = extClass.getMethod("of", Fluid.class);

            final Method getStill0 = findMethod(extClass, "getStillTexture");
            final Method getFlow0 = findMethod(extClass, "getFlowingTexture");
            final Method getOverlay0 = findMethod(extClass, "getOverlayTexture");

            final Method getStill3 = findMethod(extClass, "getStillTexture", FluidState.class, BlockAndTintGetter.class, BlockPos.class);
            final Method getFlow3 = findMethod(extClass, "getFlowingTexture", FluidState.class, BlockAndTintGetter.class, BlockPos.class);
            final Method getOverlay3 = findMethod(extClass, "getOverlayTexture", FluidState.class, BlockAndTintGetter.class, BlockPos.class);

            final Set<ResourceLocation> textureIds = new HashSet<>();
            for (final Fluid fluid : BuiltInRegistries.FLUID) {
                try {
                    final Object ext = of.invoke(null, fluid);
                    if (ext == null) continue;

                    final FluidState fluidState = fluid.defaultFluidState();

                    final ResourceLocation still = invokeTexture(ext, getStill0, getStill3, fluidState, level);
                    final ResourceLocation flow = invokeTexture(ext, getFlow0, getFlow3, fluidState, level);
                    final ResourceLocation overlay = invokeTexture(ext, getOverlay0, getOverlay3, fluidState, level);

                    if (still != null && textureIds.add(still)) outSprites.add(atlas.apply(still));
                    if (flow != null && textureIds.add(flow)) outSprites.add(atlas.apply(flow));
                    if (overlay != null && textureIds.add(overlay)) outSprites.add(atlas.apply(overlay));
                } catch (final Throwable ignored) {
                    // Some modded fluids can throw from their render properties; ignore per-fluid failures so we still
                    // build a useful mask.
                }
            }

            return true;
        } catch (final ClassNotFoundException ignored) {
            return false;
        } catch (final Throwable t) {
            if (!loggedFluidMaskBuildFailed) {
                loggedFluidMaskBuildFailed = true;
                LOGGER.warn("Failed to build fluid sprite mask via Forge fluid render properties; falling back to Fabric/vanilla.", t);
            }
            return false;
        }
    }

    private static boolean collectFluidSpritesFabric(final ClientLevel level, final List<TextureAtlasSprite> outSprites) {
        try {
            final Class<?> registryClass = Class.forName("net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry");
            final Field instanceField = registryClass.getField("INSTANCE");
            final Object registry = instanceField.get(null);
            final Method getHandler = registryClass.getMethod("get", Fluid.class);

            final Class<?> handlerClass = Class.forName("net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler");
            final Method getSprites = handlerClass.getMethod(
                "getFluidSprites",
                net.minecraft.world.level.BlockAndTintGetter.class,
                BlockPos.class,
                FluidState.class
            );

            for (final Fluid fluid : BuiltInRegistries.FLUID) {
                try {
                    final Object handler = getHandler.invoke(registry, fluid);
                    if (handler == null) continue;

                    final FluidState fluidState = fluid.defaultFluidState();
                    final TextureAtlasSprite[] sprites = (TextureAtlasSprite[]) getSprites.invoke(handler, level, BlockPos.ZERO, fluidState);
                    if (sprites == null) continue;
                    for (final TextureAtlasSprite sprite : sprites) {
                        if (sprite != null) outSprites.add(sprite);
                    }
                } catch (final Throwable ignored) {
                    // Per-fluid handler failures should not break the entire mask.
                }
            }

            return true;
        } catch (final ClassNotFoundException ignored) {
            return false;
        } catch (final Throwable t) {
            if (!loggedFluidMaskBuildFailed) {
                loggedFluidMaskBuildFailed = true;
                LOGGER.warn("Failed to build fluid sprite mask via Fabric fluid render handlers; falling back to vanilla.", t);
            }
            return false;
        }
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

    private static int ensureByteTexture(final int existingId, final int width, final int height) {
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
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, width, height, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE,
                (ByteBuffer) null);

            return id;
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevUnpackAlignment);
            GlStateManager._bindTexture(prevBinding);
        }
    }

    private static void uploadByteTexture(final int texId, final int width, final int height, final ByteBuffer data) {
        final int prevBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        final int prevUnpackAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        try {
            GlStateManager._bindTexture(texId);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, data);
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
