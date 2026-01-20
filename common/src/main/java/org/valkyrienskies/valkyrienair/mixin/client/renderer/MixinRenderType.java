package org.valkyrienskies.valkyrienair.mixin.client.renderer;

import com.google.common.collect.ImmutableList;
import java.util.List;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.ShipWaterPocketEmbeddiumRenderTypes;

@Mixin(RenderType.class)
public abstract class MixinRenderType {

    @Unique
    private static List<RenderType> valkyrienair$chunkBufferLayers = null;

    /**
     * Forge's {@code ChunkRenderTypeSet} snapshots the result of {@code RenderType.chunkBufferLayers()} during static
     * initialization, and assigns chunk-layer IDs based on that list.
     *
     * <p>To allow selecting a distinct translucent RenderType for water (Embeddium compat), we must ensure the custom
     * RenderType is part of the chunk render type list early enough to receive a valid chunk-layer ID.
     */
    @Inject(method = "chunkBufferLayers()Ljava/util/List;", at = @At("RETURN"), cancellable = true, require = 0)
    private static void valkyrienair$addEmbeddiumWaterTranslucentLayer(final CallbackInfoReturnable<List<RenderType>> cir) {
        if (valkyrienair$chunkBufferLayers != null) {
            cir.setReturnValue(valkyrienair$chunkBufferLayers);
            return;
        }

        final List<RenderType> original = cir.getReturnValue();
        final RenderType extra = ShipWaterPocketEmbeddiumRenderTypes.embeddiumWaterTranslucent();
        valkyrienair$chunkBufferLayers = ImmutableList.<RenderType>builder()
            .addAll(original)
            .add(extra)
            .build();
        cir.setReturnValue(valkyrienair$chunkBufferLayers);
    }
}

