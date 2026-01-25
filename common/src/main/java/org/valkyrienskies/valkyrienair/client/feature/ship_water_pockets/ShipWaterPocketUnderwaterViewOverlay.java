package org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets;

import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.primitives.AABBdc;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.LoadedShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.valkyrienair.config.ValkyrienAirConfig;
import org.valkyrienskies.valkyrienair.feature.ship_water_pockets.ShipWaterPocketManager;

/**
 * Renders an underwater "diffusion" overlay on submerged ship openings and transparent blocks.
 *
 * <p>This is a geometry-based effect (not a screen overlay) so it works for holes/open voxels and doesn't require
 * forcing the player's camera into underwater fog. It is intentionally conservative and only renders on faces that
 * separate ship interior space from outside, water-reachable world water.</p>
 */
public final class ShipWaterPocketUnderwaterViewOverlay {

    private ShipWaterPocketUnderwaterViewOverlay() {}

    private static final Logger LOGGER = LogManager.getLogger("ValkyrienAir UnderwaterViewOverlay");

    private static final ResourceLocation UNDERWATER_OVERLAY_TEX =
        new ResourceLocation("minecraft", "textures/misc/underwater.png");

    private static final RenderType OVERLAY_RENDER_TYPE = RenderType.entityTranslucent(UNDERWATER_OVERLAY_TEX);

    private static final int MAX_SHIPS = 8;
    private static final int MAX_WATER_SURFACE_CACHE = 8192;

    private static final float OVERLAY_ALPHA = 0.75f;
    private static final float FACE_EPS = 0.0025f;
    private static final int FULL_BRIGHT = 0x00F000F0;

    private static final double SURFACE_EPS = 1e-5;

    private static final float[] CLIP_X0 = new float[6];
    private static final float[] CLIP_Y0 = new float[6];
    private static final float[] CLIP_Z0 = new float[6];
    private static final float[] CLIP_U0 = new float[6];
    private static final float[] CLIP_V0 = new float[6];
    private static final float[] CLIP_X1 = new float[6];
    private static final float[] CLIP_Y1 = new float[6];
    private static final float[] CLIP_Z1 = new float[6];
    private static final float[] CLIP_U1 = new float[6];
    private static final float[] CLIP_V1 = new float[6];

    private static final class ShipCache {
        private final long shipId;
        private long geometryRevision;
        private int minX;
        private int minY;
        private int minZ;
        private int sizeX;
        private int sizeY;
        private int sizeZ;
        private BitSet transparentSolids;

        private ShipCache(final long shipId) {
            this.shipId = shipId;
        }
    }

    private static final Map<Long, ShipCache> SHIP_CACHE = new HashMap<>();

    private static net.minecraft.client.multiplayer.ClientLevel lastSurfaceCacheLevel = null;
    private static final Long2DoubleOpenHashMap waterSurfaceCache = new Long2DoubleOpenHashMap();

    private static boolean loggedHookActive = false;
    private static boolean loggedAnyFaces = false;

