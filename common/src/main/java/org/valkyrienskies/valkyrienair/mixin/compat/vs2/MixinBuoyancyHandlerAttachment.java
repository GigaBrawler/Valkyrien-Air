package org.valkyrienskies.valkyrienair.mixin.compat.vs2;

import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.core.api.ships.PhysShip;
import org.valkyrienskies.core.api.world.PhysLevel;
import org.valkyrienskies.mod.common.util.BuoyancyHandlerAttachment;
import org.valkyrienskies.valkyrienair.config.ValkyrienAirConfig;
import org.valkyrienskies.valkyrienair.mixinducks.compat.vs2.ValkyrienAirBuoyancyAttachmentDuck;

@Mixin(value = BuoyancyHandlerAttachment.class, remap = false)
public abstract class MixinBuoyancyHandlerAttachment implements ValkyrienAirBuoyancyAttachmentDuck {

    @Unique
    private static final double valkyrienair$WATER_DENSITY = 1000.0;

    @Unique
    private static final double valkyrienair$GRAVITY_MAGNITUDE = 10.0;

    @Unique
    private volatile double valkyrienair$displacedVolume = 0.0;

    @Unique
    private volatile boolean valkyrienair$hasPocketCenter = false;

    @Unique
    private volatile double valkyrienair$pocketCenterX = 0.0;

    @Unique
    private volatile double valkyrienair$pocketCenterY = 0.0;

    @Unique
    private volatile double valkyrienair$pocketCenterZ = 0.0;

    @Unique
    private final Vector3d valkyrienair$tmpForce = new Vector3d();

    @Unique
    private final Vector3d valkyrienair$tmpPos = new Vector3d();

    @Override
    public double valkyrienair$getDisplacedVolume() {
        return valkyrienair$displacedVolume;
    }

    @Override
    public void valkyrienair$setDisplacedVolume(final double volume) {
        valkyrienair$displacedVolume = volume;
    }

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
        // Disable VS2's experimental pocket buoyancy and apply Valkyrien-Air's own buoyancy forces instead.
        ci.cancel();

        if (!ValkyrienAirConfig.getEnableShipWaterPockets()) return;
        if (!valkyrienair$hasPocketCenter) return;

        final double displaced = valkyrienair$displacedVolume;
        if (displaced <= 1.0e-6) return;

        final double upwardForce = displaced * valkyrienair$WATER_DENSITY * valkyrienair$GRAVITY_MAGNITUDE;
        valkyrienair$tmpForce.set(0.0, upwardForce, 0.0);
        valkyrienair$tmpPos.set(valkyrienair$pocketCenterX, valkyrienair$pocketCenterY, valkyrienair$pocketCenterZ);
        physShip.applyWorldForceToModelPos(valkyrienair$tmpForce, valkyrienair$tmpPos);
        physShip.setDoFluidDrag(true);
    }
}
