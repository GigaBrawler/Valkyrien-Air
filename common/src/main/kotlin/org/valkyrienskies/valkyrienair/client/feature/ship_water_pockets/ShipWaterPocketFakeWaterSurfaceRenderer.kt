package org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexSorting
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BiomeColors
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.level.Level
import net.minecraft.world.level.material.Fluids
import org.joml.Matrix4f
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.core.api.ships.properties.ShipTransform
import org.valkyrienskies.mod.common.hooks.VSGameEvents
import org.valkyrienskies.valkyrienair.config.ValkyrienAirConfig
import org.valkyrienskies.valkyrienair.feature.ship_water_pockets.ShipWaterPocketManager
import kotlin.math.abs
import kotlin.math.min

/**
 * Renders a "fake" water surface for pressurized ship openings.
 *
 * World water has no surface quads deep underwater (it's a continuous water column), so even when an air pocket is
 * rendered as air via shader culling, the boundary at a pressurized opening needs custom geometry.
 */
object ShipWaterPocketFakeWaterSurfaceRenderer {

    private val WATER_STILL_SPRITE_ID = ResourceLocation("minecraft", "block/water_still")
    private val FAKE_SURFACE_RENDER_TYPE = createFakeSurfaceRenderType()
    private const val FAKE_SURFACE_HEIGHT = 0.875
    private const val FAKE_SURFACE_BASE_SHIFT = FAKE_SURFACE_HEIGHT - 1.0
    private const val FREE_SURFACE_MAX_SCAN_DOWN = 64
    private const val FREE_SURFACE_MAX_SCAN_UP = 64
    private const val FREE_SURFACE_CLIP_EPS = 1e-3

    private data class UpDir(
        val dx: Int,
        val dy: Int,
        val dz: Int,
        val incWorldY: Double,
    )

    private data class CachedSurfaces(
        val builtTick: Long,
        val geometryRevision: Long,
        val upDir: UpDir,
        val surfaceWaterCells: LongArray,
    )

    private class PolyVert(
        var sx: Double,
        var sy: Double,
        var sz: Double,
        var u: Float,
        var v: Float,
        var worldY: Double,
    ) {
        fun setFrom(other: PolyVert) {
            sx = other.sx
            sy = other.sy
            sz = other.sz
            u = other.u
            v = other.v
            worldY = other.worldY
        }

        fun set(
            sx: Double,
            sy: Double,
            sz: Double,
            u: Float,
            v: Float,
            worldY: Double,
        ) {
            this.sx = sx
            this.sy = sy
            this.sz = sz
            this.u = u
            this.v = v
            this.worldY = worldY
        }
    }

    private var cachedLevel: Level? = null
    private val cacheByShipId = HashMap<Long, CachedSurfaces>()

    private val tmpShipPos = Vector3d()
    private val tmpWorldPos = Vector3d()
    private val tmpCameraShipPos = Vector3d()
    private val tmpWorldBlockPos = BlockPos.MutableBlockPos()
    private val tmpWorldBlockPos2 = BlockPos.MutableBlockPos()
    private val tmpPolyIn = Array(6) { PolyVert(0.0, 0.0, 0.0, 0f, 0f, 0.0) }
    private val tmpPolyOut = Array(6) { PolyVert(0.0, 0.0, 0.0, 0f, 0f, 0.0) }

    private var cachedWaterSprite: TextureAtlasSprite? = null

