package org.valkyrienskies.valkyrienair.client

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BiomeColors
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import org.valkyrienskies.mod.common.hooks.VSGameEvents
import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.ShipWaterPocketCurrentShipRenderContext
import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.ShipWaterPocketExternalWaterCullRenderContext
import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.ShipWaterPocketShipWaterTintRenderContext

/**
 * The common static client object that represents the mod.
 */
object ValkyrienAirModClient {

    private val tmpWorldBlockPos = BlockPos.MutableBlockPos()

    private fun computeShipWaterTintRgb(shipEventShip: org.valkyrienskies.core.api.ships.ClientShip): Int {
        val level = Minecraft.getInstance().level ?: return 0xFFFFFF
        val worldPos = shipEventShip.renderTransform.positionInWorld
        tmpWorldBlockPos.set(Mth.floor(worldPos.x()), Mth.floor(worldPos.y()), Mth.floor(worldPos.z()))

        return BiomeColors.getAverageWaterColor(level, tmpWorldBlockPos)
    }

    @JvmStatic
    fun initClient() {
        VSGameEvents.renderShip.on {
            ShipWaterPocketExternalWaterCullRenderContext.beginShipRender()
            ShipWaterPocketCurrentShipRenderContext.push(it.ship.id, false)
            ShipWaterPocketShipWaterTintRenderContext.pushShipWaterTintRgb(computeShipWaterTintRgb(it.ship))
        }
        VSGameEvents.postRenderShip.on {
            ShipWaterPocketShipWaterTintRenderContext.popShipWaterTintRgb()
            ShipWaterPocketCurrentShipRenderContext.pop()
            ShipWaterPocketExternalWaterCullRenderContext.endShipRender()
        }

        VSGameEvents.renderShipSodium.on {
            ShipWaterPocketExternalWaterCullRenderContext.beginShipRender()
            ShipWaterPocketCurrentShipRenderContext.push(it.ship.id, true)
            ShipWaterPocketShipWaterTintRenderContext.pushShipWaterTintRgb(computeShipWaterTintRgb(it.ship))
        }
        VSGameEvents.postRenderShipSodium.on {
            ShipWaterPocketShipWaterTintRenderContext.popShipWaterTintRgb()
            ShipWaterPocketCurrentShipRenderContext.pop()
            ShipWaterPocketExternalWaterCullRenderContext.endShipRender()
        }
    }
}
