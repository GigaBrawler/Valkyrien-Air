package org.valkyrienskies.valkyrienair.feature.ship_water_pockets

import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.Direction
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.level.material.FlowingFluid
import net.minecraft.world.phys.Vec3
import org.apache.logging.log4j.LogManager
import org.joml.Vector3d
import org.joml.primitives.AABBd
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
import java.util.HashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

object ShipWaterPocketManager {
    private val log = LogManager.getLogger("ValkyrienAir ShipWaterPockets")

    private const val FLOOD_UPDATE_INTERVAL_TICKS = 1L
    private const val MAX_SIM_VOLUME = 2_000_000
    private const val POCKET_BOUNDS_PADDING = 1
    private const val AIR_PRESSURE_Y_EPS = 1e-7
    private const val AIR_PRESSURE_ATM = 1.0
    // Minecraft-ish hydrostatic pressure: ~1 atm per 10 blocks of water depth for "water-density" fluids.
    // Pressure increase per block is `density * AIR_PRESSURE_PER_BLOCK_PER_DENSITY`.
    private const val AIR_PRESSURE_PER_BLOCK_PER_DENSITY = 1e-4
    private const val AIR_PRESSURE_SOLVER_ITERS = 4
    private const val AIR_PRESSURE_MIN_EFFECTIVE_AIR_VOLUME = 0.25
    private const val AIR_PRESSURE_SURFACE_SCAN_MAX_STEPS = 256
    private const val GRAVITY_RESETTLE_MAX_SCHEDULED_TICKS_PER_SHIP_PER_TICK = 4096
    // Flooding speed: this is an abstract "water plane rise" rate. Bigger/more holes increase the rise rate.
    private const val FLOOD_RISE_PER_TICK_BASE = 0.01
    private const val FLOOD_RISE_PER_TICK_PER_HOLE_FACE = 0.00125
    private const val FLOOD_RISE_MAX_PER_TICK = 0.35
    private const val FLOOD_ENTER_PLANE_EPS = 1e-4
    private const val FLOOD_EXIT_PLANE_EPS = 3e-4
    private const val SUBMERGED_INGRESS_MIN_COVERAGE = 0.34
    private const val GEOMETRY_ASYNC_SUBMISSIONS_PER_LEVEL_PER_TICK = 2
    private const val WATER_SOLVER_ASYNC_SUBMISSIONS_PER_LEVEL_PER_TICK = 2
    private const val SERVER_SYNC_WATER_SOLVE_FALLBACKS_PER_TICK = 1
    private const val MATERIALIZED_RESYNC_INTERVAL_TICKS = 2L
    @Volatile
    private var applyingInternalUpdates: Boolean = false

    private val bypassFluidOverridesDepth: ThreadLocal<IntArray> = ThreadLocal.withInitial { intArrayOf(0) }

    private val serverStates: ConcurrentHashMap<DimensionId, ConcurrentHashMap<Long, ShipPocketState>> =
        ConcurrentHashMap()
    private val clientStates: ConcurrentHashMap<DimensionId, ConcurrentHashMap<Long, ShipPocketState>> =
        ConcurrentHashMap()

    private val tmpQueryAabb: ThreadLocal<AABBd> = ThreadLocal.withInitial { AABBd() }
    private val tmpWorldPos: ThreadLocal<Vector3d> = ThreadLocal.withInitial { Vector3d() }
    private val tmpShipPos: ThreadLocal<Vector3d> = ThreadLocal.withInitial { Vector3d() }
    private val tmpWorldPos2: ThreadLocal<Vector3d> = ThreadLocal.withInitial { Vector3d() }
    private val tmpShipPos2: ThreadLocal<Vector3d> = ThreadLocal.withInitial { Vector3d() }
    private val tmpWorldPos3: ThreadLocal<Vector3d> = ThreadLocal.withInitial { Vector3d() }
    private val tmpShipPos3: ThreadLocal<Vector3d> = ThreadLocal.withInitial { Vector3d() }
    private val tmpShipBlockPos: ThreadLocal<BlockPos.MutableBlockPos> =
        ThreadLocal.withInitial { BlockPos.MutableBlockPos() }
    private val tmpShipFlowDir: ThreadLocal<Vector3d> = ThreadLocal.withInitial { Vector3d() }
    private val tmpShipGravityVec: ThreadLocal<Vector3d> = ThreadLocal.withInitial { Vector3d() }

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

    private data class IntersectingShipsCache(
        var lastLevel: Level? = null,
        var lastTick: Long = Long.MIN_VALUE,
        var lastWorldPosLong: Long = Long.MIN_VALUE,
        var ships: List<Ship> = emptyList(),
    )

    private val tmpIntersectingShipsCache: ThreadLocal<IntersectingShipsCache> =
        ThreadLocal.withInitial { IntersectingShipsCache() }

    private val tmpFloodQueue: ThreadLocal<IntArray> = ThreadLocal.withInitial { IntArray(0) }
    private val tmpFloodComponentVisited: ThreadLocal<BitSet> = ThreadLocal.withInitial { BitSet() }
    private val tmpPressureComponentVisited: ThreadLocal<BitSet> = ThreadLocal.withInitial { BitSet() }
    private val tmpPressureSubmerged: ThreadLocal<BitSet> = ThreadLocal.withInitial { BitSet() }
    private val tmpLeakedWaterToRemove: ThreadLocal<BitSet> = ThreadLocal.withInitial { BitSet() }
    private val tmpPressureEscapeHeight: ThreadLocal<DoubleArray> = ThreadLocal.withInitial { DoubleArray(0) }
    private val tmpPressureHeapIdx: ThreadLocal<IntArray> = ThreadLocal.withInitial { IntArray(0) }
    private val tmpPressureHeapPos: ThreadLocal<IntArray> = ThreadLocal.withInitial { IntArray(0) }
    private val tmpSubmergedCoverage: ThreadLocal<DoubleArray> = ThreadLocal.withInitial { DoubleArray(0) }
    private val tmpPrecomputedSurfaceY: ThreadLocal<DoubleArray> = ThreadLocal.withInitial { DoubleArray(0) }
    private val coverageFallbackDiagCount = AtomicLong(0)
    private val geometryJobsSubmitted = AtomicLong(0)
    private val geometryJobsCompleted = AtomicLong(0)
    private val geometryJobsDiscarded = AtomicLong(0)
    private val geometryJobsFailed = AtomicLong(0)
    private val geometryComputeNanosTotal = AtomicLong(0)
    private val waterSolveJobsSubmitted = AtomicLong(0)
    private val waterSolveJobsCompleted = AtomicLong(0)
    private val waterSolveJobsDiscarded = AtomicLong(0)
    private val waterSolveJobsFailed = AtomicLong(0)
    private val waterSolveComputeNanosTotal = AtomicLong(0)
    private val asyncQueueFullSkips = AtomicLong(0)
    private val waterSolveSyncFallbacks = AtomicLong(0)
    private val worldSuppressionHits = AtomicLong(0)
    private val floodQueueBacklogHighWater = AtomicLong(0)

    private data class BuoyancyFluidProps(
        val density: Double,
        val viscosity: Double,
    )

    private data class FluidCoverageSample(
        val canonicalFluid: Fluid?,
        val coverageRatio: Double,
        val centerSubmerged: Boolean,
    ) {
        fun isIngressQualified(): Boolean {
            val fluid = canonicalFluid ?: return false
            return centerSubmerged || coverageRatio >= SUBMERGED_INGRESS_MIN_COVERAGE
        }

        fun isSubmergedAny(): Boolean {
            return canonicalFluid != null && coverageRatio > 0.0
        }
    }

    private data class WorldYAffine(
        val baseWorldY: Double,
        val incX: Double,
        val incY: Double,
        val incZ: Double,
    )

    private val buoyancyFluidPropsCache: ConcurrentHashMap<Fluid, BuoyancyFluidProps> = ConcurrentHashMap()

    private fun getBuoyancyFluidProps(fluid: Fluid): BuoyancyFluidProps {
        return buoyancyFluidPropsCache.computeIfAbsent(fluid) { f ->
            var density = if (f == Fluids.LAVA) 3000.0 else 1000.0
            var viscosity = if (f == Fluids.LAVA) 6000.0 else 1000.0

            // Forge: prefer FluidType density/viscosity when available (supports modded liquids).
            try {
                val getFluidType = f.javaClass.getMethod("getFluidType")
                val fluidType = getFluidType.invoke(f) ?: return@computeIfAbsent BuoyancyFluidProps(density, viscosity)

                val getDensity = fluidType.javaClass.getMethod("getDensity")
                val getViscosity = fluidType.javaClass.getMethod("getViscosity")

                val d = (getDensity.invoke(fluidType) as? Int)?.toDouble()
                val v = (getViscosity.invoke(fluidType) as? Int)?.toDouble()
                if (d != null && d.isFinite() && d > 0.0) density = d
                if (v != null && v.isFinite() && v > 0.0) viscosity = v
            } catch (_: Throwable) {
                // Non-Forge environment or fluid type missing; keep vanilla-ish defaults.
            }

            density = density.coerceIn(100.0, 20_000.0)
            viscosity = viscosity.coerceIn(100.0, 200_000.0)
            BuoyancyFluidProps(density, viscosity)
        }
    }

    @JvmStatic
    fun isApplyingInternalUpdates(): Boolean = applyingInternalUpdates

    @JvmStatic
    fun isBypassingFluidOverrides(): Boolean = bypassFluidOverridesDepth.get()[0] > 0

    private inline fun <T> withBypassedFluidOverrides(block: () -> T): T {
        val depth = bypassFluidOverridesDepth.get()
        depth[0]++
        try {
            return block()
        } finally {
            depth[0]--
        }
    }

    @JvmStatic
    fun markShipDirty(level: Level, shipId: Long) {
        if (!ValkyrienAirConfig.enableShipWaterPockets) return
        val map = (if (level.isClientSide) clientStates else serverStates)
            .computeIfAbsent(level.dimensionId) { ConcurrentHashMap() }
        val state = map.computeIfAbsent(shipId) { ShipPocketState() }
        state.dirty = true
        state.geometryInvalidationStamp++
    }

    /**
     * Returns true if a fluid block placement into [shipPos] (in shipyard coordinates) should be blocked because the
     * target cell is outside the simulated ship interior.
     *
     * This prevents shipyard fluids from "leaking" into the exterior shipyard volume (and therefore rendering in
     * places where the real world should be visible), while still allowing fluids to exist inside interior pockets.
     */
    @JvmStatic
    fun shouldBlockShipyardWaterPlacement(
        level: Level,
        shipId: Long,
        shipPos: BlockPos,
    ): Boolean {
        if (!ValkyrienAirConfig.enableShipWaterPockets) return false
        if (level.isClientSide) return false

        val state = serverStates[level.dimensionId]?.get(shipId) ?: return false
        if (state.sizeX <= 0 || state.sizeY <= 0 || state.sizeZ <= 0) return false
        if (state.open.isEmpty) return false
        if (state.dirty) return false

        val lx = shipPos.x - state.minX
        val ly = shipPos.y - state.minY
        val lz = shipPos.z - state.minZ
        val inBounds = lx in 0 until state.sizeX && ly in 0 until state.sizeY && lz in 0 until state.sizeZ
        if (inBounds) {
            val idx = indexOf(state, lx, ly, lz)
            if (!state.open.get(idx)) return true

            val pointClass = classifyShipPoint(
                state = state,
                x = shipPos.x + 0.5,
                y = shipPos.y + 0.5,
                z = shipPos.z + 0.5,
            )

            if (pointClass.kind == PointVoidClass.SOLID || pointClass.kind == PointVoidClass.OUT_OF_BOUNDS) {
                return true
            }
            if (pointClass.kind == PointVoidClass.INTERIOR_VOID) {
                return false
            }

            // Exterior-open cells on the simulation boundary shell are outside-facing and should not accept shipyard
            // fluid placement; non-boundary exterior-connected cells are allowed and will be drained by path/height.
            val isBoundaryShell =
                lx == 0 || lx + 1 == state.sizeX ||
                    ly == 0 || ly + 1 == state.sizeY ||
                    lz == 0 || lz + 1 == state.sizeZ
            return isBoundaryShell
        }

        // Outside the sim bounds, always block; it is never part of the ship interior pocket volume.
        return true
    }

    @JvmStatic
    fun onExternalShipFluidPlacement(
        level: Level,
        shipId: Long,
        shipPos: BlockPos,
        fluid: Fluid,
    ) {
        if (!ValkyrienAirConfig.enableShipWaterPockets) return
        if (level.isClientSide) return
        if (fluid == Fluids.EMPTY) return

        val state = serverStates[level.dimensionId]?.get(shipId) ?: return
        if (state.sizeX <= 0 || state.sizeY <= 0 || state.sizeZ <= 0) return

        val lx = shipPos.x - state.minX
        val ly = shipPos.y - state.minY
        val lz = shipPos.z - state.minZ
        if (lx !in 0 until state.sizeX || ly !in 0 until state.sizeY || lz !in 0 until state.sizeZ) return

        val idx = indexOf(state, lx, ly, lz)
        if (!state.open.get(idx)) return

        val canonical = canonicalFloodSource(fluid)
        if (canonical != state.floodFluid) {
            state.floodFluid = canonical
            state.dirty = true
        }

        state.flooded.set(idx)
        state.materializedWater.set(idx)
        state.queuedFloodAdds.clear(idx)
        state.queuedFloodRemoves.clear(idx)
    }

    private fun logThrottledDiag(counter: Long, message: String, vararg args: Any?) {
        if (counter <= 3L || counter % 512L == 0L) {
            log.debug(message, *args)
        }
    }

    private fun boundsMismatch(
        state: ShipPocketState,
        minX: Int,
        minY: Int,
        minZ: Int,
        sizeX: Int,
        sizeY: Int,
        sizeZ: Int,
    ): Boolean {
        return state.sizeX != sizeX ||
            state.sizeY != sizeY ||
            state.sizeZ != sizeZ ||
            state.minX != minX ||
            state.minY != minY ||
            state.minZ != minZ
    }

    private fun trySubmitGeometryJob(
        level: Level,
        state: ShipPocketState,
        minX: Int,
        minY: Int,
        minZ: Int,
        sizeX: Int,
        sizeY: Int,
        sizeZ: Int,
    ): Boolean {
        val pending = state.pendingGeometryFuture
        if (pending != null && !pending.isDone) {
            state.geometryJobInFlight = true
            return false
        }

        val generation = state.requestedGeometryGeneration + 1L
        state.requestedGeometryGeneration = generation
        val invalidationStamp = state.geometryInvalidationStamp

        val snapshot = try {
            captureGeometryAsyncSnapshot(
                level = level,
                generation = generation,
                invalidationStamp = invalidationStamp,
                minX = minX,
                minY = minY,
                minZ = minZ,
                sizeX = sizeX,
                sizeY = sizeY,
                sizeZ = sizeZ,
                floodFluid = state.floodFluid,
            )
        } catch (t: Throwable) {
            val count = geometryJobsFailed.incrementAndGet()
            logThrottledDiag(count, "Failed to capture ship pocket geometry snapshot", t)
            state.dirty = true
            return false
        }

        val submittedFuture = ShipPocketAsyncRuntime.trySubmit(
            subsystem = ShipPocketAsyncSubsystem.GEOMETRY,
            task = { computeGeometryAsync(snapshot) },
        )
        if (submittedFuture == null) {
            val count = asyncQueueFullSkips.incrementAndGet()
            logThrottledDiag(
                count,
                "Skipped geometry job submit: async queue full pending={} max={}",
                ShipPocketAsyncRuntime.pendingJobCount(),
                ShipPocketAsyncRuntime.maxPendingJobs(),
            )
            state.dirty = true
            return false
        }
        state.pendingGeometryFuture = submittedFuture
        state.geometryJobInFlight = true

        val count = geometryJobsSubmitted.incrementAndGet()
        logThrottledDiag(
            count,
            "Submitted ship pocket geometry job gen={} invalidation={} bounds=({}, {}, {} ; {}x{}x{})",
            generation,
            invalidationStamp,
            minX,
            minY,
            minZ,
            sizeX,
            sizeY,
            sizeZ,
        )
        return true
    }

