package org.valkyrienskies.valkyrienair.feature.ship_water_pockets

import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FenceBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.IronBarsBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.StairsShape
import net.minecraft.world.level.block.state.properties.WallSide
import net.minecraft.world.level.material.Fluids
import org.apache.logging.log4j.LogManager
import org.joml.Vector3d
import org.joml.primitives.AABBd
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.core.api.ships.LoadedShip
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.api.ships.properties.ShipTransform
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.isBlockInShipyard
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.valkyrienair.config.ValkyrienAirConfig
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap

object ShipWaterPocketManager {
    private val log = LogManager.getLogger("ValkyrienAir ShipWaterPockets")

    private const val FLOOD_UPDATE_INTERVAL_TICKS = 10L
    private const val MAX_SIM_VOLUME = 2_000_000
    private const val POCKET_BOUNDS_PADDING = 1
    private const val AIR_PRESSURE_Y_EPS = 1e-7
    private const val WALL_HOLE_MIN_LATERAL_SOLIDS = 3

    @Volatile
    private var applyingInternalUpdates: Boolean = false

    private data class ShipPocketState(
        var minX: Int = 0,
        var minY: Int = 0,
        var minZ: Int = 0,
        var sizeX: Int = 0,
        var sizeY: Int = 0,
        var sizeZ: Int = 0,
        var open: BitSet = BitSet(),
        var interior: BitSet = BitSet(),
        var flooded: BitSet = BitSet(),
        var materializedWater: BitSet = BitSet(),
        var waterReachable: BitSet = BitSet(),
        var geometryRevision: Long = 0,
        var dirty: Boolean = true,
        var lastFloodUpdateTick: Long = Long.MIN_VALUE,
        var lastWaterReachableUpdateTick: Long = Long.MIN_VALUE,
    )

    private val serverStates: ConcurrentHashMap<DimensionId, ConcurrentHashMap<Long, ShipPocketState>> =
        ConcurrentHashMap()
    private val clientStates: ConcurrentHashMap<DimensionId, ConcurrentHashMap<Long, ShipPocketState>> =
        ConcurrentHashMap()

    private val tmpQueryAabb: ThreadLocal<AABBd> = ThreadLocal.withInitial { AABBd() }
    private val tmpWorldPos: ThreadLocal<Vector3d> = ThreadLocal.withInitial { Vector3d() }
    private val tmpShipPos: ThreadLocal<Vector3d> = ThreadLocal.withInitial { Vector3d() }
    private val tmpShipBlockPos: ThreadLocal<BlockPos.MutableBlockPos> =
        ThreadLocal.withInitial { BlockPos.MutableBlockPos() }
    private val tmpFloodQueue: ThreadLocal<IntArray> = ThreadLocal.withInitial { IntArray(0) }
    private val tmpPressureComponentVisited: ThreadLocal<BitSet> = ThreadLocal.withInitial { BitSet() }
    private val tmpPressureSubmerged: ThreadLocal<BitSet> = ThreadLocal.withInitial { BitSet() }
    private val tmpPressureEscapeHeight: ThreadLocal<DoubleArray> = ThreadLocal.withInitial { DoubleArray(0) }
    private val tmpPressureHeapIdx: ThreadLocal<IntArray> = ThreadLocal.withInitial { IntArray(0) }
    private val tmpPressureHeapPos: ThreadLocal<IntArray> = ThreadLocal.withInitial { IntArray(0) }

    private const val POINT_QUERY_EPS: Double = 1e-5

    @JvmStatic
    fun isApplyingInternalUpdates(): Boolean = applyingInternalUpdates

    @JvmStatic
    fun markShipDirty(level: Level, shipId: Long) {
        if (!ValkyrienAirConfig.enableShipWaterPockets) return
        val map = (if (level.isClientSide) clientStates else serverStates)
            .computeIfAbsent(level.dimensionId) { ConcurrentHashMap() }
        map.computeIfAbsent(shipId) { ShipPocketState() }.dirty = true
    }

    @JvmStatic
    fun tickServerLevel(level: ServerLevel) {
        if (!ValkyrienAirConfig.enableShipWaterPockets) return

        val states = serverStates.computeIfAbsent(level.dimensionId) { ConcurrentHashMap() }
        val loadedShipIds = LongOpenHashSet()

        level.shipObjectWorld.loadedShips.forEach { ship ->
            loadedShipIds.add(ship.id)
            val state = states.computeIfAbsent(ship.id) { ShipPocketState() }

            val aabb = ship.shipAABB ?: return@forEach
            val baseMinX = aabb.minX()
            val baseMinY = aabb.minY()
            val baseMinZ = aabb.minZ()
            val baseSizeX = aabb.maxX() - baseMinX
            val baseSizeY = aabb.maxY() - baseMinY
            val baseSizeZ = aabb.maxZ() - baseMinZ

            val minX = baseMinX - POCKET_BOUNDS_PADDING
            val minY = baseMinY - POCKET_BOUNDS_PADDING
            val minZ = baseMinZ - POCKET_BOUNDS_PADDING
            val sizeX = baseSizeX + POCKET_BOUNDS_PADDING * 2
            val sizeY = baseSizeY + POCKET_BOUNDS_PADDING * 2
            val sizeZ = baseSizeZ + POCKET_BOUNDS_PADDING * 2
            val volume = sizeX.toLong() * sizeY.toLong() * sizeZ.toLong()
            if (volume <= 0 || volume > MAX_SIM_VOLUME.toLong()) {
                if (state.dirty) {
                    log.warn("Skipping ship water pockets for ship {} (volume={}, max={})", ship.id, volume, MAX_SIM_VOLUME)
                    state.dirty = false
                }
                return@forEach
            }

            val needsRecompute = state.dirty || state.sizeX != sizeX || state.sizeY != sizeY || state.sizeZ != sizeZ ||
                state.minX != minX || state.minY != minY || state.minZ != minZ
            if (needsRecompute) {
                recomputeState(level, ship, state, minX, minY, minZ, sizeX, sizeY, sizeZ)
            }

            val now = level.gameTime
            val shipTransform = getQueryTransform(ship)
            if (needsRecompute || now != state.lastWaterReachableUpdateTick) {
                state.waterReachable = computeWaterReachable(level, state, shipTransform)
                state.lastWaterReachableUpdateTick = now
            }
            if (needsRecompute || now - state.lastFloodUpdateTick >= FLOOD_UPDATE_INTERVAL_TICKS) {
                updateFlooding(level, ship, state, shipTransform)
                state.lastFloodUpdateTick = now
            }
        }

        // Cleanup unloaded ships
        states.keys.removeIf { !loadedShipIds.contains(it) }
    }

    @JvmStatic
    fun tickClientLevel(level: Level) {
        if (!level.isClientSide) return
        if (!ValkyrienAirConfig.enableShipWaterPockets) return

        val states = clientStates.computeIfAbsent(level.dimensionId) { ConcurrentHashMap() }
        val loadedShipIds = LongOpenHashSet()

        level.shipObjectWorld.loadedShips.forEach { ship ->
            loadedShipIds.add(ship.id)
            val state = states.computeIfAbsent(ship.id) { ShipPocketState() }

            val aabb = ship.shipAABB ?: return@forEach
            val baseMinX = aabb.minX()
            val baseMinY = aabb.minY()
            val baseMinZ = aabb.minZ()
            val baseSizeX = aabb.maxX() - baseMinX
            val baseSizeY = aabb.maxY() - baseMinY
            val baseSizeZ = aabb.maxZ() - baseMinZ

            val minX = baseMinX - POCKET_BOUNDS_PADDING
            val minY = baseMinY - POCKET_BOUNDS_PADDING
            val minZ = baseMinZ - POCKET_BOUNDS_PADDING
            val sizeX = baseSizeX + POCKET_BOUNDS_PADDING * 2
            val sizeY = baseSizeY + POCKET_BOUNDS_PADDING * 2
            val sizeZ = baseSizeZ + POCKET_BOUNDS_PADDING * 2
            val volume = sizeX.toLong() * sizeY.toLong() * sizeZ.toLong()
            if (volume <= 0 || volume > MAX_SIM_VOLUME.toLong()) {
                state.dirty = false
                return@forEach
            }

            val needsRecompute =
                state.dirty || state.sizeX != sizeX || state.sizeY != sizeY || state.sizeZ != sizeZ ||
                    state.minX != minX || state.minY != minY || state.minZ != minZ
            if (needsRecompute) {
                // When (re)loading a ship, the shipyard chunks can arrive a few ticks after the ship object itself.
                // If we recompute while those chunks are still unloaded, `getBlockState` returns air everywhere, which
                // makes the ship appear entirely "open" and disables all air pockets until another shipyard block
                // update marks the ship dirty again.
                if (!areShipyardChunksLoaded(level, baseMinX, baseMinY, baseMinZ, baseSizeX, baseSizeY, baseSizeZ)) {
                    state.dirty = true
                } else {
                    recomputeState(level, ship, state, minX, minY, minZ, sizeX, sizeY, sizeZ)
                }
            }

            val now = level.gameTime
            if (now != state.lastWaterReachableUpdateTick) {
                state.waterReachable = computeWaterReachable(level, state, getQueryTransform(ship))
                state.lastWaterReachableUpdateTick = now
            }
        }

        states.keys.removeIf { !loadedShipIds.contains(it) }
    }

