package org.valkyrienskies.valkyrienair.config

object ValkyrienAirConfig {
    /**
     * Enables ship air/water pockets. Must be enabled on both client and server.
     */
    @JvmStatic
    var enableShipWaterPockets: Boolean = true

    /**
     * Multiplier for ship pocket flooding speed.
     * `1.0` = current baseline, `0.3333` = ~3x slower flooding.
     */
    @JvmStatic
    var shipPocketFloodRateMultiplier: Double = 0.3333333333333333

    /**
     * Multiplier for ship pocket leak/flood particle velocity.
     */
    @JvmStatic
    var shipPocketParticleSpeedMultiplier: Double = 1.0
}
