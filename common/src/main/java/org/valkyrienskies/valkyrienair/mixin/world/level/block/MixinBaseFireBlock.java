package org.valkyrienskies.valkyrienair.mixin.world.level.block;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.valkyrienskies.valkyrienair.config.ValkyrienAirConfig;
import org.valkyrienskies.valkyrienair.feature.ship_water_pockets.ShipWaterPocketManager;

@Mixin(BaseFireBlock.class)
public abstract class MixinBaseFireBlock {

    @ModifyExpressionValue(
        method = "canBePlacedAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;isAir()Z")
    )
    private static boolean valkyrienair$allowFireInShipAirPockets(final boolean original, final Level level,
        final BlockPos pos, final Direction direction) {
        if (original) return true;
        if (!ValkyrienAirConfig.getEnableShipWaterPockets()) return false;

        if (!level.getBlockState(pos).is(Blocks.WATER)) return false;
        return ShipWaterPocketManager.isWorldPosInShipAirPocket(level, pos);
    }
}