    private fun areShipyardChunksLoaded(
        level: Level,
        minX: Int,
        minY: Int,
        minZ: Int,
        sizeX: Int,
        sizeY: Int,
        sizeZ: Int,
    ): Boolean {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) return false
        val maxX = minX + sizeX - 1
        val maxY = minY + sizeY - 1
        val maxZ = minZ + sizeZ - 1
        return level.hasChunksAt(BlockPos(minX, minY, minZ), BlockPos(maxX, maxY, maxZ))
    }

    data class ClientWaterReachableSnapshot(
        val geometryRevision: Long,
        val minX: Int,
        val minY: Int,
        val minZ: Int,
        val sizeX: Int,
        val sizeY: Int,
        val sizeZ: Int,
        val open: BitSet,
        val interior: BitSet,
        val waterReachable: BitSet,
    )

    @JvmStatic
    fun getClientWaterReachableSnapshot(level: Level, shipId: Long): ClientWaterReachableSnapshot? {
        if (!level.isClientSide) return null
        val state = clientStates[level.dimensionId]?.get(shipId) ?: return null
        return ClientWaterReachableSnapshot(
            state.geometryRevision,
            state.minX,
            state.minY,
            state.minZ,
            state.sizeX,
            state.sizeY,
            state.sizeZ,
            state.open,
            state.interior,
            state.waterReachable,
        )
    }

    @JvmStatic
    fun overrideWaterFluidState(level: Level, worldBlockPos: BlockPos, original: net.minecraft.world.level.material.FluidState): net.minecraft.world.level.material.FluidState {
        if (!ValkyrienAirConfig.enableShipWaterPockets) return original
        if (level.isBlockInShipyard(worldBlockPos)) return original
        if (!original.isEmpty && !original.`is`(Fluids.WATER)) return original

        val queryAabb = tmpQueryAabb.get().apply {
            minX = worldBlockPos.x.toDouble()
            minY = worldBlockPos.y.toDouble()
            minZ = worldBlockPos.z.toDouble()
            maxX = (worldBlockPos.x + 1).toDouble()
            maxY = (worldBlockPos.y + 1).toDouble()
            maxZ = (worldBlockPos.z + 1).toDouble()
        }
        val worldPos = tmpWorldPos.get().set(
            worldBlockPos.x + 0.5,
            worldBlockPos.y + 0.5,
            worldBlockPos.z + 0.5
        )
        val shipPosTmp = tmpShipPos.get()
        val shipBlockPosTmp = tmpShipBlockPos.get()

        for (ship in level.shipObjectWorld.loadedShips.getIntersecting(queryAabb, level.dimensionId)) {
            val state = getState(level, ship.id) ?: continue
            val shipTransform = getQueryTransform(ship)

            shipTransform.worldToShip.transformPosition(worldPos, shipPosTmp)
            shipBlockPosTmp.set(Mth.floor(shipPosTmp.x), Mth.floor(shipPosTmp.y), Mth.floor(shipPosTmp.z))

            if (!isOpen(state, shipBlockPosTmp)) {
                val openPos = findOpenShipBlockPosForPoint(level, state, shipPosTmp, shipBlockPosTmp) ?: continue
                shipBlockPosTmp.set(openPos)
            }

            val shipFluid = level.getBlockState(shipBlockPosTmp).fluidState
            if (!shipFluid.isEmpty) return shipFluid

            if (!original.isEmpty && original.`is`(Fluids.WATER)) {
                // We are inside a ship air pocket; treat world water as air.
                if (isAirPocket(state, shipBlockPosTmp)) {
                    return shipFluid
                }
            }
        }

        return original
    }

    @JvmStatic
    fun overrideWaterFluidState(
        level: Level,
        worldX: Double,
        worldY: Double,
        worldZ: Double,
        original: net.minecraft.world.level.material.FluidState,
    ): net.minecraft.world.level.material.FluidState {
        if (!ValkyrienAirConfig.enableShipWaterPockets) return original
        if (level.isBlockInShipyard(worldX, worldY, worldZ)) return original
        if (!original.isEmpty && !original.`is`(Fluids.WATER)) return original

        val worldBlockPos = BlockPos.containing(worldX, worldY, worldZ)
        val queryAabb = tmpQueryAabb.get().apply {
            minX = worldBlockPos.x.toDouble()
            minY = worldBlockPos.y.toDouble()
            minZ = worldBlockPos.z.toDouble()
            maxX = (worldBlockPos.x + 1).toDouble()
            maxY = (worldBlockPos.y + 1).toDouble()
            maxZ = (worldBlockPos.z + 1).toDouble()
        }
        val worldPos = tmpWorldPos.get().set(worldX, worldY, worldZ)
        val shipPosTmp = tmpShipPos.get()
        val shipBlockPosTmp = tmpShipBlockPos.get()

        for (ship in level.shipObjectWorld.loadedShips.getIntersecting(queryAabb, level.dimensionId)) {
            val state = getState(level, ship.id) ?: continue
            val shipTransform = getQueryTransform(ship)

            shipTransform.worldToShip.transformPosition(worldPos, shipPosTmp)
            shipBlockPosTmp.set(Mth.floor(shipPosTmp.x), Mth.floor(shipPosTmp.y), Mth.floor(shipPosTmp.z))

            if (!isOpen(state, shipBlockPosTmp)) {
                val openPos = findOpenShipBlockPosForPoint(level, state, shipPosTmp, shipBlockPosTmp) ?: continue
                shipBlockPosTmp.set(openPos)
            }

            val shipFluid = level.getBlockState(shipBlockPosTmp).fluidState
            if (!shipFluid.isEmpty) return shipFluid

            if (!original.isEmpty && original.`is`(Fluids.WATER)) {
                // We are inside a ship air pocket; treat world water as air.
                if (isAirPocket(state, shipBlockPosTmp)) {
                    return shipFluid
                }
            }
        }

        return original
    }

    /**
     * Returns true if the given world-space block position is inside a sealed ship air pocket (i.e. open space that is
     * not reachable by world water).
     *
     * This intentionally does *not* check the actual world fluid/block state at [worldBlockPos]; it only answers whether
     * "world water should be treated as air here" based on the ship pocket simulation.
     */
    @JvmStatic
    fun isWorldPosInShipAirPocket(level: Level, worldBlockPos: BlockPos): Boolean {
        if (!ValkyrienAirConfig.enableShipWaterPockets) return false
        if (level.isBlockInShipyard(worldBlockPos)) return false

        val queryAabb = tmpQueryAabb.get().apply {
            minX = worldBlockPos.x.toDouble()
            minY = worldBlockPos.y.toDouble()
            minZ = worldBlockPos.z.toDouble()
            maxX = (worldBlockPos.x + 1).toDouble()
            maxY = (worldBlockPos.y + 1).toDouble()
            maxZ = (worldBlockPos.z + 1).toDouble()
        }
        val worldPos = tmpWorldPos.get().set(
            worldBlockPos.x + 0.5,
            worldBlockPos.y + 0.5,
            worldBlockPos.z + 0.5
        )
        val shipPosTmp = tmpShipPos.get()
        val shipBlockPosTmp = tmpShipBlockPos.get()

        for (ship in level.shipObjectWorld.loadedShips.getIntersecting(queryAabb, level.dimensionId)) {
            val state = getState(level, ship.id) ?: continue
            val shipTransform = getQueryTransform(ship)

            shipTransform.worldToShip.transformPosition(worldPos, shipPosTmp)
            shipBlockPosTmp.set(Mth.floor(shipPosTmp.x), Mth.floor(shipPosTmp.y), Mth.floor(shipPosTmp.z))

            if (!isOpen(state, shipBlockPosTmp)) {
                val openPos = findOpenShipBlockPosForPoint(level, state, shipPosTmp, shipBlockPosTmp) ?: continue
                shipBlockPosTmp.set(openPos)
            }

            if (isAirPocket(state, shipBlockPosTmp)) {
                return true
            }
        }

        return false
    }

    /**
     * If [worldBlockPos] is inside a sealed ship air pocket (see [isWorldPosInShipAirPocket]), returns the corresponding
     * shipyard block position for that point. Returns null if the position is not inside a ship air pocket.
     *
     * This is useful when a vanilla behavior tries to place a block into world water (e.g. fire) at a location that is
     * visually "inside the ship", where we actually want the block to exist in the shipyard instead of the world.
     */
    @JvmStatic
    fun getShipBlockPosForWorldPosInShipAirPocket(level: Level, worldBlockPos: BlockPos): BlockPos? {
        if (!ValkyrienAirConfig.enableShipWaterPockets) return null
        if (level.isBlockInShipyard(worldBlockPos)) return null

        val queryAabb = tmpQueryAabb.get().apply {
            minX = worldBlockPos.x.toDouble()
            minY = worldBlockPos.y.toDouble()
            minZ = worldBlockPos.z.toDouble()
            maxX = (worldBlockPos.x + 1).toDouble()
            maxY = (worldBlockPos.y + 1).toDouble()
            maxZ = (worldBlockPos.z + 1).toDouble()
        }
        val worldPos = tmpWorldPos.get().set(
            worldBlockPos.x + 0.5,
            worldBlockPos.y + 0.5,
            worldBlockPos.z + 0.5
        )
        val shipPosTmp = tmpShipPos.get()
        val shipBlockPosTmp = tmpShipBlockPos.get()

        for (ship in level.shipObjectWorld.loadedShips.getIntersecting(queryAabb, level.dimensionId)) {
            val state = getState(level, ship.id) ?: continue
            val shipTransform = getQueryTransform(ship)

            shipTransform.worldToShip.transformPosition(worldPos, shipPosTmp)
            shipBlockPosTmp.set(Mth.floor(shipPosTmp.x), Mth.floor(shipPosTmp.y), Mth.floor(shipPosTmp.z))

            if (!isOpen(state, shipBlockPosTmp)) {
                val openPos = findOpenShipBlockPosForPoint(level, state, shipPosTmp, shipBlockPosTmp) ?: continue
                shipBlockPosTmp.set(openPos)
            }

            if (isAirPocket(state, shipBlockPosTmp)) {
                return BlockPos(shipBlockPosTmp.x, shipBlockPosTmp.y, shipBlockPosTmp.z)
            }
        }

        return null
    }

    private fun findOpenShipBlockPosForPoint(
        level: Level,
        state: ShipPocketState,
        shipPos: Vector3d,
        tmp: BlockPos.MutableBlockPos,
    ): BlockPos.MutableBlockPos? {
        val x = shipPos.x
        val y = shipPos.y
        val z = shipPos.z

        if (isOpenAtShipPoint(state, x, y, z, tmp)) return tmp

        val e = POINT_QUERY_EPS
        for (dx in doubleArrayOf(-e, 0.0, e)) {
            for (dy in doubleArrayOf(-e, 0.0, e)) {
                for (dz in doubleArrayOf(-e, 0.0, e)) {
                    if (dx == 0.0 && dy == 0.0 && dz == 0.0) continue
                    if (isOpenAtShipPoint(state, x + dx, y + dy, z + dz, tmp)) return tmp
                }
            }
        }

        val baseX = Mth.floor(x)
        val baseY = Mth.floor(y)
        val baseZ = Mth.floor(z)

        // If we're inside a water-blocking block space (doors/panes/walls/etc), try to use the collision shape to pick
        // the open neighbor cell that the point can actually "exit" through. This avoids flickering at thin collision
        // blocks like doors (player can be inside the same block coordinate, but only on one side of the plane).
        run {
            tmp.set(baseX, baseY, baseZ)
            val bs = level.getBlockState(tmp)
            val shape = bs.getCollisionShape(level, tmp)
            if (!shape.isEmpty) {
                val fx = (x - baseX.toDouble()).coerceIn(0.0, 1.0)
                val fy = (y - baseY.toDouble()).coerceIn(0.0, 1.0)
                val fz = (z - baseZ.toDouble()).coerceIn(0.0, 1.0)

                val eps = 1e-4
                val boxes: List<AABB> = shape.toAabbs()

                fun contains(px: Double, py: Double, pz: Double): Boolean {
                    for (box in boxes) {
                        if (px >= box.minX && px <= box.maxX &&
                            py >= box.minY && py <= box.maxY &&
                            pz >= box.minZ && pz <= box.maxZ
                        ) {
                            return true
                        }
                    }
                    return false
                }

                fun consider(dirX: Int, dirY: Int, dirZ: Int, sampleX: Double, sampleY: Double, sampleZ: Double, faceDist: Double,
                    best: DoubleArray,
                ) {
                    if (contains(sampleX, sampleY, sampleZ)) return
                    tmp.set(baseX + dirX, baseY + dirY, baseZ + dirZ)
                    if (!isOpen(state, tmp)) return
                    val interior = isInterior(state, tmp)
                    if (faceDist < best[0] - 1e-12 || (faceDist <= best[0] + 1e-12 && interior && best[1] == 0.0)) {
                        best[0] = faceDist
                        best[1] = if (interior) 1.0 else 0.0
                        best[2] = tmp.x.toDouble()
                        best[3] = tmp.y.toDouble()
                        best[4] = tmp.z.toDouble()
                    }
                }

                val best = doubleArrayOf(Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0, 0.0)

                consider(-1, 0, 0, eps, fy, fz, fx, best) // west
                consider(1, 0, 0, 1.0 - eps, fy, fz, 1.0 - fx, best) // east
                consider(0, -1, 0, fx, eps, fz, fy, best) // down
                consider(0, 1, 0, fx, 1.0 - eps, fz, 1.0 - fy, best) // up
                consider(0, 0, -1, fx, fy, eps, fz, best) // north
                consider(0, 0, 1, fx, fy, 1.0 - eps, 1.0 - fz, best) // south

                if (best[0] != Double.POSITIVE_INFINITY) {
                    tmp.set(best[2].toInt(), best[3].toInt(), best[4].toInt())
                    return tmp
                }
            }
        }

        // Otherwise, fall back to finding the nearest open neighbor cell so entities/camera don't flicker between
        // "in water" and "in air" near boundaries.
        var bestDistSq = Double.POSITIVE_INFINITY
        var bestX = 0
        var bestY = 0
        var bestZ = 0

        for (dx in -1..1) {
            val px = baseX + dx
            val cx = px + 0.5
            val ddx = x - cx
            for (dy in -1..1) {
                val py = baseY + dy
                val cy = py + 0.5
                val ddy = y - cy
                for (dz in -1..1) {
                    if (dx == 0 && dy == 0 && dz == 0) continue
                    val pz = baseZ + dz
                    tmp.set(px, py, pz)
                    if (!isOpen(state, tmp)) continue

                    val cz = pz + 0.5
                    val ddz = z - cz
                    val distSq = ddx * ddx + ddy * ddy + ddz * ddz

                    if (distSq < bestDistSq - 1e-12) {
                        bestDistSq = distSq
                        bestX = px
                        bestY = py
                        bestZ = pz
                    }
                }
            }
        }

        if (bestDistSq != Double.POSITIVE_INFINITY) {
            tmp.set(bestX, bestY, bestZ)
            return tmp
        }

        return null
    }

    private fun isOpenAtShipPoint(
        state: ShipPocketState,
        x: Double,
        y: Double,
        z: Double,
        tmp: BlockPos.MutableBlockPos,
    ): Boolean {
        tmp.set(Mth.floor(x), Mth.floor(y), Mth.floor(z))
        return isOpen(state, tmp)
    }

    private fun getState(level: Level, shipId: Long): ShipPocketState? {
        val map = if (level.isClientSide) clientStates else serverStates
        return map[level.dimensionId]?.get(shipId)
    }

    private fun isInterior(state: ShipPocketState, shipPos: BlockPos): Boolean {
        val lx = shipPos.x - state.minX
        val ly = shipPos.y - state.minY
        val lz = shipPos.z - state.minZ
        if (lx !in 0 until state.sizeX || ly !in 0 until state.sizeY || lz !in 0 until state.sizeZ) return false
        val idx = indexOf(state, lx, ly, lz)
        return state.interior.get(idx)
    }

    private fun isOpen(state: ShipPocketState, shipPos: BlockPos): Boolean {
        val lx = shipPos.x - state.minX
        val ly = shipPos.y - state.minY
        val lz = shipPos.z - state.minZ
        if (lx !in 0 until state.sizeX || ly !in 0 until state.sizeY || lz !in 0 until state.sizeZ) return false
        val idx = indexOf(state, lx, ly, lz)
        return state.open.get(idx)
    }

    private fun isAirPocket(state: ShipPocketState, shipPos: BlockPos): Boolean {
        val lx = shipPos.x - state.minX
        val ly = shipPos.y - state.minY
        val lz = shipPos.z - state.minZ
        if (lx !in 0 until state.sizeX || ly !in 0 until state.sizeY || lz !in 0 until state.sizeZ) return false
        val idx = indexOf(state, lx, ly, lz)
        return state.open.get(idx) && !state.waterReachable.get(idx)
    }

    private fun indexOf(state: ShipPocketState, lx: Int, ly: Int, lz: Int): Int =
        lx + state.sizeX * (ly + state.sizeY * lz)

    private fun posFromIndex(state: ShipPocketState, idx: Int, out: BlockPos.MutableBlockPos): BlockPos.MutableBlockPos {
        val sx = state.sizeX
        val sy = state.sizeY
        val lx = idx % sx
        val t = idx / sx
        val ly = t % sy
        val lz = t / sy
        return out.set(state.minX + lx, state.minY + ly, state.minZ + lz)
    }

    private fun getQueryTransform(ship: Ship): ShipTransform {
        return if (ship is ClientShip) ship.renderTransform else ship.transform
    }

    private fun recomputeState(
        level: Level,
        ship: LoadedShip,
        state: ShipPocketState,
        minX: Int,
        minY: Int,
        minZ: Int,
        sizeX: Int,
        sizeY: Int,
        sizeZ: Int,
    ) {
        val boundsChanged =
            state.minX != minX || state.minY != minY || state.minZ != minZ ||
                state.sizeX != sizeX || state.sizeY != sizeY || state.sizeZ != sizeZ
        val prevOpen = state.open

        state.minX = minX
        state.minY = minY
        state.minZ = minZ
        state.sizeX = sizeX
        state.sizeY = sizeY
        state.sizeZ = sizeZ

        val volume = sizeX * sizeY * sizeZ
        val open = BitSet(volume)
        val flooded = if (level.isClientSide) state.flooded else BitSet(volume)
        val materialized = if (level.isClientSide) state.materializedWater else BitSet(volume)

        val pos = BlockPos.MutableBlockPos()

        var idx = 0
        for (z in 0 until sizeZ) {
            for (y in 0 until sizeY) {
                for (x in 0 until sizeX) {
                    pos.set(minX + x, minY + y, minZ + z)
                    val bs = level.getBlockState(pos)

                    if (!blocksWater(level, pos, bs)) {
                        open.set(idx)
                    }

                    if (!level.isClientSide) {
                        val fluidState = bs.fluidState
                        if (!fluidState.isEmpty && fluidState.`is`(Fluids.WATER)) {
                            flooded.set(idx)
                            if (bs.block == Blocks.WATER) {
                                materialized.set(idx)
                            }
                        }
                    }

                    idx++
                }
            }
        }

        val exterior = floodFillFromBoundary(open, sizeX, sizeY, sizeZ)
        val interior = BitSet(volume).apply {
            or(open)
            andNot(exterior)
        }

        state.open = open
        state.interior = interior
        state.waterReachable = BitSet(volume)
        if (!level.isClientSide) {
            state.flooded = flooded
            state.materializedWater = materialized
        }

        state.dirty = false
        if (boundsChanged || prevOpen != open) {
            state.geometryRevision++
        }
    }

    private fun computeWaterReachableWithPressure(
        level: Level,
        minX: Int,
        minY: Int,
        minZ: Int,
        sizeX: Int,
        sizeY: Int,
        sizeZ: Int,
        open: BitSet,
        shipTransform: ShipTransform,
        out: BitSet,
    ): BitSet {
        out.clear()

        val volumeLong = sizeX.toLong() * sizeY.toLong() * sizeZ.toLong()
        if (volumeLong <= 0 || volumeLong > MAX_SIM_VOLUME.toLong()) return out
        if (open.isEmpty) return out

        val volume = volumeLong.toInt()

        var componentQueue = tmpFloodQueue.get()
        if (componentQueue.size < volume) {
            componentQueue = IntArray(volume)
            tmpFloodQueue.set(componentQueue)
        }

        val componentVisited = tmpPressureComponentVisited.get()
        componentVisited.clear()

        val submerged = tmpPressureSubmerged.get()
        submerged.clear()

        var escapeHeight = tmpPressureEscapeHeight.get()
        if (escapeHeight.size < volume) {
            escapeHeight = DoubleArray(volume)
            tmpPressureEscapeHeight.set(escapeHeight)
        }

        var heapIdx = tmpPressureHeapIdx.get()
        if (heapIdx.size < volume) {
            heapIdx = IntArray(volume)
            tmpPressureHeapIdx.set(heapIdx)
        }
        var heapPos = tmpPressureHeapPos.get()
        if (heapPos.size < volume) {
            heapPos = IntArray(volume)
            tmpPressureHeapPos.set(heapPos)
        }

        val worldPosTmp = Vector3d()
        val shipPosTmp = Vector3d()
        val shipBlockPos = BlockPos.MutableBlockPos()
        val worldBlockPos = BlockPos.MutableBlockPos()

        fun shipCellSubmerged(idx: Int): Boolean {
            val lx = idx % sizeX
            val t = idx / sizeX
            val ly = t % sizeY
            val lz = t / sizeY
            shipBlockPos.set(minX + lx, minY + ly, minZ + lz)
            return isShipCellSubmergedInWorldWater(
                level,
                shipTransform,
                shipBlockPos,
                shipPosTmp,
                worldPosTmp,
                worldBlockPos,
            )
        }

        // Cache which open cells are submerged in world water. We use this mask many times and it is much cheaper to
        // query a BitSet than to re-run the world sampling logic.
        var idx = open.nextSetBit(0)
        while (idx >= 0 && idx < volume) {
            if (shipCellSubmerged(idx)) submerged.set(idx)
            idx = open.nextSetBit(idx + 1)
        }

        val baseShipX = minX + 0.5
        val baseShipY = minY + 0.5
        val baseShipZ = minZ + 0.5

        shipPosTmp.set(baseShipX, baseShipY, baseShipZ)
        shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)
        val baseWorldX = worldPosTmp.x
        val baseWorldY = worldPosTmp.y
        val baseWorldZ = worldPosTmp.z

        // World-space Y is an affine function of ship-space x/y/z. Sample it once to get a fast linear map.
        // Also compute axis step vectors so "wall inlet" classification is robust even if the transform isn't unit scale.
        val dX = Vector3d()
        val dY = Vector3d()
        val dZ = Vector3d()

        shipPosTmp.set(baseShipX + 1.0, baseShipY, baseShipZ)
        shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)
        dX.set(worldPosTmp.x - baseWorldX, worldPosTmp.y - baseWorldY, worldPosTmp.z - baseWorldZ)

        shipPosTmp.set(baseShipX, baseShipY + 1.0, baseShipZ)
        shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)
        dY.set(worldPosTmp.x - baseWorldX, worldPosTmp.y - baseWorldY, worldPosTmp.z - baseWorldZ)

        shipPosTmp.set(baseShipX, baseShipY, baseShipZ + 1.0)
        shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)
        dZ.set(worldPosTmp.x - baseWorldX, worldPosTmp.y - baseWorldY, worldPosTmp.z - baseWorldZ)

        val incX = dX.y
        val incY = dY.y
        val incZ = dZ.y
        val maxStepWorldY = maxOf(kotlin.math.abs(incX), kotlin.math.abs(incY), kotlin.math.abs(incZ))

        // Determine which ship axis is most aligned with world up (treat that as the "vertical end" axis).
        // The other four faces are treated as "walls" for the special-case flooding rule:
        // submerged wall holes always flood regardless of trapped air.
        //
        // Use transformed face normals (cross products) so this remains correct even if the transform isn't unit scale.
        val nX = Vector3d(dY).cross(dZ)
        val nY = Vector3d(dZ).cross(dX)
        val nZ = Vector3d(dX).cross(dY)

        fun verticalFrac(normal: Vector3d): Double {
            val len = normal.length()
            if (len <= 1e-12) return 0.0
            return kotlin.math.abs(normal.y) / len
        }

        val vX = verticalFrac(nX)
        val vY = verticalFrac(nY)
        val vZ = verticalFrac(nZ)

        val verticalAxis =
            if (vY >= vX && vY >= vZ) 1 else if (vX >= vZ) 0 else 2 // 0=X, 1=Y, 2=Z

        val isWallFaceX = verticalAxis != 0
        val isWallFaceY = verticalAxis != 1
        val isWallFaceZ = verticalAxis != 2

        val strideY = sizeX
        val strideZ = sizeX * sizeY

        fun isBoundary(lx: Int, ly: Int, lz: Int): Boolean {
            return lx == 0 || lx + 1 == sizeX || ly == 0 || ly + 1 == sizeY || lz == 0 || lz + 1 == sizeZ
        }

        // Analyze each open component that is exposed to world water on the bounds.
        forEachBoundaryIndex(sizeX, sizeY, sizeZ) { boundaryIdx ->
            if (!open.get(boundaryIdx)) return@forEachBoundaryIndex
            if (componentVisited.get(boundaryIdx)) return@forEachBoundaryIndex
            if (!submerged.get(boundaryIdx)) return@forEachBoundaryIndex

            // Flood-fill the full connected open component.
            var head = 0
            var tail = 0
            componentVisited.set(boundaryIdx)
            componentQueue[tail++] = boundaryIdx

            fun enqueueComponent(idx: Int) {
                if (!open.get(idx) || componentVisited.get(idx)) return
                componentVisited.set(idx)
                componentQueue[tail++] = idx
            }

            while (head < tail) {
                val idx = componentQueue[head++]

                val lx = idx % sizeX
                val t = idx / sizeX
                val ly = t % sizeY
                val lz = t / sizeY

                if (lx > 0) enqueueComponent(idx - 1)
                if (lx + 1 < sizeX) enqueueComponent(idx + 1)
                if (ly > 0) enqueueComponent(idx - strideY)
                if (ly + 1 < sizeY) enqueueComponent(idx + strideY)
                if (lz > 0) enqueueComponent(idx - strideZ)
                if (lz + 1 < sizeZ) enqueueComponent(idx + strideZ)
            }

            val componentSize = tail
            if (componentSize <= 0) return@forEachBoundaryIndex

            // If outside water can enter this component through a submerged wall hole, treat it as "unpressurized":
            // submerged wall holes always flood regardless of trapped air.
            //
            // IMPORTANT: We must detect actual hull openings, not just "this component is big enough that boundary water
            // can move inward". Use the padded AABB (POCKET_BOUNDS_PADDING=1): a true hull hole shows up as an open cell
            // 1 block in from the sim bounds, with submerged open space on the outside neighbor and solid blocks around
            // the opening (lateral to the hole axis).
            var hasSubmergedWallHole = false

            fun lateralSolidCountForX(idx: Int, ly: Int, lz: Int): Int {
                var solids = 0
                if (ly > 0 && !open.get(idx - strideY)) solids++
                if (ly + 1 < sizeY && !open.get(idx + strideY)) solids++
                if (lz > 0 && !open.get(idx - strideZ)) solids++
                if (lz + 1 < sizeZ && !open.get(idx + strideZ)) solids++
                return solids
            }

            fun lateralSolidCountForY(idx: Int, lx: Int, lz: Int): Int {
                var solids = 0
                if (lx > 0 && !open.get(idx - 1)) solids++
                if (lx + 1 < sizeX && !open.get(idx + 1)) solids++
                if (lz > 0 && !open.get(idx - strideZ)) solids++
                if (lz + 1 < sizeZ && !open.get(idx + strideZ)) solids++
                return solids
            }

            fun lateralSolidCountForZ(idx: Int, lx: Int, ly: Int): Int {
                var solids = 0
                if (lx > 0 && !open.get(idx - 1)) solids++
                if (lx + 1 < sizeX && !open.get(idx + 1)) solids++
                if (ly > 0 && !open.get(idx - strideY)) solids++
                if (ly + 1 < sizeY && !open.get(idx + strideY)) solids++
                return solids
            }

            for (i in 0 until componentSize) {
                val idx = componentQueue[i]
                if (!submerged.get(idx)) continue
                val lx = idx % sizeX
                val t = idx / sizeX
                val ly = t % sizeY
                val lz = t / sizeY
                if (isBoundary(lx, ly, lz)) continue

                if (isWallFaceX && sizeX > 2) {
                    if (lx == 1) {
                        val outIdx = idx - 1
                        val inIdx = idx + 1
                        if (open.get(outIdx) && submerged.get(outIdx) && open.get(inIdx)) {
                            if (lateralSolidCountForX(idx, ly, lz) >= WALL_HOLE_MIN_LATERAL_SOLIDS) {
                                hasSubmergedWallHole = true
                                break
                            }
                        }
                    } else if (lx == sizeX - 2) {
                        val outIdx = idx + 1
                        val inIdx = idx - 1
                        if (open.get(outIdx) && submerged.get(outIdx) && open.get(inIdx)) {
                            if (lateralSolidCountForX(idx, ly, lz) >= WALL_HOLE_MIN_LATERAL_SOLIDS) {
                                hasSubmergedWallHole = true
                                break
                            }
                        }
                    }
                }

                if (isWallFaceY && sizeY > 2) {
                    if (ly == 1) {
                        val outIdx = idx - strideY
                        val inIdx = idx + strideY
                        if (open.get(outIdx) && submerged.get(outIdx) && open.get(inIdx)) {
                            if (lateralSolidCountForY(idx, lx, lz) >= WALL_HOLE_MIN_LATERAL_SOLIDS) {
                                hasSubmergedWallHole = true
                                break
                            }
                        }
                    } else if (ly == sizeY - 2) {
                        val outIdx = idx + strideY
                        val inIdx = idx - strideY
                        if (open.get(outIdx) && submerged.get(outIdx) && open.get(inIdx)) {
                            if (lateralSolidCountForY(idx, lx, lz) >= WALL_HOLE_MIN_LATERAL_SOLIDS) {
                                hasSubmergedWallHole = true
                                break
                            }
                        }
                    }
                }

                if (isWallFaceZ && sizeZ > 2) {
                    if (lz == 1) {
                        val outIdx = idx - strideZ
                        val inIdx = idx + strideZ
                        if (open.get(outIdx) && submerged.get(outIdx) && open.get(inIdx)) {
                            if (lateralSolidCountForZ(idx, lx, ly) >= WALL_HOLE_MIN_LATERAL_SOLIDS) {
                                hasSubmergedWallHole = true
                                break
                            }
                        }
                    } else if (lz == sizeZ - 2) {
                        val outIdx = idx + strideZ
                        val inIdx = idx - strideZ
                        if (open.get(outIdx) && submerged.get(outIdx) && open.get(inIdx)) {
                            if (lateralSolidCountForZ(idx, lx, ly) >= WALL_HOLE_MIN_LATERAL_SOLIDS) {
                                hasSubmergedWallHole = true
                                break
                            }
                        }
                    }
                }
            }

            if (hasSubmergedWallHole) {
                // Vanilla-style flood fill through submerged open cells (no air pressure restriction).
                val waterQueue = heapIdx
                head = 0
                var waterTail = 0

                fun tryEnqueueWater(idx: Int) {
                    if (!open.get(idx) || out.get(idx)) return
                    if (!submerged.get(idx)) return
                    out.set(idx)
                    waterQueue[waterTail++] = idx
                }

                for (i in 0 until componentSize) {
                    val idx = componentQueue[i]
                    val lx = idx % sizeX
                    val t = idx / sizeX
                    val ly = t % sizeY
                    val lz = t / sizeY
                    if (!isBoundary(lx, ly, lz)) continue
                    if (!submerged.get(idx)) continue
                    tryEnqueueWater(idx)
                }

                while (head < waterTail) {
                    val idx = waterQueue[head++]

                    val lx = idx % sizeX
                    val t = idx / sizeX
                    val ly = t % sizeY
                    val lz = t / sizeY

                    if (lx > 0) tryEnqueueWater(idx - 1)
                    if (lx + 1 < sizeX) tryEnqueueWater(idx + 1)
                    if (ly > 0) tryEnqueueWater(idx - strideY)
                    if (ly + 1 < sizeY) tryEnqueueWater(idx + strideY)
                    if (lz > 0) tryEnqueueWater(idx - strideZ)
                    if (lz + 1 < sizeZ) tryEnqueueWater(idx + strideZ)
                }

                return@forEachBoundaryIndex
            }

            // Compute, for each cell in the component, the highest world-space Y-level L such that there exists a path
            // from that cell to the bounds that never goes below L (a maximin "escape height").
            //
            // Water can only rise into a cell if the cell height is <= its escape height; otherwise filling it would
            // require the trapped air to dip below the would-be water level.
            for (i in 0 until componentSize) {
                escapeHeight[componentQueue[i]] = Double.NEGATIVE_INFINITY
            }
            for (i in 0 until componentSize) {
                heapPos[componentQueue[i]] = -1
            }

            var heapSize = 0

            fun heapSwap(i: Int, j: Int) {
                val a = heapIdx[i]
                val b = heapIdx[j]
                heapIdx[i] = b
                heapIdx[j] = a
                heapPos[a] = j
                heapPos[b] = i
            }

            fun heapBubbleUp(i0: Int) {
                var i = i0
                while (i > 0) {
                    val parent = (i - 1) ushr 1
                    if (escapeHeight[heapIdx[parent]] >= escapeHeight[heapIdx[i]]) break
                    heapSwap(i, parent)
                    i = parent
                }
            }

            fun heapBubbleDown(i0: Int) {
                var i = i0
                while (true) {
                    val left = i * 2 + 1
                    if (left >= heapSize) break
                    val right = left + 1
                    var child = left
                    if (right < heapSize && escapeHeight[heapIdx[right]] > escapeHeight[heapIdx[left]]) {
                        child = right
                    }
                    if (escapeHeight[heapIdx[i]] >= escapeHeight[heapIdx[child]]) break
                    heapSwap(i, child)
                    i = child
                }
            }

            fun heapInsertOrUpdate(idx: Int) {
                val pos = heapPos[idx]
                if (pos >= 0) {
                    heapBubbleUp(pos)
                    return
                }

                heapIdx[heapSize] = idx
                heapPos[idx] = heapSize
                heapSize++
                heapBubbleUp(heapSize - 1)
            }

            fun heapPopMax(): Int {
                val resIdx = heapIdx[0]
                heapSize--
                if (heapSize > 0) {
                    val lastIdx = heapIdx[heapSize]
                    heapIdx[0] = lastIdx
                    heapPos[lastIdx] = 0
                    heapBubbleDown(0)
                }
                heapPos[resIdx] = -1
                return resIdx
            }

            // Initialize the heap with all boundary cells in the component.
            for (i in 0 until componentSize) {
                val idx = componentQueue[i]
                val lx = idx % sizeX
                val t = idx / sizeX
                val ly = t % sizeY
                val lz = t / sizeY
                if (!isBoundary(lx, ly, lz)) continue
                val worldY = baseWorldY + incX * lx + incY * ly + incZ * lz
                escapeHeight[idx] = worldY
                heapInsertOrUpdate(idx)
            }

            while (heapSize > 0) {
                val idx = heapPopMax()
                val value = escapeHeight[idx]

                val lx = idx % sizeX
                val t = idx / sizeX
                val ly = t % sizeY
                val lz = t / sizeY
                val worldY = baseWorldY + incX * lx + incY * ly + incZ * lz

                fun tryRelax(nIdx: Int, nWorldY: Double) {
                    if (!open.get(nIdx)) return
                    val candidate = minOf(value, nWorldY)
                    if (candidate > escapeHeight[nIdx] + AIR_PRESSURE_Y_EPS) {
                        escapeHeight[nIdx] = candidate
                        heapInsertOrUpdate(nIdx)
                    }
                }

                if (lx > 0) tryRelax(idx - 1, worldY - incX)
                if (lx + 1 < sizeX) tryRelax(idx + 1, worldY + incX)
                if (ly > 0) tryRelax(idx - strideY, worldY - incY)
                if (ly + 1 < sizeY) tryRelax(idx + strideY, worldY + incY)
                if (lz > 0) tryRelax(idx - strideZ, worldY - incZ)
                if (lz + 1 < sizeZ) tryRelax(idx + strideZ, worldY + incZ)
            }

            // Flood-fill through submerged open cells, but only into cells that are at/below their escape height.
            val waterQueue = heapIdx
            head = 0
            var waterTail = 0

            fun tryEnqueueWater(idx: Int, worldY: Double) {
                if (!open.get(idx) || out.get(idx)) return
                if (!submerged.get(idx)) return
                // Allow one-step rise above the escape height so the inlet block itself becomes wet (e.g., a bottom hole
                // where the outside water cell is one step lower than the first interior cell).
                if (worldY > escapeHeight[idx] + maxStepWorldY + AIR_PRESSURE_Y_EPS) return
                out.set(idx)
                waterQueue[waterTail++] = idx
            }

            // Seed all boundary water contact points for this open component.
            for (i in 0 until componentSize) {
                val idx = componentQueue[i]
                val lx = idx % sizeX
                val t = idx / sizeX
                val ly = t % sizeY
                val lz = t / sizeY
                if (!isBoundary(lx, ly, lz)) continue
                if (!submerged.get(idx)) continue
                val worldY = baseWorldY + incX * lx + incY * ly + incZ * lz
                tryEnqueueWater(idx, worldY)
            }

            while (head < waterTail) {
                val idx = waterQueue[head++]

                val lx = idx % sizeX
                val t = idx / sizeX
                val ly = t % sizeY
                val lz = t / sizeY
                val worldY = baseWorldY + incX * lx + incY * ly + incZ * lz

                if (lx > 0) tryEnqueueWater(idx - 1, worldY - incX)
                if (lx + 1 < sizeX) tryEnqueueWater(idx + 1, worldY + incX)
                if (ly > 0) tryEnqueueWater(idx - strideY, worldY - incY)
                if (ly + 1 < sizeY) tryEnqueueWater(idx + strideY, worldY + incY)
                if (lz > 0) tryEnqueueWater(idx - strideZ, worldY - incZ)
                if (lz + 1 < sizeZ) tryEnqueueWater(idx + strideZ, worldY + incZ)
            }
        }

        return out
    }

    private fun computeWaterReachable(
        level: Level,
        state: ShipPocketState,
        shipTransform: ShipTransform,
    ): BitSet {
        return computeWaterReachableWithPressure(
            level,
            state.minX,
            state.minY,
            state.minZ,
            state.sizeX,
            state.sizeY,
            state.sizeZ,
            state.open,
            shipTransform,
            state.waterReachable,
        )
    }

    /**
     * Computes a water-reachability mask for rendering, using the provided [shipTransform].
     *
     * This is used by the water-surface culling shader to stay stable when ships move/rotate quickly between ticks.
     * It intentionally operates on the already-computed [open] voxel set and does not mutate any [ShipPocketState].
     */
    @JvmStatic
    fun computeWaterReachableForRender(
        level: Level,
        minX: Int,
        minY: Int,
        minZ: Int,
        sizeX: Int,
        sizeY: Int,
        sizeZ: Int,
        open: BitSet,
        shipTransform: ShipTransform,
        out: BitSet,
    ): BitSet {
        return computeWaterReachableWithPressure(
            level,
            minX,
            minY,
            minZ,
            sizeX,
            sizeY,
            sizeZ,
            open,
            shipTransform,
            out,
        )
    }

    private fun updateFlooding(level: ServerLevel, ship: LoadedShip, state: ShipPocketState, shipTransform: ShipTransform) {
        val open = state.open
        val interior = state.interior
        val flooded = state.flooded
        val materialized = state.materializedWater
        if (open.isEmpty && flooded.isEmpty && materialized.isEmpty) return

        val pos = BlockPos.MutableBlockPos()

        // Remove materialized water blocks that are not in a watertight interior *and* are reachable by world water.
        // This keeps player-placed water in (dry) air pockets, while still preventing shipyard water from existing
        // in outside-of-ship spaces that are connected to the ocean.
        val toRemove = materialized.clone() as BitSet
        toRemove.andNot(interior)
        toRemove.and(state.waterReachable)
        applyBlockChanges(level, state, toRemove, toWater = false, pos = pos)

        // Compute newly flooded cells from outside water contact (waterline-aware).
        val externalWet = state.waterReachable

        // Only treat outside water contact as "flooding" for instant materialization.
        // Player-placed water inside a closed pocket should behave like vanilla flow, not instantly fill the chamber.
        flooded.or(externalWet)

        // Materialize water blocks for flooded interior cells.
        val toAdd = flooded.clone() as BitSet
        toAdd.and(interior)
        toAdd.andNot(materialized)
        applyBlockChanges(level, state, toAdd, toWater = true, pos = pos, shipTransform = shipTransform)
    }

    private fun floodFillFromSeedsSubmerged(
        level: Level,
        shipTransform: ShipTransform,
        state: ShipPocketState,
        seeds: BitSet,
    ): BitSet {
        val sizeX = state.sizeX
        val sizeY = state.sizeY
        val sizeZ = state.sizeZ
        val volume = sizeX * sizeY * sizeZ

        val open = state.open
        if (open.isEmpty || seeds.isEmpty) return BitSet()

        val visited = BitSet(volume)
        var queue = tmpFloodQueue.get()
        if (queue.size < volume) {
            queue = IntArray(volume)
            tmpFloodQueue.set(queue)
        }
        var head = 0
        var tail = 0

        val worldPosTmp = Vector3d()
        val shipPosTmp = Vector3d()
        val shipBlockPos = BlockPos.MutableBlockPos()
        val worldBlockPos = BlockPos.MutableBlockPos()

        fun shipCellSubmerged(idx: Int): Boolean {
            posFromIndex(state, idx, shipBlockPos)
            return isShipCellSubmergedInWorldWater(level, shipTransform, shipBlockPos, shipPosTmp, worldPosTmp, worldBlockPos)
        }

        fun tryEnqueue(idx: Int, requireSubmerged: Boolean) {
            if (!open.get(idx) || visited.get(idx)) return
            if (requireSubmerged && !shipCellSubmerged(idx)) return
            visited.set(idx)
            queue[tail++] = idx
        }

        var idx = seeds.nextSetBit(0)
        while (idx >= 0) {
            // Always seed from existing materialized water, even if it's above the waterline.
            tryEnqueue(idx, requireSubmerged = false)
            idx = seeds.nextSetBit(idx + 1)
        }

        val strideY = sizeX
        val strideZ = sizeX * sizeY

        while (head < tail) {
            val cur = queue[head++]

            val lx = cur % sizeX
            val t = cur / sizeX
            val ly = t % sizeY
            val lz = t / sizeY

            // Spread only through submerged open cells to prevent water rising above the external waterline.
            if (lx > 0) tryEnqueue(cur - 1, requireSubmerged = true)
            if (lx + 1 < sizeX) tryEnqueue(cur + 1, requireSubmerged = true)
            if (ly > 0) tryEnqueue(cur - strideY, requireSubmerged = true)
            if (ly + 1 < sizeY) tryEnqueue(cur + strideY, requireSubmerged = true)
            if (lz > 0) tryEnqueue(cur - strideZ, requireSubmerged = true)
            if (lz + 1 < sizeZ) tryEnqueue(cur + strideZ, requireSubmerged = true)
        }

        return visited
    }

    private fun applyBlockChanges(
        level: ServerLevel,
        state: ShipPocketState,
        indices: BitSet,
        toWater: Boolean,
        pos: BlockPos.MutableBlockPos,
        shipTransform: ShipTransform? = null,
    ) {
        if (indices.isEmpty) return

        val flags = 11 // 1 (block update) + 2 (send to clients) + 8 (force rerender)

        val worldPosTmp = Vector3d()
        val shipPosTmp = Vector3d()
        val worldBlockPos = BlockPos.MutableBlockPos()

        applyingInternalUpdates = true
        try {
            var idx = indices.nextSetBit(0)
            while (idx >= 0) {
                posFromIndex(state, idx, pos)

                val current = level.getBlockState(pos)
                if (toWater) {
                    if (current.isAir) {
                        if (shipTransform != null && !isShipCellSubmergedInWorldWater(
                                level,
                                shipTransform,
                                pos,
                                shipPosTmp,
                                worldPosTmp,
                                worldBlockPos
                            )
                        ) {
                            idx = indices.nextSetBit(idx + 1)
                            continue
                        }
                        level.setBlock(pos, Blocks.WATER.defaultBlockState(), flags)
                        state.materializedWater.set(idx)
                    }
                } else {
                    if (current.block == Blocks.WATER) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags)
                        state.materializedWater.clear(idx)
                    }
                }

                idx = indices.nextSetBit(idx + 1)
            }
        } finally {
            applyingInternalUpdates = false
        }
    }

    private fun isShipCellSubmergedInWorldWater(
        level: Level,
        shipTransform: ShipTransform,
        shipBlockPos: BlockPos,
        shipPosTmp: Vector3d,
        worldPosTmp: Vector3d,
        worldBlockPos: BlockPos.MutableBlockPos,
    ): Boolean {
        val epsCorner = 1e-4
        val epsY = 1e-5

        fun sample(shipX: Double, shipY: Double, shipZ: Double): Boolean {
            shipPosTmp.set(shipX, shipY, shipZ)
            shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)

            val wx = Mth.floor(worldPosTmp.x)
            val wy = Mth.floor(worldPosTmp.y)
            val wz = Mth.floor(worldPosTmp.z)
            worldBlockPos.set(wx, wy, wz)

            val worldFluid = level.getFluidState(worldBlockPos)
            if (worldFluid.isEmpty || !worldFluid.`is`(Fluids.WATER)) return false
            if (worldFluid.isSource) return true

            val height = worldFluid.getHeight(level, worldBlockPos).toDouble()
            val localY = worldPosTmp.y - wy.toDouble()
            return localY <= height + epsY
        }

        val x0 = shipBlockPos.x.toDouble()
        val y0 = shipBlockPos.y.toDouble()
        val z0 = shipBlockPos.z.toDouble()

        // Fast path: check the cell center first.
        shipPosTmp.set(x0 + 0.5, y0 + 0.5, z0 + 0.5)
        shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)
        val centerWx = Mth.floor(worldPosTmp.x)
        val centerWy = Mth.floor(worldPosTmp.y)
        val centerWz = Mth.floor(worldPosTmp.z)
        worldBlockPos.set(centerWx, centerWy, centerWz)
        val centerFluid = level.getFluidState(worldBlockPos)

        if (!centerFluid.isEmpty && centerFluid.`is`(Fluids.WATER)) {
            if (centerFluid.isSource) return true
            val height = centerFluid.getHeight(level, worldBlockPos).toDouble()
            val localY = worldPosTmp.y - centerWy.toDouble()
            if (localY <= height + epsY) return true
        }

        // Near the waterline / with rotation, the cell center can be above water while a corner is submerged.
        val lo = epsCorner
        val hi = 1.0 - epsCorner
        if (sample(x0 + lo, y0 + lo, z0 + lo)) return true
        if (sample(x0 + hi, y0 + lo, z0 + lo)) return true
        if (sample(x0 + lo, y0 + hi, z0 + lo)) return true
        if (sample(x0 + hi, y0 + hi, z0 + lo)) return true
        if (sample(x0 + lo, y0 + lo, z0 + hi)) return true
        if (sample(x0 + hi, y0 + lo, z0 + hi)) return true
        if (sample(x0 + lo, y0 + hi, z0 + hi)) return true
        if (sample(x0 + hi, y0 + hi, z0 + hi)) return true

        return false
    }

    private fun floodFillFromBoundary(open: BitSet, sizeX: Int, sizeY: Int, sizeZ: Int): BitSet {
        val volume = sizeX * sizeY * sizeZ
        val visited = BitSet(volume)
        val queue = IntArray(volume)
        var head = 0
        var tail = 0

        fun tryEnqueue(idx: Int) {
            if (!open.get(idx) || visited.get(idx)) return
            visited.set(idx)
            queue[tail++] = idx
        }

        forEachBoundaryIndex(sizeX, sizeY, sizeZ) { idx -> tryEnqueue(idx) }

        val strideY = sizeX
        val strideZ = sizeX * sizeY

        while (head < tail) {
            val idx = queue[head++]

            val lx = idx % sizeX
            val t = idx / sizeX
            val ly = t % sizeY
            val lz = t / sizeY

            if (lx > 0) tryEnqueue(idx - 1)
            if (lx + 1 < sizeX) tryEnqueue(idx + 1)
            if (ly > 0) tryEnqueue(idx - strideY)
            if (ly + 1 < sizeY) tryEnqueue(idx + strideY)
            if (lz > 0) tryEnqueue(idx - strideZ)
            if (lz + 1 < sizeZ) tryEnqueue(idx + strideZ)
        }

        return visited
    }

    private fun floodFillFromSeeds(open: BitSet, sizeX: Int, sizeY: Int, sizeZ: Int, seeds: BitSet): BitSet {
        val volume = sizeX * sizeY * sizeZ
        val visited = BitSet(volume)
        val queue = IntArray(volume)
        var head = 0
        var tail = 0

        var idx = seeds.nextSetBit(0)
        while (idx >= 0) {
            if (open.get(idx)) {
                visited.set(idx)
                queue[tail++] = idx
            }
            idx = seeds.nextSetBit(idx + 1)
        }

        val strideY = sizeX
        val strideZ = sizeX * sizeY

        fun tryEnqueue(i: Int) {
            if (!open.get(i) || visited.get(i)) return
            visited.set(i)
            queue[tail++] = i
        }

        while (head < tail) {
            val cur = queue[head++]

            val lx = cur % sizeX
            val t = cur / sizeX
            val ly = t % sizeY
            val lz = t / sizeY

            if (lx > 0) tryEnqueue(cur - 1)
            if (lx + 1 < sizeX) tryEnqueue(cur + 1)
            if (ly > 0) tryEnqueue(cur - strideY)
            if (ly + 1 < sizeY) tryEnqueue(cur + strideY)
            if (lz > 0) tryEnqueue(cur - strideZ)
            if (lz + 1 < sizeZ) tryEnqueue(cur + strideZ)
        }

        return visited
    }

    private fun floodFillFromBoundaryWater(level: Level, ship: LoadedShip, state: ShipPocketState): BitSet {
        val sizeX = state.sizeX
        val sizeY = state.sizeY
        val sizeZ = state.sizeZ
        val volume = sizeX * sizeY * sizeZ

        val open = state.open
        val visited = BitSet(volume)
        val queue = IntArray(volume)
        var head = 0
        var tail = 0

        val shipTransform = getQueryTransform(ship)
        val worldPosTmp = Vector3d()
        val shipPosTmp = Vector3d()
        val shipBlockPos = BlockPos.MutableBlockPos()

        fun shipCellInWorldWater(idx: Int): Boolean {
            posFromIndex(state, idx, shipBlockPos)

            shipPosTmp.set(shipBlockPos.x + 0.5, shipBlockPos.y + 0.5, shipBlockPos.z + 0.5)
            shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)

            val worldBlockPos = BlockPos.containing(worldPosTmp.x, worldPosTmp.y, worldPosTmp.z)
            return level.getFluidState(worldBlockPos).`is`(Fluids.WATER)
        }

        fun tryEnqueue(idx: Int) {
            if (!open.get(idx) || visited.get(idx)) return
            if (!shipCellInWorldWater(idx)) return
            visited.set(idx)
            queue[tail++] = idx
        }

        forEachBoundaryIndex(sizeX, sizeY, sizeZ) { idx -> tryEnqueue(idx) }

        val strideY = sizeX
        val strideZ = sizeX * sizeY

        fun trySpread(idx: Int) {
            if (!open.get(idx) || visited.get(idx)) return
            visited.set(idx)
            queue[tail++] = idx
        }

        while (head < tail) {
            val idx = queue[head++]

            val lx = idx % sizeX
            val t = idx / sizeX
            val ly = t % sizeY
            val lz = t / sizeY

            if (lx > 0) trySpread(idx - 1)
            if (lx + 1 < sizeX) trySpread(idx + 1)
            if (ly > 0) trySpread(idx - strideY)
            if (ly + 1 < sizeY) trySpread(idx + strideY)
            if (lz > 0) trySpread(idx - strideZ)
            if (lz + 1 < sizeZ) trySpread(idx + strideZ)
        }

        return visited
    }

    private fun forEachBoundaryIndex(sizeX: Int, sizeY: Int, sizeZ: Int, cb: (Int) -> Unit) {
        fun idx(lx: Int, ly: Int, lz: Int) = lx + sizeX * (ly + sizeY * lz)

        for (y in 0 until sizeY) {
            for (x in 0 until sizeX) {
                cb(idx(x, y, 0))
                cb(idx(x, y, sizeZ - 1))
            }
        }
        for (z in 0 until sizeZ) {
            for (x in 0 until sizeX) {
                cb(idx(x, 0, z))
                cb(idx(x, sizeY - 1, z))
            }
        }
        for (z in 0 until sizeZ) {
            for (y in 0 until sizeY) {
                cb(idx(0, y, z))
                cb(idx(sizeX - 1, y, z))
            }
        }
    }

    private fun blocksWater(level: Level, pos: BlockPos, state: BlockState): Boolean {
        val block = state.block

        if (state.isAir) return false

        when (block) {
            is DoorBlock -> return !state.getValue(BlockStateProperties.OPEN)
            is TrapDoorBlock -> return !state.getValue(BlockStateProperties.OPEN)
            is FenceGateBlock -> return false
            is FenceBlock -> return false
            is SlabBlock -> return false
            is StairBlock -> {
                val shape = state.getValue(BlockStateProperties.STAIRS_SHAPE)
                return shape != StairsShape.OUTER_LEFT && shape != StairsShape.OUTER_RIGHT
            }
            is IronBarsBlock -> {
                if (block == Blocks.IRON_BARS) return false
                val north = state.getValue(BlockStateProperties.NORTH)
                val south = state.getValue(BlockStateProperties.SOUTH)
                val east = state.getValue(BlockStateProperties.EAST)
                val west = state.getValue(BlockStateProperties.WEST)
                val seals = (north && south) || (east && west)
                return seals
            }
            is WallBlock -> {
                val north = state.getValue(BlockStateProperties.NORTH_WALL) != WallSide.NONE
                val south = state.getValue(BlockStateProperties.SOUTH_WALL) != WallSide.NONE
                val east = state.getValue(BlockStateProperties.EAST_WALL) != WallSide.NONE
                val west = state.getValue(BlockStateProperties.WEST_WALL) != WallSide.NONE
                val seals = (north && south) || (east && west)
                return seals
            }
        }

        // If you can normally walk through it (plants, etc), it leaks.
        if (state.getCollisionShape(level, pos).isEmpty) return false

        // Default: only treat full collision blocks as watertight.
        return state.isCollisionShapeFullBlock(level, pos)
    }
}
