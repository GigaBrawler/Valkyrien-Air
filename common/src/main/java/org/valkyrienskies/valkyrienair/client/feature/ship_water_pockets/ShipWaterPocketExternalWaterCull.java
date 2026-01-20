package org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.shaders.Uniform;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
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
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.primitives.AABBdc;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
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

    private static final int MAX_SHIPS = 4;
    private static final int SUB = 8;
    private static final int OCC_WORDS_PER_VOXEL = (SUB * SUB * SUB) / 32; // 512 bits / 32 = 16

    // Must match shader constants (power-of-two).
    private static final int MASK_TEX_WIDTH = 4096;
    private static final int MASK_TEX_WIDTH_MASK = MASK_TEX_WIDTH - 1;
    private static final int MASK_TEX_WIDTH_SHIFT = 12;

    private static final ResourceLocation WATER_STILL = new ResourceLocation("minecraft", "block/water_still");
    private static final ResourceLocation WATER_FLOW = new ResourceLocation("minecraft", "block/water_flow");
    private static final ResourceLocation WATER_OVERLAY = new ResourceLocation("minecraft", "block/water_overlay");

    private static final Matrix4f IDENTITY_MAT4 = new Matrix4f();

    private static ClientLevel lastLevel = null;
    private static final Int2ObjectOpenHashMap<ProgramHandles> PROGRAM_HANDLES = new Int2ObjectOpenHashMap<>();
    private static boolean programSupported = false;
    private static final ThreadLocal<FloatBuffer> MATRIX_BUFFER =
        ThreadLocal.withInitial(() -> BufferUtils.createFloatBuffer(16));

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
        private long lastAirUploadTick = Long.MIN_VALUE;

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

    private static final class ShaderHandles {
        private ShaderInstance shader;
        private boolean supported;

        private Uniform cullEnabled;
        private Uniform isShipPass;
        private Uniform cameraWorldPos;
        private Uniform waterStillUv;
        private Uniform waterFlowUv;
        private Uniform waterOverlayUv;

        private final Uniform[] shipAabbMin = new Uniform[MAX_SHIPS];
        private final Uniform[] shipAabbMax = new Uniform[MAX_SHIPS];
        private final Uniform[] cameraShipPos = new Uniform[MAX_SHIPS];
        private final Uniform[] gridMin = new Uniform[MAX_SHIPS];
        private final Uniform[] gridSize = new Uniform[MAX_SHIPS];
        private final Uniform[] worldToShip = new Uniform[MAX_SHIPS];
    }

    private static final class ProgramHandles {
        private final int programId;
        private boolean supported;
        private boolean maskUnitsAllocated;

        private int cullEnabledLoc = -1;
        private int isShipPassLoc = -1;
        private int cameraWorldPosLoc = -1;
        private int waterStillUvLoc = -1;
        private int waterFlowUvLoc = -1;
        private int waterOverlayUvLoc = -1;

        private final int[] shipAabbMinLoc = new int[MAX_SHIPS];
        private final int[] shipAabbMaxLoc = new int[MAX_SHIPS];
        private final int[] cameraShipPosLoc = new int[MAX_SHIPS];
        private final int[] gridMinLoc = new int[MAX_SHIPS];
        private final int[] gridSizeLoc = new int[MAX_SHIPS];
        private final int[] worldToShipLoc = new int[MAX_SHIPS];
        private final int[] airMaskLoc = new int[MAX_SHIPS];
        private final int[] occMaskLoc = new int[MAX_SHIPS];

        private final int[] airMaskUnit = new int[MAX_SHIPS];
        private final int[] occMaskUnit = new int[MAX_SHIPS];

        private ProgramHandles(final int programId) {
            this.programId = programId;
            java.util.Arrays.fill(shipAabbMinLoc, -1);
            java.util.Arrays.fill(shipAabbMaxLoc, -1);
            java.util.Arrays.fill(cameraShipPosLoc, -1);
            java.util.Arrays.fill(gridMinLoc, -1);
            java.util.Arrays.fill(gridSizeLoc, -1);
            java.util.Arrays.fill(worldToShipLoc, -1);
            java.util.Arrays.fill(airMaskLoc, -1);
            java.util.Arrays.fill(occMaskLoc, -1);
            java.util.Arrays.fill(airMaskUnit, -1);
            java.util.Arrays.fill(occMaskUnit, -1);
        }
    }

    private static final ShaderHandles SHADER = new ShaderHandles();

    private static boolean everEnabled = false;
    private static boolean shaderEverSupported = false;

    public static void clear() {
        SHADER.shader = null;
        SHADER.supported = false;

        everEnabled = false;
        shaderEverSupported = false;
        programSupported = false;
        PROGRAM_HANDLES.clear();

        for (final ShipMasks masks : SHIP_MASKS.values()) {
            masks.close();
        }
        SHIP_MASKS.clear();

        lastLevel = null;
    }

    public static boolean isShaderCullingActive() {
        if (!ValkyrienAirConfig.getEnableShipWaterPockets()) return false;
        return (shaderEverSupported && everEnabled) || programSupported;
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
        setupForWorldTranslucentPassProgram(programId, level, cameraX, cameraY, cameraZ, false);
    }

    public static void setupForWorldTranslucentPassProgram(final int programId, final ClientLevel level,
        final double cameraX, final double cameraY, final double cameraZ, final boolean shipPass) {
        if (level == null || programId == 0) return;

        RenderSystem.assertOnRenderThread();

        if (lastLevel != level) {
            clear();
            lastLevel = level;
        }

        final ProgramHandles handles = bindProgramHandles(programId);
        if (!handles.supported) return;

        if (!ValkyrienAirConfig.getEnableShipWaterPockets()) {
            disableProgram(programId);
            return;
        }

        if (!ensureMaskTextureUnits(handles)) {
            disableProgram(programId);
            return;
        }

        if (handles.cullEnabledLoc >= 0) {
            GL20.glUniform1f(handles.cullEnabledLoc, 1.0f);
        }
        if (handles.isShipPassLoc >= 0) {
            GL20.glUniform1f(handles.isShipPassLoc, shipPass ? 1.0f : 0.0f);
        }
        programSupported = true;

        if (handles.cameraWorldPosLoc >= 0) {
            GL20.glUniform3f(handles.cameraWorldPosLoc, (float) cameraX, (float) cameraY, (float) cameraZ);
        }

        final Vec3 cameraPos = new Vec3(cameraX, cameraY, cameraZ);
        updateWaterUvProgram(handles);

        final List<LoadedShip> ships = selectClosestShips(level, cameraPos, MAX_SHIPS);
        updateShipUniformsAndMasksProgram(handles, level, ships, cameraX, cameraY, cameraZ);
    }

    public static void disableProgram(final int programId) {
        if (programId == 0) return;
        RenderSystem.assertOnRenderThread();

        final ProgramHandles handles = PROGRAM_HANDLES.get(programId);
        if (handles == null || !handles.supported) return;

        if (handles.cullEnabledLoc >= 0) {
            GL20.glUniform1f(handles.cullEnabledLoc, 0.0f);
        }
        if (handles.isShipPassLoc >= 0) {
            GL20.glUniform1f(handles.isShipPassLoc, 0.0f);
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
        handles.cullEnabledLoc = GL20.glGetUniformLocation(programId, "ValkyrienAir_CullEnabled");
        if (handles.cullEnabledLoc < 0) {
            handles.supported = false;
            PROGRAM_HANDLES.put(programId, handles);
            return handles;
        }

        handles.isShipPassLoc = GL20.glGetUniformLocation(programId, "ValkyrienAir_IsShipPass");
        handles.cameraWorldPosLoc = GL20.glGetUniformLocation(programId, "ValkyrienAir_CameraWorldPos");
        handles.waterStillUvLoc = GL20.glGetUniformLocation(programId, "ValkyrienAir_WaterStillUv");
        handles.waterFlowUvLoc = GL20.glGetUniformLocation(programId, "ValkyrienAir_WaterFlowUv");
        handles.waterOverlayUvLoc = GL20.glGetUniformLocation(programId, "ValkyrienAir_WaterOverlayUv");

        boolean ok = handles.isShipPassLoc >= 0;

        for (int i = 0; i < MAX_SHIPS; i++) {
            handles.shipAabbMinLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_ShipAabbMin" + i);
            handles.shipAabbMaxLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_ShipAabbMax" + i);
            handles.cameraShipPosLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_CameraShipPos" + i);
            handles.gridMinLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_GridMin" + i);
            handles.gridSizeLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_GridSize" + i);
            handles.worldToShipLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_WorldToShip" + i);
            handles.airMaskLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_AirMask" + i);
            handles.occMaskLoc[i] = GL20.glGetUniformLocation(programId, "ValkyrienAir_OccMask" + i);

            ok &= handles.shipAabbMinLoc[i] >= 0;
            ok &= handles.shipAabbMaxLoc[i] >= 0;
            ok &= handles.cameraShipPosLoc[i] >= 0;
            ok &= handles.gridMinLoc[i] >= 0;
            ok &= handles.gridSizeLoc[i] >= 0;
            ok &= handles.worldToShipLoc[i] >= 0;
            ok &= handles.airMaskLoc[i] >= 0;
            ok &= handles.occMaskLoc[i] >= 0;
        }

        handles.supported = ok;
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

    private static void updateWaterUvProgram(final ProgramHandles handles) {
        if (handles.waterStillUvLoc < 0 && handles.waterFlowUvLoc < 0 && handles.waterOverlayUvLoc < 0) return;

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

            updateAirMask(level, masks, snapshot, gameTime);

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

            final Matrix4dc worldToShip = getShipTransform(ship).getWorldToShip();
            final double biasedM30 = worldToShip.m30() - (double) minX;
            final double biasedM31 = worldToShip.m31() - (double) minY;
            final double biasedM32 = worldToShip.m32() - (double) minZ;
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

            updateAirMask(level, masks, snapshot, gameTime);

            bindProgramMaskTextures(handles, slot, masks);

            if (handles.shipAabbMinLoc[slot] >= 0) {
                GL20.glUniform4f(handles.shipAabbMinLoc[slot],
                    (float) shipWorldAabbDc.minX(), (float) shipWorldAabbDc.minY(), (float) shipWorldAabbDc.minZ(), 0.0f);
            }
            if (handles.shipAabbMaxLoc[slot] >= 0) {
                GL20.glUniform4f(handles.shipAabbMaxLoc[slot],
                    (float) shipWorldAabbDc.maxX(), (float) shipWorldAabbDc.maxY(), (float) shipWorldAabbDc.maxZ(), 0.0f);
            }
            if (handles.gridMinLoc[slot] >= 0) {
                GL20.glUniform4f(handles.gridMinLoc[slot], 0.0f, 0.0f, 0.0f, 0.0f);
            }
            if (handles.gridSizeLoc[slot] >= 0) {
                GL20.glUniform4f(handles.gridSizeLoc[slot], (float) sizeX, (float) sizeY, (float) sizeZ, 0.0f);
            }

            final Matrix4dc worldToShip = getShipTransform(ship).getWorldToShip();
            final double biasedM30 = worldToShip.m30() - (double) minX;
            final double biasedM31 = worldToShip.m31() - (double) minY;
            final double biasedM32 = worldToShip.m32() - (double) minZ;
            masks.worldToShip.set(
                (float) worldToShip.m00(), (float) worldToShip.m01(), (float) worldToShip.m02(), (float) worldToShip.m03(),
                (float) worldToShip.m10(), (float) worldToShip.m11(), (float) worldToShip.m12(), (float) worldToShip.m13(),
                (float) worldToShip.m20(), (float) worldToShip.m21(), (float) worldToShip.m22(), (float) worldToShip.m23(),
                (float) biasedM30, (float) biasedM31, (float) biasedM32, (float) worldToShip.m33()
            );
            uploadMatrixUniform(handles.worldToShipLoc[slot], masks.worldToShip);

            if (handles.cameraShipPosLoc[slot] >= 0) {
                final double camShipX = worldToShip.m00() * cameraX + worldToShip.m10() * cameraY + worldToShip.m20() * cameraZ + biasedM30;
                final double camShipY = worldToShip.m01() * cameraX + worldToShip.m11() * cameraY + worldToShip.m21() * cameraZ + biasedM31;
                final double camShipZ = worldToShip.m02() * cameraX + worldToShip.m12() * cameraY + worldToShip.m22() * cameraZ + biasedM32;
                GL20.glUniform3f(handles.cameraShipPosLoc[slot], (float) camShipX, (float) camShipY, (float) camShipZ);
            }
        }

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
        if (handles.airMaskLoc[slot] >= 0) {
            GL20.glUniform1i(handles.airMaskLoc[slot], 0);
        }
        if (handles.occMaskLoc[slot] >= 0) {
            GL20.glUniform1i(handles.occMaskLoc[slot], 0);
        }

        final int airUnit = handles.airMaskUnit[slot];
        final int occUnit = handles.occMaskUnit[slot];
        if (airUnit >= 0 && occUnit >= 0) {
            final int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + airUnit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + occUnit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL13.glActiveTexture(prevActive);
        }

        if (handles.shipAabbMinLoc[slot] >= 0) {
            GL20.glUniform4f(handles.shipAabbMinLoc[slot], 0.0f, 0.0f, 0.0f, 0.0f);
        }
        if (handles.shipAabbMaxLoc[slot] >= 0) {
            GL20.glUniform4f(handles.shipAabbMaxLoc[slot], -1.0f, -1.0f, -1.0f, 0.0f);
        }
        if (handles.gridMinLoc[slot] >= 0) {
            GL20.glUniform4f(handles.gridMinLoc[slot], 0.0f, 0.0f, 0.0f, 0.0f);
        }
        if (handles.gridSizeLoc[slot] >= 0) {
            GL20.glUniform4f(handles.gridSizeLoc[slot], 0.0f, 0.0f, 0.0f, 0.0f);
        }
        if (handles.cameraShipPosLoc[slot] >= 0) {
            GL20.glUniform3f(handles.cameraShipPosLoc[slot], 0.0f, 0.0f, 0.0f);
        }
        uploadMatrixUniform(handles.worldToShipLoc[slot], IDENTITY_MAT4);
    }

    private static void bindProgramMaskTextures(final ProgramHandles handles, final int slot, final ShipMasks masks) {
        final int airUnit = handles.airMaskUnit[slot];
        final int occUnit = handles.occMaskUnit[slot];
        if (airUnit < 0 || occUnit < 0) return;

        if (handles.airMaskLoc[slot] >= 0) {
            GL20.glUniform1i(handles.airMaskLoc[slot], airUnit);
        }
        if (handles.occMaskLoc[slot] >= 0) {
            GL20.glUniform1i(handles.occMaskLoc[slot], occUnit);
        }

        final int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + airUnit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, masks.airTexId);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + occUnit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, masks.occTexId);
        GL13.glActiveTexture(prevActive);
    }

    private static boolean ensureMaskTextureUnits(final ProgramHandles handles) {
        if (handles.maskUnitsAllocated) return true;

        final int maxUnits = GL11.glGetInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS);
        if (maxUnits <= 0) return false;

        final boolean[] used = new boolean[maxUnits];
        markUsedTextureUnits(handles.programId, used);

        final int neededUnits = MAX_SHIPS * 2;
        final int[] allocated = new int[neededUnits];
        int found = 0;

        for (int unit = maxUnits - 1; unit >= 0 && found < neededUnits; unit--) {
            if (used[unit]) continue;
            allocated[found++] = unit;
            used[unit] = true;
        }

        if (found < neededUnits) return false;

        for (int slot = 0; slot < MAX_SHIPS; slot++) {
            handles.airMaskUnit[slot] = allocated[slot * 2];
            handles.occMaskUnit[slot] = allocated[slot * 2 + 1];
        }

        handles.maskUnitsAllocated = true;
        return true;
    }

    private static void markUsedTextureUnits(final int programId, final boolean[] usedUnits) {
        final int uniformCount = GL20.glGetProgrami(programId, GL20.GL_ACTIVE_UNIFORMS);
        final IntBuffer sizeBuf = BufferUtils.createIntBuffer(1);
        final IntBuffer typeBuf = BufferUtils.createIntBuffer(1);

        for (int i = 0; i < uniformCount; i++) {
            sizeBuf.clear();
            typeBuf.clear();
            final String name = GL20.glGetActiveUniform(programId, i, sizeBuf, typeBuf);
            if (name == null || name.isEmpty()) continue;

            final int type = typeBuf.get(0);
            if (!isSamplerUniformType(type)) continue;

            final int location = GL20.glGetUniformLocation(programId, name);
            if (location < 0) continue;

            final int count = Math.max(1, sizeBuf.get(0));
            final int[] values = new int[count];
            GL20.glGetUniformiv(programId, location, values);

            for (final int value : values) {
                if (value < 0 || value >= usedUnits.length) continue;
                usedUnits[value] = true;
            }
        }
    }

    private static boolean isSamplerUniformType(final int type) {
        return type == GL20.GL_SAMPLER_1D ||
            type == GL20.GL_SAMPLER_2D ||
            type == GL20.GL_SAMPLER_3D ||
            type == GL20.GL_SAMPLER_CUBE ||
            type == GL20.GL_SAMPLER_1D_SHADOW ||
            type == GL20.GL_SAMPLER_2D_SHADOW ||
            type == GL30.GL_SAMPLER_1D_ARRAY ||
            type == GL30.GL_SAMPLER_2D_ARRAY ||
            type == GL30.GL_SAMPLER_1D_ARRAY_SHADOW ||
            type == GL30.GL_SAMPLER_2D_ARRAY_SHADOW ||
            type == GL30.GL_SAMPLER_CUBE_SHADOW ||
            type == GL31.GL_SAMPLER_BUFFER ||
            type == GL31.GL_SAMPLER_2D_RECT ||
            type == GL31.GL_SAMPLER_2D_RECT_SHADOW ||
            type == GL32.GL_SAMPLER_2D_MULTISAMPLE ||
            type == GL32.GL_SAMPLER_2D_MULTISAMPLE_ARRAY ||
            type == GL30.GL_INT_SAMPLER_1D ||
            type == GL30.GL_INT_SAMPLER_2D ||
            type == GL30.GL_INT_SAMPLER_3D ||
            type == GL30.GL_INT_SAMPLER_CUBE ||
            type == GL30.GL_INT_SAMPLER_1D_ARRAY ||
            type == GL30.GL_INT_SAMPLER_2D_ARRAY ||
            type == GL31.GL_INT_SAMPLER_2D_RECT ||
            type == GL31.GL_INT_SAMPLER_BUFFER ||
            type == GL32.GL_INT_SAMPLER_2D_MULTISAMPLE ||
            type == GL32.GL_INT_SAMPLER_2D_MULTISAMPLE_ARRAY ||
            type == GL30.GL_UNSIGNED_INT_SAMPLER_1D ||
            type == GL30.GL_UNSIGNED_INT_SAMPLER_2D ||
            type == GL30.GL_UNSIGNED_INT_SAMPLER_3D ||
            type == GL30.GL_UNSIGNED_INT_SAMPLER_CUBE ||
            type == GL30.GL_UNSIGNED_INT_SAMPLER_1D_ARRAY ||
            type == GL30.GL_UNSIGNED_INT_SAMPLER_2D_ARRAY ||
            type == GL31.GL_UNSIGNED_INT_SAMPLER_2D_RECT ||
            type == GL31.GL_UNSIGNED_INT_SAMPLER_BUFFER ||
            type == GL32.GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE ||
            type == GL32.GL_UNSIGNED_INT_SAMPLER_2D_MULTISAMPLE_ARRAY;
    }

    private static void uploadMatrixUniform(final int location, final Matrix4f matrix) {
        if (location < 0) return;
        final FloatBuffer buffer = MATRIX_BUFFER.get();
        buffer.clear();
        matrix.get(buffer);
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

    private static void updateAirMask(final ClientLevel level, final ShipMasks masks,
        final ShipWaterPocketManager.ClientWaterReachableSnapshot snapshot, final long gameTime) {
        if (masks.lastAirUploadTick == gameTime) return;
        masks.lastAirUploadTick = gameTime;

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

        final int capacity = MASK_TEX_WIDTH * height;
        if (masks.airData == null || masks.airData.length != capacity) {
            masks.airData = new int[capacity];
            masks.airBuffer = BufferUtils.createIntBuffer(capacity);
        } else {
            java.util.Arrays.fill(masks.airData, 0);
        }

        final var open = snapshot.getOpen();
        final var waterReachable = snapshot.getWaterReachable();

        if (open != null && waterReachable != null) {
            // air = open && !waterReachable
            for (int idx = open.nextSetBit(0); idx >= 0; idx = open.nextSetBit(idx + 1)) {
                if (waterReachable.get(idx)) continue;
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
