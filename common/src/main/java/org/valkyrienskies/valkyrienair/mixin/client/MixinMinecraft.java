package org.valkyrienskies.valkyrienair.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.ShipWaterPocketCurrentShipRenderContext;
import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.ShipWaterPocketExternalWaterCull;
import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.ShipWaterPocketExternalWaterCullRenderContext;
import org.valkyrienskies.valkyrienair.feature.ship_water_pockets.ShipWaterPocketManager;

@Mixin(value = Minecraft.class, priority = 900)
public abstract class MixinMinecraft {

    @Inject(method = "tick", at = @At("TAIL"))
    private void valkyrienair$tickShipWaterPockets(final CallbackInfo ci) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused()) return;

        final ClientLevel level = mc.level;
        if (level == null) return;

        ShipWaterPocketManager.tickClientLevel(level);
    }

    @Inject(method = "clearLevel", at = @At("TAIL"))
    private void valkyrienair$clearShipWaterPocketRenderer(final CallbackInfo ci) {
        ShipWaterPocketExternalWaterCull.clear();
        ShipWaterPocketExternalWaterCullRenderContext.clear();
        ShipWaterPocketCurrentShipRenderContext.clear();
    }
}
