package org.valkyrienskies.valkyrienair.mixin.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.valkyrienair.config.ValkyrienAirConfig;

@Mixin(AbstractCauldronBlock.class)
public abstract class MixinAbstractCauldronBlock {

    @Shadow
    protected abstract double getContentHeight(BlockState state);

    @org.spongepowered.asm.mixin.Unique
    private static boolean valkyrienair$intersectsShipBlock(final Matrix4dc worldToShip, final Entity entity,
        final BlockPos pos) {
        final double minX = entity.getBoundingBox().minX;
        final double minY = entity.getBoundingBox().minY;
        final double minZ = entity.getBoundingBox().minZ;
        final double maxX = entity.getBoundingBox().maxX;
        final double maxY = entity.getBoundingBox().maxY;
        final double maxZ = entity.getBoundingBox().maxZ;

        final double hx = (maxX - minX) * 0.5;
        final double hy = (maxY - minY) * 0.5;
        final double hz = (maxZ - minZ) * 0.5;

        // Entity OBB center in world.
        final double worldCx = (minX + maxX) * 0.5;
        final double worldCy = (minY + maxY) * 0.5;
        final double worldCz = (minZ + maxZ) * 0.5;

        // Transform center to ship space.
        final double shipCx = worldToShip.m00() * worldCx + worldToShip.m10() * worldCy + worldToShip.m20() * worldCz + worldToShip.m30();
        final double shipCy = worldToShip.m01() * worldCx + worldToShip.m11() * worldCy + worldToShip.m21() * worldCz + worldToShip.m31();
        final double shipCz = worldToShip.m02() * worldCx + worldToShip.m12() * worldCy + worldToShip.m22() * worldCz + worldToShip.m32();

        // Block AABB in ship space.
        final double bCx = pos.getX() + 0.5;
        final double bCy = pos.getY() + 0.5;
        final double bCz = pos.getZ() + 0.5;

        final double tX = bCx - shipCx;
        final double tY = bCy - shipCy;
        final double tZ = bCz - shipCz;

        // R[i][j] = dot(Ai, Bj). Bj are the ship axes, so R is just the components of the entity's ship-space axes.
        // Ai are the world axes transformed into ship space, i.e. columns of worldToShip.
        final double r00 = worldToShip.m00();
        final double r01 = worldToShip.m01();
        final double r02 = worldToShip.m02();
        final double r10 = worldToShip.m10();
        final double r11 = worldToShip.m11();
        final double r12 = worldToShip.m12();
        final double r20 = worldToShip.m20();
        final double r21 = worldToShip.m21();
        final double r22 = worldToShip.m22();

        final double absEps = 1.0e-9;
        final double ar00 = Math.abs(r00) + absEps;
        final double ar01 = Math.abs(r01) + absEps;
        final double ar02 = Math.abs(r02) + absEps;
        final double ar10 = Math.abs(r10) + absEps;
        final double ar11 = Math.abs(r11) + absEps;
        final double ar12 = Math.abs(r12) + absEps;
        final double ar20 = Math.abs(r20) + absEps;
        final double ar21 = Math.abs(r21) + absEps;
        final double ar22 = Math.abs(r22) + absEps;

        // t in A's coordinates: tA[i] = dot(t, Ai)
        final double tA0 = tX * r00 + tY * r01 + tZ * r02;
        final double tA1 = tX * r10 + tY * r11 + tZ * r12;
        final double tA2 = tX * r20 + tY * r21 + tZ * r22;

        final double bEx = 0.5;
        final double bEy = 0.5;
        final double bEz = 0.5;

        double ra;
        double rb;

        // Test axes L = A0, A1, A2
        ra = hx;
        rb = bEx * ar00 + bEy * ar01 + bEz * ar02;
        if (Math.abs(tA0) > ra + rb) return false;

        ra = hy;
        rb = bEx * ar10 + bEy * ar11 + bEz * ar12;
        if (Math.abs(tA1) > ra + rb) return false;

        ra = hz;
        rb = bEx * ar20 + bEy * ar21 + bEz * ar22;
        if (Math.abs(tA2) > ra + rb) return false;

        // Test axes L = B0, B1, B2 (ship axes)
        ra = hx * ar00 + hy * ar10 + hz * ar20;
        rb = bEx;
        if (Math.abs(tX) > ra + rb) return false;

        ra = hx * ar01 + hy * ar11 + hz * ar21;
        rb = bEy;
        if (Math.abs(tY) > ra + rb) return false;

        ra = hx * ar02 + hy * ar12 + hz * ar22;
        rb = bEz;
        if (Math.abs(tZ) > ra + rb) return false;

        // Test axis L = A0 x B0
        ra = hy * ar20 + hz * ar10;
        rb = bEy * ar02 + bEz * ar01;
        if (Math.abs(tA2 * r10 - tA1 * r20) > ra + rb) return false;

        // Test axis L = A0 x B1
        ra = hy * ar21 + hz * ar11;
        rb = bEx * ar02 + bEz * ar00;
        if (Math.abs(tA2 * r11 - tA1 * r21) > ra + rb) return false;

        // Test axis L = A0 x B2
        ra = hy * ar22 + hz * ar12;
        rb = bEx * ar01 + bEy * ar00;
        if (Math.abs(tA2 * r12 - tA1 * r22) > ra + rb) return false;

        // Test axis L = A1 x B0
        ra = hx * ar20 + hz * ar00;
        rb = bEy * ar12 + bEz * ar11;
        if (Math.abs(tA0 * r20 - tA2 * r00) > ra + rb) return false;

        // Test axis L = A1 x B1
        ra = hx * ar21 + hz * ar01;
        rb = bEx * ar12 + bEz * ar10;
        if (Math.abs(tA0 * r21 - tA2 * r01) > ra + rb) return false;

        // Test axis L = A1 x B2
        ra = hx * ar22 + hz * ar02;
        rb = bEx * ar11 + bEy * ar10;
        if (Math.abs(tA0 * r22 - tA2 * r02) > ra + rb) return false;

        // Test axis L = A2 x B0
        ra = hx * ar10 + hy * ar00;
        rb = bEy * ar22 + bEz * ar21;
        if (Math.abs(tA1 * r00 - tA0 * r10) > ra + rb) return false;

        // Test axis L = A2 x B1
        ra = hx * ar11 + hy * ar01;
        rb = bEx * ar22 + bEz * ar20;
        if (Math.abs(tA1 * r01 - tA0 * r11) > ra + rb) return false;

        // Test axis L = A2 x B2
        ra = hx * ar12 + hy * ar02;
        rb = bEx * ar21 + bEy * ar20;
        if (Math.abs(tA1 * r02 - tA0 * r12) > ra + rb) return false;

        return true;
    }

