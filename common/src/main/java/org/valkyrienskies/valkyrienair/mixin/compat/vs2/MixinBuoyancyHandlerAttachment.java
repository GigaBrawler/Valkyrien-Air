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
    private static final double valkyrienair$MAX_POCKET_BUOYANCY_WEIGHT_MULT = 1.0;

    @Unique
    private static final double valkyrienair$DEFAULT_FLUID_VISCOSITY = 1000.0;

    @Unique
    private volatile double valkyrienair$displacedVolume = 0.0;

    @Unique
    private volatile boolean valkyrienair$hasPocketCenter = false;

    @Unique
    private volatile double valkyrienair$buoyancyFluidDensity = valkyrienair$WATER_DENSITY;

    @Unique
    private volatile double valkyrienair$buoyancyFluidViscosity = valkyrienair$DEFAULT_FLUID_VISCOSITY;

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

    @Override
    public double valkyrienair$getBuoyancyFluidDensity() {
        return valkyrienair$buoyancyFluidDensity;
    }

    @Override
    public double valkyrienair$getBuoyancyFluidViscosity() {
        return valkyrienair$buoyancyFluidViscosity;
    }

    @Override
    public void valkyrienair$setBuoyancyFluidDensity(final double density) {
        valkyrienair$buoyancyFluidDensity = density;
    }

    @Override
    public void valkyrienair$setBuoyancyFluidViscosity(final double viscosity) {
        valkyrienair$buoyancyFluidViscosity = viscosity;
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

        final double overlap = physShip.getLiquidOverlap();
        if (!Double.isFinite(overlap) || overlap <= 1.0e-6) return;

        double density = valkyrienair$buoyancyFluidDensity;
        if (!Double.isFinite(density) || density <= 0.0) density = valkyrienair$WATER_DENSITY;
        density = Math.max(100.0, Math.min(density, 20_000.0));

        double upwardForce = displaced * density * valkyrienair$GRAVITY_MAGNITUDE * overlap;
        if (!Double.isFinite(upwardForce) || upwardForce <= 0.0) return;

        // Clamp to prevent runaway impulses if a ship has extremely low mass relative to displaced air volume.
        final double mass = physShip.getMass();
        if (!Double.isFinite(mass) || mass <= 1.0e-6) return;

        final double maxForce = mass * valkyrienair$GRAVITY_MAGNITUDE * valkyrienair$MAX_POCKET_BUOYANCY_WEIGHT_MULT;
        if (Double.isFinite(maxForce) && maxForce > 0.0) {
            upwardForce = Math.min(upwardForce, maxForce);
        }

        valkyrienair$tmpForce.set(0.0, upwardForce, 0.0);
        valkyrienair$tmpPos.set(pocketX, pocketY, pocketZ);
        physShip.applyWorldForceToModelPos(valkyrienair$tmpForce, valkyrienair$tmpPos);
        physShip.setDoFluidDrag(true);

        // Extra damping for very viscous fluids (e.g. lava) to prevent "bounce"/launch oscillations at depth.
        double viscosity = valkyrienair$buoyancyFluidViscosity;
        if (Double.isFinite(viscosity) && viscosity > valkyrienair$DEFAULT_FLUID_VISCOSITY * 1.5) {
            viscosity = Math.max(100.0, Math.min(viscosity, 200_000.0));
            final double viscosityScale = Math.max(0.25, Math.min(viscosity / valkyrienair$DEFAULT_FLUID_VISCOSITY, 20.0));

            final var vel = physShip.getVelocity();
            final double baseDamping = 0.35;
            final double damping = baseDamping * viscosityScale * overlap;

            double fx = -vel.x() * mass * damping;
            double fy = -vel.y() * mass * damping;
            double fz = -vel.z() * mass * damping;

            final double maxDamp = mass * valkyrienair$GRAVITY_MAGNITUDE * 3.0;
            fx = clamp(fx, -maxDamp, maxDamp);
            fy = clamp(fy, -maxDamp, maxDamp);
            fz = clamp(fz, -maxDamp, maxDamp);

            valkyrienair$tmpForce.set(fx, fy, fz);
            physShip.applyWorldForceToModelPos(valkyrienair$tmpForce, physShip.getCenterOfMass());
        }
    }

    @Unique
    private static double clamp(final double v, final double min, final double max) {
        return v < min ? min : Math.min(v, max);
    }
}
