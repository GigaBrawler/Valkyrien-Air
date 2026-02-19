package org.valkyrienskies.valkyrienair.forge

import net.minecraftforge.common.ForgeConfigSpec
import net.minecraftforge.fml.event.config.ModConfigEvent
import org.valkyrienskies.valkyrienair.ValkyrienAirMod
import org.valkyrienskies.valkyrienair.config.ValkyrienAirConfig

object ValkyrienAirForgeConfig {
    val spec: ForgeConfigSpec

    private val enableShipWaterPocketsValue: ForgeConfigSpec.BooleanValue
    private val shipPocketFloodRateMultiplierValue: ForgeConfigSpec.DoubleValue
    private val shipPocketParticleSpeedMultiplierValue: ForgeConfigSpec.DoubleValue

    init {
        val builder = ForgeConfigSpec.Builder()

        builder.push("ship_water_pockets")
        enableShipWaterPocketsValue = builder
            .comment("Enable ship air/water pocket simulation.")
            .define("enable_ship_water_pockets", ValkyrienAirConfig.enableShipWaterPockets)
        shipPocketFloodRateMultiplierValue = builder
            .comment(
                "Flood speed multiplier for ship air pockets. " +
                    "1.0 = baseline, 0.3333 = ~3x slower flooding."
            )
            .defineInRange(
                "flood_rate_multiplier",
                ValkyrienAirConfig.shipPocketFloodRateMultiplier,
                0.05,
                5.0
            )
        shipPocketParticleSpeedMultiplierValue = builder
            .comment("Leak/flood particle speed multiplier.")
            .defineInRange(
                "particle_speed_multiplier",
                ValkyrienAirConfig.shipPocketParticleSpeedMultiplier,
                0.1,
                5.0
            )
        builder.pop()

        spec = builder.build()
    }

    fun onConfigLoading(event: ModConfigEvent.Loading) {
        if (event.config.modId != ValkyrienAirMod.MOD_ID) return
        applyToCommonConfig()
    }

    fun onConfigReloading(event: ModConfigEvent.Reloading) {
        if (event.config.modId != ValkyrienAirMod.MOD_ID) return
        applyToCommonConfig()
    }

    fun applyToCommonConfig() {
        ValkyrienAirConfig.enableShipWaterPockets = enableShipWaterPocketsValue.get()
        ValkyrienAirConfig.shipPocketFloodRateMultiplier = shipPocketFloodRateMultiplierValue.get()
        ValkyrienAirConfig.shipPocketParticleSpeedMultiplier = shipPocketParticleSpeedMultiplierValue.get()
    }
}
