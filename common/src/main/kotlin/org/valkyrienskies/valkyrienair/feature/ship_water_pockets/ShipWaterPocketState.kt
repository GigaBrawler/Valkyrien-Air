package org.valkyrienskies.valkyrienair.feature.ship_water_pockets

import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap
import net.minecraft.core.Direction
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.Fluids
import java.util.BitSet
import java.util.concurrent.CompletableFuture

internal data class ShipPocketState(
    var minX: Int = 0,
    var minY: Int = 0,
    var minZ: Int = 0,
    var sizeX: Int = 0,
    var sizeY: Int = 0,
    var sizeZ: Int = 0,
    var open: BitSet = BitSet(),
    var exterior: BitSet = BitSet(),
    var interior: BitSet = BitSet(),
    var floodFluid: Fluid = Fluids.WATER,
    var flooded: BitSet = BitSet(),
    var materializedWater: BitSet = BitSet(),
    var waterReachable: BitSet = BitSet(),
    var unreachableVoid: BitSet = BitSet(),
    // Face conductance masks (shape-aware connectivity), stored on positive axes only.
    var faceCondXP: ShortArray = ShortArray(0),
    var faceCondYP: ShortArray = ShortArray(0),
    var faceCondZP: ShortArray = ShortArray(0),
    var buoyancy: BuoyancyMetrics = BuoyancyMetrics(),
    var floodPlaneByComponent: Int2DoubleOpenHashMap = Int2DoubleOpenHashMap(),
    var geometryRevision: Long = 0,
    var geometryInvalidationStamp: Long = 0,
    var pendingGeometryFuture: CompletableFuture<GeometryAsyncResult>? = null,
    var requestedGeometryGeneration: Long = 0,
    var appliedGeometryGeneration: Long = 0,
    var geometryJobInFlight: Boolean = false,
    var geometryLastComputeNanos: Long = 0,
    var geometryComputeCount: Long = 0,
    var queuedFloodAdds: BitSet = BitSet(),
    var queuedFloodRemoves: BitSet = BitSet(),
    var nextQueuedAddIdx: Int = 0,
    var nextQueuedRemoveIdx: Int = 0,
    var dirty: Boolean = true,
    var lastFloodUpdateTick: Long = Long.MIN_VALUE,
    var lastWaterReachableUpdateTick: Long = Long.MIN_VALUE,
    var lastMaterializedResyncTick: Long = Long.MIN_VALUE,
    // Ship "gravity" for shipyard fluids is discrete (one of the 6 directions). When it changes due to ship
    // rotation, vanilla fluids won't tick automatically; schedule a budgeted wave of fluid ticks so they resettle.
    var lastGravityDownDir: Direction? = null,
    var pendingGravityResettleNextIdx: Int = -1,
)

internal data class BuoyancyMetrics(
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