    public static void render(final double camX, final double camY, final double camZ) {
        if (!ValkyrienAirConfig.getEnableShipWaterPockets()) return;

        final Minecraft mc = Minecraft.getInstance();
        final var level = mc.level;
        if (level == null || mc.gameRenderer == null) return;
        if (lastSurfaceCacheLevel != level) {
            lastSurfaceCacheLevel = level;
            waterSurfaceCache.clear();
        }

        final Camera camera = mc.gameRenderer.getMainCamera();
        final FogType fogType = camera.getFluidInCamera();
        if (fogType == FogType.WATER) return;

        final Vec3 cameraPos = new Vec3(camX, camY, camZ);
        final List<LoadedShip> ships = selectClosestShips(level, cameraPos, MAX_SHIPS);
        if (ships.isEmpty()) return;

        if (!loggedHookActive) {
            loggedHookActive = true;
            LOGGER.info("UnderwaterViewOverlay hook active (ships={}, fogType={})", ships.size(), fogType);
        }

        final float time = (level.getGameTime() + mc.getFrameTime()) * 0.02f;

        // Use a dedicated immediate buffer source so we don't interfere with the main world/entity batching order.
        final MultiBufferSource.BufferSource bufferSource = MultiBufferSource.immediate(Tesselator.getInstance().getBuilder());
        final VertexConsumer vc = bufferSource.getBuffer(OVERLAY_RENDER_TYPE);

        int facesThisFrame = 0;

        for (final LoadedShip ship : ships) {
            final long shipId = ship.getId();
            final ShipWaterPocketManager.ClientWaterReachableSnapshot snapshot =
                ShipWaterPocketManager.getClientWaterReachableSnapshot(level, shipId);
            if (snapshot == null) continue;

            final ShipCache cache = SHIP_CACHE.computeIfAbsent(shipId, ShipCache::new);
            ensureTransparentSolids(level, cache, snapshot);

            final ShipTransform shipTransform = getShipTransform(ship);
            final Matrix4dc shipToWorld = shipTransform.getShipToWorld();
            final Matrix4f shipToWorldF = new Matrix4f(shipToWorld);

            // IMPORTANT: Shipyard/ship-space coordinates can be extremely large, which destroys float precision if
            // we emit vertices in absolute ship-space. Render using a local [0..size] grid and bake the grid origin
            // (minX/minY/minZ) into the translation in double precision.
            final double minX = cache.minX;
            final double minY = cache.minY;
            final double minZ = cache.minZ;

            final double biasedM30 = shipToWorld.m00() * minX + shipToWorld.m10() * minY + shipToWorld.m20() * minZ + shipToWorld.m30();
            final double biasedM31 = shipToWorld.m01() * minX + shipToWorld.m11() * minY + shipToWorld.m21() * minZ + shipToWorld.m31();
            final double biasedM32 = shipToWorld.m02() * minX + shipToWorld.m12() * minY + shipToWorld.m22() * minZ + shipToWorld.m32();

            shipToWorldF.m30((float) (biasedM30 - camX));
            shipToWorldF.m31((float) (biasedM31 - camY));
            shipToWorldF.m32((float) (biasedM32 - camZ));

            final int waterTintRgb = computeWaterTintRgb(level, shipTransform);
            final float r = ((waterTintRgb >> 16) & 0xFF) / 255.0f;
            final float g = ((waterTintRgb >> 8) & 0xFF) / 255.0f;
            final float b = (waterTintRgb & 0xFF) / 255.0f;

            facesThisFrame += emitOverlayFaces(
                level,
                shipToWorldF,
                vc,
                cache,
                snapshot,
                shipToWorld.m00(),
                shipToWorld.m10(),
                shipToWorld.m20(),
                shipToWorld.m01(),
                shipToWorld.m11(),
                shipToWorld.m21(),
                shipToWorld.m02(),
                shipToWorld.m12(),
                shipToWorld.m22(),
                biasedM30,
                biasedM31,
                biasedM32,
                r,
                g,
                b,
                OVERLAY_ALPHA,
                time
            );
        }

        if (facesThisFrame > 0 && !loggedAnyFaces) {
            loggedAnyFaces = true;
            LOGGER.info("UnderwaterViewOverlay emitted {} faces (sample)", facesThisFrame);
        }

        bufferSource.endBatch();
    }

    private static int computeWaterTintRgb(final net.minecraft.client.multiplayer.ClientLevel level, final ShipTransform shipTransform) {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final var worldPos = shipTransform.getPositionInWorld();
        pos.set(Mth.floor(worldPos.x()), Mth.floor(worldPos.y()), Mth.floor(worldPos.z()));
        return BiomeColors.getAverageWaterColor(level, pos);
    }

    private static void ensureTransparentSolids(final net.minecraft.client.multiplayer.ClientLevel level, final ShipCache cache,
        final ShipWaterPocketManager.ClientWaterReachableSnapshot snapshot) {
        final int minX = snapshot.getMinX();
        final int minY = snapshot.getMinY();
        final int minZ = snapshot.getMinZ();
        final int sizeX = snapshot.getSizeX();
        final int sizeY = snapshot.getSizeY();
        final int sizeZ = snapshot.getSizeZ();
        final int volume = sizeX * sizeY * sizeZ;

        final boolean boundsChanged = cache.minX != minX || cache.minY != minY || cache.minZ != minZ ||
            cache.sizeX != sizeX || cache.sizeY != sizeY || cache.sizeZ != sizeZ;

        if (!boundsChanged && cache.geometryRevision == snapshot.getGeometryRevision() && cache.transparentSolids != null &&
            cache.transparentSolids.size() == volume) {
            return;
        }

        cache.geometryRevision = snapshot.getGeometryRevision();
        cache.minX = minX;
        cache.minY = minY;
        cache.minZ = minZ;
        cache.sizeX = sizeX;
        cache.sizeY = sizeY;
        cache.sizeZ = sizeZ;

        final BitSet transparent = new BitSet(volume);
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        int idx = 0;
        for (int lz = 0; lz < sizeZ; lz++) {
            for (int ly = 0; ly < sizeY; ly++) {
                for (int lx = 0; lx < sizeX; lx++) {
                    pos.set(minX + lx, minY + ly, minZ + lz);
                    final BlockState state = level.getBlockState(pos);
                    if (!state.getFluidState().isEmpty()) {
                        idx++;
                        continue;
                    }

                    final RenderType rt = ItemBlockRenderTypes.getChunkRenderType(state);
                    if (rt == RenderType.translucent() || rt == RenderType.cutout() || rt == RenderType.cutoutMipped()) {
                        transparent.set(idx);
                    }

                    idx++;
                }
            }
        }

        cache.transparentSolids = transparent;
    }

