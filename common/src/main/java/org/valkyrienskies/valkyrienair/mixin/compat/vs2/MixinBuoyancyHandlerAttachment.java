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
        if (!ValkyrienAirConfig.getEnableShipWaterPockets()) return;

        // Disable VS2's experimental pocket buoyancy and apply Valkyrien-Air's own buoyancy forces instead.
        ci.cancel();

        // Reset VS2's buoyancy scaling to a stable baseline (VS2's pocket buoyancy works by inflating this value).
        // Setting this to 0.0 can destabilize physics, so we keep the default scale and apply our own pocket forces.
        physShip.setBuoyantFactor(1.0);

        if (!valkyrienair$hasPocketCenter) return;

        final double displaced = valkyrienair$displacedVolume;
        if (!Double.isFinite(displaced)) return;
        if (displaced <= 1.0e-6) return;

        final double pocketX = valkyrienair$pocketCenterX;
        final double pocketY = valkyrienair$pocketCenterY;
        final double pocketZ = valkyrienair$pocketCenterZ;
        if (!Double.isFinite(pocketX) || !Double.isFinite(pocketY) || !Double.isFinite(pocketZ)) return;

        double upwardForce = displaced * valkyrienair$WATER_DENSITY * valkyrienair$GRAVITY_MAGNITUDE;
        if (!Double.isFinite(upwardForce) || upwardForce <= 0.0) return;

        // Clamp to prevent runaway impulses if a ship has extremely low mass relative to displaced air volume.
        final double mass = physShip.getMass();
        if (!Double.isFinite(mass) || mass <= 1.0e-6) return;

        final double maxForce = mass * valkyrienair$GRAVITY_MAGNITUDE * 5.0;
        if (Double.isFinite(maxForce) && maxForce > 0.0) {
            upwardForce = Math.min(upwardForce, maxForce);
        }

        valkyrienair$tmpForce.set(0.0, upwardForce, 0.0);
        valkyrienair$tmpPos.set(pocketX, pocketY, pocketZ);
        physShip.applyWorldForceToModelPos(valkyrienair$tmpForce, valkyrienair$tmpPos);
        physShip.setDoFluidDrag(true);
    }
}
