package org.valkyrienskies.valkyrienair.mixin.client.embeddium;

import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass", remap = false)
public interface TerrainRenderPassAccessor {

    @Accessor("layer")
    RenderType valkyrienair$getLayer();
}