    private static boolean isOutsideSubmergedWater(final BitSet open, final BitSet interior, final BitSet waterReachable,
        final int idx) {
        return open.get(idx) && !interior.get(idx) && waterReachable.get(idx);
    }

    private static boolean isInteriorOpen(final BitSet open, final BitSet interior, final int idx) {
        return open.get(idx) && interior.get(idx);
    }

    private static int emitOverlayFaces(
        final net.minecraft.client.multiplayer.ClientLevel level,
        final Matrix4f matrix,
        final VertexConsumer vc,
        final ShipCache cache,
        final ShipWaterPocketManager.ClientWaterReachableSnapshot snapshot,
        final double m00,
        final double m10,
        final double m20,
        final double m01,
        final double m11,
        final double m21,
        final double m02,
        final double m12,
        final double m22,
        final double tX,
        final double tY,
        final double tZ,
        final float r,
        final float g,
        final float b,
        final float alpha,
        final float time
    ) {

        final int sizeX = cache.sizeX;
        final int sizeY = cache.sizeY;
        final int sizeZ = cache.sizeZ;
        final int volume = sizeX * sizeY * sizeZ;
        if (volume <= 0) return 0;

        final BitSet open = snapshot.getOpen();
        final BitSet interior = snapshot.getInterior();
        final BitSet waterReachable = snapshot.getWaterReachable();
        final BitSet transparentSolids = cache.transparentSolids;

        final BlockPos.MutableBlockPos waterPos = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();

        final int strideY = sizeX;
        final int strideZ = sizeX * sizeY;

        int quadsEmitted = 0;

        for (int outsideIdx = waterReachable.nextSetBit(0); outsideIdx >= 0; outsideIdx = waterReachable.nextSetBit(outsideIdx + 1)) {
            if (outsideIdx >= volume) break;
            if (!isOutsideSubmergedWater(open, interior, waterReachable, outsideIdx)) continue;

            final int lx = outsideIdx % sizeX;
            final int t = outsideIdx / sizeX;
            final int ly = t % sizeY;
            final int lz = t / sizeY;

            boolean needsOverlay = false;
            if (lx > 0) {
                final int n = outsideIdx - 1;
                needsOverlay |= isInteriorOpen(open, interior, n) || (!open.get(n) && transparentSolids != null && transparentSolids.get(n));
            }
            if (!needsOverlay && lx + 1 < sizeX) {
                final int n = outsideIdx + 1;
                needsOverlay |= isInteriorOpen(open, interior, n) || (!open.get(n) && transparentSolids != null && transparentSolids.get(n));
            }
            if (!needsOverlay && ly > 0) {
                final int n = outsideIdx - strideY;
                needsOverlay |= isInteriorOpen(open, interior, n) || (!open.get(n) && transparentSolids != null && transparentSolids.get(n));
            }
            if (!needsOverlay && ly + 1 < sizeY) {
                final int n = outsideIdx + strideY;
                needsOverlay |= isInteriorOpen(open, interior, n) || (!open.get(n) && transparentSolids != null && transparentSolids.get(n));
            }
            if (!needsOverlay && lz > 0) {
                final int n = outsideIdx - strideZ;
                needsOverlay |= isInteriorOpen(open, interior, n) || (!open.get(n) && transparentSolids != null && transparentSolids.get(n));
            }
            if (!needsOverlay && lz + 1 < sizeZ) {
                final int n = outsideIdx + strideZ;
                needsOverlay |= isInteriorOpen(open, interior, n) || (!open.get(n) && transparentSolids != null && transparentSolids.get(n));
            }
            if (!needsOverlay) continue;

            final double centerX = lx + 0.5;
            final double centerY = ly + 0.5;
            final double centerZ = lz + 0.5;

            final double worldX = m00 * centerX + m10 * centerY + m20 * centerZ + tX;
            final double worldY = m01 * centerX + m11 * centerY + m21 * centerZ + tY;
            final double worldZ = m02 * centerX + m12 * centerY + m22 * centerZ + tZ;

            final double waterSurfaceY = findWorldWaterSurfaceY(level, waterPos, scanPos, worldX, worldY, worldZ);
            if (Double.isNaN(waterSurfaceY)) continue;

            // -X neighbor
            if (lx > 0) {
                final int n = outsideIdx - 1;
                if (isInteriorOpen(open, interior, n)) {
                    quadsEmitted += emitFaceXClipped(matrix, vc, lx, ly, lz, +1.0f, m01, m11, m21, tY, waterSurfaceY, r, g, b, alpha);
                } else if (!open.get(n) && transparentSolids != null && transparentSolids.get(n)) {
                    quadsEmitted += emitFaceXClipped(matrix, vc, lx, ly, lz, -1.0f, m01, m11, m21, tY, waterSurfaceY, r, g, b, alpha);
                }
            }
            // +X neighbor
            if (lx + 1 < sizeX) {
                final int n = outsideIdx + 1;
                if (isInteriorOpen(open, interior, n)) {
                    quadsEmitted += emitFaceXClipped(matrix, vc, lx + 1, ly, lz, -1.0f, m01, m11, m21, tY, waterSurfaceY, r, g, b, alpha);
                } else if (!open.get(n) && transparentSolids != null && transparentSolids.get(n)) {
                    quadsEmitted += emitFaceXClipped(matrix, vc, lx + 1, ly, lz, +1.0f, m01, m11, m21, tY, waterSurfaceY, r, g, b, alpha);
                }
            }
            // -Y neighbor
            if (ly > 0) {
                final int n = outsideIdx - strideY;
                if (isInteriorOpen(open, interior, n)) {
                    quadsEmitted += emitFaceYClipped(matrix, vc, lx, ly, lz, +1.0f, m01, m11, m21, tY, waterSurfaceY, r, g, b, alpha);
                } else if (!open.get(n) && transparentSolids != null && transparentSolids.get(n)) {
                    quadsEmitted += emitFaceYClipped(matrix, vc, lx, ly, lz, -1.0f, m01, m11, m21, tY, waterSurfaceY, r, g, b, alpha);
                }
            }
            // +Y neighbor
            if (ly + 1 < sizeY) {
                final int n = outsideIdx + strideY;
                if (isInteriorOpen(open, interior, n)) {
                    quadsEmitted += emitFaceYClipped(matrix, vc, lx, ly + 1, lz, -1.0f, m01, m11, m21, tY, waterSurfaceY, r, g, b, alpha);
                } else if (!open.get(n) && transparentSolids != null && transparentSolids.get(n)) {
                    quadsEmitted += emitFaceYClipped(matrix, vc, lx, ly + 1, lz, +1.0f, m01, m11, m21, tY, waterSurfaceY, r, g, b, alpha);
                }
            }
            // -Z neighbor
            if (lz > 0) {
                final int n = outsideIdx - strideZ;
                if (isInteriorOpen(open, interior, n)) {
                    quadsEmitted += emitFaceZClipped(matrix, vc, lx, ly, lz, +1.0f, m01, m11, m21, tY, waterSurfaceY, r, g, b, alpha);
                } else if (!open.get(n) && transparentSolids != null && transparentSolids.get(n)) {
                    quadsEmitted += emitFaceZClipped(matrix, vc, lx, ly, lz, -1.0f, m01, m11, m21, tY, waterSurfaceY, r, g, b, alpha);
                }
            }
            // +Z neighbor
            if (lz + 1 < sizeZ) {
                final int n = outsideIdx + strideZ;
                if (isInteriorOpen(open, interior, n)) {
                    quadsEmitted += emitFaceZClipped(matrix, vc, lx, ly, lz + 1, -1.0f, m01, m11, m21, tY, waterSurfaceY, r, g, b, alpha);
                } else if (!open.get(n) && transparentSolids != null && transparentSolids.get(n)) {
                    quadsEmitted += emitFaceZClipped(matrix, vc, lx, ly, lz + 1, +1.0f, m01, m11, m21, tY, waterSurfaceY, r, g, b, alpha);
                }
            }
        }

        return quadsEmitted;
    }