    private fun applyGeometryResult(
        state: ShipPocketState,
        result: GeometryAsyncResult,
    ) {
        val boundsChanged = boundsMismatch(
            state = state,
            minX = result.minX,
            minY = result.minY,
            minZ = result.minZ,
            sizeX = result.sizeX,
            sizeY = result.sizeY,
            sizeZ = result.sizeZ,
        )

        val prevOpen = state.open
        val prevFaceCondXP = state.faceCondXP
        val prevFaceCondYP = state.faceCondYP
        val prevFaceCondZP = state.faceCondZP

        state.minX = result.minX
        state.minY = result.minY
        state.minZ = result.minZ
        state.sizeX = result.sizeX
        state.sizeY = result.sizeY
        state.sizeZ = result.sizeZ

        state.open = result.open
        state.exterior = result.exterior
        state.interior = result.interior
        state.flooded = result.flooded
        state.materializedWater = result.materializedWater
        state.faceCondXP = result.faceCondXP
        state.faceCondYP = result.faceCondYP
        state.faceCondZP = result.faceCondZP
        state.shapeTemplatePalette = result.templatePalette
        state.templateIndexByVoxel = result.templateIndexByVoxel
        state.voxelExteriorComponentMask = result.voxelExteriorComponentMask
        state.voxelInteriorComponentMask = result.voxelInteriorComponentMask
        state.componentGraphDegraded = result.componentGraphDegraded
        state.waterReachable = BitSet(result.sizeX * result.sizeY * result.sizeZ)
        state.unreachableVoid = state.open.clone() as BitSet
        state.floodPlaneByComponent.clear()
        clearFloodWriteQueues(state)

        if (
            boundsChanged ||
            prevOpen != state.open ||
            !prevFaceCondXP.contentEquals(state.faceCondXP) ||
            !prevFaceCondYP.contentEquals(state.faceCondYP) ||
            !prevFaceCondZP.contentEquals(state.faceCondZP)
        ) {
            state.geometryRevision++
        }

        state.appliedGeometryGeneration = result.generation
        state.geometryLastComputeNanos = result.computeNanos
        state.geometryComputeCount++
        state.dirty = false
    }

    private fun tryApplyCompletedGeometryJob(
        state: ShipPocketState,
        minX: Int,
        minY: Int,
        minZ: Int,
        sizeX: Int,
        sizeY: Int,
        sizeZ: Int,
    ): Boolean {
        val future = state.pendingGeometryFuture ?: return false
        if (!future.isDone) return false

        state.pendingGeometryFuture = null
        state.geometryJobInFlight = false

        val result = try {
            future.join()
        } catch (t: Throwable) {
            val root = t.cause ?: t
            val count = geometryJobsFailed.incrementAndGet()
            logThrottledDiag(count, "Ship pocket geometry job failed", root)
            state.dirty = true
            return false
        }

        if (result.generation != state.requestedGeometryGeneration ||
            result.invalidationStamp != state.geometryInvalidationStamp ||
            result.minX != minX ||
            result.minY != minY ||
            result.minZ != minZ ||
            result.sizeX != sizeX ||
            result.sizeY != sizeY ||
            result.sizeZ != sizeZ
        ) {
            val count = geometryJobsDiscarded.incrementAndGet()
            logThrottledDiag(
                count,
                "Discarded stale ship pocket geometry result gen={} currentGen={} invalidation={} currentInvalidation={}",
                result.generation,
                state.requestedGeometryGeneration,
                result.invalidationStamp,
                state.geometryInvalidationStamp,
            )
            state.dirty = true
            return false
        }

        applyGeometryResult(state, result)
        geometryComputeNanosTotal.addAndGet(result.computeNanos)
        val completed = geometryJobsCompleted.incrementAndGet()
        val avgMs = geometryComputeNanosTotal.get().toDouble() / completed.toDouble() / 1_000_000.0
        logThrottledDiag(
            completed,
            "Applied ship pocket geometry gen={} computeMs={} avgComputeMs={}",
            result.generation,
            result.computeNanos.toDouble() / 1_000_000.0,
            avgMs,
        )
        return true
    }

    private fun computeWorldYAffine(
        minX: Int,
        minY: Int,
        minZ: Int,
        shipTransform: ShipTransform,
        shipPosTmp: Vector3d,
        worldPosTmp: Vector3d,
    ): WorldYAffine {
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

        return WorldYAffine(
            baseWorldY = baseWorldY,
            incX = incX,
            incY = incY,
            incZ = incZ,
        )
    }