    @JvmStatic
    fun onRenderShip(event: VSGameEvents.ShipRenderEvent) {
        if (!ValkyrienAirConfig.enableShipWaterPockets) return
        if (event.renderType !== RenderType.translucent()) return

        val mc = Minecraft.getInstance()
        val level = mc.level ?: return

        val ship = event.ship
        val snapshot = ShipWaterPocketManager.getClientWaterReachableSnapshot(level, ship.id) ?: return
        if (snapshot.waterReachable.isEmpty) return

        val shipTransform = ship.renderTransform
        val surfaces = getOrBuildSurfaces(level, shipTransform, ship.id, snapshot) ?: return
        if (surfaces.surfaceWaterCells.isEmpty()) return

        val sprite = getWaterSprite(mc) ?: return

        // Render immediately using the projection/model-view matrices for this pass.
        //
        // MultiBufferSource flush uses RenderSystem matrices (not the chunk-layer ShaderInstance uniforms), so set them
        // explicitly for correctness under both vanilla and Embeddium.
        tmpWorldPos.set(event.camX, event.camY, event.camZ)
        shipTransform.worldToShip.transformPosition(tmpWorldPos, tmpCameraShipPos)

        val prevProj = Matrix4f(RenderSystem.getProjectionMatrix())
        val modelViewStack = RenderSystem.getModelViewStack()

        modelViewStack.pushPose()
        try {
            RenderSystem.setProjectionMatrix(Matrix4f(event.projectionMatrix), VertexSorting.DISTANCE_TO_ORIGIN)
            modelViewStack.last().pose().set(Matrix4f(event.poseStack.last().pose()))
            RenderSystem.applyModelViewMatrix()

            val bufferSource = MultiBufferSource.immediate(Tesselator.getInstance().builder)
            val vc = bufferSource.getBuffer(FAKE_SURFACE_RENDER_TYPE)

            renderSurfacesShipSpaceCameraRelative(
                level = level,
                shipTransform = shipTransform,
                sprite = sprite,
                upDir = surfaces.upDir,
                surfaceWaterCells = surfaces.surfaceWaterCells,
                cameraShipPos = tmpCameraShipPos,
                vc = vc,
            )

            bufferSource.endBatch(FAKE_SURFACE_RENDER_TYPE)
        } finally {
            // Restore GL matrix state.
            RenderSystem.setProjectionMatrix(prevProj, VertexSorting.DISTANCE_TO_ORIGIN)
            modelViewStack.popPose()
            RenderSystem.applyModelViewMatrix()
        }
    }

    /**
     * Called from [org.valkyrienskies.valkyrienair.client.ValkyrienAirModClient] for the Sodium/Embeddium ship renderer.
     *
     * This is reflection-based so the class can load in "vanilla" environments where Embeddium isn't present.
     */
    @JvmStatic
    fun onPostRenderShipSodium(event: Any) {
        if (!ValkyrienAirConfig.enableShipWaterPockets) return

        val mc = Minecraft.getInstance()
        val level = mc.level ?: return

        val ship = getEventShip(event) ?: return
        val passLayer = getSodiumPassLayer(event) ?: return
        if (passLayer !== RenderType.translucent()) return

        val matrices = getEventMatrices(event) ?: return
        val proj = getSodiumProjection(matrices) ?: return
        val mv = getSodiumModelView(matrices) ?: return

        val snapshot = ShipWaterPocketManager.getClientWaterReachableSnapshot(level, ship.id) ?: return
        if (snapshot.waterReachable.isEmpty) return

        val shipTransform = ship.renderTransform
        val surfaces = getOrBuildSurfaces(level, shipTransform, ship.id, snapshot) ?: return
        if (surfaces.surfaceWaterCells.isEmpty()) return

        val sprite = getWaterSprite(mc) ?: return

        tmpWorldPos.set(getEventCamX(event) ?: return, getEventCamY(event) ?: return, getEventCamZ(event) ?: return)
        shipTransform.worldToShip.transformPosition(tmpWorldPos, tmpCameraShipPos)

        // Build the ship model-view matrix for this ship/pass. This matches the transform used by VS2's Sodium ship
        // renderer (see SodiumCompat#vsRenderLayer).
        val shipMv = org.joml.Matrix4d(mv)
            .translate(-tmpWorldPos.x, -tmpWorldPos.y, -tmpWorldPos.z)
            .mul(shipTransform.shipToWorld)
            .translate(tmpCameraShipPos)

        // Render immediately using the current projection/model-view matrices for this frame.
        val bufferSource = MultiBufferSource.immediate(Tesselator.getInstance().builder)
        val vc = bufferSource.getBuffer(FAKE_SURFACE_RENDER_TYPE)

        val prevProj = Matrix4f(RenderSystem.getProjectionMatrix())
        val modelViewStack = RenderSystem.getModelViewStack()

        modelViewStack.pushPose()
        try {
            RenderSystem.setProjectionMatrix(Matrix4f(proj), VertexSorting.DISTANCE_TO_ORIGIN)
            modelViewStack.last().pose().set(Matrix4f(shipMv))
            RenderSystem.applyModelViewMatrix()

            renderSurfacesShipSpaceCameraRelative(
                level = level,
                shipTransform = shipTransform,
                sprite = sprite,
                upDir = surfaces.upDir,
                surfaceWaterCells = surfaces.surfaceWaterCells,
                cameraShipPos = tmpCameraShipPos,
                vc = vc,
            )

            bufferSource.endBatch(FAKE_SURFACE_RENDER_TYPE)
        } finally {
            // Restore GL matrix state.
            RenderSystem.setProjectionMatrix(prevProj, VertexSorting.DISTANCE_TO_ORIGIN)
            modelViewStack.popPose()
            RenderSystem.applyModelViewMatrix()
        }
    }