    private static double findWorldWaterSurfaceY(
        final net.minecraft.client.multiplayer.ClientLevel level,
        final BlockPos.MutableBlockPos waterPos,
        final BlockPos.MutableBlockPos scanPos,
        final double worldX,
        final double worldY,
        final double worldZ
    ) {
        final int blockX = Mth.floor(worldX);
        final int blockY = Mth.floor(worldY);
        final int blockZ = Mth.floor(worldZ);

        waterPos.set(blockX, blockY, blockZ);
        if (!level.getFluidState(waterPos).is(FluidTags.WATER)) {
            // Outside submerged voxels can land near the waterline due to rotation; allow a 1-block adjustment.
            waterPos.move(0, -1, 0);
            if (!level.getFluidState(waterPos).is(FluidTags.WATER)) {
                waterPos.move(0, 2, 0);
                if (!level.getFluidState(waterPos).is(FluidTags.WATER)) {
                    return Double.NaN;
                }
            }
        }

        final long key = BlockPos.asLong(waterPos.getX(), waterPos.getY(), waterPos.getZ());
        if (waterSurfaceCache.containsKey(key)) {
            return waterSurfaceCache.get(key);
        }

        scanPos.set(waterPos);
        final int maxYExclusive = level.getMaxBuildHeight();
        while (scanPos.getY() < maxYExclusive && level.getFluidState(scanPos).is(FluidTags.WATER)) {
            scanPos.move(0, 1, 0);
        }
        scanPos.move(0, -1, 0);

        final FluidState topFluid = level.getFluidState(scanPos);
        final double surfaceY = scanPos.getY() + topFluid.getHeight(level, scanPos);
        if (waterSurfaceCache.size() >= MAX_WATER_SURFACE_CACHE) {
            waterSurfaceCache.clear();
        }
        waterSurfaceCache.put(key, surfaceY);
        return surfaceY;
    }

