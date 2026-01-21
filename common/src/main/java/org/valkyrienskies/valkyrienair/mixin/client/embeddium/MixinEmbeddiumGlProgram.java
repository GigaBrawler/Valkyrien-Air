package org.valkyrienskies.valkyrienair.mixin.client.embeddium;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.EmbeddiumChunkRenderContext;
import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.ShipWaterPocketExternalWaterCull;
import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.ShipWaterPocketExternalWaterCullRenderContext;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.gl.shader.GlProgram", remap = false)
public abstract class MixinEmbeddiumGlProgram {

    @Unique
    private int valkyrienair$programId = 0;

    @Unique
    private static final Logger VA_LOGGER = LogManager.getLogger("ValkyrienAir EmbeddiumWaterCull");

    @Unique
    private static final java.util.Set<Integer> VA_LOGGED_PROGRAM_BINDS =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    @Unique
    private static boolean valkyrienair$debugLogProgramBinds() {
        return Boolean.getBoolean("valkyrienair.debugEmbeddiumProgramBinds");
    }

    @Inject(method = "bind", at = @At("TAIL"), require = 0)
    private void valkyrienair$afterBind(final CallbackInfo ci) {
        if (!EmbeddiumChunkRenderContext.isActive()) return;

        final int programId = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if (programId == 0) return;
        this.valkyrienair$programId = programId;

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

        if (valkyrienair$debugLogProgramBinds() && VA_LOGGED_PROGRAM_BINDS.add(programId)) {
            final RenderType layer = EmbeddiumChunkRenderContext.getCurrentLayer();
            VA_LOGGER.info("GlProgram.bind programId={} layer={}", programId, layer);
        }
    }

    @Inject(method = "unbind", at = @At("HEAD"), require = 0)
    private void valkyrienair$beforeUnbind(final CallbackInfo ci) {
        if (!EmbeddiumChunkRenderContext.isActive()) return;

        int programId = this.valkyrienair$programId;
        this.valkyrienair$programId = 0;
        if (programId == 0) {
            programId = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        }
        if (programId == 0) return;

        ShipWaterPocketExternalWaterCull.disableProgram(programId);
    }
}
