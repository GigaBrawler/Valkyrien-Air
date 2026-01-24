package org.valkyrienskies.valkyrienair.mixinducks.compat.vs2;

/**
 * Stores additional buoyancy data on VS2's {@code BuoyancyHandlerAttachment} without depending on a specific
 * {@code BuoyancyData} schema.
 */
public interface ValkyrienAirBuoyancyAttachmentDuck {

    boolean valkyrienair$hasPocketCenter();

    double valkyrienair$getPocketCenterX();

    double valkyrienair$getPocketCenterY();

    double valkyrienair$getPocketCenterZ();

    void valkyrienair$setPocketCenter(double x, double y, double z);
}