    private static double worldY(
        final double m01,
        final double m11,
        final double m21,
        final double tY,
        final float x,
        final float y,
        final float z
    ) {
        return m01 * x + m11 * y + m21 * z + tY;
    }

    private static int emitFaceXClipped(
        final Matrix4f matrix,
        final VertexConsumer vc,
        final int xPlane,
        final int y0,
        final int z0,
        final float normalX,
        final double m01,
        final double m11,
        final double m21,
        final double tY,
        final double waterSurfaceY,
        final float r,
        final float g,
        final float b,
        final float a
    ) {
        final float x = xPlane + (normalX > 0.0f ? -FACE_EPS : FACE_EPS);
        final float y1 = y0 + 1.0f;
        final float z1 = z0 + 1.0f;

        final double wy0 = worldY(m01, m11, m21, tY, x, y0, z0);
        final double wy1 = worldY(m01, m11, m21, tY, x, y1, z0);
        final double wy2 = worldY(m01, m11, m21, tY, x, y1, z1);
        final double wy3 = worldY(m01, m11, m21, tY, x, y0, z1);
        final double minWy = Math.min(Math.min(wy0, wy1), Math.min(wy2, wy3));
        final double maxWy = Math.max(Math.max(wy0, wy1), Math.max(wy2, wy3));
        if (maxWy <= waterSurfaceY + SURFACE_EPS) {
            quad(vc, matrix, x, y0, z0, x, y1, z0, x, y1, z1, x, y0, z1, 0.0f, 0.0f, 1.0f, 1.0f, normalX, 0.0f, 0.0f, r, g, b,
                a);
            return 1;
        }
        if (minWy > waterSurfaceY + SURFACE_EPS) return 0;

        // Face-local UVs: u=y, v=z.
        CLIP_X0[0] = x;
        CLIP_Y0[0] = y0;
        CLIP_Z0[0] = z0;
        CLIP_U0[0] = 0.0f;
        CLIP_V0[0] = 0.0f;

        CLIP_X0[1] = x;
        CLIP_Y0[1] = y1;
        CLIP_Z0[1] = z0;
        CLIP_U0[1] = 1.0f;
        CLIP_V0[1] = 0.0f;

        CLIP_X0[2] = x;
        CLIP_Y0[2] = y1;
        CLIP_Z0[2] = z1;
        CLIP_U0[2] = 1.0f;
        CLIP_V0[2] = 1.0f;

        CLIP_X0[3] = x;
        CLIP_Y0[3] = y0;
        CLIP_Z0[3] = z1;
        CLIP_U0[3] = 0.0f;
        CLIP_V0[3] = 1.0f;

        return emitClippedPolygonAsQuads(vc, matrix, CLIP_X0, CLIP_Y0, CLIP_Z0, CLIP_U0, CLIP_V0, 4, CLIP_X1, CLIP_Y1, CLIP_Z1, CLIP_U1, CLIP_V1, m01,
            m11, m21, tY, waterSurfaceY, normalX, 0.0f, 0.0f, r, g, b, a);
    }