    @Inject(method = "isEntityInsideContent", at = @At("HEAD"), cancellable = true)
    private void valkyrienair$shipyardCauldronContentCheck(final BlockState state, final BlockPos pos,
        final Entity entity, final CallbackInfoReturnable<Boolean> cir) {
        if (!ValkyrienAirConfig.getEnableShipWaterPockets()) return;

        final Level level = entity.level();
        if (level.isClientSide) return;
        if (!VSGameUtilsKt.isBlockInShipyard(level, pos)) return;

        final Ship ship = VSGameUtilsKt.getShipManagingPos(level, pos);
        if (ship == null) return;

        final ShipTransform shipTransform = ship.getTransform();
        final Matrix4dc worldToShip = shipTransform.getWorldToShip();

        // Entity#checkInsideBlocks can invoke block callbacks for nearby ship blocks due to ship rotation / rounding.
        // Vanilla relies on checkInsideBlocks' AABB-vs-block selection and only checks Y here, so we replicate that
        // behavior by first testing actual ship-space overlap, then applying the vanilla Y thresholds.
        if (!valkyrienair$intersectsShipBlock(worldToShip, entity, pos)) {
            cir.setReturnValue(false);
            cir.cancel();
            return;
        }

        final double minX = entity.getBoundingBox().minX;
        final double minY = entity.getBoundingBox().minY;
        final double minZ = entity.getBoundingBox().minZ;
        final double maxX = entity.getBoundingBox().maxX;
        final double maxY = entity.getBoundingBox().maxY;
        final double maxZ = entity.getBoundingBox().maxZ;

        final double hx = (maxX - minX) * 0.5;
        final double hy = (maxY - minY) * 0.5;
        final double hz = (maxZ - minZ) * 0.5;

        final double worldCx = (minX + maxX) * 0.5;
        final double worldCy = (minY + maxY) * 0.5;
        final double worldCz = (minZ + maxZ) * 0.5;

        final double shipCy = worldToShip.m01() * worldCx + worldToShip.m11() * worldCy + worldToShip.m21() * worldCz + worldToShip.m31();
        final double rY =
            hx * Math.abs(worldToShip.m01()) + hy * Math.abs(worldToShip.m11()) + hz * Math.abs(worldToShip.m21());
        final double shipMinY = shipCy - rY;
        final double shipMaxY = shipCy + rY;

        final double contentTopY = pos.getY() + this.getContentHeight(state);
        final boolean inside = shipMinY < contentTopY && shipMaxY > pos.getY() + 0.25;
        cir.setReturnValue(inside);
        cir.cancel();
    }
}