    private fun getWaterSprite(mc: Minecraft): TextureAtlasSprite? {
        val cached = cachedWaterSprite
        if (cached != null) return cached

        val atlas = mc.modelManager.getAtlas(InventoryMenu.BLOCK_ATLAS)
        val sprite = atlas.getSprite(WATER_STILL_SPRITE_ID)
        cachedWaterSprite = sprite
        return sprite
    }

    private fun clearCacheIfLevelChanged(level: Level) {
        if (cachedLevel !== level) {
            cachedLevel = level
            cacheByShipId.clear()
        }
    }

    private fun getOrBuildSurfaces(
        level: Level,
        shipTransform: ShipTransform,
        shipId: Long,
        snapshot: ShipWaterPocketManager.ClientWaterReachableSnapshot,
    ): CachedSurfaces? {
        clearCacheIfLevelChanged(level)

        val tick = level.gameTime
        val upDir = computeUpDir(shipTransform)
        val cached = cacheByShipId[shipId]
        if (cached != null &&
            cached.builtTick == tick &&
            cached.geometryRevision == snapshot.geometryRevision &&
            cached.upDir == upDir
        ) {
            return cached
        }

        val water = snapshot.waterReachable
        val open = snapshot.open
        val sizeX = snapshot.sizeX
        val sizeY = snapshot.sizeY
        val sizeZ = snapshot.sizeZ
        val volume = sizeX.toLong() * sizeY.toLong() * sizeZ.toLong()
        if (volume <= 0L) return null

        val strideY = sizeX
        val strideZ = sizeX * sizeY

        fun hasAirAbove(idx: Int, lx: Int, ly: Int, lz: Int): Boolean {
            val dx = upDir.dx
            val dy = upDir.dy
            val dz = upDir.dz
            if (dx != 0) {
                if (dx > 0) {
                    if (lx + 1 >= sizeX) return false
                    val above = idx + 1
                    return open.get(above) && !water.get(above)
                } else {
                    if (lx <= 0) return false
                    val above = idx - 1
                    return open.get(above) && !water.get(above)
                }
            }
            if (dy != 0) {
                if (dy > 0) {
                    if (ly + 1 >= sizeY) return false
                    val above = idx + strideY
                    return open.get(above) && !water.get(above)
                } else {
                    if (ly <= 0) return false
                    val above = idx - strideY
                    return open.get(above) && !water.get(above)
                }
            }
            if (dz != 0) {
                if (dz > 0) {
                    if (lz + 1 >= sizeZ) return false
                    val above = idx + strideZ
                    return open.get(above) && !water.get(above)
                } else {
                    if (lz <= 0) return false
                    val above = idx - strideZ
                    return open.get(above) && !water.get(above)
                }
            }
            return false
        }

        // Collect water-reachable cells that have an air pocket directly "above" them (relative to world up).
        var out = LongArray(64)
        var count = 0

        var idx = water.nextSetBit(0)
        while (idx >= 0) {
            val lx = idx % sizeX
            val t = idx / sizeX
            val ly = t % sizeY
            val lz = t / sizeY

            // Only render fake surfaces inside the ship AABB (exclude the 1-block padded boundary layer).
            // This prevents drawing surfaces in the exterior water around the ship.
            if (lx == 0 || lx + 1 == sizeX || ly == 0 || ly + 1 == sizeY || lz == 0 || lz + 1 == sizeZ) {
                idx = water.nextSetBit(idx + 1)
                continue
            }

            if (hasAirAbove(idx, lx, ly, lz)) {
                val x = snapshot.minX + lx
                val y = snapshot.minY + ly
                val z = snapshot.minZ + lz
                if (count >= out.size) out = out.copyOf(out.size * 2)
                out[count++] = BlockPos.asLong(x, y, z)
            }

            idx = water.nextSetBit(idx + 1)
        }

        val surfaces = if (count == 0) LongArray(0) else out.copyOf(count)
        val built = CachedSurfaces(
            builtTick = tick,
            geometryRevision = snapshot.geometryRevision,
            upDir = upDir,
            surfaceWaterCells = surfaces,
        )
        cacheByShipId[shipId] = built
        return built
    }