    private static int emitFaceYClipped(
        final Matrix4f matrix,
        final VertexConsumer vc,
        final int x0,
        final int yPlane,
        final int z0,
        final float normalY,
        final double m01,
        final double m11,
        final double m21,
        final double tY,
        final double waterSurfaceY,
        final float r,
        final float g,
        final float b,
        final float a
    ) {
        final float y = yPlane + (normalY > 0.0f ? -FACE_EPS : FACE_EPS);
        final float x1 = x0 + 1.0f;
        final float z1 = z0 + 1.0f;

        final double wy0 = worldY(m01, m11, m21, tY, x0, y, z0);
        final double wy1 = worldY(m01, m11, m21, tY, x1, y, z0);
        final double wy2 = worldY(m01, m11, m21, tY, x1, y, z1);
        final double wy3 = worldY(m01, m11, m21, tY, x0, y, z1);
        final double minWy = Math.min(Math.min(wy0, wy1), Math.min(wy2, wy3));
        final double maxWy = Math.max(Math.max(wy0, wy1), Math.max(wy2, wy3));
        if (maxWy <= waterSurfaceY + SURFACE_EPS) {
            quad(vc, matrix, x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, normalY, 0.0f, r, g, b, a);
            return 1;
        }
        if (minWy > waterSurfaceY + SURFACE_EPS) return 0;

        // Face-local UVs: u=x, v=z.
        CLIP_X0[0] = x0;
        CLIP_Y0[0] = y;
        CLIP_Z0[0] = z0;
        CLIP_U0[0] = 0.0f;
        CLIP_V0[0] = 0.0f;

        CLIP_X0[1] = x1;
        CLIP_Y0[1] = y;
        CLIP_Z0[1] = z0;
        CLIP_U0[1] = 1.0f;
        CLIP_V0[1] = 0.0f;

        CLIP_X0[2] = x1;
        CLIP_Y0[2] = y;
        CLIP_Z0[2] = z1;
        CLIP_U0[2] = 1.0f;
        CLIP_V0[2] = 1.0f;

        CLIP_X0[3] = x0;
        CLIP_Y0[3] = y;
        CLIP_Z0[3] = z1;
        CLIP_U0[3] = 0.0f;
        CLIP_V0[3] = 1.0f;

        return emitClippedPolygonAsQuads(vc, matrix, CLIP_X0, CLIP_Y0, CLIP_Z0, CLIP_U0, CLIP_V0, 4, CLIP_X1, CLIP_Y1, CLIP_Z1, CLIP_U1, CLIP_V1, m01,
            m11, m21, tY, waterSurfaceY, 0.0f, normalY, 0.0f, r, g, b, a);
    }

    private static int emitFaceZClipped(
        final Matrix4f matrix,
        final VertexConsumer vc,
        final int x0,
        final int y0,
        final int zPlane,
        final float normalZ,
        final double m01,
        final double m11,
        final double m21,
        final double tY,
        final double waterSurfaceY,
        final float r,
        final float g,
        final float b,
        final float a
    ) {
        final float z = zPlane + (normalZ > 0.0f ? -FACE_EPS : FACE_EPS);
        final float x1 = x0 + 1.0f;
        final float y1 = y0 + 1.0f;

        final double wy0 = worldY(m01, m11, m21, tY, x0, y0, z);
        final double wy1 = worldY(m01, m11, m21, tY, x1, y0, z);
        final double wy2 = worldY(m01, m11, m21, tY, x1, y1, z);
        final double wy3 = worldY(m01, m11, m21, tY, x0, y1, z);
        final double minWy = Math.min(Math.min(wy0, wy1), Math.min(wy2, wy3));
        final double maxWy = Math.max(Math.max(wy0, wy1), Math.max(wy2, wy3));
        if (maxWy <= waterSurfaceY + SURFACE_EPS) {
            quad(vc, matrix, x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, normalZ, r, g, b, a);
            return 1;
        }
        if (minWy > waterSurfaceY + SURFACE_EPS) return 0;

        // Face-local UVs: u=x, v=y.
        CLIP_X0[0] = x0;
        CLIP_Y0[0] = y0;
        CLIP_Z0[0] = z;
        CLIP_U0[0] = 0.0f;
        CLIP_V0[0] = 0.0f;

        CLIP_X0[1] = x1;
        CLIP_Y0[1] = y0;
        CLIP_Z0[1] = z;
        CLIP_U0[1] = 1.0f;
        CLIP_V0[1] = 0.0f;

        CLIP_X0[2] = x1;
        CLIP_Y0[2] = y1;
        CLIP_Z0[2] = z;
        CLIP_U0[2] = 1.0f;
        CLIP_V0[2] = 1.0f;

        CLIP_X0[3] = x0;
        CLIP_Y0[3] = y1;
        CLIP_Z0[3] = z;
        CLIP_U0[3] = 0.0f;
        CLIP_V0[3] = 1.0f;

        return emitClippedPolygonAsQuads(vc, matrix, CLIP_X0, CLIP_Y0, CLIP_Z0, CLIP_U0, CLIP_V0, 4, CLIP_X1, CLIP_Y1, CLIP_Z1, CLIP_U1, CLIP_V1, m01,
            m11, m21, tY, waterSurfaceY, 0.0f, 0.0f, normalZ, r, g, b, a);
    }