    private fun estimateExteriorFluidSurfaceY(
        level: Level,
        shipTransform: ShipTransform,
        shipBlockPos: BlockPos,
        sampleFluid: Fluid,
        shipPosTmp: Vector3d,
        worldPosTmp: Vector3d,
        worldBlockPos: BlockPos.MutableBlockPos,
    ): Double? {
        return withBypassedFluidOverrides {
            val canonical = canonicalFloodSource(sampleFluid)

            val shipX = shipBlockPos.x.toDouble() + 0.5
            val shipY = shipBlockPos.y.toDouble() + 0.5
            val shipZ = shipBlockPos.z.toDouble() + 0.5
            shipPosTmp.set(shipX, shipY, shipZ)
            shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)

            worldBlockPos.set(
                Mth.floor(worldPosTmp.x),
                Mth.floor(worldPosTmp.y),
                Mth.floor(worldPosTmp.z),
            )

            var y = worldBlockPos.y
            var steps = 0
            var lastSurface = Double.NEGATIVE_INFINITY

            while (steps < AIR_PRESSURE_SURFACE_SCAN_MAX_STEPS && y < level.maxBuildHeight) {
                val fs = level.getFluidState(worldBlockPos)
                if (fs.isEmpty || canonicalFloodSource(fs.type) != canonical) break

                val h = if (fs.isSource) 1.0 else fs.getHeight(level, worldBlockPos).toDouble()
                lastSurface = y.toDouble() + h
                if (h < 1.0 - 1e-6) break

                worldBlockPos.move(0, 1, 0)
                y++
                steps++
            }

            if (!lastSurface.isFinite()) {
                null
            } else if (steps >= AIR_PRESSURE_SURFACE_SCAN_MAX_STEPS) {
                maxOf(lastSurface, (level.seaLevel + 1).toDouble())
            } else {
                lastSurface
            }
        }
    }

    private fun captureWaterSolveSnapshot(
        level: Level,
        state: ShipPocketState,
        shipTransform: ShipTransform,
        generation: Long,
        captureTick: Long,
    ): WaterSolveSnapshot? {
        val sizeX = state.sizeX
        val sizeY = state.sizeY
        val sizeZ = state.sizeZ
        val volumeLong = sizeX.toLong() * sizeY.toLong() * sizeZ.toLong()
        if (volumeLong <= 0 || volumeLong > MAX_SIM_VOLUME.toLong()) return null
        val volume = volumeLong.toInt()

        val shipPosTmp = tmpShipPos2.get()
        val worldPosTmp = tmpWorldPos2.get()
        val shipBlockPos = tmpShipBlockPos.get()
        val worldBlockPos = BlockPos.MutableBlockPos()

        val affine = computeWorldYAffine(
            minX = state.minX,
            minY = state.minY,
            minZ = state.minZ,
            shipTransform = shipTransform,
            shipPosTmp = shipPosTmp,
            worldPosTmp = worldPosTmp,
        )

        val submerged = BitSet(volume)
        var submergedCoverage = tmpSubmergedCoverage.get()
        if (submergedCoverage.size < volume) {
            submergedCoverage = DoubleArray(volume)
            tmpSubmergedCoverage.set(submergedCoverage)
        } else {
            java.util.Arrays.fill(submergedCoverage, 0, volume, 0.0)
        }

        var surfaceYByCell = tmpPrecomputedSurfaceY.get()
        if (surfaceYByCell.size < volume) {
            surfaceYByCell = DoubleArray(volume)
            tmpPrecomputedSurfaceY.set(surfaceYByCell)
        }
        java.util.Arrays.fill(surfaceYByCell, 0, volume, Double.NaN)

        val floodFluidScores = HashMap<Fluid, Double>()
        val open = state.open
        var idx = open.nextSetBit(0)
        while (idx >= 0 && idx < volume) {
            posFromIndex(state, idx, shipBlockPos)
            val coverage = getShipCellFluidCoverage(
                level = level,
                shipTransform = shipTransform,
                shipBlockPos = shipBlockPos,
                shipPosTmp = shipPosTmp,
                worldPosTmp = worldPosTmp,
                worldBlockPos = worldBlockPos,
            )
            val fluid = coverage.canonicalFluid
            if (coverage.isSubmergedAny() && fluid != null) {
                submergedCoverage[idx] = coverage.coverageRatio
                val score = if (coverage.isIngressQualified()) {
                    coverage.coverageRatio.coerceAtLeast(SUBMERGED_INGRESS_MIN_COVERAGE)
                } else {
                    coverage.coverageRatio * 0.25
                }
                floodFluidScores[fluid] = (floodFluidScores[fluid] ?: 0.0) + score

                if (coverage.isIngressQualified()) {
                    submerged.set(idx)
                    val surface = estimateExteriorFluidSurfaceY(
                        level = level,
                        shipTransform = shipTransform,
                        shipBlockPos = shipBlockPos,
                        sampleFluid = fluid,
                        shipPosTmp = shipPosTmp,
                        worldPosTmp = worldPosTmp,
                        worldBlockPos = worldBlockPos,
                    )
                    if (surface != null && surface.isFinite()) {
                        surfaceYByCell[idx] = surface
                    }
                }
            }
            idx = open.nextSetBit(idx + 1)
        }

        var dominantFloodFluid: Fluid? = null
        var dominantScore = Double.NEGATIVE_INFINITY
        for ((fluid, score) in floodFluidScores) {
            if (score > dominantScore) {
                dominantScore = score
                dominantFloodFluid = fluid
            }
        }

        return WaterSolveSnapshot(
            generation = generation,
            geometryRevision = state.geometryRevision,
            captureTick = captureTick,
            minX = state.minX,
            minY = state.minY,
            minZ = state.minZ,
            sizeX = sizeX,
            sizeY = sizeY,
            sizeZ = sizeZ,
            open = state.open,
            interior = state.interior,
            exterior = state.exterior,
            materializedWater = state.materializedWater.clone() as BitSet,
            floodFluid = state.floodFluid,
            faceCondXP = state.faceCondXP,
            faceCondYP = state.faceCondYP,
            faceCondZP = state.faceCondZP,
            templatePalette = state.shapeTemplatePalette,
            templateIndexByVoxel = state.templateIndexByVoxel,
            voxelExteriorComponentMask = state.voxelExteriorComponentMask,
            voxelInteriorComponentMask = state.voxelInteriorComponentMask,
            submerged = submerged,
            submergedCoverage = submergedCoverage.copyOf(volume),
            dominantFloodFluid = dominantFloodFluid,
            surfaceYByCell = surfaceYByCell.copyOf(volume),
            baseWorldY = affine.baseWorldY,
            incX = affine.incX,
            incY = affine.incY,
            incZ = affine.incZ,
        )
    }

    private fun trySubmitWaterSolveJob(
        level: Level,
        state: ShipPocketState,
        shipTransform: ShipTransform,
        captureTick: Long,
    ): Boolean {
        val pending = state.pendingWaterSolveFuture
        if (pending != null && !pending.isDone) {
            state.waterSolveJobInFlight = true
            return false
        }

        val generation = state.requestedWaterSolveGeneration + 1L
        state.requestedWaterSolveGeneration = generation

        val snapshot = try {
            captureWaterSolveSnapshot(
                level = level,
                state = state,
                shipTransform = shipTransform,
                generation = generation,
                captureTick = captureTick,
            )
        } catch (t: Throwable) {
            val count = waterSolveJobsFailed.incrementAndGet()
            logThrottledDiag(count, "Failed to capture water solve snapshot", t)
            return false
        } ?: return false

        val submittedFuture = ShipPocketAsyncRuntime.trySubmit(
            subsystem = ShipPocketAsyncSubsystem.WATER_SOLVER,
            task = { computeWaterSolveAsync(snapshot) },
        )
        if (submittedFuture == null) {
            val count = asyncQueueFullSkips.incrementAndGet()
            logThrottledDiag(
                count,
                "Skipped water solver submit: async queue full pending={} max={}",
                ShipPocketAsyncRuntime.pendingJobCount(),
                ShipPocketAsyncRuntime.maxPendingJobs(),
            )
            return false
        }

        state.pendingWaterSolveFuture = submittedFuture
        state.waterSolveJobInFlight = true

        val count = waterSolveJobsSubmitted.incrementAndGet()
        logThrottledDiag(
            count,
            "Submitted water solve job gen={} geomRev={} tick={}",
            generation,
            snapshot.geometryRevision,
            snapshot.captureTick,
        )
        return true
    }

    private fun applyWaterSolveResult(
        state: ShipPocketState,
        result: WaterSolveResult,
    ) {
        state.waterReachable = result.waterReachable
        state.unreachableVoid = result.unreachableVoid
        state.buoyancy.submergedAirVolume = result.buoyancy.submergedAirVolume
        state.buoyancy.submergedAirSumX = result.buoyancy.submergedAirSumX
        state.buoyancy.submergedAirSumY = result.buoyancy.submergedAirSumY
        state.buoyancy.submergedAirSumZ = result.buoyancy.submergedAirSumZ

        val floodFluid = result.floodFluid
        if (floodFluid != null) {
            val canonical = canonicalFloodSource(floodFluid)
            if (canonical != state.floodFluid) {
                state.floodFluid = canonical
                state.dirty = true
            }
        }

        state.appliedWaterSolveGeneration = result.generation
        state.waterSolveLastComputeNanos = result.computeNanos
        state.waterSolveComputeCount++
        state.lastWaterReachableUpdateTick = result.captureTick
    }

    private fun tryApplyCompletedWaterSolveJob(
        state: ShipPocketState,
        nowTick: Long,
    ): Boolean {
        val future = state.pendingWaterSolveFuture ?: return false
        if (!future.isDone) return false

        state.pendingWaterSolveFuture = null
        state.waterSolveJobInFlight = false

        val result = try {
            future.join()
        } catch (t: Throwable) {
            val root = t.cause ?: t
            val count = waterSolveJobsFailed.incrementAndGet()
            logThrottledDiag(count, "Ship pocket water solve job failed", root)
            return false
        }

        if (result.generation != state.requestedWaterSolveGeneration ||
            result.geometryRevision != state.geometryRevision
        ) {
            ShipPocketAsyncRuntime.noteDiscard(ShipPocketAsyncSubsystem.WATER_SOLVER)
            val count = waterSolveJobsDiscarded.incrementAndGet()
            logThrottledDiag(
                count,
                "Discarded stale water solve result gen={} currentGen={} resultGeomRev={} currentGeomRev={}",
                result.generation,
                state.requestedWaterSolveGeneration,
                result.geometryRevision,
                state.geometryRevision,
            )
            return false
        }
        if (nowTick - result.captureTick > 1L) {
            ShipPocketAsyncRuntime.noteDiscard(ShipPocketAsyncSubsystem.WATER_SOLVER)
            val count = waterSolveJobsDiscarded.incrementAndGet()
            logThrottledDiag(
                count,
                "Discarded stale-aged water solve result gen={} nowTick={} captureTick={}",
                result.generation,
                nowTick,
                result.captureTick,
            )
            return false
        }

        applyWaterSolveResult(state, result)
        waterSolveComputeNanosTotal.addAndGet(result.computeNanos)
        val completed = waterSolveJobsCompleted.incrementAndGet()
        val avgMs = waterSolveComputeNanosTotal.get().toDouble() / completed.toDouble() / 1_000_000.0
        logThrottledDiag(
            completed,
            "Applied water solve gen={} computeMs={} avgComputeMs={}",
            result.generation,
            result.computeNanos.toDouble() / 1_000_000.0,
            avgMs,
        )
        return true
    }

    @JvmStatic
    fun tickServerLevel(level: ServerLevel) {
        if (!ValkyrienAirConfig.enableShipWaterPockets) return

        val states = serverStates.computeIfAbsent(level.dimensionId) { ConcurrentHashMap() }
        val loadedShipIds = LongOpenHashSet()
        var remainingGeometrySubmissions = GEOMETRY_ASYNC_SUBMISSIONS_PER_LEVEL_PER_TICK
        var remainingWaterSolveSubmissions = WATER_SOLVER_ASYNC_SUBMISSIONS_PER_LEVEL_PER_TICK
        var remainingWaterSolveSyncFallbacks = SERVER_SYNC_WATER_SOLVE_FALLBACKS_PER_TICK

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
                state.pendingGeometryFuture?.cancel(true)
                state.pendingGeometryFuture = null
                state.geometryJobInFlight = false
                state.pendingWaterSolveFuture?.cancel(true)
                state.pendingWaterSolveFuture = null
                state.waterSolveJobInFlight = false
                clearFloodWriteQueues(state)
                return@forEach
            }

            val geometryApplied = tryApplyCompletedGeometryJob(
                state = state,
                minX = minX,
                minY = minY,
                minZ = minZ,
                sizeX = sizeX,
                sizeY = sizeY,
                sizeZ = sizeZ,
            )

            var needsRecompute = state.dirty || boundsMismatch(state, minX, minY, minZ, sizeX, sizeY, sizeZ)
            if (needsRecompute) {
                // When (re)loading a ship, the shipyard chunks can arrive a few ticks after the ship object itself.
                // If we recompute while those chunks are still unloaded, `getBlockState` returns air everywhere, which
                // makes the ship appear entirely "open" and disables all air pockets until another shipyard block
                // update marks the ship dirty again.
                if (!areShipyardChunksLoaded(level, baseMinX, baseMinY, baseMinZ, baseSizeX, baseSizeY, baseSizeZ)) {
                    state.dirty = true
                } else if (remainingGeometrySubmissions > 0 &&
                    trySubmitGeometryJob(level, state, minX, minY, minZ, sizeX, sizeY, sizeZ)
                ) {
                    remainingGeometrySubmissions--
                }
            }

            val now = level.gameTime
            val shipTransform = getQueryTransform(ship)

            // If ship rotation changes the discrete shipyard "down" direction, wake up any fluids that are
            // sitting still in the shipyard so they can reflow under the new gravity.
            val gravityDown = computeShipGravityDownDir(shipTransform)
            val lastGravity = state.lastGravityDownDir
            if (lastGravity == null) {
                state.lastGravityDownDir = gravityDown
            } else if (lastGravity != gravityDown) {
                state.lastGravityDownDir = gravityDown
                state.pendingGravityResettleNextIdx = 0
            }
            tickGravityResettle(level, state)

            var waterSolveUpdated = tryApplyCompletedWaterSolveJob(state, now)

            if ((geometryApplied || now != state.lastWaterReachableUpdateTick) &&
                state.sizeX > 0 &&
                state.sizeY > 0 &&
                state.sizeZ > 0
            ) {
                if (remainingWaterSolveSubmissions > 0 &&
                    trySubmitWaterSolveJob(level, state, shipTransform, now)
                ) {
                    remainingWaterSolveSubmissions--
                }

                val staleTicks = now - state.lastWaterReachableUpdateTick
                if (!waterSolveUpdated &&
                    staleTicks > 1L &&
                    remainingWaterSolveSyncFallbacks > 0
                ) {
                    val generation = state.requestedWaterSolveGeneration + 1L
                    val snapshot = captureWaterSolveSnapshot(
                        level = level,
                        state = state,
                        shipTransform = shipTransform,
                        generation = generation,
                        captureTick = now,
                    )
                    if (snapshot != null) {
                        state.pendingWaterSolveFuture?.cancel(true)
                        state.pendingWaterSolveFuture = null
                        state.waterSolveJobInFlight = false
                        state.requestedWaterSolveGeneration = generation
                        val result = computeWaterSolveAsync(snapshot)
                        applyWaterSolveResult(state, result)
                        remainingWaterSolveSyncFallbacks--
                        waterSolveUpdated = true

                        val fallbackCount = waterSolveSyncFallbacks.incrementAndGet()
                        logThrottledDiag(
                            fallbackCount,
                            "Ran synchronous water solve fallback generation={} shipId={}",
                            generation,
                            ship.id,
                        )
                    }
                }
            }
            if (waterSolveUpdated) {
                updateVsBuoyancyFromPockets(ship, state)
            }
            if (state.sizeX > 0 && state.sizeY > 0 && state.sizeZ > 0 &&
                (geometryApplied || now - state.lastMaterializedResyncTick >= MATERIALIZED_RESYNC_INTERVAL_TICKS)
            ) {
                syncMaterializedFloodFluidFromWorld(level, state)
                state.lastMaterializedResyncTick = now
            }
            cleanupLeakedShipyardWater(level, state)
            needsRecompute = state.dirty || boundsMismatch(state, minX, minY, minZ, sizeX, sizeY, sizeZ)
            if ((geometryApplied || needsRecompute || now - state.lastFloodUpdateTick >= FLOOD_UPDATE_INTERVAL_TICKS) &&
                state.sizeX > 0 &&
                state.sizeY > 0 &&
                state.sizeZ > 0
            ) {
                updateFlooding(level, state, shipTransform)
                state.lastFloodUpdateTick = now
            }

            val flushResult = flushFloodWriteQueue(
                level = level,
                state = state,
                shipTransform = shipTransform,
                setApplyingInternalUpdates = { applyingInternalUpdates = it },
                isFloodFluidType = { fluid -> canonicalFloodSource(fluid) == state.floodFluid },
                isIngressQualifiedForAdd = { pos, transform, shipPosTmp, worldPosTmp, worldBlockPos ->
                    val submergedSample = getShipCellFluidCoverage(
                        level = level,
                        shipTransform = transform,
                        shipBlockPos = pos,
                        shipPosTmp = shipPosTmp,
                        worldPosTmp = worldPosTmp,
                        worldBlockPos = worldBlockPos,
                    )
                    val submergedFluid = submergedSample.canonicalFluid
                    submergedSample.isIngressQualified() &&
                        submergedFluid != null &&
                        canonicalFloodSource(submergedFluid) == state.floodFluid
                },
            )
            if (flushResult.remainingQueued > 0) {
                while (true) {
                    val prev = floodQueueBacklogHighWater.get()
                    val nowBacklog = flushResult.remainingQueued.toLong()
                    if (nowBacklog <= prev) break
                    if (floodQueueBacklogHighWater.compareAndSet(prev, nowBacklog)) {
                        logThrottledDiag(
                            nowBacklog,
                            "New ship flood queue high-water backlog={}",
                            nowBacklog,
                        )
                        break
                    }
                }
            }
        }

        // Cleanup unloaded ships
        states.entries.removeIf { entry ->
            if (loadedShipIds.contains(entry.key)) return@removeIf false
            entry.value.pendingGeometryFuture?.cancel(true)
            entry.value.pendingGeometryFuture = null
            entry.value.geometryJobInFlight = false
            entry.value.pendingWaterSolveFuture?.cancel(true)
            entry.value.pendingWaterSolveFuture = null
            entry.value.waterSolveJobInFlight = false
            true
        }
    }

    private fun computeShipGravityDownDir(shipTransform: ShipTransform): Direction {
        val rot = shipTransform.shipToWorldRotation
        val v = tmpShipGravityVec.get()

        v.set(0.0, 1.0, 0.0)
        rot.transform(v)
        val yY = v.y

        v.set(1.0, 0.0, 0.0)
        rot.transform(v)
        val yX = v.y

        v.set(0.0, 0.0, 1.0)
        rot.transform(v)
        val yZ = v.y

        var best = Direction.DOWN
        var bestY = -yY // local -Y

        if (yY < bestY) {
            bestY = yY
            best = Direction.UP
        }
        if (yX < bestY) {
            bestY = yX
            best = Direction.EAST
        }
        if (-yX < bestY) {
            bestY = -yX
            best = Direction.WEST
        }
        if (yZ < bestY) {
            bestY = yZ
            best = Direction.SOUTH
        }
        if (-yZ < bestY) {
            best = Direction.NORTH
        }

        return best
    }

    private fun tickGravityResettle(level: ServerLevel, state: ShipPocketState) {
        val nextIdx = state.pendingGravityResettleNextIdx
        if (nextIdx < 0) return

        val fluidBlocks = state.materializedWater
        if (fluidBlocks.isEmpty) {
            state.pendingGravityResettleNextIdx = -1
            return
        }

        val pos = BlockPos.MutableBlockPos()
        var idx = fluidBlocks.nextSetBit(nextIdx)
        var scheduled = 0
        while (idx >= 0 && scheduled < GRAVITY_RESETTLE_MAX_SCHEDULED_TICKS_PER_SHIP_PER_TICK) {
            posFromIndex(state, idx, pos)
            val fs = level.getFluidState(pos)
            if (!fs.isEmpty) {
                level.scheduleTick(pos, fs.type, 1)
                scheduled++
            }
            idx = fluidBlocks.nextSetBit(idx + 1)
        }

        state.pendingGravityResettleNextIdx = idx
    }

    private fun cleanupLeakedShipyardWater(level: ServerLevel, state: ShipPocketState) {
        if (state.materializedWater.isEmpty) return

        val toRemove = tmpLeakedWaterToRemove.get()
        toRemove.clear()
        toRemove.or(state.materializedWater)
        // Only purge cells that are no longer open (e.g. stale writes into solids).
        // Keep open-cell water managed by the drain solver even if interior classification changes this tick.
        toRemove.andNot(state.open)

        if (toRemove.isEmpty) return
        applyBlockChanges(level, state, toRemove, toWater = false, pos = BlockPos.MutableBlockPos())
    }

    private fun syncMaterializedFloodFluidFromWorld(level: ServerLevel, state: ShipPocketState) {
        val open = state.open
        val volume = state.sizeX * state.sizeY * state.sizeZ
        if (volume <= 0 || open.isEmpty) {
            state.materializedWater.clear()
            return
        }

        val materialized = state.materializedWater
        materialized.and(open)

        val pos = BlockPos.MutableBlockPos()
        var idx = open.nextSetBit(0)
        while (idx >= 0 && idx < volume) {
            posFromIndex(state, idx, pos)
            val current = level.getBlockState(pos)
            val currentFluid = current.fluidState
            val hasFloodFluid = !currentFluid.isEmpty && canonicalFloodSource(currentFluid.type) == state.floodFluid
            if (hasFloodFluid &&
                (current.block is LiquidBlock ||
                    (isWaterloggableForFlood(current, state.floodFluid) &&
                        current.getValue(BlockStateProperties.WATERLOGGED)))
            ) {
                materialized.set(idx)
            } else {
                materialized.clear(idx)
            }
            idx = open.nextSetBit(idx + 1)
        }
    }

    private fun updateVsBuoyancyFromPockets(ship: LoadedShip, state: ShipPocketState) {
        val serverShip = ship as? LoadedServerShip ?: return
        val buoyancyHandler = serverShip.getAttachment(BuoyancyHandlerAttachment::class.java) ?: return
        val buoyancyDuck = buoyancyHandler as? ValkyrienAirBuoyancyAttachmentDuck

        val props = getBuoyancyFluidProps(state.floodFluid)
        buoyancyDuck?.`valkyrienair$setBuoyancyFluidDensity`(props.density)
        buoyancyDuck?.`valkyrienair$setBuoyancyFluidViscosity`(props.viscosity)

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
        var remainingGeometrySubmissions = GEOMETRY_ASYNC_SUBMISSIONS_PER_LEVEL_PER_TICK
        var remainingWaterSolveSubmissions = WATER_SOLVER_ASYNC_SUBMISSIONS_PER_LEVEL_PER_TICK

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
                state.pendingGeometryFuture?.cancel(true)
                state.pendingGeometryFuture = null
                state.geometryJobInFlight = false
                state.pendingWaterSolveFuture?.cancel(true)
                state.pendingWaterSolveFuture = null
                state.waterSolveJobInFlight = false
                clearFloodWriteQueues(state)
                return@forEach
            }

            val geometryApplied = tryApplyCompletedGeometryJob(
                state = state,
                minX = minX,
                minY = minY,
                minZ = minZ,
                sizeX = sizeX,
                sizeY = sizeY,
                sizeZ = sizeZ,
            )
            val needsRecompute =
                state.dirty || boundsMismatch(state, minX, minY, minZ, sizeX, sizeY, sizeZ)
            if (needsRecompute) {
                // When (re)loading a ship, the shipyard chunks can arrive a few ticks after the ship object itself.
                // If we recompute while those chunks are still unloaded, `getBlockState` returns air everywhere, which
                // makes the ship appear entirely "open" and disables all air pockets until another shipyard block
                // update marks the ship dirty again.
                if (!areShipyardChunksLoaded(level, baseMinX, baseMinY, baseMinZ, baseSizeX, baseSizeY, baseSizeZ)) {
                    state.dirty = true
                } else if (remainingGeometrySubmissions > 0 &&
                    trySubmitGeometryJob(level, state, minX, minY, minZ, sizeX, sizeY, sizeZ)
                ) {
                    remainingGeometrySubmissions--
                }
            }

            val now = level.gameTime
            val shipTransform = getQueryTransform(ship)
            tryApplyCompletedWaterSolveJob(state, now)
            if ((geometryApplied || now != state.lastWaterReachableUpdateTick) &&
                state.sizeX > 0 &&
                state.sizeY > 0 &&
                state.sizeZ > 0
            ) {
                if (remainingWaterSolveSubmissions > 0 &&
                    trySubmitWaterSolveJob(level, state, shipTransform, now)
                ) {
                    remainingWaterSolveSubmissions--
                }
            }

            spawnLeakParticlesClient(level, state, shipTransform)
        }

        states.entries.removeIf { entry ->
            if (loadedShipIds.contains(entry.key)) return@removeIf false
            entry.value.pendingGeometryFuture?.cancel(true)
            entry.value.pendingGeometryFuture = null
            entry.value.geometryJobInFlight = false
            entry.value.pendingWaterSolveFuture?.cancel(true)
            entry.value.pendingWaterSolveFuture = null
            entry.value.waterSolveJobInFlight = false
            true
        }
    }

    private fun leakParticleForFluid(fluid: Fluid): ParticleOptions {
        val canonical = canonicalFloodSource(fluid)
        if (canonical == Fluids.LAVA) return ParticleTypes.LAVA
        return try {
            val legacy = canonical.defaultFluidState().createLegacyBlock()
            if (!legacy.isAir) BlockParticleOption(ParticleTypes.BLOCK, legacy) else ParticleTypes.SPLASH
        } catch (_: Throwable) {
            ParticleTypes.SPLASH
        }
    }

    private fun emitDirectionalLeakParticles(
        level: Level,
        state: ShipPocketState,
        shipTransform: ShipTransform,
        cellIdx: Int,
        faceDirCode: Int,
        jetDirCode: Int,
        particle: ParticleOptions,
        particleCount: Int,
        baseSpeed: Double,
    ) {
        if (particleCount <= 0) return
        val sizeX = state.sizeX
        val sizeY = state.sizeY
        val sizeZ = state.sizeZ
        val volume = sizeX * sizeY * sizeZ
        if (cellIdx < 0 || cellIdx >= volume) return

        val cellLX = cellIdx % sizeX
        val cellT = cellIdx / sizeX
        val cellLY = cellT % sizeY
        val cellLZ = cellT / sizeY

        val centerShipX = (state.minX + cellLX).toDouble() + 0.5
        val centerShipY = (state.minY + cellLY).toDouble() + 0.5
        val centerShipZ = (state.minZ + cellLZ).toDouble() + 0.5

        val faceDirX: Int
        val faceDirY: Int
        val faceDirZ: Int
        when (faceDirCode) {
            0 -> {
                faceDirX = -1; faceDirY = 0; faceDirZ = 0
            }
            1 -> {
                faceDirX = 1; faceDirY = 0; faceDirZ = 0
            }
            2 -> {
                faceDirX = 0; faceDirY = -1; faceDirZ = 0
            }
            3 -> {
                faceDirX = 0; faceDirY = 1; faceDirZ = 0
            }
            4 -> {
                faceDirX = 0; faceDirY = 0; faceDirZ = -1
            }
            else -> {
                faceDirX = 0; faceDirY = 0; faceDirZ = 1
            }
        }

        val jetDirX: Int
        val jetDirY: Int
        val jetDirZ: Int
        when (jetDirCode) {
            0 -> {
                jetDirX = -1; jetDirY = 0; jetDirZ = 0
            }
            1 -> {
                jetDirX = 1; jetDirY = 0; jetDirZ = 0
            }
            2 -> {
                jetDirX = 0; jetDirY = -1; jetDirZ = 0
            }
            3 -> {
                jetDirX = 0; jetDirY = 1; jetDirZ = 0
            }
            4 -> {
                jetDirX = 0; jetDirY = 0; jetDirZ = -1
            }
            else -> {
                jetDirX = 0; jetDirY = 0; jetDirZ = 1
            }
        }

        val tangentAX: Double
        val tangentAY: Double
        val tangentAZ: Double
        val tangentBX: Double
        val tangentBY: Double
        val tangentBZ: Double
        when {
            faceDirX != 0 -> {
                tangentAX = 0.0; tangentAY = 1.0; tangentAZ = 0.0
                tangentBX = 0.0; tangentBY = 0.0; tangentBZ = 1.0
            }
            faceDirY != 0 -> {
                tangentAX = 1.0; tangentAY = 0.0; tangentAZ = 0.0
                tangentBX = 0.0; tangentBY = 0.0; tangentBZ = 1.0
            }
            else -> {
                tangentAX = 1.0; tangentAY = 0.0; tangentAZ = 0.0
                tangentBX = 0.0; tangentBY = 1.0; tangentBZ = 0.0
            }
        }

        val shipPosTmp = tmpShipPos.get()
        val worldPosTmp = tmpWorldPos.get()
        val jetWorld = tmpShipFlowDir.get()
        val tangentAWorld = tmpShipGravityVec.get()
        val tangentBWorld = Vector3d()
        val rand = level.random
        val serverLevel = level as? ServerLevel

        shipPosTmp.set(centerShipX, centerShipY, centerShipZ)
        shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)
        val centerWorldX = worldPosTmp.x
        val centerWorldY = worldPosTmp.y
        val centerWorldZ = worldPosTmp.z

        shipPosTmp.set(
            centerShipX + jetDirX.toDouble(),
            centerShipY + jetDirY.toDouble(),
            centerShipZ + jetDirZ.toDouble(),
        )
        shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)
        jetWorld.set(worldPosTmp.x - centerWorldX, worldPosTmp.y - centerWorldY, worldPosTmp.z - centerWorldZ)
        if (jetWorld.lengthSquared() > 1.0e-12) {
            jetWorld.normalize().mul(baseSpeed)
        } else {
            jetWorld.set(0.0, 0.0, 0.0)
        }

        shipPosTmp.set(centerShipX + tangentAX, centerShipY + tangentAY, centerShipZ + tangentAZ)
        shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)
        tangentAWorld.set(worldPosTmp.x - centerWorldX, worldPosTmp.y - centerWorldY, worldPosTmp.z - centerWorldZ)
        if (tangentAWorld.lengthSquared() > 1.0e-12) {
            tangentAWorld.normalize()
        } else {
            tangentAWorld.set(0.0, 0.0, 0.0)
        }

        shipPosTmp.set(centerShipX + tangentBX, centerShipY + tangentBY, centerShipZ + tangentBZ)
        shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)
        tangentBWorld.set(worldPosTmp.x - centerWorldX, worldPosTmp.y - centerWorldY, worldPosTmp.z - centerWorldZ)
        if (tangentBWorld.lengthSquared() > 1.0e-12) {
            tangentBWorld.normalize()
        } else {
            tangentBWorld.set(0.0, 0.0, 0.0)
        }

        val faceOffset = 0.501
        val positionSpread = 0.49
        val tangentialSpeed = 0.05
        repeat(particleCount) {
            val u = (rand.nextDouble() - 0.5) * 2.0 * positionSpread
            val v = (rand.nextDouble() - 0.5) * 2.0 * positionSpread

            val pxShip = centerShipX + faceDirX.toDouble() * faceOffset + tangentAX * u + tangentBX * v
            val pyShip = centerShipY + faceDirY.toDouble() * faceOffset + tangentAY * u + tangentBY * v
            val pzShip = centerShipZ + faceDirZ.toDouble() * faceOffset + tangentAZ * u + tangentBZ * v
            shipPosTmp.set(pxShip, pyShip, pzShip)
            shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)

            val su = (rand.nextDouble() - 0.5) * 2.0 * tangentialSpeed
            val sv = (rand.nextDouble() - 0.5) * 2.0 * tangentialSpeed
            val vx = jetWorld.x + tangentAWorld.x * su + tangentBWorld.x * sv
            val vy = jetWorld.y + tangentAWorld.y * su + tangentBWorld.y * sv
            val vz = jetWorld.z + tangentAWorld.z * su + tangentBWorld.z * sv

            if (serverLevel != null) {
                serverLevel.sendParticles(particle, worldPosTmp.x, worldPosTmp.y, worldPosTmp.z, 1, vx, vy, vz, 0.0)
            } else {
                level.addParticle(particle, worldPosTmp.x, worldPosTmp.y, worldPosTmp.z, vx, vy, vz)
            }
        }
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
        val shipCellPos = BlockPos.MutableBlockPos()

        fun isCellAlreadyFloodFluid(cellIdx: Int): Boolean {
            if (cellIdx < 0 || cellIdx >= volume) return false
            posFromIndex(state, cellIdx, shipCellPos)
            val cellFluid = level.getFluidState(shipCellPos)
            return !cellFluid.isEmpty && canonicalFloodSource(cellFluid.type) == state.floodFluid
        }

        val faceDirBuf = IntArray(6)
        val faceConductanceBuf = IntArray(6)

        val maxHoles = 4
        val chosenHoleIdx = IntArray(maxHoles)
        val chosenOutDirCode = IntArray(maxHoles)
        val chosenConductance = IntArray(maxHoles)
        var chosenCount = 0

        fun alreadyChosen(holeIdx: Int): Boolean {
            for (i in 0 until chosenCount) if (chosenHoleIdx[i] == holeIdx) return true
            return false
        }

        fun chooseHole(holeIdx: Int, outDirCode: Int, conductance: Int) {
            if (chosenCount >= maxHoles) return
            chosenHoleIdx[chosenCount] = holeIdx
            chosenOutDirCode[chosenCount] = outDirCode
            chosenConductance[chosenCount] = conductance
            chosenCount++
        }

        fun tryChooseHoleInterior(cellIdx: Int): Boolean {
            if (cellIdx < 0 || cellIdx >= volume) return false
            if (!interior.get(cellIdx)) return false
            if (!targetWetInterior.get(cellIdx)) return false
            if (isCellAlreadyFloodFluid(cellIdx)) return false
            if (alreadyChosen(cellIdx)) return false

            val lx = cellIdx % sizeX
            val t = cellIdx / sizeX
            val ly = t % sizeY
            val lz = t / sizeY

            var faceCount = 0
            fun tryFace(nIdx: Int, outDirCode: Int, conductance: Int) {
                if (conductance <= 0) return
                if (nIdx < 0 || nIdx >= volume) return
                if (interior.get(nIdx)) return
                if (!open.get(nIdx)) return
                if (!targetWet.get(nIdx)) return
                faceDirBuf[faceCount] = outDirCode
                faceConductanceBuf[faceCount] = conductance
                faceCount++
            }

            if (lx > 0) tryFace(cellIdx - 1, 0, edgeConductance(state, cellIdx, lx, ly, lz, 0))
            if (lx + 1 < sizeX) tryFace(cellIdx + 1, 1, edgeConductance(state, cellIdx, lx, ly, lz, 1))
            if (ly > 0) tryFace(cellIdx - strideY, 2, edgeConductance(state, cellIdx, lx, ly, lz, 2))
            if (ly + 1 < sizeY) tryFace(cellIdx + strideY, 3, edgeConductance(state, cellIdx, lx, ly, lz, 3))
            if (lz > 0) tryFace(cellIdx - strideZ, 4, edgeConductance(state, cellIdx, lx, ly, lz, 4))
            if (lz + 1 < sizeZ) tryFace(cellIdx + strideZ, 5, edgeConductance(state, cellIdx, lx, ly, lz, 5))

            if (faceCount == 0) return false

            var chosenFace = 0
            var bestScore = Int.MIN_VALUE
            for (i in 0 until faceCount) {
                val candidateOut = faceDirBuf[i]
                val inDir = candidateOut xor 1
                val inNeighbor = when (inDir) {
                    0 -> if (lx > 0) cellIdx - 1 else -1
                    1 -> if (lx + 1 < sizeX) cellIdx + 1 else -1
                    2 -> if (ly > 0) cellIdx - strideY else -1
                    3 -> if (ly + 1 < sizeY) cellIdx + strideY else -1
                    4 -> if (lz > 0) cellIdx - strideZ else -1
                    else -> if (lz + 1 < sizeZ) cellIdx + strideZ else -1
                }
                var score = faceConductanceBuf[i]
                if (inNeighbor >= 0 && missing.get(inNeighbor)) score += 10_000
                if (score > bestScore) {
                    bestScore = score
                    chosenFace = i
                }
            }

            chooseHole(cellIdx, faceDirBuf[chosenFace], faceConductanceBuf[chosenFace])
            return true
        }

        val scanBudget = 512
        var scanned = 0
        var idx = missing.nextSetBit(rand.nextInt(volume))
        if (idx < 0) idx = missing.nextSetBit(0)
        while (idx >= 0 && idx < volume && chosenCount < maxHoles && scanned < scanBudget) {
            val lx = idx % sizeX
            val t = idx / sizeX
            val ly = t % sizeY
            val lz = t / sizeY

            tryChooseHoleInterior(idx)
            if (chosenCount >= maxHoles) break
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
                if (candidate >= 0) tryChooseHoleInterior(candidate)
            }
        }
        if (chosenCount == 0) return

        val leakParticle = leakParticleForFluid(state.floodFluid)
        val particleSpeedMultiplier = ValkyrienAirConfig.shipPocketParticleSpeedMultiplier.coerceIn(0.1, 5.0)
        for (iHole in 0 until chosenCount) {
            val holeIdx = chosenHoleIdx[iHole]
            val outDirCode = chosenOutDirCode[iHole]
            val conductance = chosenConductance[iHole].coerceAtLeast(1)
            val particleCount = (3 + conductance / 8).coerceIn(3, 18)
            val speed = ((0.09 + conductance * 0.0004).coerceIn(0.09, 0.22)) * particleSpeedMultiplier
            emitDirectionalLeakParticles(
                level = level,
                state = state,
                shipTransform = shipTransform,
                cellIdx = holeIdx,
                faceDirCode = outDirCode,
                jetDirCode = outDirCode xor 1,
                particle = leakParticle,
                particleCount = particleCount,
                baseSpeed = speed,
            )
            val outwardCount = (particleCount / 2).coerceIn(1, 9)
            emitDirectionalLeakParticles(
                level = level,
                state = state,
                shipTransform = shipTransform,
                cellIdx = holeIdx,
                faceDirCode = outDirCode,
                jetDirCode = outDirCode,
                particle = leakParticle,
                particleCount = outwardCount,
                baseSpeed = (speed * 0.9).coerceAtMost(0.3 * particleSpeedMultiplier),
            )
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
        val floodFluid: Fluid,
        val minX: Int,
        val minY: Int,
        val minZ: Int,
        val sizeX: Int,
        val sizeY: Int,
        val sizeZ: Int,
        val open: BitSet,
        val interior: BitSet,
        val waterReachable: BitSet,
        val unreachableVoid: BitSet,
    )

    @JvmStatic
    fun getClientWaterReachableSnapshot(level: Level, shipId: Long): ClientWaterReachableSnapshot? {
        if (!level.isClientSide) return null
        val state = clientStates[level.dimensionId]?.get(shipId) ?: return null
        return ClientWaterReachableSnapshot(
            state.geometryRevision,
            state.floodFluid,
            state.minX,
            state.minY,
            state.minZ,
            state.sizeX,
            state.sizeY,
            state.sizeZ,
            state.open,
            state.interior,
            state.waterReachable,
            state.unreachableVoid,
        )
    }

    private fun isSuppressionClassification(state: ShipPocketState, classification: PointVoidClassification): Boolean {
        if (classification.kind != PointVoidClass.INTERIOR_VOID) return false
        val idx = classification.voxelIndex
        if (idx < 0) return false
        if (state.materializedWater.get(idx)) return false
        return true
    }

    private fun isAirPocketClassification(state: ShipPocketState, classification: PointVoidClassification): Boolean {
        if (classification.kind != PointVoidClass.INTERIOR_VOID) return false
        val idx = classification.voxelIndex
        if (idx < 0) return false
        if (state.materializedWater.get(idx)) return false
        return state.unreachableVoid.get(idx)
    }

    private fun getIntersectingShipsCached(
        level: Level,
        worldBlockPos: BlockPos,
        queryAabb: AABBd,
    ): List<Ship> {
        val cache = tmpIntersectingShipsCache.get()
        val tick = level.gameTime
        val posLong = worldBlockPos.asLong()
        if (cache.lastLevel === level &&
            cache.lastTick == tick &&
            cache.lastWorldPosLong == posLong
        ) {
            return cache.ships
        }

        val ships = ArrayList<Ship>()
        for (ship in level.shipObjectWorld.loadedShips.getIntersecting(queryAabb, level.dimensionId)) {
            ships.add(ship)
        }

        cache.lastLevel = level
        cache.lastTick = tick
        cache.lastWorldPosLong = posLong
        cache.ships = ships
        return ships
    }

    @JvmStatic
    fun overrideWaterFluidState(level: Level, worldBlockPos: BlockPos, original: net.minecraft.world.level.material.FluidState): net.minecraft.world.level.material.FluidState {
        return overrideWaterFluidState(
            level = level,
            worldX = worldBlockPos.x + 0.5,
            worldY = worldBlockPos.y + 0.5,
            worldZ = worldBlockPos.z + 0.5,
            original = original,
        )
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

        for (ship in getIntersectingShipsCached(level, worldBlockPos, queryAabb)) {
            val state = getState(level, ship.id) ?: continue
            val shipTransform = getQueryTransform(ship)

            shipTransform.worldToShip.transformPosition(worldPos, shipPosTmp)
            val classification = classifyShipPointWithEpsilon(
                state = state,
                x = shipPosTmp.x,
                y = shipPosTmp.y,
                z = shipPosTmp.z,
                out = shipBlockPosTmp,
            )
            if (classification.kind == PointVoidClass.OUT_OF_BOUNDS || classification.kind == PointVoidClass.SOLID) {
                continue
            }

            val shipFluid = findShipFluidAtShipPoint(level, shipPosTmp, shipBlockPosTmp)
            if (!shipFluid.isEmpty) return shipFluid
            if (!original.isEmpty && isSuppressionClassification(state, classification)) {
                val count = worldSuppressionHits.incrementAndGet()
                logThrottledDiag(count, "Suppressed world fluid query in ship interior suppression zone")
                return Fluids.EMPTY.defaultFluidState()
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

        for (ship in getIntersectingShipsCached(level, worldBlockPos, queryAabb)) {
            val shipTransform = getQueryTransform(ship)

            shipTransform.worldToShip.transformPosition(worldPos, shipPosTmp)
            val state = getState(level, ship.id)
            if (state != null) {
                val classification = classifyShipPointWithEpsilon(
                    state = state,
                    x = shipPosTmp.x,
                    y = shipPosTmp.y,
                    z = shipPosTmp.z,
                    out = shipBlockPosTmp,
                )
                if (classification.kind == PointVoidClass.OUT_OF_BOUNDS || classification.kind == PointVoidClass.SOLID) {
                    continue
                }
            } else {
                shipBlockPosTmp.set(Mth.floor(shipPosTmp.x), Mth.floor(shipPosTmp.y), Mth.floor(shipPosTmp.z))
            }

            val shipFluid = findShipFluidAtShipPoint(level, shipPosTmp, shipBlockPosTmp)
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

    @JvmStatic
    fun isWorldPosInShipWorldFluidSuppressionZone(level: Level, worldBlockPos: BlockPos): Boolean {
        return isWorldPosInShipWorldFluidSuppressionZone(
            level = level,
            worldX = worldBlockPos.x + 0.5,
            worldY = worldBlockPos.y + 0.5,
            worldZ = worldBlockPos.z + 0.5,
        )
    }

    @JvmStatic
    fun isWorldPosInShipWorldFluidSuppressionZone(level: Level, worldX: Double, worldY: Double, worldZ: Double): Boolean {
        if (!ValkyrienAirConfig.enableShipWaterPockets) return false
        if (level.isBlockInShipyard(worldX, worldY, worldZ)) return false

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

        for (ship in getIntersectingShipsCached(level, worldBlockPos, queryAabb)) {
            val state = getState(level, ship.id) ?: continue
            val shipTransform = getQueryTransform(ship)

            shipTransform.worldToShip.transformPosition(worldPos, shipPosTmp)
            val classification = classifyShipPointWithEpsilon(
                state = state,
                x = shipPosTmp.x,
                y = shipPosTmp.y,
                z = shipPosTmp.z,
                out = shipBlockPosTmp,
            )
            if (isSuppressionClassification(state, classification)) {
                return true
            }
        }

        return false
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
        return isWorldPosInShipAirPocket(
            level,
            worldBlockPos.x + 0.5,
            worldBlockPos.y + 0.5,
            worldBlockPos.z + 0.5
        )
    }

    @JvmStatic
    fun isWorldPosInShipAirPocket(level: Level, worldX: Double, worldY: Double, worldZ: Double): Boolean {
        if (!ValkyrienAirConfig.enableShipWaterPockets) return false
        if (level.isBlockInShipyard(worldX, worldY, worldZ)) return false

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

        for (ship in getIntersectingShipsCached(level, worldBlockPos, queryAabb)) {
            val state = getState(level, ship.id) ?: continue
            val shipTransform = getQueryTransform(ship)

            shipTransform.worldToShip.transformPosition(worldPos, shipPosTmp)
            val classification = classifyShipPointWithEpsilon(
                state = state,
                x = shipPosTmp.x,
                y = shipPosTmp.y,
                z = shipPosTmp.z,
                out = shipBlockPosTmp,
            )
            if (isAirPocketClassification(state, classification)) {
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

        for (ship in getIntersectingShipsCached(level, worldBlockPos, queryAabb)) {
            val state = getState(level, ship.id) ?: continue
            val shipTransform = getQueryTransform(ship)

            shipTransform.worldToShip.transformPosition(worldPos, shipPosTmp)
            val classification = classifyShipPointWithEpsilon(
                state = state,
                x = shipPosTmp.x,
                y = shipPosTmp.y,
                z = shipPosTmp.z,
                out = shipBlockPosTmp,
            )
            if (!isAirPocketClassification(state, classification)) continue
            return BlockPos(classification.voxelX, classification.voxelY, classification.voxelZ)
        }

        return null
    }

    private fun getState(level: Level, shipId: Long): ShipPocketState? {
        val map = if (level.isClientSide) clientStates else serverStates
        return map[level.dimensionId]?.get(shipId)
    }

    private fun getQueryTransform(ship: Ship): ShipTransform {
        return ship.transform
    }

    private fun canonicalFloodSource(fluid: Fluid): Fluid {
        return if (fluid is FlowingFluid) fluid.source else fluid
    }

    private fun isWaterloggableForFlood(state: BlockState, floodFluid: Fluid): Boolean {
        return canonicalFloodSource(floodFluid) == Fluids.WATER && state.hasProperty(BlockStateProperties.WATERLOGGED)
    }

    private fun computeWaterReachableWithPressure(
        level: Level?,
        minX: Int,
        minY: Int,
        minZ: Int,
        sizeX: Int,
        sizeY: Int,
        sizeZ: Int,
        open: BitSet,
        interior: BitSet,
        shipTransform: ShipTransform?,
        out: BitSet,
        exteriorOpen: BitSet? = null,
        buoyancyOut: BuoyancyMetrics? = null,
        materializedWater: BitSet? = null,
        floodFluidOut: AtomicReference<Fluid?>? = null,
        faceCondXP: ShortArray? = null,
        faceCondYP: ShortArray? = null,
        faceCondZP: ShortArray? = null,
        templatePalette: List<ShapeCellTemplate>? = null,
        templateIndexByVoxel: IntArray? = null,
        voxelExteriorComponentMask: LongArray? = null,
        voxelInteriorComponentMask: LongArray? = null,
        precomputedSubmerged: BitSet? = null,
        precomputedSubmergedCoverage: DoubleArray? = null,
        precomputedDominantFloodFluid: Fluid? = null,
        precomputedSurfaceYByCell: DoubleArray? = null,
        precomputedAffine: WorldYAffine? = null,
        allowWorldSurfaceScan: Boolean = true,
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

        val worldPosTmp = if (level != null) tmpWorldPos.get() else null
        val shipPosTmp = if (level != null) tmpShipPos.get() else null
        val shipBlockPos = if (level != null) tmpShipBlockPos.get() else null
        val worldBlockPos = if (level != null) BlockPos.MutableBlockPos() else null

        var submergedCoverage = tmpSubmergedCoverage.get()
        if (submergedCoverage.size < volume) {
            submergedCoverage = DoubleArray(volume)
            tmpSubmergedCoverage.set(submergedCoverage)
        } else {
            java.util.Arrays.fill(submergedCoverage, 0, volume, 0.0)
        }
        var dominantFloodFluid: Fluid? = precomputedDominantFloodFluid
        if (precomputedSubmerged != null &&
            precomputedSubmergedCoverage != null &&
            precomputedSubmergedCoverage.size >= volume
        ) {
            submerged.or(precomputedSubmerged)
            java.lang.System.arraycopy(precomputedSubmergedCoverage, 0, submergedCoverage, 0, volume)
        } else {
            val sampledLevel = level
            val sampledTransform = shipTransform
            val sampledWorldPosTmp = worldPosTmp
            val sampledShipPosTmp = shipPosTmp
            val sampledShipBlockPos = shipBlockPos
            val sampledWorldBlockPos = worldBlockPos
            if (sampledLevel == null ||
                sampledTransform == null ||
                sampledWorldPosTmp == null ||
                sampledShipPosTmp == null ||
                sampledShipBlockPos == null ||
                sampledWorldBlockPos == null
            ) {
                return out
            }

            val floodFluidScores = HashMap<Fluid, Double>()

            fun shipCellFluidCoverage(idx: Int): FluidCoverageSample {
                val lx = idx % sizeX
                val t = idx / sizeX
                val ly = t % sizeY
                val lz = t / sizeY
                sampledShipBlockPos.set(minX + lx, minY + ly, minZ + lz)
                return getShipCellFluidCoverage(
                    sampledLevel,
                    sampledTransform,
                    sampledShipBlockPos,
                    sampledShipPosTmp,
                    sampledWorldPosTmp,
                    sampledWorldBlockPos,
                )
            }

            // Cache which open cells are submerged in world fluid, using coverage-aware qualification.
            var idx = open.nextSetBit(0)
            while (idx >= 0 && idx < volume) {
                val coverage = shipCellFluidCoverage(idx)
                val fluid = coverage.canonicalFluid
                if (coverage.isSubmergedAny() && fluid != null) {
                    submergedCoverage[idx] = coverage.coverageRatio
                    val score = if (coverage.isIngressQualified()) {
                        coverage.coverageRatio.coerceAtLeast(SUBMERGED_INGRESS_MIN_COVERAGE)
                    } else {
                        coverage.coverageRatio * 0.25
                    }
                    floodFluidScores[fluid] = (floodFluidScores[fluid] ?: 0.0) + score
                }
                if (coverage.isIngressQualified()) {
                    submerged.set(idx)
                }
                idx = open.nextSetBit(idx + 1)
            }

            var dominantScore = Double.NEGATIVE_INFINITY
            for ((fluid, score) in floodFluidScores) {
                if (score > dominantScore) {
                    dominantScore = score
                    dominantFloodFluid = fluid
                }
            }
        }
        if (dominantFloodFluid != null && floodFluidOut != null && floodFluidOut.get() == null) {
            floodFluidOut.set(dominantFloodFluid)
        }

        val strideY = sizeX
        val strideZ = sizeX * sizeY
        val hasFaceConductance =
            faceCondXP != null &&
                faceCondYP != null &&
                faceCondZP != null &&
                faceCondXP.size == volume &&
                faceCondYP.size == volume &&
                faceCondZP.size == volume

        fun edgeCond(idx: Int, lx: Int, ly: Int, lz: Int, dirCode: Int): Int {
            if (!hasFaceConductance) return 1
            return when (dirCode) {
                0 -> if (lx > 0) faceCondXP[idx - 1].toInt() and 0xFFFF else 0
                1 -> if (lx + 1 < sizeX) faceCondXP[idx].toInt() and 0xFFFF else 0
                2 -> if (ly > 0) faceCondYP[idx - strideY].toInt() and 0xFFFF else 0
                3 -> if (ly + 1 < sizeY) faceCondYP[idx].toInt() and 0xFFFF else 0
                4 -> if (lz > 0) faceCondZP[idx - strideZ].toInt() and 0xFFFF else 0
                else -> if (lz + 1 < sizeZ) faceCondZP[idx].toInt() and 0xFFFF else 0
            }
        }

        // Component-filtered flood traversal is currently disabled for stability; precise subcell point
        // classification remains active for gameplay queries (swimming/breathing/suppression).
        val hasTemplateConnectivity = false

        fun filteredEdgeCond(
            idxCur: Int,
            idxNeighbor: Int,
            lx: Int,
            ly: Int,
            lz: Int,
            dirCode: Int,
            componentMaskCur: Long = -1L,
            componentMaskNeighbor: Long = -1L,
        ): Int {
            if (componentMaskCur == 0L || componentMaskNeighbor == 0L) return 0
            if (!hasTemplateConnectivity) {
                return edgeCond(idxCur, lx, ly, lz, dirCode)
            }

            val palette = templatePalette ?: return edgeCond(idxCur, lx, ly, lz, dirCode)
            val templateIdxArr = templateIndexByVoxel ?: return edgeCond(idxCur, lx, ly, lz, dirCode)
            val templateIdxCur = templateIdxArr[idxCur]
            val templateIdxNeighbor = templateIdxArr[idxNeighbor]
            if (templateIdxCur !in palette.indices || templateIdxNeighbor !in palette.indices) {
                return edgeCond(idxCur, lx, ly, lz, dirCode)
            }

            return computeTemplateFaceConductance(
                templateA = palette[templateIdxCur],
                templateB = palette[templateIdxNeighbor],
                dirCodeFromA = dirCode,
                componentMaskA = componentMaskCur,
                componentMaskB = componentMaskNeighbor,
            )
        }

        // Compute an affine map from local ship voxel coords -> world Y. This is much faster than per-point transforms.
        val affine = precomputedAffine ?: run {
            val sampledTransform = shipTransform ?: return out
            val sampledShipPosTmp = shipPosTmp ?: return out
            val sampledWorldPosTmp = worldPosTmp ?: return out
            computeWorldYAffine(
                minX = minX,
                minY = minY,
                minZ = minZ,
                shipTransform = sampledTransform,
                shipPosTmp = sampledShipPosTmp,
                worldPosTmp = sampledWorldPosTmp,
            )
        }
        val baseWorldY = affine.baseWorldY
        val incX = affine.incX
        val incY = affine.incY
        val incZ = affine.incZ

        fun worldYAtLocal(x: Double, y: Double, z: Double): Double {
            return baseWorldY + incX * x + incY * y + incZ * z
        }

        fun cellCenterWorldY(lx: Int, ly: Int, lz: Int): Double {
            return baseWorldY + incX * (lx + 0.5) + incY * (ly + 0.5) + incZ * (lz + 0.5)
        }

        fun openingFaceTopWorldY(lx: Int, ly: Int, lz: Int, outDirCode: Int): Double {
            val x0 = lx.toDouble()
            val y0 = ly.toDouble()
            val z0 = lz.toDouble()
            val x1 = x0 + 1.0
            val y1 = y0 + 1.0
            val z1 = z0 + 1.0

            return when (outDirCode) {
                0 -> maxOf(
                    worldYAtLocal(x0, y0, z0),
                    worldYAtLocal(x0, y1, z0),
                    worldYAtLocal(x0, y0, z1),
                    worldYAtLocal(x0, y1, z1),
                )
                1 -> maxOf(
                    worldYAtLocal(x1, y0, z0),
                    worldYAtLocal(x1, y1, z0),
                    worldYAtLocal(x1, y0, z1),
                    worldYAtLocal(x1, y1, z1),
                )
                2 -> maxOf(
                    worldYAtLocal(x0, y0, z0),
                    worldYAtLocal(x1, y0, z0),
                    worldYAtLocal(x0, y0, z1),
                    worldYAtLocal(x1, y0, z1),
                )
                3 -> maxOf(
                    worldYAtLocal(x0, y1, z0),
                    worldYAtLocal(x1, y1, z0),
                    worldYAtLocal(x0, y1, z1),
                    worldYAtLocal(x1, y1, z1),
                )
                4 -> maxOf(
                    worldYAtLocal(x0, y0, z0),
                    worldYAtLocal(x1, y0, z0),
                    worldYAtLocal(x0, y1, z0),
                    worldYAtLocal(x1, y1, z0),
                )
                else -> maxOf(
                    worldYAtLocal(x0, y0, z1),
                    worldYAtLocal(x1, y0, z1),
                    worldYAtLocal(x0, y1, z1),
                    worldYAtLocal(x1, y1, z1),
                )
            }
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

            forEachBoundaryIndexGraph(sizeX, sizeY, sizeZ) { boundaryIdx ->
                tryEnqueueExterior(boundaryIdx)
            }

            while (head < tail) {
                val cur = componentQueue[head++]

                val lx = cur % sizeX
                val t = cur / sizeX
                val ly = t % sizeY
                val lz = t / sizeY

                if (lx > 0 && edgeCond(cur, lx, ly, lz, 0) > 0) tryEnqueueExterior(cur - 1)
                if (lx + 1 < sizeX && edgeCond(cur, lx, ly, lz, 1) > 0) tryEnqueueExterior(cur + 1)
                if (ly > 0 && edgeCond(cur, lx, ly, lz, 2) > 0) tryEnqueueExterior(cur - strideY)
                if (ly + 1 < sizeY && edgeCond(cur, lx, ly, lz, 3) > 0) tryEnqueueExterior(cur + strideY)
                if (lz > 0 && edgeCond(cur, lx, ly, lz, 4) > 0) tryEnqueueExterior(cur - strideZ)
                if (lz + 1 < sizeZ && edgeCond(cur, lx, ly, lz, 5) > 0) tryEnqueueExterior(cur + strideZ)
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
            var airVentConductance = 0
            var bestSurfaceSampleIdx = -1
            var bestSurfaceSampleY = Double.NEGATIVE_INFINITY

            fun processHole(curIdx: Int, lx: Int, ly: Int, lz: Int, nIdx: Int, outDirCode: Int, conductance: Int) {
                if (conductance <= 0) return
                if (!open.get(nIdx)) return
                if (exteriorOpen != null && !exteriorOpen.get(nIdx)) return

                if (submerged.get(nIdx)) {
                    // Submerged hull opening: water can enter. Track the highest submerged opening as the fill level.
                    waterLevel = maxOf(waterLevel, openingFaceTopWorldY(lx, ly, lz, outDirCode))
                    if (seedCount < waterQueue.size) {
                        waterQueue[seedCount++] = curIdx
                    }

                    // Representative "near-surface" sample point for estimating exterior water pressure.
                    // (Choosing the highest submerged opening tends to reduce the scan distance to the fluid surface.)
                    val sampleY = cellCenterWorldY(lx, ly, lz)
                    if (sampleY > bestSurfaceSampleY) {
                        bestSurfaceSampleY = sampleY
                        bestSurfaceSampleIdx = curIdx
                    }
                } else {
                    // Non-submerged opening to the exterior air: air can escape, so the pocket is unpressurized.
                    airVentConductance += conductance
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
                val curInteriorMask = voxelInteriorComponentMask?.let { masks ->
                    if (cur in masks.indices) masks[cur] else 0L
                } ?: -1L

                if (lx > 0) {
                    val n = cur - 1
                    if (hasTemplateConnectivity) {
                        val neighborInteriorMask = voxelInteriorComponentMask?.let { masks ->
                            if (n in masks.indices) masks[n] else 0L
                        } ?: 0L
                        val condInterior = filteredEdgeCond(
                            idxCur = cur,
                            idxNeighbor = n,
                            lx = lx,
                            ly = ly,
                            lz = lz,
                            dirCode = 0,
                            componentMaskCur = curInteriorMask,
                            componentMaskNeighbor = neighborInteriorMask,
                        )
                        if (condInterior > 0) enqueueInterior(n)

                        val neighborExteriorMask = voxelExteriorComponentMask?.let { masks ->
                            if (n in masks.indices) masks[n] else 0L
                        } ?: 0L
                        val condExterior = filteredEdgeCond(
                            idxCur = cur,
                            idxNeighbor = n,
                            lx = lx,
                            ly = ly,
                            lz = lz,
                            dirCode = 0,
                            componentMaskCur = curInteriorMask,
                            componentMaskNeighbor = neighborExteriorMask,
                        )
                        if (condExterior > 0) processHole(cur, lx, ly, lz, n, 0, condExterior)
                    } else {
                        val cond = edgeCond(cur, lx, ly, lz, 0)
                        if (cond > 0) {
                            if (interior.get(n)) enqueueInterior(n) else processHole(cur, lx, ly, lz, n, 0, cond)
                        }
                    }
                }
                if (lx + 1 < sizeX) {
                    val n = cur + 1
                    if (hasTemplateConnectivity) {
                        val neighborInteriorMask = voxelInteriorComponentMask?.let { masks ->
                            if (n in masks.indices) masks[n] else 0L
                        } ?: 0L
                        val condInterior = filteredEdgeCond(
                            idxCur = cur,
                            idxNeighbor = n,
                            lx = lx,
                            ly = ly,
                            lz = lz,
                            dirCode = 1,
                            componentMaskCur = curInteriorMask,
                            componentMaskNeighbor = neighborInteriorMask,
                        )
                        if (condInterior > 0) enqueueInterior(n)

                        val neighborExteriorMask = voxelExteriorComponentMask?.let { masks ->
                            if (n in masks.indices) masks[n] else 0L
                        } ?: 0L
                        val condExterior = filteredEdgeCond(
                            idxCur = cur,
                            idxNeighbor = n,
                            lx = lx,
                            ly = ly,
                            lz = lz,
                            dirCode = 1,
                            componentMaskCur = curInteriorMask,
                            componentMaskNeighbor = neighborExteriorMask,
                        )
                        if (condExterior > 0) processHole(cur, lx, ly, lz, n, 1, condExterior)
                    } else {
                        val cond = edgeCond(cur, lx, ly, lz, 1)
                        if (cond > 0) {
                            if (interior.get(n)) enqueueInterior(n) else processHole(cur, lx, ly, lz, n, 1, cond)
                        }
                    }
                }
                if (ly > 0) {
                    val n = cur - strideY
                    if (hasTemplateConnectivity) {
                        val neighborInteriorMask = voxelInteriorComponentMask?.let { masks ->
                            if (n in masks.indices) masks[n] else 0L
                        } ?: 0L
                        val condInterior = filteredEdgeCond(
                            idxCur = cur,
                            idxNeighbor = n,
                            lx = lx,
                            ly = ly,
                            lz = lz,
                            dirCode = 2,
                            componentMaskCur = curInteriorMask,
                            componentMaskNeighbor = neighborInteriorMask,
                        )
                        if (condInterior > 0) enqueueInterior(n)

                        val neighborExteriorMask = voxelExteriorComponentMask?.let { masks ->
                            if (n in masks.indices) masks[n] else 0L
                        } ?: 0L
                        val condExterior = filteredEdgeCond(
                            idxCur = cur,
                            idxNeighbor = n,
                            lx = lx,
                            ly = ly,
                            lz = lz,
                            dirCode = 2,
                            componentMaskCur = curInteriorMask,
                            componentMaskNeighbor = neighborExteriorMask,
                        )
                        if (condExterior > 0) processHole(cur, lx, ly, lz, n, 2, condExterior)
                    } else {
                        val cond = edgeCond(cur, lx, ly, lz, 2)
                        if (cond > 0) {
                            if (interior.get(n)) enqueueInterior(n) else processHole(cur, lx, ly, lz, n, 2, cond)
                        }
                    }
                }
                if (ly + 1 < sizeY) {
                    val n = cur + strideY
                    if (hasTemplateConnectivity) {
                        val neighborInteriorMask = voxelInteriorComponentMask?.let { masks ->
                            if (n in masks.indices) masks[n] else 0L
                        } ?: 0L
                        val condInterior = filteredEdgeCond(
                            idxCur = cur,
                            idxNeighbor = n,
                            lx = lx,
                            ly = ly,
                            lz = lz,
                            dirCode = 3,
                            componentMaskCur = curInteriorMask,
                            componentMaskNeighbor = neighborInteriorMask,
                        )
                        if (condInterior > 0) enqueueInterior(n)

                        val neighborExteriorMask = voxelExteriorComponentMask?.let { masks ->
                            if (n in masks.indices) masks[n] else 0L
                        } ?: 0L
                        val condExterior = filteredEdgeCond(
                            idxCur = cur,
                            idxNeighbor = n,
                            lx = lx,
                            ly = ly,
                            lz = lz,
                            dirCode = 3,
                            componentMaskCur = curInteriorMask,
                            componentMaskNeighbor = neighborExteriorMask,
                        )
                        if (condExterior > 0) processHole(cur, lx, ly, lz, n, 3, condExterior)
                    } else {
                        val cond = edgeCond(cur, lx, ly, lz, 3)
                        if (cond > 0) {
                            if (interior.get(n)) enqueueInterior(n) else processHole(cur, lx, ly, lz, n, 3, cond)
                        }
                    }
                }
                if (lz > 0) {
                    val n = cur - strideZ
                    if (hasTemplateConnectivity) {
                        val neighborInteriorMask = voxelInteriorComponentMask?.let { masks ->
                            if (n in masks.indices) masks[n] else 0L
                        } ?: 0L
                        val condInterior = filteredEdgeCond(
                            idxCur = cur,
                            idxNeighbor = n,
                            lx = lx,
                            ly = ly,
                            lz = lz,
                            dirCode = 4,
                            componentMaskCur = curInteriorMask,
                            componentMaskNeighbor = neighborInteriorMask,
                        )
                        if (condInterior > 0) enqueueInterior(n)

                        val neighborExteriorMask = voxelExteriorComponentMask?.let { masks ->
                            if (n in masks.indices) masks[n] else 0L
                        } ?: 0L
                        val condExterior = filteredEdgeCond(
                            idxCur = cur,
                            idxNeighbor = n,
                            lx = lx,
                            ly = ly,
                            lz = lz,
                            dirCode = 4,
                            componentMaskCur = curInteriorMask,
                            componentMaskNeighbor = neighborExteriorMask,
                        )
                        if (condExterior > 0) processHole(cur, lx, ly, lz, n, 4, condExterior)
                    } else {
                        val cond = edgeCond(cur, lx, ly, lz, 4)
                        if (cond > 0) {
                            if (interior.get(n)) enqueueInterior(n) else processHole(cur, lx, ly, lz, n, 4, cond)
                        }
                    }
                }
                if (lz + 1 < sizeZ) {
                    val n = cur + strideZ
                    if (hasTemplateConnectivity) {
                        val neighborInteriorMask = voxelInteriorComponentMask?.let { masks ->
                            if (n in masks.indices) masks[n] else 0L
                        } ?: 0L
                        val condInterior = filteredEdgeCond(
                            idxCur = cur,
                            idxNeighbor = n,
                            lx = lx,
                            ly = ly,
                            lz = lz,
                            dirCode = 5,
                            componentMaskCur = curInteriorMask,
                            componentMaskNeighbor = neighborInteriorMask,
                        )
                        if (condInterior > 0) enqueueInterior(n)

                        val neighborExteriorMask = voxelExteriorComponentMask?.let { masks ->
                            if (n in masks.indices) masks[n] else 0L
                        } ?: 0L
                        val condExterior = filteredEdgeCond(
                            idxCur = cur,
                            idxNeighbor = n,
                            lx = lx,
                            ly = ly,
                            lz = lz,
                            dirCode = 5,
                            componentMaskCur = curInteriorMask,
                            componentMaskNeighbor = neighborExteriorMask,
                        )
                        if (condExterior > 0) processHole(cur, lx, ly, lz, n, 5, condExterior)
                    } else {
                        val cond = edgeCond(cur, lx, ly, lz, 5)
                        if (cond > 0) {
                            if (interior.get(n)) enqueueInterior(n) else processHole(cur, lx, ly, lz, n, 5, cond)
                        }
                    }
                }
            }

            hasAirVent = airVentConductance > 0

            if (seedCount > 0) {
                // If the component has no direct vent to outside air, model simple hydrostatic air compression so
                // sealed pockets can still flood more as they go deeper (and avoid "1-block-short" sideways fills).
                var pressurizedPlane = waterLevel
                if (!hasAirVent && bestSurfaceSampleIdx >= 0 && waterLevel.isFinite()) {
                    var sampleFluid: Fluid? = dominantFloodFluid
                    if (sampleFluid == null &&
                        level != null &&
                        shipTransform != null &&
                        shipBlockPos != null &&
                        shipPosTmp != null &&
                        worldPosTmp != null &&
                        worldBlockPos != null
                    ) {
                        val lx = bestSurfaceSampleIdx % sizeX
                        val t = bestSurfaceSampleIdx / sizeX
                        val ly = t % sizeY
                        val lz = t / sizeY
                        shipBlockPos.set(minX + lx, minY + ly, minZ + lz)
                        sampleFluid = getShipCellFluidCoverage(
                            level = level,
                            shipTransform = shipTransform,
                            shipBlockPos = shipBlockPos,
                            shipPosTmp = shipPosTmp,
                            worldPosTmp = worldPosTmp,
                            worldBlockPos = worldBlockPos,
                        ).canonicalFluid
                    }
                    if (sampleFluid != null) sampleFluid = canonicalFloodSource(sampleFluid)
                    if (sampleFluid != null) {
                        val precomputedSurfaceY = precomputedSurfaceYByCell?.let { arr ->
                            if (bestSurfaceSampleIdx in arr.indices) arr[bestSurfaceSampleIdx] else Double.NaN
                        }
                        var surfaceY: Double? =
                            if (precomputedSurfaceY != null && precomputedSurfaceY.isFinite()) precomputedSurfaceY else null

                        if (surfaceY == null &&
                            allowWorldSurfaceScan &&
                            level != null &&
                            shipTransform != null &&
                            shipBlockPos != null &&
                            shipPosTmp != null &&
                            worldPosTmp != null &&
                            worldBlockPos != null
                        ) {
                            val lx = bestSurfaceSampleIdx % sizeX
                            val t = bestSurfaceSampleIdx / sizeX
                            val ly = t % sizeY
                            val lz = t / sizeY
                            shipBlockPos.set(minX + lx, minY + ly, minZ + lz)
                            surfaceY = estimateExteriorFluidSurfaceY(
                                level = level,
                                shipTransform = shipTransform,
                                shipBlockPos = shipBlockPos,
                                sampleFluid = sampleFluid,
                                shipPosTmp = shipPosTmp,
                                worldPosTmp = worldPosTmp,
                                worldBlockPos = worldBlockPos,
                            )
                        }

                        if (surfaceY != null) {
                            val surfaceYClamped = maxOf(surfaceY, waterLevel)
                            val density = getBuoyancyFluidProps(sampleFluid).density
                            var plane = waterLevel
                            val totalVol = tail.toDouble()

                            repeat(AIR_PRESSURE_SOLVER_ITERS) {
                                var airCells = 0
                                for (i in 0 until tail) {
                                    val cellIdx = componentQueue[i]
                                    val cx = cellIdx % sizeX
                                    val ct = cellIdx / sizeX
                                    val cy = ct % sizeY
                                    val cz = ct / sizeY
                                    if (cellCenterWorldY(cx, cy, cz) > plane + AIR_PRESSURE_Y_EPS) {
                                        airCells++
                                    }
                                }

                                val effectiveAirVol =
                                    maxOf(airCells.toDouble(), AIR_PRESSURE_MIN_EFFECTIVE_AIR_VOLUME)
                                        .coerceAtMost(totalVol)
                                val pAir = AIR_PRESSURE_ATM * (totalVol / effectiveAirVol)

                                val planeNew =
                                    surfaceYClamped - (pAir - AIR_PRESSURE_ATM) / (density * AIR_PRESSURE_PER_BLOCK_PER_DENSITY)
                                // Damped relaxation: the discrete voxel air-volume function can cause oscillation.
                                val targetPlane = maxOf(waterLevel, planeNew).coerceAtMost(surfaceYClamped)
                                plane = plane * 0.5 + targetPlane * 0.5
                            }

                            pressurizedPlane = plane
                        }
                    }
                }

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
                    return wy <= pressurizedPlane + planeEps
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

                    if (lx > 0 && edgeCond(cur, lx, ly, lz, 0) > 0) tryEnqueueWater(cur - 1)
                    if (lx + 1 < sizeX && edgeCond(cur, lx, ly, lz, 1) > 0) tryEnqueueWater(cur + 1)
                    if (ly > 0 && edgeCond(cur, lx, ly, lz, 2) > 0) tryEnqueueWater(cur - strideY)
                    if (ly + 1 < sizeY && edgeCond(cur, lx, ly, lz, 3) > 0) tryEnqueueWater(cur + strideY)
                    if (lz > 0 && edgeCond(cur, lx, ly, lz, 4) > 0) tryEnqueueWater(cur - strideZ)
                    if (lz + 1 < sizeZ && edgeCond(cur, lx, ly, lz, 5) > 0) tryEnqueueWater(cur + strideZ)
                }
            }

            // Buoyancy accounting:
            // - Only *submerged* interior air displaces world water and contributes buoyancy.
            if (buoyancy != null) {
                for (i in 0 until tail) {
                    val cellIdx = componentQueue[i]
                    val coverage = submergedCoverage[cellIdx].coerceIn(0.0, 1.0)
                    if (coverage <= 1.0e-6) continue

                    val lx = cellIdx % sizeX
                    val t = cellIdx / sizeX
                    val ly = t % sizeY
                    val lz = t / sizeY

                    val sx = minX + lx + 0.5
                    val sy = minY + ly + 0.5
                    val sz = minZ + lz + 0.5

                    if (materializedWater != null && materializedWater.get(cellIdx)) continue

                    buoyancy.submergedAirVolume += coverage
                    buoyancy.submergedAirSumX += sx * coverage
                    buoyancy.submergedAirSumY += sy * coverage
                    buoyancy.submergedAirSumZ += sz * coverage
                }
            }

            start = interior.nextSetBit(start + 1)
        }

        return out
    }

    internal fun computeWaterReachableWithPressurePrepared(
        snapshot: WaterSolveSnapshot,
        out: BitSet,
        buoyancyOut: BuoyancyMetrics,
        floodFluidOut: AtomicReference<Fluid?>,
    ) {
        computeWaterReachableWithPressure(
            level = null,
            minX = snapshot.minX,
            minY = snapshot.minY,
            minZ = snapshot.minZ,
            sizeX = snapshot.sizeX,
            sizeY = snapshot.sizeY,
            sizeZ = snapshot.sizeZ,
            open = snapshot.open,
            interior = snapshot.interior,
            shipTransform = null,
            out = out,
            exteriorOpen = snapshot.exterior,
            buoyancyOut = buoyancyOut,
            materializedWater = snapshot.materializedWater,
            floodFluidOut = floodFluidOut,
            faceCondXP = snapshot.faceCondXP,
            faceCondYP = snapshot.faceCondYP,
            faceCondZP = snapshot.faceCondZP,
            templatePalette = snapshot.templatePalette,
            templateIndexByVoxel = snapshot.templateIndexByVoxel,
            voxelExteriorComponentMask = snapshot.voxelExteriorComponentMask,
            voxelInteriorComponentMask = snapshot.voxelInteriorComponentMask,
            precomputedSubmerged = snapshot.submerged,
            precomputedSubmergedCoverage = snapshot.submergedCoverage,
            precomputedDominantFloodFluid = snapshot.dominantFloodFluid,
            precomputedSurfaceYByCell = snapshot.surfaceYByCell,
            precomputedAffine = WorldYAffine(
                baseWorldY = snapshot.baseWorldY,
                incX = snapshot.incX,
                incY = snapshot.incY,
                incZ = snapshot.incZ,
            ),
            allowWorldSurfaceScan = false,
        )
    }

    private fun computeWaterReachable(
        level: Level,
        state: ShipPocketState,
        shipTransform: ShipTransform,
    ): BitSet {
        val buoyancyOut = if (level.isClientSide) null else state.buoyancy
        buoyancyOut?.reset()
        val floodFluidOut = AtomicReference<Fluid?>()
        val reachable = computeWaterReachableWithPressure(
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
            exteriorOpen = state.exterior,
            buoyancyOut = buoyancyOut,
            materializedWater = if (level.isClientSide) null else state.materializedWater,
            floodFluidOut = floodFluidOut,
            faceCondXP = state.faceCondXP,
            faceCondYP = state.faceCondYP,
            faceCondZP = state.faceCondZP,
            templatePalette = state.shapeTemplatePalette,
            templateIndexByVoxel = state.templateIndexByVoxel,
            voxelExteriorComponentMask = state.voxelExteriorComponentMask,
            voxelInteriorComponentMask = state.voxelInteriorComponentMask,
        )
        val floodFluid = floodFluidOut.get()
        if (floodFluid != null) {
            val canonical = canonicalFloodSource(floodFluid)
            if (canonical != state.floodFluid) {
                state.floodFluid = canonical
                // The active flood fluid changed (e.g. ship entered a different liquid). Re-scan shipyard blocks so our
                // cached flooded/materialized masks stay consistent.
                state.dirty = true
            }
        }
        state.unreachableVoid = state.open.clone() as BitSet
        state.unreachableVoid.andNot(reachable)
        return reachable
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
            exteriorOpen = null,
            faceCondXP = null,
            faceCondYP = null,
            faceCondZP = null,
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
        // NOTE: Even if *some* interior pockets are under exterior water pressure, other pockets may be above the
        // waterline and should still be able to drain out through openings to outside air. We handle this per interior
        // component below (see drainFloodedInteriorToOutsideAir).

        val sizeX = state.sizeX
        val sizeY = state.sizeY
        val sizeZ = state.sizeZ
        val volume = sizeX * sizeY * sizeZ

        val newPlanes = Int2DoubleOpenHashMap()
        val toAddAll = BitSet(volume)
        val toRemoveAll = BitSet(volume)

        if (!targetWetInterior.isEmpty) {
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
            } else {
                // Compute a fast affine map from local voxel coords -> world Y for this ship transform.
                val baseShipX = state.minX.toDouble()
                val baseShipY = state.minY.toDouble()
                val baseShipZ = state.minZ.toDouble()

                val shipPosTmp = tmpShipPos2.get()
                val worldPosTmp = tmpWorldPos2.get()

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

                val strideY = sizeX
                val strideZ = sizeX * sizeY

                val visited = tmpFloodComponentVisited.get()
                visited.clear()

                var queue = tmpFloodQueue.get()
                if (queue.size < volume) {
                    queue = IntArray(volume)
                    tmpFloodQueue.set(queue)
                }

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

                        fun tryNeighbor(n: Int, conductance: Int) {
                            if (conductance <= 0) return
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
                                submergedHoleFaces += conductance
                            }
                        }

                        if (lx > 0) tryNeighbor(idx - 1, edgeConductance(state, idx, lx, ly, lz, 0))
                        if (lx + 1 < sizeX) tryNeighbor(idx + 1, edgeConductance(state, idx, lx, ly, lz, 1))
                        if (ly > 0) tryNeighbor(idx - strideY, edgeConductance(state, idx, lx, ly, lz, 2))
                        if (ly + 1 < sizeY) tryNeighbor(idx + strideY, edgeConductance(state, idx, lx, ly, lz, 3))
                        if (lz > 0) tryNeighbor(idx - strideZ, edgeConductance(state, idx, lx, ly, lz, 4))
                        if (lz + 1 < sizeZ) tryNeighbor(idx + strideZ, edgeConductance(state, idx, lx, ly, lz, 5))
                    }

                    if (!targetPlane.isFinite()) return

                    val oldPlane = if (state.floodPlaneByComponent.containsKey(rep)) state.floodPlaneByComponent.get(rep) else minY
                    val floodRateMultiplier = ValkyrienAirConfig.shipPocketFloodRateMultiplier
                        .coerceIn(0.05, 5.0)
                    val rise = ((FLOOD_RISE_PER_TICK_BASE +
                        submergedHoleFaces.coerceAtLeast(1).toDouble() * FLOOD_RISE_PER_TICK_PER_HOLE_FACE)
                        .coerceAtMost(FLOOD_RISE_MAX_PER_TICK)) * floodRateMultiplier
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
                        if (wy <= newPlane + FLOOD_ENTER_PLANE_EPS) {
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
            }
        }

        // Drain any flooded interior components which are no longer under exterior water pressure.
        drainFloodedInteriorToOutsideAir(
            level,
            state,
            shipTransform,
            protectedInterior = targetWetInterior,
            newPlanesOut = newPlanes,
            toRemoveAll = toRemoveAll,
        )

        state.floodPlaneByComponent = newPlanes

        enqueueFloodWriteDiffs(state, toAddAll, toRemoveAll)
    }

    private fun drainFloodedInteriorToOutsideAir(
        level: ServerLevel,
        state: ShipPocketState,
        shipTransform: ShipTransform,
        protectedInterior: BitSet?,
        newPlanesOut: Int2DoubleOpenHashMap,
        toRemoveAll: BitSet,
    ) {
        val open = state.open
        val interior = state.interior
        val materialized = state.materializedWater
        if (open.isEmpty || materialized.isEmpty) {
            return
        }

        val sizeX = state.sizeX
        val sizeY = state.sizeY
        val sizeZ = state.sizeZ
        val volume = sizeX * sizeY * sizeZ
        val strideY = sizeX
        val strideZ = sizeX * sizeY

        // Exterior open-space mask (connected to the sim bounds), used to avoid treating enclosed cavities as vents.
        val exteriorOpen = state.exterior

        // Compute a fast affine map from local voxel coords -> world Y for this ship transform.
        val baseShipX = state.minX.toDouble()
        val baseShipY = state.minY.toDouble()
        val baseShipZ = state.minZ.toDouble()

        val shipPosTmp = tmpShipPos2.get()
        val worldPosTmp = tmpWorldPos2.get()

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

        fun openingFaceMinWorldY(lx: Int, ly: Int, lz: Int, outDirCode: Int): Double {
            val x0 = lx.toDouble()
            val y0 = ly.toDouble()
            val z0 = lz.toDouble()
            val x1 = x0 + 1.0
            val y1 = y0 + 1.0
            val z1 = z0 + 1.0
            fun wy(x: Double, y: Double, z: Double): Double = baseWorldY + incX * x + incY * y + incZ * z
            return when (outDirCode) {
                0 -> minOf(wy(x0, y0, z0), wy(x0, y1, z0), wy(x0, y0, z1), wy(x0, y1, z1))
                1 -> minOf(wy(x1, y0, z0), wy(x1, y1, z0), wy(x1, y0, z1), wy(x1, y1, z1))
                2 -> minOf(wy(x0, y0, z0), wy(x1, y0, z0), wy(x0, y0, z1), wy(x1, y0, z1))
                3 -> minOf(wy(x0, y1, z0), wy(x1, y1, z0), wy(x0, y1, z1), wy(x1, y1, z1))
                4 -> minOf(wy(x0, y0, z0), wy(x1, y0, z0), wy(x0, y1, z0), wy(x1, y1, z0))
                else -> minOf(wy(x0, y0, z1), wy(x1, y0, z1), wy(x0, y1, z1), wy(x1, y1, z1))
            }
        }

        val visited = tmpFloodComponentVisited.get()
        visited.clear()

        var queue = tmpFloodQueue.get()
        if (queue.size < volume) {
            queue = IntArray(volume)
            tmpFloodQueue.set(queue)
        }

        val shipBlockPos = BlockPos.MutableBlockPos()
        val shipPosCornerTmp = tmpShipPos3.get()
        val worldPosCornerTmp = tmpWorldPos3.get()
        val worldBlockPos = BlockPos.MutableBlockPos()
        var drainParticleBudget = 2
        fun spawnDrainParticles(ventIdx: Int, outDirCode: Int, conductance: Int) {
            if (drainParticleBudget <= 0) return
            if (ventIdx < 0 || ventIdx >= volume) return
            if (conductance <= 0) return
            drainParticleBudget--

            val particle = leakParticleForFluid(state.floodFluid)
            val particleCount = (2 + conductance / 12).coerceIn(2, 10)
            val particleSpeedMultiplier = ValkyrienAirConfig.shipPocketParticleSpeedMultiplier.coerceIn(0.1, 5.0)
            val speed = ((0.10 + conductance * 0.00035).coerceIn(0.10, 0.18)) * particleSpeedMultiplier
            emitDirectionalLeakParticles(
                level = level,
                state = state,
                shipTransform = shipTransform,
                cellIdx = ventIdx,
                faceDirCode = outDirCode xor 1,
                jetDirCode = outDirCode,
                particle = particle,
                particleCount = particleCount,
                baseSpeed = speed,
            )
        }

        fun scanComponent(start: Int) {
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited.set(start)

            var rep = start
            var hasProtected = false
            var currentTop = Double.NEGATIVE_INFINITY
            var drainTarget = Double.POSITIVE_INFINITY
            var drainFaces = 0
            var bestVentIdx = -1
            var bestVentOutDirCode = 0
            var bestVentConductance = 0

            fun considerVent(
                holeIdx: Int,
                outDirCode: Int,
                fromWaterWy: Double,
                waterLX: Int,
                waterLY: Int,
                waterLZ: Int,
                fromWaterIdx: Int,
                conductance: Int,
            ) {
                if (conductance <= 0) return
                if (!open.get(holeIdx) || !exteriorOpen.get(holeIdx)) return
                val lx = holeIdx % sizeX
                val t = holeIdx / sizeX
                val ly = t % sizeY
                val lz = t / sizeY
                val isBoundaryCell = lx == 0 || lx + 1 == sizeX || ly == 0 || ly + 1 == sizeY || lz == 0 || lz + 1 == sizeZ
                // Keep vent detection strict to avoid draining through arbitrary interior air cells:
                // allow exterior-open cells, but if geometry heuristics still classify them as interior,
                // only accept them when they are on the simulation boundary shell.
                if (interior.get(holeIdx) && !isBoundaryCell) return

                val shipX = state.minX + lx
                val shipY = state.minY + ly
                val shipZ = state.minZ + lz
                shipBlockPos.set(shipX, shipY, shipZ)

                // A vent must open into outside *air* (not submerged in world water).
                if (isShipCellSubmergedInWorldFluid(level, shipTransform, shipBlockPos, shipPosCornerTmp, worldPosCornerTmp, worldBlockPos)) return
                // ...and must actually open into *air*, not terrain/solid blocks (e.g. when the ship rests on the sea floor).
                // Otherwise we'd incorrectly "flush" water just because the outside isn't liquid.
                run {
                    shipPosCornerTmp.set(shipX.toDouble() + 0.5, shipY.toDouble() + 0.5, shipZ.toDouble() + 0.5)
                    shipTransform.shipToWorld.transformPosition(shipPosCornerTmp, worldPosCornerTmp)
                    worldBlockPos.set(
                        Mth.floor(worldPosCornerTmp.x),
                        Mth.floor(worldPosCornerTmp.y),
                        Mth.floor(worldPosCornerTmp.z),
                    )
                    if (!level.getBlockState(worldBlockPos).isAir) return
                }

                // Water can't "flush" out through an opening that's above the draining water cell in world-space.
                // This fixes bowls/open-top containers losing water upward when moved out of the ocean.
                val holeWy = openingFaceMinWorldY(waterLX, waterLY, waterLZ, outDirCode)
                if (holeWy > fromWaterWy + 1.0e-6) return

                val filteredConductance = conductance
                if (filteredConductance <= 0) return

                drainFaces += filteredConductance
                if (holeWy < drainTarget) {
                    drainTarget = holeWy
                    bestVentIdx = holeIdx
                    bestVentOutDirCode = outDirCode
                    bestVentConductance = filteredConductance
                }
            }

            while (head < tail) {
                val idx = queue[head++]
                if (idx < rep) rep = idx
                if (!hasProtected && protectedInterior != null && protectedInterior.get(idx)) {
                    hasProtected = true
                }

                val lx = idx % sizeX
                val t = idx / sizeX
                val ly = t % sizeY
                val lz = t / sizeY

                val isWaterCell = !hasProtected && materialized.get(idx)
                val waterWy = if (isWaterCell) cellCenterWorldY(lx, ly, lz) else Double.NaN
                if (isWaterCell && waterWy > currentTop) currentTop = waterWy

                fun tryNeighbor(n: Int, outDirCode: Int, conductance: Int) {
                    if (conductance <= 0) return
                    if (n < 0 || n >= volume) return
                    if (!open.get(n)) return
                    if (!visited.get(n)) {
                        visited.set(n)
                        queue[tail++] = n
                    }
                    if (isWaterCell && exteriorOpen.get(n)) {
                        considerVent(n, outDirCode, waterWy, lx, ly, lz, idx, conductance)
                    }
                }

                if (lx > 0) tryNeighbor(idx - 1, 0, edgeConductance(state, idx, lx, ly, lz, 0))
                if (lx + 1 < sizeX) tryNeighbor(idx + 1, 1, edgeConductance(state, idx, lx, ly, lz, 1))
                if (ly > 0) tryNeighbor(idx - strideY, 2, edgeConductance(state, idx, lx, ly, lz, 2))
                if (ly + 1 < sizeY) tryNeighbor(idx + strideY, 3, edgeConductance(state, idx, lx, ly, lz, 3))
                if (lz > 0) tryNeighbor(idx - strideZ, 4, edgeConductance(state, idx, lx, ly, lz, 4))
                if (lz + 1 < sizeZ) tryNeighbor(idx + strideZ, 5, edgeConductance(state, idx, lx, ly, lz, 5))
            }

            if (hasProtected) return
            if (!currentTop.isFinite()) return
            if (!drainTarget.isFinite() || drainFaces <= 0) return

            val oldPlane =
                if (state.floodPlaneByComponent.containsKey(rep)) state.floodPlaneByComponent.get(rep) else currentTop

            // Draining speed matches flooding speed profile (same conductance scaling + same config multiplier).
            val floodRateMultiplier = ValkyrienAirConfig.shipPocketFloodRateMultiplier.coerceIn(0.05, 5.0)
            val drainRate = ((FLOOD_RISE_PER_TICK_BASE +
                drainFaces.toDouble() * FLOOD_RISE_PER_TICK_PER_HOLE_FACE)
                .coerceAtMost(FLOOD_RISE_MAX_PER_TICK)) * floodRateMultiplier
            val newPlane = maxOf(drainTarget, oldPlane - drainRate)
            newPlanesOut.put(rep, newPlane)

            if (bestVentIdx >= 0 && oldPlane - newPlane > 1.0e-6) {
                spawnDrainParticles(bestVentIdx, bestVentOutDirCode, bestVentConductance)
            }

            for (i in 0 until tail) {
                val idx = queue[i]
                if (!materialized.get(idx)) continue

                val lx = idx % sizeX
                val t = idx / sizeX
                val ly = t % sizeY
                val lz = t / sizeY
                val wy = cellCenterWorldY(lx, ly, lz)
                if (wy > newPlane + FLOOD_EXIT_PLANE_EPS) {
                    toRemoveAll.set(idx)
                }
            }
        }

        var start = materialized.nextSetBit(0)
        while (start >= 0 && start < volume) {
            if (!open.get(start) || visited.get(start)) {
                start = materialized.nextSetBit(start + 1)
                continue
            }
            scanComponent(start)
            start = materialized.nextSetBit(start + 1)
        }
    }

    private fun stabilizeFloodedWater(level: ServerLevel, state: ShipPocketState, targetWetInterior: BitSet) {
        if (targetWetInterior.isEmpty) return

        val flags = 11 // 1 (block update) + 2 (send to clients) + 8 (force rerender)
        val pos = BlockPos.MutableBlockPos()
        val sourceBlockState = state.floodFluid.defaultFluidState().createLegacyBlock()

        applyingInternalUpdates = true
        try {
            var idx = targetWetInterior.nextSetBit(0)
            while (idx >= 0) {
                posFromIndex(state, idx, pos)
                val bs = level.getBlockState(pos)
                val fluidState = bs.fluidState
                if (!fluidState.isEmpty &&
                    canonicalFloodSource(fluidState.type) == state.floodFluid &&
                    !fluidState.isSource
                ) {
                    level.setBlock(pos, sourceBlockState, flags)
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

        val worldPosTmp = tmpWorldPos2.get()
        val shipPosTmp = tmpShipPos2.get()
        val shipBlockPos = BlockPos.MutableBlockPos()
        val worldBlockPos = BlockPos.MutableBlockPos()

        fun shipCellSubmerged(idx: Int): Boolean {
            posFromIndex(state, idx, shipBlockPos)
            return isShipCellSubmergedInWorldFluid(level, shipTransform, shipBlockPos, shipPosTmp, worldPosTmp, worldBlockPos)
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
            if (lx > 0 && edgeConductance(state, cur, lx, ly, lz, 0) > 0) tryEnqueue(cur - 1, requireSubmerged = true)
            if (lx + 1 < sizeX && edgeConductance(state, cur, lx, ly, lz, 1) > 0) tryEnqueue(cur + 1, requireSubmerged = true)
            if (ly > 0 && edgeConductance(state, cur, lx, ly, lz, 2) > 0) tryEnqueue(cur - strideY, requireSubmerged = true)
            if (ly + 1 < sizeY && edgeConductance(state, cur, lx, ly, lz, 3) > 0) tryEnqueue(cur + strideY, requireSubmerged = true)
            if (lz > 0 && edgeConductance(state, cur, lx, ly, lz, 4) > 0) tryEnqueue(cur - strideZ, requireSubmerged = true)
            if (lz + 1 < sizeZ && edgeConductance(state, cur, lx, ly, lz, 5) > 0) tryEnqueue(cur + strideZ, requireSubmerged = true)
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
        val sourceBlockState = state.floodFluid.defaultFluidState().createLegacyBlock()

        val worldPosTmp = tmpWorldPos2.get()
        val shipPosTmp = tmpShipPos2.get()
        val worldBlockPos = BlockPos.MutableBlockPos()

        applyingInternalUpdates = true
        try {
            var idx = indices.nextSetBit(0)
            while (idx >= 0) {
                posFromIndex(state, idx, pos)

                val current = level.getBlockState(pos)
                if (toWater) {
                    if (shipTransform != null) {
                        val submergedSample = getShipCellFluidCoverage(
                            level,
                            shipTransform,
                            pos,
                            shipPosTmp,
                            worldPosTmp,
                            worldBlockPos
                        )
                        val submergedFluid = submergedSample.canonicalFluid
                        if (!submergedSample.isIngressQualified() || submergedFluid == null || canonicalFloodSource(submergedFluid) != state.floodFluid) {
                            idx = indices.nextSetBit(idx + 1)
                            continue
                        }
                    }

                    val currentFluid = current.fluidState
                    if (!currentFluid.isEmpty && canonicalFloodSource(currentFluid.type) == state.floodFluid) {
                        state.materializedWater.set(idx)
                    } else if (current.isAir) {
                        level.setBlock(pos, sourceBlockState, flags)
                        state.materializedWater.set(idx)
                    } else if (isWaterloggableForFlood(current, state.floodFluid)) {
                        if (!current.getValue(BlockStateProperties.WATERLOGGED)) {
                            level.setBlock(pos, current.setValue(BlockStateProperties.WATERLOGGED, true), flags)
                            level.scheduleTick(pos, Fluids.WATER, 1)
                        }
                        state.materializedWater.set(idx)
                    }
                } else {
                    val currentFluid = current.fluidState
                    if (current.block is LiquidBlock &&
                        !currentFluid.isEmpty &&
                        canonicalFloodSource(currentFluid.type) == state.floodFluid
                    ) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags)
                        level.scheduleTick(pos, state.floodFluid, 1)
                        state.materializedWater.clear(idx)
                    } else if (isWaterloggableForFlood(current, state.floodFluid) &&
                        current.getValue(BlockStateProperties.WATERLOGGED)
                    ) {
                        level.setBlock(pos, current.setValue(BlockStateProperties.WATERLOGGED, false), flags)
                        level.scheduleTick(pos, Fluids.WATER, 1)
                        state.materializedWater.clear(idx)
                    } else {
                        state.materializedWater.clear(idx)
                    }
                }

                idx = indices.nextSetBit(idx + 1)
            }
        } finally {
            applyingInternalUpdates = false
        }
    }

    private fun isShipCellSubmergedInWorldFluid(
        level: Level,
        shipTransform: ShipTransform,
        shipBlockPos: BlockPos,
        shipPosTmp: Vector3d,
        worldPosTmp: Vector3d,
        worldBlockPos: BlockPos.MutableBlockPos,
    ): Boolean {
        return getShipCellFluidCoverage(level, shipTransform, shipBlockPos, shipPosTmp, worldPosTmp, worldBlockPos)
            .isIngressQualified()
    }

    private fun getShipCellSubmergedWorldFluidType(
        level: Level,
        shipTransform: ShipTransform,
        shipBlockPos: BlockPos,
        shipPosTmp: Vector3d,
        worldPosTmp: Vector3d,
        worldBlockPos: BlockPos.MutableBlockPos,
    ): Fluid? {
        val sample = getShipCellFluidCoverage(level, shipTransform, shipBlockPos, shipPosTmp, worldPosTmp, worldBlockPos)
        return if (sample.isIngressQualified()) sample.canonicalFluid else null
    }

    private fun getShipCellFluidCoverage(
        level: Level,
        shipTransform: ShipTransform,
        shipBlockPos: BlockPos,
        shipPosTmp: Vector3d,
        worldPosTmp: Vector3d,
        worldBlockPos: BlockPos.MutableBlockPos,
    ): FluidCoverageSample {
        return withBypassedFluidOverrides {
            val epsCorner = 1e-4
            val epsY = 1e-5
            val sampledFluids = arrayOfNulls<Fluid>(9)
            val sampledFluidCounts = IntArray(9)
            var sampledFluidCount = 0
            var centerCanonical: Fluid? = null
            var centerSubmerged = false
            var submergedSamples = 0

            fun sample(shipX: Double, shipY: Double, shipZ: Double): Fluid? {
                shipPosTmp.set(shipX, shipY, shipZ)
                shipTransform.shipToWorld.transformPosition(shipPosTmp, worldPosTmp)

                val wx = Mth.floor(worldPosTmp.x)
                val wy = Mth.floor(worldPosTmp.y)
                val wz = Mth.floor(worldPosTmp.z)
                worldBlockPos.set(wx, wy, wz)

                val worldFluid = level.getFluidState(worldBlockPos)
                if (worldFluid.isEmpty) return null
                if (worldFluid.isSource) return canonicalFloodSource(worldFluid.type)

                val height = worldFluid.getHeight(level, worldBlockPos).toDouble()
                val localY = worldPosTmp.y - wy.toDouble()
                return if (localY <= height + epsY) canonicalFloodSource(worldFluid.type) else null
            }

            fun registerSample(fluid: Fluid?) {
                if (fluid == null) return
                submergedSamples++
                for (i in 0 until sampledFluidCount) {
                    if (sampledFluids[i] == fluid) {
                        sampledFluidCounts[i]++
                        return
                    }
                }
                if (sampledFluidCount < sampledFluids.size) {
                    sampledFluids[sampledFluidCount] = fluid
                    sampledFluidCounts[sampledFluidCount] = 1
                    sampledFluidCount++
                } else {
                    // Should never happen (9 samples max), but keep deterministic behavior.
                    sampledFluidCounts[0]++
                }
            }

            val x0 = shipBlockPos.x.toDouble()
            val y0 = shipBlockPos.y.toDouble()
            val z0 = shipBlockPos.z.toDouble()

            centerCanonical = sample(x0 + 0.5, y0 + 0.5, z0 + 0.5)
            centerSubmerged = centerCanonical != null
            registerSample(centerCanonical)

            // Near the fluid surface / with rotation, the cell center can be above fluid while a corner is submerged.
            val lo = epsCorner
            val hi = 1.0 - epsCorner
            registerSample(sample(x0 + lo, y0 + lo, z0 + lo))
            registerSample(sample(x0 + hi, y0 + lo, z0 + lo))
            registerSample(sample(x0 + lo, y0 + hi, z0 + lo))
            registerSample(sample(x0 + hi, y0 + hi, z0 + lo))
            registerSample(sample(x0 + lo, y0 + lo, z0 + hi))
            registerSample(sample(x0 + hi, y0 + lo, z0 + hi))
            registerSample(sample(x0 + lo, y0 + hi, z0 + hi))
            registerSample(sample(x0 + hi, y0 + hi, z0 + hi))

            if (submergedSamples == 0) {
                return@withBypassedFluidOverrides FluidCoverageSample(
                    canonicalFluid = null,
                    coverageRatio = 0.0,
                    centerSubmerged = false,
                )
            }

            var bestFluid: Fluid? = null
            var bestCount = 0
            for (i in 0 until sampledFluidCount) {
                val fluid = sampledFluids[i] ?: continue
                val count = sampledFluidCounts[i]
                if (count > bestCount) {
                    bestCount = count
                    bestFluid = fluid
                } else if (count == bestCount && centerCanonical != null && fluid == centerCanonical) {
                    bestFluid = fluid
                }
            }

            if (bestFluid == null || bestCount <= 0) {
                val c = coverageFallbackDiagCount.incrementAndGet()
                if (c <= 3L || c % 500L == 0L) {
                    log.debug("Fluid coverage fallback triggered (shipCell={}, samples={})", shipBlockPos, submergedSamples)
                }
                return@withBypassedFluidOverrides FluidCoverageSample(
                    canonicalFluid = null,
                    coverageRatio = 0.0,
                    centerSubmerged = false,
                )
            }

            var ratio = bestCount / 9.0
            if (!ratio.isFinite()) ratio = 0.0
            ratio = ratio.coerceIn(0.0, 1.0)

            return@withBypassedFluidOverrides FluidCoverageSample(
                canonicalFluid = bestFluid,
                coverageRatio = ratio,
                centerSubmerged = centerCanonical != null && bestFluid == centerCanonical && centerSubmerged,
            )
        }
    }

}