    private fun computeUpDir(shipTransform: ShipTransform): UpDir {
        val shipToWorld = shipTransform.shipToWorld

        tmpShipPos.set(0.0, 0.0, 0.0)
        shipToWorld.transformPosition(tmpShipPos, tmpWorldPos)
        val baseY = tmpWorldPos.y

        tmpShipPos.set(1.0, 0.0, 0.0)
        shipToWorld.transformPosition(tmpShipPos, tmpWorldPos)
        val incX = tmpWorldPos.y - baseY

        tmpShipPos.set(0.0, 1.0, 0.0)
        shipToWorld.transformPosition(tmpShipPos, tmpWorldPos)
        val incY = tmpWorldPos.y - baseY

        tmpShipPos.set(0.0, 0.0, 1.0)
        shipToWorld.transformPosition(tmpShipPos, tmpWorldPos)
        val incZ = tmpWorldPos.y - baseY

        val ax = abs(incX)
        val ay = abs(incY)
        val az = abs(incZ)

        return if (ay >= ax && ay >= az) {
            val dy = if (incY >= 0.0) 1 else -1
            UpDir(dx = 0, dy = dy, dz = 0, incWorldY = abs(incY))
        } else if (ax >= az) {
            val dx = if (incX >= 0.0) 1 else -1
            UpDir(dx = dx, dy = 0, dz = 0, incWorldY = abs(incX))
        } else {
            val dz = if (incZ >= 0.0) 1 else -1
            UpDir(dx = 0, dy = 0, dz = dz, incWorldY = abs(incZ))
        }
    }