    private static int emitClippedPolygonAsQuads(
        final VertexConsumer vc,
        final Matrix4f matrix,
        final float[] inX,
        final float[] inY,
        final float[] inZ,
        final float[] inU,
        final float[] inV,
        final int inCount,
        final float[] outX,
        final float[] outY,
        final float[] outZ,
        final float[] outU,
        final float[] outV,
        final double m01,
        final double m11,
        final double m21,
        final double tY,
        final double waterSurfaceY,
        final float nx,
        final float ny,
        final float nz,
        final float r,
        final float g,
        final float b,
        final float a
    ) {
        final int outCount = clipToSurfaceY(inX, inY, inZ, inU, inV, inCount, outX, outY, outZ, outU, outV, m01, m11, m21, tY, waterSurfaceY);
        if (outCount < 3) return 0;

        if (outCount == 4) {
            vertex(vc, matrix, outX[0], outY[0], outZ[0], outU[0], outV[0], nx, ny, nz, r, g, b, a);
            vertex(vc, matrix, outX[1], outY[1], outZ[1], outU[1], outV[1], nx, ny, nz, r, g, b, a);
            vertex(vc, matrix, outX[2], outY[2], outZ[2], outU[2], outV[2], nx, ny, nz, r, g, b, a);
            vertex(vc, matrix, outX[3], outY[3], outZ[3], outU[3], outV[3], nx, ny, nz, r, g, b, a);
            return 1;
        }

        // Emit triangle fan as degenerate quads (RenderType is QUADS).
        int quadsEmitted = 0;
        final float x0 = outX[0];
        final float y0 = outY[0];
        final float z0 = outZ[0];
        final float u0 = outU[0];
        final float v0 = outV[0];
        for (int i = 1; i + 1 < outCount; i++) {
            vertex(vc, matrix, x0, y0, z0, u0, v0, nx, ny, nz, r, g, b, a);
            vertex(vc, matrix, outX[i], outY[i], outZ[i], outU[i], outV[i], nx, ny, nz, r, g, b, a);
            vertex(vc, matrix, outX[i + 1], outY[i + 1], outZ[i + 1], outU[i + 1], outV[i + 1], nx, ny, nz, r, g, b, a);
            vertex(vc, matrix, outX[i + 1], outY[i + 1], outZ[i + 1], outU[i + 1], outV[i + 1], nx, ny, nz, r, g, b, a);
            quadsEmitted++;
        }
        return quadsEmitted;
    }

