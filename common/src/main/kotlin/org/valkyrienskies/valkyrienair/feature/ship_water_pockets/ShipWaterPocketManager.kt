package org.valkyrienskies.valkyrienair.feature.ship_water_pockets

import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
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
import net.minecraft.world.phys.Vec3
import org.apache.logging.log4j.LogManager
import org.joml.Vector3d
import org.joml.primitives.AABBd
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.core.api.ships.LoadedShip
import org.valkyrienskies.core.api.ships.LoadedServerShip
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.core.api.ships.properties.ShipTransform
import org.valkyrienskies.core.api.world.properties.DimensionId
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.isBlockInShipyard
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.BuoyancyHandlerAttachment
import org.valkyrienskies.valkyrienair.config.ValkyrienAirConfig
import org.valkyrienskies.valkyrienair.mixinducks.compat.vs2.ValkyrienAirBuoyancyAttachmentDuck
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap

object ShipWaterPocketManager {
    private val log = LogManager.getLogger("ValkyrienAir ShipWaterPockets")

    private const val FLOOD_UPDATE_INTERVAL_TICKS = 1L
    private const val MAX_SIM_VOLUME = 2_000_000
    private const val POCKET_BOUNDS_PADDING = 1
    private const val AIR_PRESSURE_Y_EPS = 1e-7
    // Flooding speed: this is an abstract "water plane rise" rate. Bigger/more holes increase the rise rate.
    private const val FLOOD_RISE_PER_TICK_BASE = 0.01
    private const val FLOOD_RISE_PER_TICK_PER_HOLE_FACE = 0.02
    private const val FLOOD_RISE_MAX_PER_TICK = 0.35
    // How "tight" a submerged wall opening must look (out of the 4 lateral directions around the opening axis)
    // to be treated as a hull wall-hole that ignores air pressure. Keep this permissive enough to catch openings
    // in complex geometry (corners/edges), but restrictive enough to avoid classifying generic exterior water contact.
    private const val WALL_HOLE_MIN_LATERAL_SOLIDS = 2

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
        var buoyancy: BuoyancyMetrics = BuoyancyMetrics(),
        var floodPlaneByComponent: Int2DoubleOpenHashMap = Int2DoubleOpenHashMap(),
        var geometryRevision: Long = 0,
        var dirty: Boolean = true,
        var lastFloodUpdateTick: Long = Long.MIN_VALUE,
        var lastWaterReachableUpdateTick: Long = Long.MIN_VALUE,
    )

    private data class BuoyancyMetrics(
        var submergedAirVolume: Double = 0.0,
        var submergedAirSumX: Double = 0.0,
        var submergedAirSumY: Double = 0.0,
        var submergedAirSumZ: Double = 0.0,
    ) {
        fun reset() {
            submergedAirVolume = 0.0
            submergedAirSumX = 0.0
            submergedAirSumY = 0.0
            submergedAirSumZ = 0.0
        }
    }

    private val serverStates: ConcurrentHashMap<DimensionId, ConcurrentHashMap<Long, ShipPocketState>> =
        ConcurrentHashMap()
    private val clientStates: ConcurrentHashMap<DimensionId, ConcurrentHashMap<Long, ShipPocketState>> =
        ConcurrentHashMap()

    private val tmpQueryAabb: ThreadLocal<AABBd> = ThreadLocal.withInitial { AABBd() }
    private val tmpWorldPos: ThreadLocal<Vector3d> = ThreadLocal.withInitial { Vector3d() }
    private val tmpShipPos: ThreadLocal<Vector3d> = ThreadLocal.withInitial { Vector3d() }
    private val tmpShipBlockPos: ThreadLocal<BlockPos.MutableBlockPos> =
        ThreadLocal.withInitial { BlockPos.MutableBlockPos() }
    private val tmpShipFlowDir: ThreadLocal<Vector3d> = ThreadLocal.withInitial { Vector3d() }

    private data class ShipFluidSampleCache(
        var lastLevel: Level? = null,
        var lastWorldPosLong: Long = Long.MIN_VALUE,
        var lastConfigEnabled: Boolean = false,
        var computed: Boolean = false,
        var flow: Vec3? = null,
        var height: Float? = null,
    )

    private val tmpShipFluidSampleCache: ThreadLocal<ShipFluidSampleCache> =
        ThreadLocal.withInitial { ShipFluidSampleCache() }

    private val tmpFloodQueue: ThreadLocal<IntArray> = ThreadLocal.withInitial { IntArray(0) }
    private val tmpFloodComponentVisited: ThreadLocal<BitSet> = ThreadLocal.withInitial { BitSet() }
    private val tmpPressureComponentVisited: ThreadLocal<BitSet> = ThreadLocal.withInitial { BitSet() }
    private val tmpPressureSubmerged: ThreadLocal<BitSet> = ThreadLocal.withInitial { BitSet() }
    private val tmpLeakedWaterToRemove: ThreadLocal<BitSet> = ThreadLocal.withInitial { BitSet() }
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

    /**
     * Returns true if a water block placement into [shipPos] (in shipyard coordinates) should be blocked because the
     * target cell corresponds to world water (ocean) outside the ship interior.
     *
     * This prevents shipyard water from "leaking" into the surrounding world water volume through holes, while still
     * allowing flooding inside interior pockets.
     */
    @JvmStatic
    fun shouldBlockShipyardWaterPlacement(
        level: Level,
        shipId: Long,
        shipPos: BlockPos,
        shipTransform: ShipTransform,
    ): Boolean {
        if (!ValkyrienAirConfig.enableShipWaterPockets) return false
        if (level.isClientSide) return false

        val state = serverStates[level.dimensionId]?.get(shipId) ?: return false

        val lx = shipPos.x - state.minX
        val ly = shipPos.y - state.minY
        val lz = shipPos.z - state.minZ
        val inBounds = lx in 0 until state.sizeX && ly in 0 until state.sizeY && lz in 0 until state.sizeZ
        if (inBounds) {
            val idx = indexOf(state, lx, ly, lz)
            if (state.interior.get(idx)) return false
        }

        // Outside the pocket sim bounds (or outside the interior mask), treat shipyard water as "leaking" only if this
        // ship-space cell is actually submerged in world water at its current world position.
        val shipPosTmp = tmpShipPos.get()
        val worldPosTmp = tmpWorldPos.get()
        val worldBlockPos = BlockPos.MutableBlockPos()
        return isShipCellSubmergedInWorldWater(level, shipTransform, shipPos, shipPosTmp, worldPosTmp, worldBlockPos)
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
            val shipTransform = getQueryTransform(ship)
            if (needsRecompute || now != state.lastWaterReachableUpdateTick) {
                state.waterReachable = computeWaterReachable(level, state, shipTransform)
                state.lastWaterReachableUpdateTick = now
                updateVsBuoyancyFromPockets(ship, state)
            }
            cleanupLeakedShipyardWater(level, state)
            if (needsRecompute || now - state.lastFloodUpdateTick >= FLOOD_UPDATE_INTERVAL_TICKS) {
                updateFlooding(level, state, shipTransform)
                state.lastFloodUpdateTick = now
            }
        }

        // Cleanup unloaded ships
        states.keys.removeIf { !loadedShipIds.contains(it) }
    }

    private fun cleanupLeakedShipyardWater(level: ServerLevel, state: ShipPocketState) {
        if (state.materializedWater.isEmpty) return
        if (state.waterReachable.isEmpty) return

        val toRemove = tmpLeakedWaterToRemove.get()
        toRemove.clear()
        toRemove.or(state.materializedWater)
        toRemove.andNot(state.interior)
        toRemove.and(state.waterReachable)

        if (toRemove.isEmpty) return
        applyBlockChanges(level, state, toRemove, toWater = false, pos = BlockPos.MutableBlockPos())
    }

    private fun updateVsBuoyancyFromPockets(ship: LoadedShip, state: ShipPocketState) {
        val serverShip = ship as? LoadedServerShip ?: return
        val buoyancyHandler = serverShip.getAttachment(BuoyancyHandlerAttachment::class.java) ?: return
        val buoyancyDuck = buoyancyHandler as? ValkyrienAirBuoyancyAttachmentDuck

        // The additional buoyant force from pockets is just the volume of *submerged interior air* that is currently
        // not flooded (i.e. displacing world water).
        val maxAbs = state.interior.cardinality().toDouble().coerceAtLeast(1.0)
        val displaced = state.buoyancy.submergedAirVolume.coerceIn(0.0, maxAbs)
        buoyancyDuck?.`valkyrienair$setDisplacedVolume`(displaced)

        val centerX: Double
        val centerY: Double
        val centerZ: Double
        if (displaced > 1.0e-6) {
            centerX = state.buoyancy.submergedAirSumX / displaced
            centerY = state.buoyancy.submergedAirSumY / displaced
            centerZ = state.buoyancy.submergedAirSumZ / displaced
        } else {
            centerX = 0.0
            centerY = 0.0
            centerZ = 0.0
        }
        buoyancyDuck?.`valkyrienair$setPocketCenter`(centerX, centerY, centerZ)
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
            val shipTransform = getQueryTransform(ship)
            if (now != state.lastWaterReachableUpdateTick) {
                state.waterReachable = computeWaterReachable(level, state, shipTransform)
                state.lastWaterReachableUpdateTick = now
            }

            spawnLeakParticlesClient(level, state, shipTransform)
        }

        states.keys.removeIf { !loadedShipIds.contains(it) }
    }

    private fun spawnLeakParticlesClient(level: Level, state: ShipPocketState, shipTransform: ShipTransform) {
        if (!level.isClientSide) return

        val interior = state.interior
        if (interior.isEmpty) return

        val targetWet = state.waterReachable
        if (targetWet.isEmpty) return

        val targetWetInterior = targetWet.clone() as BitSet
        targetWetInterior.and(interior)
        if (targetWetInterior.isEmpty) return

        val missing = targetWetInterior.clone() as BitSet
        missing.andNot(state.materializedWater)
        if (missing.isEmpty) return

        // Don't spawn particles if no nearby players can see them.
        val maxDistSq = 96.0 * 96.0
        val shipWorld = shipTransform.positionInWorld
        val anyViewerNearby = level.players().any {
            val dx = it.x - shipWorld.x()
            val dy = it.y - shipWorld.y()
            val dz = it.z - shipWorld.z()
            dx * dx + dy * dy + dz * dz <= maxDistSq
        }
        if (!anyViewerNearby) return

        val sizeX = state.sizeX
        val sizeY = state.sizeY
        val sizeZ = state.sizeZ
        val volume = sizeX * sizeY * sizeZ
        val strideY = sizeX
        val strideZ = sizeX * sizeY

        val open = state.open
        val rand = level.random

        val shipPosTmp = tmpShipPos.get()
        val worldPosTmp = tmpWorldPos.get()
        val velTmp = tmpShipFlowDir.get()
        val faceBuf = IntArray(6)

        // Find a few submerged hull openings that are actively flooding (target wet but not yet filled).
        //
        // We deliberately spawn particles from the actual opening block (an *interior* cell adjacent to submerged
        // exterior water), centered within that ship block, and point them into the air pocket in world-space so
        // this stays correct under arbitrary ship rotation.
        val maxHoles = 4
        val chosenHoleIdx = IntArray(maxHoles)
        val chosenInDirCode = IntArray(maxHoles)
        var chosenCount = 0

        fun alreadyChosen(holeIdx: Int): Boolean {
            for (i in 0 until chosenCount) if (chosenHoleIdx[i] == holeIdx) return true
            return false
        }

        fun chooseHole(holeIdx: Int, inDirCode: Int) {
            if (chosenCount >= maxHoles) return
            chosenHoleIdx[chosenCount++] = holeIdx
            chosenInDirCode[chosenCount - 1] = inDirCode
        }

        fun tryChooseHoleInterior(cellIdx: Int): Boolean {
            if (cellIdx < 0 || cellIdx >= volume) return false
            if (!interior.get(cellIdx)) return false
            if (!targetWetInterior.get(cellIdx)) return false
            if (alreadyChosen(cellIdx)) return false

            val lx = cellIdx % sizeX
            val t = cellIdx / sizeX
            val ly = t % sizeY
            val lz = t / sizeY

            // Collect exposed faces to submerged exterior water.
            var faceCount = 0

            fun tryFace(nIdx: Int, outDirCode: Int) {
                if (nIdx < 0 || nIdx >= volume) return
                if (interior.get(nIdx)) return
                if (!open.get(nIdx)) return
                if (!targetWet.get(nIdx)) return
                faceBuf[faceCount++] = outDirCode
            }

            if (lx > 0) tryFace(cellIdx - 1, 0) // -X
            if (lx + 1 < sizeX) tryFace(cellIdx + 1, 1) // +X
            if (ly > 0) tryFace(cellIdx - strideY, 2) // -Y
            if (ly + 1 < sizeY) tryFace(cellIdx + strideY, 3) // +Y
            if (lz > 0) tryFace(cellIdx - strideZ, 4) // -Z
            if (lz + 1 < sizeZ) tryFace(cellIdx + strideZ, 5) // +Z

            if (faceCount == 0) return false

            // Pick a stable exposed face and point particles inward (opposite direction).
            //
            // Prefer a face whose inward neighbor is currently missing (active flood front), otherwise just use the
            // first exposed face for stability.
            var outDirCode = faceBuf[0]
            if (faceCount > 1) {
                for (i in 0 until faceCount) {
                    val candidateOut = faceBuf[i]
                    val inDir = candidateOut xor 1
                    val nIdx = when (inDir) {
                        0 -> if (lx > 0) cellIdx - 1 else -1
                        1 -> if (lx + 1 < sizeX) cellIdx + 1 else -1
                        2 -> if (ly > 0) cellIdx - strideY else -1
                        3 -> if (ly + 1 < sizeY) cellIdx + strideY else -1
                        4 -> if (lz > 0) cellIdx - strideZ else -1
                        else -> if (lz + 1 < sizeZ) cellIdx + strideZ else -1
                    }
                    if (nIdx >= 0 && missing.get(nIdx)) {
                        outDirCode = candidateOut
                        break
                    }
                }
            }
            val inDirCode = outDirCode xor 1
            chooseHole(cellIdx, inDirCode)
            return true
        }

        // Prefer choosing holes near the current flood front (missing wet interior), but fall back to sampling any
        // reachable wet interior cell if we can't find holes adjacent to missing cells (e.g. later-stage flooding).
        val scanBudget = 512
        var scanned = 0
        var idx = missing.nextSetBit(rand.nextInt(volume))
        if (idx < 0) idx = missing.nextSetBit(0)
        while (idx >= 0 && idx < volume && chosenCount < maxHoles && scanned < scanBudget) {
            val lx = idx % sizeX
            val t = idx / sizeX
            val ly = t % sizeY
            val lz = t / sizeY

            // This missing cell could itself be the opening.
            tryChooseHoleInterior(idx)
            if (chosenCount >= maxHoles) break

            // Or the opening could be a nearby (already-filled) boundary cell.
            if (lx > 0) tryChooseHoleInterior(idx - 1)
            if (chosenCount >= maxHoles) break
            if (lx + 1 < sizeX) tryChooseHoleInterior(idx + 1)
            if (chosenCount >= maxHoles) break
            if (ly > 0) tryChooseHoleInterior(idx - strideY)
            if (chosenCount >= maxHoles) break
            if (ly + 1 < sizeY) tryChooseHoleInterior(idx + strideY)
            if (chosenCount >= maxHoles) break
            if (lz > 0) tryChooseHoleInterior(idx - strideZ)
            if (chosenCount >= maxHoles) break
            if (lz + 1 < sizeZ) tryChooseHoleInterior(idx + strideZ)

            scanned++
            idx = missing.nextSetBit(idx + 1)
        }

        if (chosenCount == 0) {
            repeat(64) {
                if (chosenCount >= maxHoles) return@repeat
                val start = rand.nextInt(volume)
                var candidate = targetWetInterior.nextSetBit(start)
                if (candidate < 0) candidate = targetWetInterior.nextSetBit(0)
                if (candidate >= 0) {
                    tryChooseHoleInterior(candidate)
                }
            }
        }

        if (chosenCount == 0) return

        // Emit directed splash particles into the pocket, covering the "hole square" within the hole block.
        for (iHole in 0 until chosenCount) {
            val holeIdx = chosenHoleIdx[iHole]
            val inDirCode = chosenInDirCode[iHole]
            val holeLX = holeIdx % sizeX
            val holeT = holeIdx / sizeX
            val holeLY = holeT % sizeY
            val holeLZ = holeT / sizeY

            // World position of the hole block center.
            val holeShipX = (state.minX + holeLX).toDouble() + 0.5
            val holeShipY = (state.minY + holeLY).toDouble() + 0.5
            val holeShipZ = (state.minZ + holeLZ).toDouble() + 0.5

            shipPosTmp.set(holeShipX, holeShipY, holeShipZ)
            shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)
            val holeWorldX = worldPosTmp.x
            val holeWorldY = worldPosTmp.y
            val holeWorldZ = worldPosTmp.z

            val dirX: Int
            val dirY: Int
            val dirZ: Int
            when (inDirCode) {
                0 -> { dirX = -1; dirY = 0; dirZ = 0 }
                1 -> { dirX = 1; dirY = 0; dirZ = 0 }
                2 -> { dirX = 0; dirY = -1; dirZ = 0 }
                3 -> { dirX = 0; dirY = 1; dirZ = 0 }
                4 -> { dirX = 0; dirY = 0; dirZ = -1 }
                else -> { dirX = 0; dirY = 0; dirZ = 1 }
            }

            // World direction into the pocket (compute using world-space positions for correctness under rotation).
            shipPosTmp.set(holeShipX + dirX.toDouble(), holeShipY + dirY.toDouble(), holeShipZ + dirZ.toDouble())
            shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)
            velTmp.set(worldPosTmp.x - holeWorldX, worldPosTmp.y - holeWorldY, worldPosTmp.z - holeWorldZ)
            if (velTmp.lengthSquared() > 1.0e-12) {
                velTmp.normalize()
                velTmp.mul(0.12)
            } else {
                velTmp.set(0.0, 0.0, 0.0)
            }

            // Tangent axes for sampling positions over the hole square (in ship-space, then transformed).
            val spread = 0.49
            val tangentAX: Double
            val tangentAY: Double
            val tangentAZ: Double
            val tangentBX: Double
            val tangentBY: Double
            val tangentBZ: Double

            when {
                dirX != 0 -> { // normal along X => tangents are Y and Z
                    tangentAX = 0.0; tangentAY = 1.0; tangentAZ = 0.0
                    tangentBX = 0.0; tangentBY = 0.0; tangentBZ = 1.0
                }
                dirY != 0 -> { // normal along Y => tangents are X and Z
                    tangentAX = 1.0; tangentAY = 0.0; tangentAZ = 0.0
                    tangentBX = 0.0; tangentBY = 0.0; tangentBZ = 1.0
                }
                else -> { // normal along Z => tangents are X and Y
                    tangentAX = 1.0; tangentAY = 0.0; tangentAZ = 0.0
                    tangentBX = 0.0; tangentBY = 1.0; tangentBZ = 0.0
                }
            }

            val particles = 8
            repeat(particles) {
                val u = (rand.nextDouble() - 0.5) * 2.0 * spread
                val v = (rand.nextDouble() - 0.5) * 2.0 * spread

                val pxShip = holeShipX + tangentAX * u + tangentBX * v
                val pyShip = holeShipY + tangentAY * u + tangentBY * v
                val pzShip = holeShipZ + tangentAZ * u + tangentBZ * v

                shipPosTmp.set(pxShip, pyShip, pzShip)
                shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)

                val vx = velTmp.x + (rand.nextDouble() - 0.5) * 0.03
                val vy = velTmp.y + (rand.nextDouble() - 0.5) * 0.03
                val vz = velTmp.z + (rand.nextDouble() - 0.5) * 0.03

                level.addParticle(ParticleTypes.SPLASH, worldPosTmp.x, worldPosTmp.y, worldPosTmp.z, vx, vy, vz)
            }
        }
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
     * If [worldBlockPos] intersects a ship-space fluid block (shipyard geometry), returns that fluid's flow vector
     * rotated into world space based on the ship's current transform. Returns null if no ship fluid applies.
     *
     * This is used by entity fluid pushing so that ship fluids push entities in the correct direction when ships are
     * rotated. World water is intentionally unaffected.
     */
    @JvmStatic
    fun computeRotatedShipFluidFlow(level: Level, worldBlockPos: BlockPos): Vec3? {
        return computeShipFluidSample(level, worldBlockPos).flow
    }

    private fun computeShipFluidSample(level: Level, worldBlockPos: BlockPos): ShipFluidSampleCache {
        val cache = tmpShipFluidSampleCache.get()
        val posLong = worldBlockPos.asLong()
        val enabled = ValkyrienAirConfig.enableShipWaterPockets
        if (cache.lastLevel === level && cache.lastWorldPosLong == posLong && cache.lastConfigEnabled == enabled && cache.computed) {
            return cache
        }

        cache.lastLevel = level
        cache.lastWorldPosLong = posLong
        cache.lastConfigEnabled = enabled
        cache.computed = true
        cache.flow = null
        cache.height = null

        if (!enabled) return cache
        if (level.isBlockInShipyard(worldBlockPos)) return cache

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
        val dir = tmpShipFlowDir.get()

        for (ship in level.shipObjectWorld.loadedShips.getIntersecting(queryAabb, level.dimensionId)) {
            val shipTransform = getQueryTransform(ship)

            shipTransform.worldToShip.transformPosition(worldPos, shipPosTmp)
            shipBlockPosTmp.set(Mth.floor(shipPosTmp.x), Mth.floor(shipPosTmp.y), Mth.floor(shipPosTmp.z))

            val state = getState(level, ship.id)
            if (state != null && !isOpen(state, shipBlockPosTmp)) {
                val openPos = findOpenShipBlockPosForPoint(level, state, shipPosTmp, shipBlockPosTmp) ?: continue
                shipBlockPosTmp.set(openPos)
            }

            val shipFluid = level.getBlockState(shipBlockPosTmp).fluidState
            if (shipFluid.isEmpty) continue

            cache.height = shipFluid.getHeight(level, shipBlockPosTmp)

            val shipFlow = shipFluid.getFlow(level, shipBlockPosTmp)
            if (shipFlow.lengthSqr() < 1.0e-12) {
                cache.flow = shipFlow
                return cache
            }

            dir.set(shipFlow.x, shipFlow.y, shipFlow.z)
            shipTransform.shipToWorldRotation.transform(dir)
            cache.flow = Vec3(dir.x, dir.y, dir.z)
            return cache
        }

        return cache
    }

    /**
     * If [worldBlockPos] intersects a ship-space fluid block (shipyard geometry), returns that fluid's height within
     * the shipyard cell. Returns null if no ship fluid applies.
     *
     * This is required because vanilla/Forge fluid logic computes heights using neighbor block queries, and when ship
     * fluids are queried from world space the neighbor lookups would otherwise sample world blocks (air) instead of the
     * shipyard, causing incorrect swimming/overlays (especially for flowing fluids).
     */
    @JvmStatic
    fun computeShipFluidHeight(level: Level, worldBlockPos: BlockPos): Float? {
        return computeShipFluidSample(level, worldBlockPos).height
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

            if (findNearbyAirPocket(state, shipPosTmp, shipBlockPosTmp, radius = 1) != null) {
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

            val airPos = findNearbyAirPocket(state, shipPosTmp, shipBlockPosTmp, radius = 1) ?: continue
            return BlockPos(airPos.x, airPos.y, airPos.z)
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
                // For full blocks, treat the collision shape as a solid hull voxel and pick the open neighbor cell
                // based on which face the point is closest to. This prevents ambiguous mapping when a world-space point
                // falls inside a solid ship block due to rotation/rounding (e.g. right on the hull).
                val fullBlock = bs.isCollisionShapeFullBlock(level, tmp)

                val fx = (x - baseX.toDouble()).coerceIn(0.0, 1.0)
                val fy = (y - baseY.toDouble()).coerceIn(0.0, 1.0)
                val fz = (z - baseZ.toDouble()).coerceIn(0.0, 1.0)

                val eps = 1e-4
                val boxes: List<AABB> = shape.toAabbs()

                fun contains(px: Double, py: Double, pz: Double): Boolean {
                    if (fullBlock) return false
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
        // "Air pocket" is only meaningful for watertight interior cells (not exterior air above the waterline).
        return state.interior.get(idx) && !state.materializedWater.get(idx)
    }

    private fun findNearbyAirPocket(
        state: ShipPocketState,
        shipPos: Vector3d,
        shipBlockPos: BlockPos.MutableBlockPos,
        radius: Int,
    ): BlockPos.MutableBlockPos? {
        val baseX = shipBlockPos.x
        val baseY = shipBlockPos.y
        val baseZ = shipBlockPos.z

        if (isAirPocket(state, shipBlockPos)) return shipBlockPos

        val e = POINT_QUERY_EPS
        for (dxi in -1..1) {
            val dx = dxi.toDouble() * e
            for (dyi in -1..1) {
                val dy = dyi.toDouble() * e
                for (dzi in -1..1) {
                    val dz = dzi.toDouble() * e
                    if (dx == 0.0 && dy == 0.0 && dz == 0.0) continue
                    shipBlockPos.set(
                        Mth.floor(shipPos.x + dx),
                        Mth.floor(shipPos.y + dy),
                        Mth.floor(shipPos.z + dz),
                    )
                    if (isAirPocket(state, shipBlockPos)) return shipBlockPos
                }
            }
        }

        if (radius <= 0) return null

        var bestDistSq = Double.POSITIVE_INFINITY
        var bestX = 0
        var bestY = 0
        var bestZ = 0

        val x = shipPos.x
        val y = shipPos.y
        val z = shipPos.z

        for (dx in -radius..radius) {
            val px = baseX + dx
            val cx = px + 0.5
            val ddx = x - cx
            for (dy in -radius..radius) {
                val py = baseY + dy
                val cy = py + 0.5
                val ddy = y - cy
                for (dz in -radius..radius) {
                    val pz = baseZ + dz
                    shipBlockPos.set(px, py, pz)
                    if (!isAirPocket(state, shipBlockPos)) continue

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
            shipBlockPos.set(bestX, bestY, bestZ)
            return shipBlockPos
        }

        return null
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
        val flooded = BitSet(volume)
        val materialized = BitSet(volume)

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

                    val fluidState = bs.fluidState
                    if (!fluidState.isEmpty && fluidState.`is`(Fluids.WATER)) {
                        flooded.set(idx)
                        if (bs.block == Blocks.WATER) {
                            materialized.set(idx)
                        }
                    }

                    idx++
                }
            }
        }

        // Classify "ship interior" open voxels using a cheap enclosure heuristic based on the ship's watertight blocks.
        //
        // We intentionally do *not* require the space to be topologically sealed from the sim bounds, because pressurized
        // pockets can exist even when there are underwater openings (e.g. a diving bell / bottom hole with trapped air).
        //
        // This mask is used for gameplay logic (treating world water as air, redirecting fire placement, etc.), and must
        // include rooms that are connected to the exterior through submerged holes so physics matches the rendering cull.
        val interior = computeInteriorMask(open, sizeX, sizeY, sizeZ)

        state.open = open
        state.interior = interior
        state.waterReachable = BitSet(volume)
        state.flooded = flooded
        state.materializedWater = materialized
        state.floodPlaneByComponent.clear()

        state.dirty = false
        if (boundsChanged || prevOpen != open) {
            state.geometryRevision++
        }
    }

    /**
     * Computes a conservative approximation of ship-interior air space.
     *
     * We treat an open voxel as "interior" if it is enclosed by watertight blocks on **both sides** of at least 2 of the
     * 3 ship axes. This keeps most real rooms interior even when they have openings (doors/holes), while rejecting the
     * large exterior volume around the ship.
     *
     * This heuristic intentionally errs on the side of classifying *less* space as interior to avoid culling/physics
     * issues in the ocean around the ship.
     */
    private fun computeInteriorMask(open: BitSet, sizeX: Int, sizeY: Int, sizeZ: Int): BitSet {
        val volumeLong = sizeX.toLong() * sizeY.toLong() * sizeZ.toLong()
        if (volumeLong <= 0L) return BitSet()
        val volume = volumeLong.toInt()

        if (open.isEmpty) return BitSet(volume)

        val strideY = sizeX
        val strideZ = sizeX * sizeY

        val negX = BitSet(volume)
        val posX = BitSet(volume)
        val negY = BitSet(volume)
        val posY = BitSet(volume)
        val negZ = BitSet(volume)
        val posZ = BitSet(volume)

        // X direction (for each Y/Z line).
        for (z in 0 until sizeZ) {
            for (y in 0 until sizeY) {
                val base = sizeX * (y + sizeY * z)
                var seenSolid = false
                for (x in 0 until sizeX) {
                    val idx = base + x
                    if (!open.get(idx)) {
                        seenSolid = true
                    } else if (seenSolid) {
                        negX.set(idx)
                    }
                }
                seenSolid = false
                for (x in sizeX - 1 downTo 0) {
                    val idx = base + x
                    if (!open.get(idx)) {
                        seenSolid = true
                    } else if (seenSolid) {
                        posX.set(idx)
                    }
                }
            }
        }

        // Y direction (for each X/Z line).
        for (z in 0 until sizeZ) {
            for (x in 0 until sizeX) {
                var seenSolid = false
                var idx = x + strideZ * z
                for (y in 0 until sizeY) {
                    if (!open.get(idx)) {
                        seenSolid = true
                    } else if (seenSolid) {
                        negY.set(idx)
                    }
                    idx += strideY
                }
                seenSolid = false
                idx = x + strideZ * z + strideY * (sizeY - 1)
                for (y in sizeY - 1 downTo 0) {
                    if (!open.get(idx)) {
                        seenSolid = true
                    } else if (seenSolid) {
                        posY.set(idx)
                    }
                    idx -= strideY
                }
            }
        }

        // Z direction (for each X/Y line).
        for (y in 0 until sizeY) {
            for (x in 0 until sizeX) {
                var seenSolid = false
                var idx = x + strideY * y
                for (z in 0 until sizeZ) {
                    if (!open.get(idx)) {
                        seenSolid = true
                    } else if (seenSolid) {
                        negZ.set(idx)
                    }
                    idx += strideZ
                }
                seenSolid = false
                idx = x + strideY * y + strideZ * (sizeZ - 1)
                for (z in sizeZ - 1 downTo 0) {
                    if (!open.get(idx)) {
                        seenSolid = true
                    } else if (seenSolid) {
                        posZ.set(idx)
                    }
                    idx -= strideZ
                }
            }
        }

        val interior = BitSet(volume)
        var idx = open.nextSetBit(0)
        while (idx >= 0 && idx < volume) {
            val lx = idx % sizeX
            val t = idx / sizeX
            val ly = t % sizeY
            val lz = t / sizeY

            // Never classify the padded boundary layer as interior.
            if (lx == 0 || lx + 1 == sizeX || ly == 0 || ly + 1 == sizeY || lz == 0 || lz + 1 == sizeZ) {
                idx = open.nextSetBit(idx + 1)
                continue
            }

            var axisPairs = 0
            if (negX.get(idx) && posX.get(idx)) axisPairs++
            if (negY.get(idx) && posY.get(idx)) axisPairs++
            if (negZ.get(idx) && posZ.get(idx)) axisPairs++

            if (axisPairs >= 2) {
                interior.set(idx)
            }

            idx = open.nextSetBit(idx + 1)
        }

        return interior
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
        interior: BitSet,
        shipTransform: ShipTransform,
        out: BitSet,
        buoyancyOut: BuoyancyMetrics? = null,
        materializedWater: BitSet? = null,
    ): BitSet {
        out.clear()

        val buoyancy = buoyancyOut
        buoyancy?.reset()

        val volumeLong = sizeX.toLong() * sizeY.toLong() * sizeZ.toLong()
        if (volumeLong <= 0 || volumeLong > MAX_SIM_VOLUME.toLong()) return out
        if (open.isEmpty) return out

        val volume = volumeLong.toInt()

        var componentQueue = tmpFloodQueue.get()
        if (componentQueue.size < volume) {
            componentQueue = IntArray(volume)
            tmpFloodQueue.set(componentQueue)
        }

        var waterQueue = tmpPressureHeapIdx.get()
        if (waterQueue.size < volume) {
            waterQueue = IntArray(volume)
            tmpPressureHeapIdx.set(waterQueue)
        }

        val interiorVisited = tmpPressureComponentVisited.get()
        interiorVisited.clear()

        val submerged = tmpPressureSubmerged.get()
        submerged.clear()

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

        // Cache which open cells are submerged in world water.
        var idx = open.nextSetBit(0)
        while (idx >= 0 && idx < volume) {
            if (shipCellSubmerged(idx)) submerged.set(idx)
            idx = open.nextSetBit(idx + 1)
        }

        val strideY = sizeX
        val strideZ = sizeX * sizeY

        // Compute an affine map from local ship voxel coords -> world Y. This is much faster than per-point transforms.
        val baseShipX = minX.toDouble()
        val baseShipY = minY.toDouble()
        val baseShipZ = minZ.toDouble()

        shipPosTmp.set(baseShipX, baseShipY, baseShipZ)
        shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)
        val baseWorldY = worldPosTmp.y

        shipPosTmp.set(baseShipX + 1.0, baseShipY, baseShipZ)
        shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)
        val incX = worldPosTmp.y - baseWorldY

        shipPosTmp.set(baseShipX, baseShipY + 1.0, baseShipZ)
        shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)
        val incY = worldPosTmp.y - baseWorldY

        shipPosTmp.set(baseShipX, baseShipY, baseShipZ + 1.0)
        shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)
        val incZ = worldPosTmp.y - baseWorldY

        fun worldYAtLocal(x: Double, y: Double, z: Double): Double {
            return baseWorldY + incX * x + incY * y + incZ * z
        }

        fun cellCenterWorldY(lx: Int, ly: Int, lz: Int): Double {
            return baseWorldY + incX * (lx + 0.5) + incY * (ly + 0.5) + incZ * (lz + 0.5)
        }

        fun cellMaxWorldY(lx: Int, ly: Int, lz: Int): Double {
            val x0 = lx.toDouble()
            val y0 = ly.toDouble()
            val z0 = lz.toDouble()
            val x1 = x0 + 1.0
            val y1 = y0 + 1.0
            val z1 = z0 + 1.0

            // Highest world-space point of this voxel cell (max of its 8 corners).
            return maxOf(
                worldYAtLocal(x0, y0, z0),
                worldYAtLocal(x1, y0, z0),
                worldYAtLocal(x0, y1, z0),
                worldYAtLocal(x1, y1, z0),
                worldYAtLocal(x0, y0, z1),
                worldYAtLocal(x1, y0, z1),
                worldYAtLocal(x0, y1, z1),
                worldYAtLocal(x1, y1, z1),
            )
        }

        // 1) Flood-fill exterior world water. This ensures we never cull ocean water around the ship.
        run {
            var head = 0
            var tail = 0

            fun tryEnqueueExterior(i: Int) {
                if (!open.get(i) || interior.get(i)) return
                if (!submerged.get(i) || out.get(i)) return
                out.set(i)
                componentQueue[tail++] = i
            }

            forEachBoundaryIndex(sizeX, sizeY, sizeZ) { boundaryIdx ->
                tryEnqueueExterior(boundaryIdx)
            }

            while (head < tail) {
                val cur = componentQueue[head++]

                val lx = cur % sizeX
                val t = cur / sizeX
                val ly = t % sizeY
                val lz = t / sizeY

                if (lx > 0) tryEnqueueExterior(cur - 1)
                if (lx + 1 < sizeX) tryEnqueueExterior(cur + 1)
                if (ly > 0) tryEnqueueExterior(cur - strideY)
                if (ly + 1 < sizeY) tryEnqueueExterior(cur + strideY)
                if (lz > 0) tryEnqueueExterior(cur - strideZ)
                if (lz + 1 < sizeZ) tryEnqueueExterior(cur + strideZ)
            }
        }

        // 2) For each interior air-space component, compute holes to outside and fill water to the highest submerged hole.
        val planeEps = 1e-6

        var start = interior.nextSetBit(0)
        while (start >= 0 && start < volume) {
            if (interiorVisited.get(start) || !open.get(start)) {
                start = interior.nextSetBit(start + 1)
                continue
            }

            var head = 0
            var tail = 0
            interiorVisited.set(start)
            componentQueue[tail++] = start

            var hasAirVent = false
            var waterLevel = Double.NEGATIVE_INFINITY
            var seedCount = 0

            fun processHole(curIdx: Int, lx: Int, ly: Int, lz: Int, nIdx: Int) {
                if (!open.get(nIdx) || interior.get(nIdx)) return

                if (submerged.get(nIdx)) {
                    // Submerged hull opening: water can enter. Track the highest submerged opening as the fill level.
                    waterLevel = maxOf(waterLevel, cellMaxWorldY(lx, ly, lz))
                    waterQueue[seedCount++] = curIdx
                } else {
                    // Non-submerged opening to the exterior air: air can escape, so the pocket is unpressurized.
                    hasAirVent = true
                }
            }

            fun enqueueInterior(i: Int) {
                if (!open.get(i) || !interior.get(i) || interiorVisited.get(i)) return
                interiorVisited.set(i)
                componentQueue[tail++] = i
            }

            while (head < tail) {
                val cur = componentQueue[head++]

                val lx = cur % sizeX
                val t = cur / sizeX
                val ly = t % sizeY
                val lz = t / sizeY

                if (lx > 0) {
                    val n = cur - 1
                    if (interior.get(n)) enqueueInterior(n) else processHole(cur, lx, ly, lz, n)
                }
                if (lx + 1 < sizeX) {
                    val n = cur + 1
                    if (interior.get(n)) enqueueInterior(n) else processHole(cur, lx, ly, lz, n)
                }
                if (ly > 0) {
                    val n = cur - strideY
                    if (interior.get(n)) enqueueInterior(n) else processHole(cur, lx, ly, lz, n)
                }
                if (ly + 1 < sizeY) {
                    val n = cur + strideY
                    if (interior.get(n)) enqueueInterior(n) else processHole(cur, lx, ly, lz, n)
                }
                if (lz > 0) {
                    val n = cur - strideZ
                    if (interior.get(n)) enqueueInterior(n) else processHole(cur, lx, ly, lz, n)
                }
                if (lz + 1 < sizeZ) {
                    val n = cur + strideZ
                    if (interior.get(n)) enqueueInterior(n) else processHole(cur, lx, ly, lz, n)
                }
            }

            if (seedCount > 0) {
                var waterHead = 0
                var waterTail = 0

                fun canFillPressurized(i: Int): Boolean {
                    if (!open.get(i) || !interior.get(i) || out.get(i)) return false
                    if (!submerged.get(i)) return false
                    if (hasAirVent) return true

                    val lx = i % sizeX
                    val t = i / sizeX
                    val ly = t % sizeY
                    val lz = t / sizeY
                    val wy = cellCenterWorldY(lx, ly, lz)
                    return wy <= waterLevel + planeEps
                }

                fun tryEnqueueWater(i: Int) {
                    if (!canFillPressurized(i)) return
                    out.set(i)
                    waterQueue[waterTail++] = i
                }

                // Seed from interior cells adjacent to submerged exterior openings.
                for (i in 0 until seedCount) {
                    tryEnqueueWater(waterQueue[i])
                }

                while (waterHead < waterTail) {
                    val cur = waterQueue[waterHead++]

                    val lx = cur % sizeX
                    val t = cur / sizeX
                    val ly = t % sizeY
                    val lz = t / sizeY

                    if (lx > 0) tryEnqueueWater(cur - 1)
                    if (lx + 1 < sizeX) tryEnqueueWater(cur + 1)
                    if (ly > 0) tryEnqueueWater(cur - strideY)
                    if (ly + 1 < sizeY) tryEnqueueWater(cur + strideY)
                    if (lz > 0) tryEnqueueWater(cur - strideZ)
                    if (lz + 1 < sizeZ) tryEnqueueWater(cur + strideZ)
                }
            }

            // Buoyancy accounting:
            // - Only *submerged* interior air displaces world water and contributes buoyancy.
            if (buoyancy != null) {
                for (i in 0 until tail) {
                    val cellIdx = componentQueue[i]
                    if (!submerged.get(cellIdx)) continue

                    val lx = cellIdx % sizeX
                    val t = cellIdx / sizeX
                    val ly = t % sizeY
                    val lz = t / sizeY

                    val sx = minX + lx + 0.5
                    val sy = minY + ly + 0.5
                    val sz = minZ + lz + 0.5

                    if (materializedWater != null && materializedWater.get(cellIdx)) continue

                    buoyancy.submergedAirVolume += 1.0
                    buoyancy.submergedAirSumX += sx
                    buoyancy.submergedAirSumY += sy
                    buoyancy.submergedAirSumZ += sz
                }
            }

            start = interior.nextSetBit(start + 1)
        }

        return out
    }

    private fun computeWaterReachable(
        level: Level,
        state: ShipPocketState,
        shipTransform: ShipTransform,
    ): BitSet {
        val buoyancyOut = if (level.isClientSide) null else state.buoyancy
        buoyancyOut?.reset()
        return computeWaterReachableWithPressure(
            level,
            state.minX,
            state.minY,
            state.minZ,
            state.sizeX,
            state.sizeY,
            state.sizeZ,
            state.open,
            state.interior,
            shipTransform,
            state.waterReachable,
            buoyancyOut = buoyancyOut,
            materializedWater = if (level.isClientSide) null else state.materializedWater,
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
        interior: BitSet,
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
            interior,
            shipTransform,
            out,
        )
    }

    private fun updateFlooding(level: ServerLevel, state: ShipPocketState, shipTransform: ShipTransform) {
        val open = state.open
        val interior = state.interior
        val materialized = state.materializedWater
        if (open.isEmpty || interior.isEmpty) {
            state.floodPlaneByComponent.clear()
            return
        }

        // Target flooded interior (equilibrium) from outside water contact / pressure simulation.
        val targetWetInterior = state.waterReachable.clone() as BitSet
        targetWetInterior.and(interior)
        if (targetWetInterior.isEmpty) {
            state.floodPlaneByComponent.clear()
            return
        }

        // If everything that *should* be wet is already wet, stop the slow-fill simulation.
        val missing = targetWetInterior.clone() as BitSet
        missing.andNot(materialized)
        if (missing.isEmpty) {
            // Still water stabilisation: when a pocket reaches its equilibrium fill level and remains connected to
            // outside water, force any remaining flowing water blocks inside the flooded region to become sources.
            //
            // This avoids "stuck" flowing levels that can happen because we materialize water gradually and vanilla
            // fluid updates may leave behind non-source states.
            stabilizeFloodedWater(level, state, targetWetInterior)
            state.floodPlaneByComponent.clear()
            return
        }

        // Compute a fast affine map from local voxel coords -> world Y for this ship transform.
        val baseShipX = state.minX.toDouble()
        val baseShipY = state.minY.toDouble()
        val baseShipZ = state.minZ.toDouble()

        val shipPosTmp = Vector3d()
        val worldPosTmp = Vector3d()

        shipPosTmp.set(baseShipX, baseShipY, baseShipZ)
        shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)
        val baseWorldY = worldPosTmp.y

        shipPosTmp.set(baseShipX + 1.0, baseShipY, baseShipZ)
        shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)
        val incX = worldPosTmp.y - baseWorldY

        shipPosTmp.set(baseShipX, baseShipY + 1.0, baseShipZ)
        shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)
        val incY = worldPosTmp.y - baseWorldY

        shipPosTmp.set(baseShipX, baseShipY, baseShipZ + 1.0)
        shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)
        val incZ = worldPosTmp.y - baseWorldY

        fun cellCenterWorldY(lx: Int, ly: Int, lz: Int): Double {
            return baseWorldY + incX * (lx + 0.5) + incY * (ly + 0.5) + incZ * (lz + 0.5)
        }

        val sizeX = state.sizeX
        val sizeY = state.sizeY
        val sizeZ = state.sizeZ
        val volume = sizeX * sizeY * sizeZ
        val strideY = sizeX
        val strideZ = sizeX * sizeY

        val visited = tmpFloodComponentVisited.get()
        visited.clear()

        var queue = tmpFloodQueue.get()
        if (queue.size < volume) {
            queue = IntArray(volume)
            tmpFloodQueue.set(queue)
        }

        val newPlanes = Int2DoubleOpenHashMap()
        val toAddAll = BitSet(volume)

        fun scanComponent(start: Int) {
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited.set(start)

            var rep = start
            var minY = Double.POSITIVE_INFINITY
            var targetPlane = Double.NEGATIVE_INFINITY
            var submergedHoleFaces = 0

            while (head < tail) {
                val idx = queue[head++]
                if (idx < rep) rep = idx

                val lx = idx % sizeX
                val t = idx / sizeX
                val ly = t % sizeY
                val lz = t / sizeY

                val wy = cellCenterWorldY(lx, ly, lz)
                if (wy < minY) minY = wy
                if (targetWetInterior.get(idx) && wy > targetPlane) targetPlane = wy

                fun tryNeighbor(n: Int, dirCode: Int) {
                    if (n < 0 || n >= volume) return
                    if (interior.get(n)) {
                        if (!visited.get(n)) {
                            visited.set(n)
                            queue[tail++] = n
                        }
                    } else {
                        // Count submerged hull openings into world water as "holes" controlling fill rate.
                        if (!open.get(n)) return
                        if (!state.waterReachable.get(n)) return
                        submergedHoleFaces++
                    }
                }

                if (lx > 0) tryNeighbor(idx - 1, 0) // -X
                if (lx + 1 < sizeX) tryNeighbor(idx + 1, 1) // +X
                if (ly > 0) tryNeighbor(idx - strideY, 2) // -Y
                if (ly + 1 < sizeY) tryNeighbor(idx + strideY, 3) // +Y
                if (lz > 0) tryNeighbor(idx - strideZ, 4) // -Z
                if (lz + 1 < sizeZ) tryNeighbor(idx + strideZ, 5) // +Z
            }

            if (!targetPlane.isFinite()) return

            val oldPlane = if (state.floodPlaneByComponent.containsKey(rep)) state.floodPlaneByComponent.get(rep) else minY
            val rise = (FLOOD_RISE_PER_TICK_BASE +
                submergedHoleFaces.coerceAtLeast(1).toDouble() * FLOOD_RISE_PER_TICK_PER_HOLE_FACE)
                .coerceAtMost(FLOOD_RISE_MAX_PER_TICK)
            val newPlane = minOf(targetPlane, oldPlane + rise)
            newPlanes.put(rep, newPlane)

            // Materialize new water blocks up to the current plane height, rising from the pocket's lowest point.
            for (i in 0 until tail) {
                val idx = queue[i]
                if (!targetWetInterior.get(idx)) continue
                if (materialized.get(idx)) continue

                val lx = idx % sizeX
                val t = idx / sizeX
                val ly = t % sizeY
                val lz = t / sizeY
                val wy = cellCenterWorldY(lx, ly, lz)
                if (wy <= newPlane + 1e-6) {
                    toAddAll.set(idx)
                }
            }
        }

        var start = missing.nextSetBit(0)
        while (start >= 0 && start < volume) {
            if (!interior.get(start) || visited.get(start)) {
                start = missing.nextSetBit(start + 1)
                continue
            }
            scanComponent(start)
            start = missing.nextSetBit(start + 1)
        }

        state.floodPlaneByComponent = newPlanes

        if (!toAddAll.isEmpty) {
            applyBlockChanges(level, state, toAddAll, toWater = true, pos = BlockPos.MutableBlockPos(), shipTransform = shipTransform)
        }
    }

    private fun stabilizeFloodedWater(level: ServerLevel, state: ShipPocketState, targetWetInterior: BitSet) {
        if (targetWetInterior.isEmpty) return

        val flags = 11 // 1 (block update) + 2 (send to clients) + 8 (force rerender)
        val pos = BlockPos.MutableBlockPos()

        applyingInternalUpdates = true
        try {
            var idx = targetWetInterior.nextSetBit(0)
            while (idx >= 0) {
                posFromIndex(state, idx, pos)
                val bs = level.getBlockState(pos)
                if (bs.block == Blocks.WATER && bs.hasProperty(BlockStateProperties.LEVEL)) {
                    val levelProp = bs.getValue(BlockStateProperties.LEVEL)
                    if (levelProp != 0) {
                        level.setBlock(pos, Blocks.WATER.defaultBlockState(), flags)
                    }
                }
                idx = targetWetInterior.nextSetBit(idx + 1)
            }
        } finally {
            applyingInternalUpdates = false
        }
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