    private fun renderSurfacesShipSpaceCameraRelative(
        level: Level,
        shipTransform: ShipTransform,
        sprite: TextureAtlasSprite,
        upDir: UpDir,
        surfaceWaterCells: LongArray,
        cameraShipPos: Vector3d,
        vc: com.mojang.blaze3d.vertex.VertexConsumer,
    ) {
        val u0 = sprite.u0
        val u1 = sprite.u1
        val v0 = sprite.v0
        val v1 = sprite.v1

        for (packedPos in surfaceWaterCells) {
            val x = BlockPos.getX(packedPos)
            val y = BlockPos.getY(packedPos)
            val z = BlockPos.getZ(packedPos)

            // Render the surface slightly below the block boundary to match the look of vanilla water (which is not
            // flush with the full block height) and reduce z-fighting.
            val baseShift = FAKE_SURFACE_BASE_SHIFT

            // Determine whether we're near the true world water free surface. If we can find it within a short scan,
            // clip the fake surface by that plane so it "shrinks" smoothly when the ship emerges at an angle.
            val centerX = x + 0.5 + upDir.dx * (0.5 + baseShift)
            val centerY = y + 0.5 + upDir.dy * (0.5 + baseShift)
            val centerZ = z + 0.5 + upDir.dz * (0.5 + baseShift)
            tmpShipPos.set(centerX, centerY, centerZ)
            shipTransform.shipToWorld.transformPosition(tmpShipPos, tmpWorldPos)
            tmpWorldBlockPos.set(Mth.floor(tmpWorldPos.x), Mth.floor(tmpWorldPos.y), Mth.floor(tmpWorldPos.z))

            val freeSurfaceY = findWorldWaterFreeSurfaceY(level, tmpWorldPos.x, tmpWorldPos.y, tmpWorldPos.z)
            val clipY = freeSurfaceY?.minus(FREE_SURFACE_CLIP_EPS)

            val color = BiomeColors.getAverageWaterColor(level, tmpWorldBlockPos)
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF

            val light = LevelRenderer.getLightColor(level, tmpWorldBlockPos)

            withFaceVerticesShipSpace(upDir, x.toDouble(), y.toDouble(), z.toDouble(), baseShift) { x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3 ->
                // Build the base quad (ship-space), then optionally clip it by the world water free surface (world Y).
                val poly = tmpPolyIn
                poly[0].set(x0, y0, z0, u0, v0, 0.0)
                poly[1].set(x1, y1, z1, u0, v1, 0.0)
                poly[2].set(x2, y2, z2, u1, v1, 0.0)
                poly[3].set(x3, y3, z3, u1, v0, 0.0)

                for (i in 0 until 4) {
                    val v = poly[i]
                    tmpShipPos.set(v.sx, v.sy, v.sz)
                    shipTransform.shipToWorld.transformPosition(tmpShipPos, tmpWorldPos)
                    v.worldY = tmpWorldPos.y
                }

                val clippedCount = if (clipY != null) clipPolyToWorldY(poly, 4, clipY, tmpPolyOut) else 4
                val clippedPoly = if (clipY != null) tmpPolyOut else poly
                emitPolyCameraRelativeShipSpace(
                    vc = vc,
                    cameraShipPos = cameraShipPos,
                    poly = clippedPoly,
                    polySize = clippedCount,
                    nx = upDir.dx.toFloat(),
                    ny = upDir.dy.toFloat(),
                    nz = upDir.dz.toFloat(),
                    r = r,
                    g = g,
                    b = b,
                    a = 0xFF,
                    light = light,
                )
            }
        }
    }

    private fun emitPolyCameraRelativeShipSpace(
        vc: com.mojang.blaze3d.vertex.VertexConsumer,
        cameraShipPos: Vector3d,
        poly: Array<PolyVert>,
        polySize: Int,
        nx: Float,
        ny: Float,
        nz: Float,
        r: Int,
        g: Int,
        b: Int,
        a: Int,
        light: Int,
    ) {
        if (polySize < 3) return

        // Emit as triangles, but use degenerate quads so we can keep the standard translucent block RenderType.
        val v0 = poly[0]
        var i = 1
        while (i + 1 < polySize) {
            val v1 = poly[i]
            val v2 = poly[i + 1]
            emitTriangleAsDegenerateQuadCameraRelativeShipSpace(
                vc,
                cameraShipPos,
                v0,
                v1,
                v2,
                nx,
                ny,
                nz,
                r,
                g,
                b,
                a,
                light,
            )
            i++
        }
    }