    private static int clipToSurfaceY(
        final float[] inX,
        final float[] inY,
        final float[] inZ,
        final float[] inU,
        final float[] inV,
        final int inCount,
        final float[] outX,
        final float[] outY,
        final float[] outZ,
        final float[] outU,
        final float[] outV,
        final double m01,
        final double m11,
        final double m21,
        final double tY,
        final double waterSurfaceY
    ) {
        int outCount = 0;

        float prevX = inX[inCount - 1];
        float prevY = inY[inCount - 1];
        float prevZ = inZ[inCount - 1];
        float prevU = inU[inCount - 1];
        float prevV = inV[inCount - 1];
        double prevWorldY = worldY(m01, m11, m21, tY, prevX, prevY, prevZ);
        boolean prevInside = prevWorldY <= waterSurfaceY + SURFACE_EPS;

        for (int i = 0; i < inCount; i++) {
            final float curX = inX[i];
            final float curY = inY[i];
            final float curZ = inZ[i];
            final float curU = inU[i];
            final float curV = inV[i];
            final double curWorldY = worldY(m01, m11, m21, tY, curX, curY, curZ);
            final boolean curInside = curWorldY <= waterSurfaceY + SURFACE_EPS;

            if (prevInside && curInside) {
                outX[outCount] = curX;
                outY[outCount] = curY;
                outZ[outCount] = curZ;
                outU[outCount] = curU;
                outV[outCount] = curV;
                outCount++;
            } else if (prevInside && !curInside) {
                final double denom = (curWorldY - prevWorldY);
                if (Math.abs(denom) > 1e-12) {
                    final float tt = (float) Mth.clamp((waterSurfaceY - prevWorldY) / denom, 0.0, 1.0);
                    outX[outCount] = Mth.lerp(tt, prevX, curX);
                    outY[outCount] = Mth.lerp(tt, prevY, curY);
                    outZ[outCount] = Mth.lerp(tt, prevZ, curZ);
                    outU[outCount] = Mth.lerp(tt, prevU, curU);
                    outV[outCount] = Mth.lerp(tt, prevV, curV);
                    outCount++;
                }
            } else if (!prevInside && curInside) {
                final double denom = (curWorldY - prevWorldY);
                if (Math.abs(denom) > 1e-12) {
                    final float tt = (float) Mth.clamp((waterSurfaceY - prevWorldY) / denom, 0.0, 1.0);
                    outX[outCount] = Mth.lerp(tt, prevX, curX);
                    outY[outCount] = Mth.lerp(tt, prevY, curY);
                    outZ[outCount] = Mth.lerp(tt, prevZ, curZ);
                    outU[outCount] = Mth.lerp(tt, prevU, curU);
                    outV[outCount] = Mth.lerp(tt, prevV, curV);
                    outCount++;
                }

                outX[outCount] = curX;
                outY[outCount] = curY;
                outZ[outCount] = curZ;
                outU[outCount] = curU;
                outV[outCount] = curV;
                outCount++;
            }

            prevX = curX;
            prevY = curY;
            prevZ = curZ;
            prevU = curU;
            prevV = curV;
            prevWorldY = curWorldY;
            prevInside = curInside;
        }

        return outCount;
    }

    private static void vertex(
        final VertexConsumer vc,
        final Matrix4f matrix,
        final float x,
        final float y,
        final float z,
        final float u,
        final float v,
        final float nx,
        final float ny,
        final float nz,
        final float r,
        final float g,
        final float b,
        final float a
    ) {
        vc.vertex(matrix, x, y, z)
            .color(r, g, b, a)
            .uv(u, v)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(FULL_BRIGHT)
            .normal(nx, ny, nz)
            .endVertex();
    }

    private static void quad(final VertexConsumer vc, final Matrix4f matrix,
        final float x0, final float y0, final float z0,
        final float x1, final float y1, final float z1,
        final float x2, final float y2, final float z2,
        final float x3, final float y3, final float z3,
        final float u0, final float v0,
        final float u1, final float v1,
        final float nx, final float ny, final float nz,
        final float r, final float g, final float b, final float a) {

        vertex(vc, matrix, x0, y0, z0, u0, v0, nx, ny, nz, r, g, b, a);
        vertex(vc, matrix, x1, y1, z1, u1, v0, nx, ny, nz, r, g, b, a);
        vertex(vc, matrix, x2, y2, z2, u1, v1, nx, ny, nz, r, g, b, a);
        vertex(vc, matrix, x3, y3, z3, u0, v1, nx, ny, nz, r, g, b, a);
    }

    private static List<LoadedShip> selectClosestShips(final net.minecraft.client.multiplayer.ClientLevel level, final Vec3 cameraPos,
        final int maxCount) {
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
        final AABBdc shipWorldAabbDc = getShipWorldAabb(ship);
        if (shipWorldAabbDc == null) return Double.POSITIVE_INFINITY;

        final double closestX = Mth.clamp(cameraPos.x, shipWorldAabbDc.minX(), shipWorldAabbDc.maxX());
        final double closestY = Mth.clamp(cameraPos.y, shipWorldAabbDc.minY(), shipWorldAabbDc.maxY());
        final double closestZ = Mth.clamp(cameraPos.z, shipWorldAabbDc.minZ(), shipWorldAabbDc.maxZ());
        final double dx = closestX - cameraPos.x;
        final double dy = closestY - cameraPos.y;
        final double dz = closestZ - cameraPos.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static AABBdc getShipWorldAabb(final LoadedShip ship) {
        if (ship instanceof final ClientShip clientShip) {
            return clientShip.getRenderAABB();
        }
        return ship.getWorldAABB();
    }

    private static ShipTransform getShipTransform(final LoadedShip ship) {
        if (ship instanceof final ClientShip clientShip) {
            return clientShip.getRenderTransform();
        }
        return ship.getShipTransform();
    }
}
