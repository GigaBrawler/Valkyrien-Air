package org.valkyrienskies.valkyrienair.mixin.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.ShipWaterPocketExternalWaterCullRenderContext;
import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.ShipWaterPocketUnderwaterViewFog;

// Some renderers can overwrite LevelRenderer's chunk-layer rendering, which makes INVOKE-based injections into that
// method fragile. We track the active world translucent pass here and drive shader uniform updates from ShaderInstance#apply.
@Mixin(value = LevelRenderer.class, priority = 900)
public abstract class MixinLevelRenderer {

    @Shadow
    private @Nullable ClientLevel level;

    @Inject(
        method = "renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
        at = @At("HEAD"),
        require = 0
    )
    private void valkyrienair$beginWorldTranslucentChunkLayer(final RenderType renderType,
        final PoseStack poseStack, final double camX, final double camY, final double camZ, final Matrix4f projectionMatrix,
        final CallbackInfo ci) {
        if (this.level == null) return;
        if (renderType != RenderType.translucent()) return;
        ShipWaterPocketUnderwaterViewFog.captureOpaqueDepthIfNeeded(this.level);
        ShipWaterPocketExternalWaterCullRenderContext.beginWorldTranslucentChunkLayer(this.level, camX, camY, camZ);
    }

    @Inject(
        method = "renderChunkLayer(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V",
        at = @At("TAIL"),
        require = 0
    )
    private void valkyrienair$endWorldTranslucentChunkLayer(final RenderType renderType,
        final PoseStack poseStack, final double camX, final double camY, final double camZ, final Matrix4f projectionMatrix,
        final CallbackInfo ci) {
        if (renderType != RenderType.translucent()) return;
        ShipWaterPocketExternalWaterCullRenderContext.endWorldTranslucentChunkLayer();
    }

    @Inject(
        method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
        at = @At("TAIL"),
        require = 0
    )
    private void valkyrienair$renderUnderwaterViewOverlay(final PoseStack poseStack, final float partialTick,
        final long finishNanoTime, final boolean renderBlockOutline, final Camera camera, final GameRenderer gameRenderer,
        final LightTexture lightTexture, final Matrix4f projectionMatrix, final CallbackInfo ci) {
        if (this.level == null || camera == null) return;
        final var camPos = camera.getPosition();

        // Some render pipelines (notably Embeddium) can leave RenderSystem matrices in a state that is unsuitable for
        // entity-style geometry rendering by the end of LevelRenderer#renderLevel, causing our overlay to appear
        // screen-relative and flicker. Re-apply the world matrices for the duration of the overlay draw.
        final Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        final VertexSorting oldVertexSorting = RenderSystem.getVertexSorting();

        final PoseStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushPose();
        modelViewStack.setIdentity();
        modelViewStack.mulPoseMatrix(poseStack.last().pose());

        RenderSystem.setProjectionMatrix(projectionMatrix, VertexSorting.DISTANCE_TO_ORIGIN);
        RenderSystem.applyModelViewMatrix();
        try {
            ShipWaterPocketUnderwaterViewFog.renderAndComposite(this.level, camera, projectionMatrix, poseStack.last().pose());
        } finally {
            modelViewStack.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(oldProjection, oldVertexSorting);
        }
    }
}
