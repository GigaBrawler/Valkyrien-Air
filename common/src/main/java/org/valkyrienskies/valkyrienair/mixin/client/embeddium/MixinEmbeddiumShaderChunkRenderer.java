package org.valkyrienskies.valkyrienair.mixin.client.embeddium;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.ShipWaterPocketEmbeddiumRenderTypes;
import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.ShipWaterPocketExternalWaterCull;
import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.ShipWaterPocketExternalWaterCullRenderContext;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.ShaderChunkRenderer", remap = false)
public abstract class MixinEmbeddiumShaderChunkRenderer {

    @Inject(method = "begin", at = @At("TAIL"), require = 0)
    private void valkyrienair$setupWaterCullUniforms(final @Coerce Object terrainRenderPass, final CallbackInfo ci) {
        final int programId = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if (programId == 0) return;

        if (terrainRenderPass instanceof final TerrainRenderPassAccessor passAccessor) {
            final RenderType layer = passAccessor.valkyrienair$getLayer();
            if (!ShipWaterPocketEmbeddiumRenderTypes.isWorldTranslucentPass(layer)) {
                ShipWaterPocketExternalWaterCull.disableProgram(programId);
                return;
            }
        }

        final var minecraft = Minecraft.getInstance();
        final var level = minecraft.level;
        final var camera = minecraft.gameRenderer.getMainCamera();
        if (level == null || camera == null) return;

        final var cameraPos = camera.getPosition();
        ShipWaterPocketExternalWaterCull.setupForWorldTranslucentPassProgram(
            programId,
            level,
            cameraPos.x,
            cameraPos.y,
            cameraPos.z,
            ShipWaterPocketExternalWaterCullRenderContext.isInShipRender()
        );
    }

    @Inject(method = "end", at = @At("HEAD"), require = 0)
    private void valkyrienair$disableWaterCullUniforms(final @Coerce Object terrainRenderPass, final CallbackInfo ci) {
        final int programId = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if (programId == 0) return;
        ShipWaterPocketExternalWaterCull.disableProgram(programId);
    }
}
