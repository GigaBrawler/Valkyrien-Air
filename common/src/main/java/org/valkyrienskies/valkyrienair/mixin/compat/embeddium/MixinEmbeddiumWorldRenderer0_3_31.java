package org.valkyrienskies.valkyrienair.mixin.compat.embeddium;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.EmbeddiumChunkRenderContext;
import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.ShipWaterPocketExternalWaterCullRenderContext;
import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.ShipWaterPocketUnderwaterViewFog;

@Pseudo
@Mixin(targets = "org.embeddedt.embeddium.impl.render.EmbeddiumWorldRenderer", remap = false)
public abstract class MixinEmbeddiumWorldRenderer0_3_31 {

    @Inject(
        method = "drawChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lorg/joml/Matrix4f;DDD)V",
        at = @At("HEAD"),
        require = 0
    )
    private void valkyrienair$beginWorldTranslucentChunkLayer(final RenderType renderLayer, final Matrix4f normal,
        final double x, final double y, final double z, final CallbackInfo ci) {
        if (renderLayer != RenderType.translucent()) return;
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        ShipWaterPocketUnderwaterViewFog.captureOpaqueDepthIfNeeded(level);
        EmbeddiumChunkRenderContext.pushChunkCameraWorldPos(x, y, z);
        ShipWaterPocketExternalWaterCullRenderContext.beginWorldTranslucentChunkLayer(
            level,
            EmbeddiumChunkRenderContext.getChunkCamXOr(x),
            EmbeddiumChunkRenderContext.getChunkCamYOr(y),
            EmbeddiumChunkRenderContext.getChunkCamZOr(z)
        );
    }

    @Inject(
        method = "drawChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lorg/joml/Matrix4f;DDD)V",
        at = @At("TAIL"),
        require = 0
    )
    private void valkyrienair$endWorldTranslucentChunkLayer(final RenderType renderLayer, final Matrix4f normal,
        final double x, final double y, final double z, final CallbackInfo ci) {
        if (renderLayer != RenderType.translucent()) return;
        ShipWaterPocketExternalWaterCullRenderContext.endWorldTranslucentChunkLayer();
        EmbeddiumChunkRenderContext.pop();
    }
}
