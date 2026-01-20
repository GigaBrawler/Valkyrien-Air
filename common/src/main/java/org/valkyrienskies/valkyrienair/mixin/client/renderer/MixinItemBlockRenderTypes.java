package org.valkyrienskies.valkyrienair.mixin.client.renderer;

import dev.architectury.platform.Platform;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.ShipWaterPocketEmbeddiumRenderTypes;
import org.valkyrienskies.valkyrienair.config.ValkyrienAirConfig;

@Mixin(ItemBlockRenderTypes.class)
public abstract class MixinItemBlockRenderTypes {

    @Inject(
        method = "getRenderLayer(Lnet/minecraft/world/level/material/FluidState;)Lnet/minecraft/client/renderer/RenderType;",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private static void valkyrienair$useEmbeddiumCompatibleWaterLayer(final FluidState fluidState,
        final CallbackInfoReturnable<RenderType> cir) {
        if (!ValkyrienAirConfig.getEnableShipWaterPockets()) return;
        if (!Platform.isModLoaded("embeddium")) return;
        if (fluidState == null || fluidState.isEmpty()) return;
        if (!fluidState.is(Fluids.WATER) && !fluidState.is(Fluids.FLOWING_WATER)) return;
        cir.setReturnValue(ShipWaterPocketEmbeddiumRenderTypes.embeddiumWaterTranslucent());
    }
}

