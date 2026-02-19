package org.valkyrienskies.valkyrienair.feature.ship_water_pockets

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.material.FlowingFluid
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.Fluids
import java.util.BitSet

internal data class GeometryAsyncSnapshot(
    val generation: Long,
    val invalidationStamp: Long,
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val floodFluid: Fluid,
    val blockStates: Array<BlockState>,
    val shapeGeometry: Array<ShapeWaterGeometry>,
)

internal data class GeometryAsyncResult(
    val generation: Long,
    val invalidationStamp: Long,
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val open: BitSet,
    val exterior: BitSet,
    val interior: BitSet,
    val flooded: BitSet,
    val materializedWater: BitSet,
    val faceCondXP: ShortArray,
    val faceCondYP: ShortArray,
    val faceCondZP: ShortArray,
    val computeNanos: Long,
)

private val EMPTY_GEOMETRY = ShapeWaterGeometry(
    fullSolid = false,
    refined = false,
    boxes = emptyList(),
)

private fun canonicalFloodSource(fluid: Fluid): Fluid {
    return if (fluid is FlowingFluid) fluid.source else fluid
}

private fun isWaterloggableForFlood(state: BlockState, floodFluid: Fluid): Boolean {
    return canonicalFloodSource(floodFluid) == Fluids.WATER && state.hasProperty(BlockStateProperties.WATERLOGGED)
}

internal fun captureGeometryAsyncSnapshot(
    level: Level,
    generation: Long,
    invalidationStamp: Long,
    minX: Int,
    minY: Int,
    minZ: Int,
    sizeX: Int,
    sizeY: Int,
    sizeZ: Int,
    floodFluid: Fluid,
): GeometryAsyncSnapshot {
    val volume = sizeX * sizeY * sizeZ
    val blockStates = Array(volume) { Blocks.AIR.defaultBlockState() }
    val shapeGeometry = Array(volume) { EMPTY_GEOMETRY }

    val pos = BlockPos.MutableBlockPos()
    var idx = 0
    for (z in 0 until sizeZ) {
        for (y in 0 until sizeY) {
            for (x in 0 until sizeX) {
                pos.set(minX + x, minY + y, minZ + z)
                val state = level.getBlockState(pos)
                blockStates[idx] = state
                shapeGeometry[idx] = computeShapeWaterGeometry(level, pos, state)
                idx++
            }
        }
    }

    return GeometryAsyncSnapshot(
        generation = generation,
        invalidationStamp = invalidationStamp,
        minX = minX,
        minY = minY,
        minZ = minZ,
        sizeX = sizeX,
        sizeY = sizeY,
        sizeZ = sizeZ,
        floodFluid = floodFluid,
        blockStates = blockStates,
        shapeGeometry = shapeGeometry,
    )
}

internal fun computeGeometryAsync(snapshot: GeometryAsyncSnapshot): GeometryAsyncResult {
    val startNanos = System.nanoTime()

    val sizeX = snapshot.sizeX
    val sizeY = snapshot.sizeY
    val sizeZ = snapshot.sizeZ
    val volume = sizeX * sizeY * sizeZ

    val open = BitSet(volume)
    val flooded = BitSet(volume)
    val materialized = BitSet(volume)
    val faceCondXP = ShortArray(volume)
    val faceCondYP = ShortArray(volume)
    val faceCondZP = ShortArray(volume)

    var idx = 0
    for (z in 0 until sizeZ) {
        for (y in 0 until sizeY) {
            for (x in 0 until sizeX) {
                val geom = snapshot.shapeGeometry[idx]
                if (!geom.fullSolid && hasOpenVolume(geom)) {
                    open.set(idx)
                }

                val bs = snapshot.blockStates[idx]
                val fluidState = bs.fluidState
                if (!fluidState.isEmpty && canonicalFloodSource(fluidState.type) == snapshot.floodFluid) {
                    flooded.set(idx)
                    if (
                        bs.block is LiquidBlock ||
                        (isWaterloggableForFlood(bs, snapshot.floodFluid) &&
                            bs.getValue(BlockStateProperties.WATERLOGGED))
                    ) {
                        materialized.set(idx)
                    }
                }

                idx++
            }
        }
    }

    val strideY = sizeX
    val strideZ = sizeX * sizeY

    idx = 0
    for (z in 0 until sizeZ) {
        for (y in 0 until sizeY) {
            for (x in 0 until sizeX) {
                if (!open.get(idx)) {
                    idx++
                    continue
                }

                if (x + 1 < sizeX) {
                    val n = idx + 1
                    if (open.get(n)) {
                        faceCondXP[idx] = computeFaceConductance(
                            snapshot.shapeGeometry[idx],
                            snapshot.shapeGeometry[n],
                            axis = 0
                        ).toShort()
                    }
                }
                if (y + 1 < sizeY) {
                    val n = idx + strideY
                    if (open.get(n)) {
                        faceCondYP[idx] = computeFaceConductance(
                            snapshot.shapeGeometry[idx],
                            snapshot.shapeGeometry[n],
                            axis = 1
                        ).toShort()
                    }
                }
                if (z + 1 < sizeZ) {
                    val n = idx + strideZ
                    if (open.get(n)) {
                        faceCondZP[idx] = computeFaceConductance(
                            snapshot.shapeGeometry[idx],
                            snapshot.shapeGeometry[n],
                            axis = 2
                        ).toShort()
                    }
                }

                idx++
            }
        }
    }

    fun edgeCond(idxCur: Int, lx: Int, ly: Int, lz: Int, dirCode: Int): Int {
        return when (dirCode) {
            0 -> if (lx > 0) faceCondXP[idxCur - 1].toInt() and 0xFFFF else 0
            1 -> if (lx + 1 < sizeX) faceCondXP[idxCur].toInt() and 0xFFFF else 0
            2 -> if (ly > 0) faceCondYP[idxCur - strideY].toInt() and 0xFFFF else 0
            3 -> if (ly + 1 < sizeY) faceCondYP[idxCur].toInt() and 0xFFFF else 0
            4 -> if (lz > 0) faceCondZP[idxCur - strideZ].toInt() and 0xFFFF else 0
            else -> if (lz + 1 < sizeZ) faceCondZP[idxCur].toInt() and 0xFFFF else 0
        }
    }

    val strictExterior = floodFillFromBoundaryGraph(open, sizeX, sizeY, sizeZ) { idxCur, lx, ly, lz, dir ->
        edgeCond(idxCur, lx, ly, lz, dir)
    }
    val strictInterior = open.clone() as BitSet
    strictInterior.andNot(strictExterior)
    val heuristicInterior = computeInteriorMaskHeuristic(open, sizeX, sizeY, sizeZ)
    strictInterior.or(heuristicInterior)

    return GeometryAsyncResult(
        generation = snapshot.generation,
        invalidationStamp = snapshot.invalidationStamp,
        minX = snapshot.minX,
        minY = snapshot.minY,
        minZ = snapshot.minZ,
        sizeX = sizeX,
        sizeY = sizeY,
        sizeZ = sizeZ,
        open = open,
        exterior = strictExterior,
        interior = strictInterior,
        flooded = flooded,
        materializedWater = materialized,
        faceCondXP = faceCondXP,
        faceCondYP = faceCondYP,
        faceCondZP = faceCondZP,
        computeNanos = System.nanoTime() - startNanos,
    )
}
