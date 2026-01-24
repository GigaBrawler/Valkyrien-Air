package org.valkyrienskies.valkyrienair.mixin.feature.ship_water_pockets;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.valkyrienair.config.ValkyrienAirConfig;
import org.valkyrienskies.valkyrienair.feature.ship_water_pockets.ShipWaterPocketManager;

@Mixin(Level.class)
public abstract class MixinLevel {

    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void valkyrienair$redirectFirePlacementToShipyardInAirPockets(final BlockPos pos, final BlockState state,
        final int flags, final int recursionLeft, final CallbackInfoReturnable<Boolean> cir) {
        if (!ValkyrienAirConfig.getEnableShipWaterPockets()) return;

        final Level level = Level.class.cast(this);
        if (level.isClientSide) return;
        if (ShipWaterPocketManager.isApplyingInternalUpdates()) return;
        if (VSGameUtilsKt.isBlockInShipyard(level, pos)) return;

        if (!state.is(Blocks.FIRE) && !state.is(Blocks.SOUL_FIRE)) return;

        final BlockPos shipPos = ShipWaterPocketManager.getShipBlockPosForWorldPosInShipAirPocket(level, pos);
        if (shipPos == null) return;
        if (shipPos.equals(pos)) return;
        if (!VSGameUtilsKt.isBlockInShipyard(level, shipPos)) return;

        final BlockState existing = level.getBlockState(shipPos);
        if (!existing.isAir() && !existing.is(state.getBlock())) {
            cir.setReturnValue(false);
            cir.cancel();
            return;
        }

        final boolean ok = level.setBlock(shipPos, state, flags, recursionLeft);
        cir.setReturnValue(ok);
        cir.cancel();
    }

    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void valkyrienair$blockShipyardWaterFromLeakingIntoWorldWater(final BlockPos pos, final BlockState state,
        final int flags, final int recursionLeft, final CallbackInfoReturnable<Boolean> cir) {
        if (!ValkyrienAirConfig.getEnableShipWaterPockets()) return;

        final Level level = Level.class.cast(this);
        if (level.isClientSide) return;
        if (ShipWaterPocketManager.isApplyingInternalUpdates()) return;
        if (!VSGameUtilsKt.isBlockInShipyard(level, pos)) return;

        final var fluidState = state.getFluidState();
        if (fluidState.isEmpty() || !fluidState.is(Fluids.WATER)) return;

        final Ship ship = VSGameUtilsKt.getShipManagingPos(level, pos);
        if (ship == null) return;

        if (ShipWaterPocketManager.shouldBlockShipyardWaterPlacement(level, ship.getId(), pos, ship.getTransform())) {
            cir.setReturnValue(false);
            cir.cancel();
        }
    }

    @Inject(
        method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("TAIL")
    )
    private void vs$markShipWaterPocketDirty(final BlockPos pos, final BlockState state, final int flags,
        final int recursionLeft, final CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        if (ShipWaterPocketManager.isApplyingInternalUpdates()) return;

        final Level level = Level.class.cast(this);
        if (!VSGameUtilsKt.isBlockInShipyard(level, pos)) return;

        final Ship ship = VSGameUtilsKt.getShipManagingPos(level, pos);
        if (ship == null) return;

        ShipWaterPocketManager.markShipDirty(level, ship.getId());
    }
}
