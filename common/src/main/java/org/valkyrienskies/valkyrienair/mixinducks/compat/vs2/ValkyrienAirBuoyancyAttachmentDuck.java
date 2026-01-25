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
}