    private fun emitTriangleAsDegenerateQuadCameraRelativeShipSpace(
        vc: com.mojang.blaze3d.vertex.VertexConsumer,
        cameraShipPos: Vector3d,
        a: PolyVert,
        b: PolyVert,
        c: PolyVert,
        nx: Float,
        ny: Float,
        nz: Float,
        r: Int,
        g: Int,
        bCol: Int,
        aCol: Int,
        light: Int,
    ) {
        fun v(p: PolyVert) {
            vc.vertex(p.sx - cameraShipPos.x, p.sy - cameraShipPos.y, p.sz - cameraShipPos.z)
                .color(r, g, bCol, aCol)
                .uv(p.u, p.v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(nx, ny, nz)
                .endVertex()
        }

        v(a)
        v(b)
        v(c)
        v(a) // degenerate 2nd tri
    }

    private fun clipPolyToWorldY(inPoly: Array<PolyVert>, inSize: Int, clipY: Double, outPoly: Array<PolyVert>): Int {
        if (inSize <= 0) return 0

        var outSize = 0
        var prev = inPoly[inSize - 1]
        var prevInside = prev.worldY <= clipY

        var i = 0
        while (i < inSize) {
            val cur = inPoly[i]
            val curInside = cur.worldY <= clipY

            if (curInside) {
                if (!prevInside) {
                    intersectAtWorldY(prev, cur, clipY, outPoly[outSize++])
                }
                outPoly[outSize++].setFrom(cur)
            } else if (prevInside) {
                intersectAtWorldY(prev, cur, clipY, outPoly[outSize++])
            }

            prev = cur
            prevInside = curInside
            i++
        }

        return outSize
    }

    private fun intersectAtWorldY(a: PolyVert, b: PolyVert, clipY: Double, out: PolyVert) {
        val dy = b.worldY - a.worldY
        val t = if (abs(dy) <= 1e-12) 0.0 else (clipY - a.worldY) / dy
        val sx = a.sx + (b.sx - a.sx) * t
        val sy = a.sy + (b.sy - a.sy) * t
        val sz = a.sz + (b.sz - a.sz) * t
        val u = a.u + (b.u - a.u) * t.toFloat()
        val v = a.v + (b.v - a.v) * t.toFloat()
        out.set(sx, sy, sz, u, v, clipY)
    }

    private fun findWorldWaterFreeSurfaceY(level: Level, x: Double, y: Double, z: Double): Double? {
        val baseX = Mth.floor(x)
        val baseY = Mth.floor(y)
        val baseZ = Mth.floor(z)

        // Find a water block at/under the sample point.
        var waterY = Int.MIN_VALUE
        var waterFs: net.minecraft.world.level.material.FluidState? = null
        var dy = 0
        while (dy <= FREE_SURFACE_MAX_SCAN_DOWN) {
            tmpWorldBlockPos2.set(baseX, baseY - dy, baseZ)
            val fs = level.getFluidState(tmpWorldBlockPos2)
            if (!fs.isEmpty && fs.`is`(Fluids.WATER)) {
                waterY = tmpWorldBlockPos2.y
                waterFs = fs
                break
            }
            dy++
        }
        if (waterFs == null) return null

        // Scan upward to find the *free* surface (where water transitions to non-water).
        var topY = waterY
        var topFs = waterFs
        var up = 0
        while (up < FREE_SURFACE_MAX_SCAN_UP) {
            val nextY = topY + 1
            if (nextY >= level.maxBuildHeight) break
            tmpWorldBlockPos2.set(baseX, nextY, baseZ)
            val above = level.getFluidState(tmpWorldBlockPos2)
            if (above.isEmpty || !above.`is`(Fluids.WATER)) {
                tmpWorldBlockPos2.set(baseX, topY, baseZ)
                val height = topFs!!.getHeight(level, tmpWorldBlockPos2).toDouble()
                // Vanilla's rendered water surface for a full source block isn't perfectly flush with the block top.
                // Clamp to the fake surface height so we blend at the same visible level.
                return topY.toDouble() + min(height, FAKE_SURFACE_HEIGHT)
            }
            topY = nextY
            topFs = above
            up++
        }

        // Free surface is too far above (deep underwater). Don't treat it as "near surface" for blending.
        return null
    }

    private fun createFakeSurfaceRenderType(): RenderType {
        return RenderType.create(
            "valkyrienair_fake_water_surface",
            DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS,
            256,
            true,
            true,
            RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.RENDERTYPE_TRANSLUCENT_SHADER)
                .setTextureState(RenderStateShard.BLOCK_SHEET_MIPPED)
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setOverlayState(RenderStateShard.OVERLAY)
                .setCullState(RenderStateShard.NO_CULL)
                .createCompositeState(true),
        )
    }

