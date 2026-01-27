package org.valkyrienskies.valkyrienair.mixinducks.compat.vs2;

/**
 * Stores additional buoyancy data on VS2's {@code BuoyancyHandlerAttachment} without depending on a specific
 * {@code BuoyancyData} schema.
 */
public interface ValkyrienAirBuoyancyAttachmentDuck {

    /**
     * @return Additional displaced volume (in m^3) contributed by ship air pockets.
     */
    double valkyrienair$getDisplacedVolume();

    /**
     * Sets the additional displaced volume (in m^3) contributed by ship air pockets.
     *
     * <p>This is used by Valkyrien-Air's own buoyancy calculation. VS2's experimental pocket buoyancy is not used.</p>
     */
    void valkyrienair$setDisplacedVolume(double volume);

    boolean valkyrienair$hasPocketCenter();

    double valkyrienair$getPocketCenterX();

    double valkyrienair$getPocketCenterY();

    double valkyrienair$getPocketCenterZ();

    void valkyrienair$setPocketCenter(double x, double y, double z);

    /**
     * @return Density (kg/m^3) of the exterior liquid the ship is currently interacting with.
     */
    double valkyrienair$getBuoyancyFluidDensity();

    /**
     * @return Viscosity (arbitrary Minecraft/Forge units) of the exterior liquid the ship is currently interacting with.
     */
    double valkyrienair$getBuoyancyFluidViscosity();

    void valkyrienair$setBuoyancyFluidDensity(double density);

    void valkyrienair$setBuoyancyFluidViscosity(double viscosity);
}
