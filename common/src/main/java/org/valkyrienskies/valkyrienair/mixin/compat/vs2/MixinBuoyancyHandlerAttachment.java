package org.valkyrienskies.valkyrienair.mixin.compat.vs2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.PhysShip;
import org.valkyrienskies.core.api.world.PhysLevel;
import org.valkyrienskies.mod.common.config.VSGameConfig;
import org.valkyrienskies.mod.common.util.BuoyancyHandlerAttachment;
import org.valkyrienskies.valkyrienair.config.ValkyrienAirConfig;
import org.valkyrienskies.valkyrienair.mixinducks.compat.vs2.ValkyrienAirBuoyancyAttachmentDuck;

@Mixin(value = BuoyancyHandlerAttachment.class, remap = false)
public abstract class MixinBuoyancyHandlerAttachment implements ValkyrienAirBuoyancyAttachmentDuck {

    @Shadow
    public BuoyancyHandlerAttachment.BuoyancyData buoyancyData;

    @Unique
    private volatile boolean valkyrienair$hasPocketCenter = false;

    @Unique
    private volatile double valkyrienair$pocketCenterX = 0.0;

    @Unique
    private volatile double valkyrienair$pocketCenterY = 0.0;

    @Unique
    private volatile double valkyrienair$pocketCenterZ = 0.0;

    @Override
    public boolean valkyrienair$hasPocketCenter() {
        return valkyrienair$hasPocketCenter;
    }

    @Override
    public double valkyrienair$getPocketCenterX() {
        return valkyrienair$pocketCenterX;
    }

    @Override
    public double valkyrienair$getPocketCenterY() {
        return valkyrienair$pocketCenterY;
    }

    @Override
    public double valkyrienair$getPocketCenterZ() {
        return valkyrienair$pocketCenterZ;
    }

    @Override
    public void valkyrienair$setPocketCenter(final double x, final double y, final double z) {
        valkyrienair$pocketCenterX = x;
        valkyrienair$pocketCenterY = y;
        valkyrienair$pocketCenterZ = z;
        valkyrienair$hasPocketCenter = true;
    }

    @Inject(method = "physTick", at = @At("HEAD"), cancellable = true)
    private void valkyrienair$disableVs2PocketBuoyancy(final PhysShip physShip, final PhysLevel physLevel,
        final CallbackInfo ci) {
        if (!ValkyrienAirConfig.getEnableShipWaterPockets()) return;
        if (!VSGameConfig.SERVER.getEnablePocketBuoyancy()) return;

        // Disable VS2's stock pocket buoyancy calculation and apply our own pocket volume results.
        ci.cancel();

        final double netVolume = buoyancyData.getPocketVolumeTotal();
        final double perVolume = VSGameConfig.SERVER.getBuoyancyFactorPerPocketVolume();
        final double coverage = Math.max(0.0, Math.min(1.0, physShip.getLiquidOverlap()));

        // Use buoyantFactor (VS2's native integration) instead of directly applying forces.
        //
        // This keeps the ship stable and lets VS2's own fluid-drag + buoyancy integration handle the dynamics.
        double factor = 1.0 + (netVolume * perVolume * coverage);
        if (factor < 0.0) factor = 0.0;
        physShip.setBuoyantFactor(factor);
        physShip.setDoFluidDrag(true);
    }
}
