package org.valkyrienskies.valkyrienair.mixin.client.embeddium;

import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.EmbeddiumChunkRenderContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.ShaderChunkRenderer", remap = false)
public abstract class MixinEmbeddiumShaderChunkRenderer {

    @Inject(method = "begin", at = @At("HEAD"), require = 0)
    private void valkyrienair$beginChunkPass(final @Coerce Object terrainRenderPass, final CallbackInfo ci) {
        EmbeddiumChunkRenderContext.begin(terrainRenderPass);
    }

    @Inject(method = "end", at = @At("TAIL"), require = 0)
    private void valkyrienair$endChunkPass(final @Coerce Object terrainRenderPass, final CallbackInfo ci) {
        EmbeddiumChunkRenderContext.end();
    }
}