    private inline fun withFaceVerticesShipSpace(
        upDir: UpDir,
        x: Double,
        y: Double,
        z: Double,
        shift: Double,
        cb: (Double, Double, Double, Double, Double, Double, Double, Double, Double, Double, Double, Double) -> Unit,
    ) {
        val dx = upDir.dx
        val dy = upDir.dy
        val dz = upDir.dz

        // Face plane coordinate (shifted along upDir axis).
        val fx = if (dx > 0) x + 1.0 else x
        val fy = if (dy > 0) y + 1.0 else y
        val fz = if (dz > 0) z + 1.0 else z

        when {
            dx != 0 -> {
                val planeX = fx + shift * dx
                cb(
                    planeX, y, z,
                    planeX, y, z + 1.0,
                    planeX, y + 1.0, z + 1.0,
                    planeX, y + 1.0, z,
                )
            }
            dy != 0 -> {
                val planeY = fy + shift * dy
                cb(
                    x, planeY, z,
                    x, planeY, z + 1.0,
                    x + 1.0, planeY, z + 1.0,
                    x + 1.0, planeY, z,
                )
            }
            dz != 0 -> {
                val planeZ = fz + shift * dz
                cb(
                    x, y, planeZ,
                    x + 1.0, y, planeZ,
                    x + 1.0, y + 1.0, planeZ,
                    x, y + 1.0, planeZ,
                )
            }
            else -> {}
        }
    }

    private fun getEventShip(event: Any): ClientShip? {
        return try {
            event.javaClass.getMethod("getShip").invoke(event) as? ClientShip
        } catch (_: Throwable) {
            null
        }
    }

    private fun getEventCamX(event: Any): Double? = try {
        event.javaClass.getMethod("getCamX").invoke(event) as? Double
    } catch (_: Throwable) {
        null
    }

    private fun getEventCamY(event: Any): Double? = try {
        event.javaClass.getMethod("getCamY").invoke(event) as? Double
    } catch (_: Throwable) {
        null
    }

    private fun getEventCamZ(event: Any): Double? = try {
        event.javaClass.getMethod("getCamZ").invoke(event) as? Double
    } catch (_: Throwable) {
        null
    }

    private fun getEventMatrices(event: Any): Any? {
        return try {
            event.javaClass.getMethod("getMatrices").invoke(event)
        } catch (_: Throwable) {
            null
        }
    }

    private fun getSodiumProjection(matrices: Any): org.joml.Matrix4fc? {
        return try {
            matrices.javaClass.getMethod("projection").invoke(matrices) as? org.joml.Matrix4fc
        } catch (_: Throwable) {
            null
        }
    }

    private fun getSodiumModelView(matrices: Any): org.joml.Matrix4fc? {
        return try {
            matrices.javaClass.getMethod("modelView").invoke(matrices) as? org.joml.Matrix4fc
        } catch (_: Throwable) {
            null
        }
    }

    private fun getSodiumPassLayer(event: Any): RenderType? {
        val pass = try {
            event.javaClass.getMethod("getPass").invoke(event)
        } catch (_: Throwable) {
            null
        } ?: return null

        return try {
            val field = pass.javaClass.getDeclaredField("layer")
            field.isAccessible = true
            field.get(pass) as? RenderType
        } catch (_: Throwable) {
            null
        }
    }
}
