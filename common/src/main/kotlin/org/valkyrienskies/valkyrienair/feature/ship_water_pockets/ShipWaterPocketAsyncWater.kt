package org.valkyrienskies.valkyrienair.feature.ship_water_pockets

import net.minecraft.world.level.material.Fluid
import java.util.BitSet
import java.util.concurrent.atomic.AtomicReference

internal data class WaterSolveSnapshot(
    val generation: Long,
    val geometryRevision: Long,
    val captureTick: Long,
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val open: BitSet,
    val interior: BitSet,
    val exterior: BitSet,
    val materializedWater: BitSet,
    val floodFluid: Fluid,
    val faceCondXP: ShortArray,
    val faceCondYP: ShortArray,
    val faceCondZP: ShortArray,
    val templatePalette: List<ShapeCellTemplate>,
    val templateIndexByVoxel: IntArray,
    val voxelExteriorComponentMask: LongArray,
    val voxelInteriorComponentMask: LongArray,
    val submerged: BitSet,
    val submergedCoverage: DoubleArray,
    val dominantFloodFluid: Fluid?,
    val surfaceYByCell: DoubleArray,
    val baseWorldY: Double,
    val incX: Double,
    val incY: Double,
    val incZ: Double,
)

internal data class FloodPlanResult(
    val toAdd: BitSet = BitSet(),
    val toRemove: BitSet = BitSet(),
    val newPlanes: it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap =
        it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap(),
)

internal data class WaterSolveResult(
    val generation: Long,
    val geometryRevision: Long,
    val captureTick: Long,
    val computedTick: Long,
    val waterReachable: BitSet,
    val unreachableVoid: BitSet,
    val buoyancy: BuoyancyMetrics,
    val floodFluid: Fluid?,
    val computeNanos: Long,
    val floodPlan: FloodPlanResult? = null,
)

internal fun computeWaterSolveAsync(snapshot: WaterSolveSnapshot): WaterSolveResult {
    val startNanos = System.nanoTime()
    val out = BitSet(snapshot.sizeX * snapshot.sizeY * snapshot.sizeZ)
    val buoyancy = BuoyancyMetrics()
    // Start unset so the solver can publish the dominant submerged canonical fluid for this tick.
    // If no fluid is detected, keep the previous flood fluid to avoid unnecessary churn.
    val floodFluidOut = AtomicReference<Fluid?>(null)

    ShipWaterPocketManager.computeWaterReachableWithPressurePrepared(
        snapshot = snapshot,
        out = out,
        buoyancyOut = buoyancy,
        floodFluidOut = floodFluidOut,
    )

    val unreachable = snapshot.open.clone() as BitSet
    unreachable.andNot(out)

    val computeNanos = System.nanoTime() - startNanos
    return WaterSolveResult(
        generation = snapshot.generation,
        geometryRevision = snapshot.geometryRevision,
        captureTick = snapshot.captureTick,
        computedTick = snapshot.captureTick,
        waterReachable = out,
        unreachableVoid = unreachable,
        buoyancy = buoyancy,
        floodFluid = floodFluidOut.get() ?: snapshot.floodFluid,
        computeNanos = computeNanos,
    )
}
