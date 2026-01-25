package org.valkyrienskies.valkyrienair.mixin.client.renderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.ShipWaterPocketCurrentShipRenderContext;
import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.ShipWaterPocketExternalWaterCull;
import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.ShipWaterPocketExternalWaterCullRenderContext;
import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.ShipWaterPocketShipWaterTintRenderContext;

@Mixin(ShaderInstance.class)
public abstract class MixinShaderInstance {

    @Unique
    private boolean valkyrienair$checkedExternalWaterCullUniform = false;

    @Unique
    private boolean valkyrienair$hasExternalWaterCullUniform = false;

    @Inject(method = "apply()V", at = @At("TAIL"), require = 0)
    private void valkyrienair$applyExternalWorldWaterCullingUniforms(final CallbackInfo ci) {
        final ShaderInstance shader = (ShaderInstance) (Object) this;

        if (!this.valkyrienair$checkedExternalWaterCullUniform) {
            this.valkyrienair$checkedExternalWaterCullUniform = true;
            this.valkyrienair$hasExternalWaterCullUniform = shader.getUniform("ValkyrienAir_CullEnabled") != null;
        }

        if (!this.valkyrienair$hasExternalWaterCullUniform) return;

        final boolean shipTintActive = ShipWaterPocketShipWaterTintRenderContext.isActive();
        final int shipTintRgb = shipTintActive ? ShipWaterPocketShipWaterTintRenderContext.getTintRgb() : 0xFFFFFF;

        final boolean shipRenderActive = ShipWaterPocketCurrentShipRenderContext.isActive();
        final long shipId = shipRenderActive ? ShipWaterPocketCurrentShipRenderContext.getShipId() : 0L;
        final boolean shipSpaceCoords = shipRenderActive && ShipWaterPocketCurrentShipRenderContext.isShipSpaceCoords();

        boolean cameraInWater = false;
        final Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer != null) {
            final FogType fogType = mc.gameRenderer.getMainCamera().getFluidInCamera();
            cameraInWater = fogType == FogType.WATER;
        }

        if (ShipWaterPocketExternalWaterCullRenderContext.isInWorldTranslucentChunkLayer()) {
            final var level = ShipWaterPocketExternalWaterCullRenderContext.getLevel();
            if (level != null) {
                if (shipRenderActive && shipId != 0L) {
                    ShipWaterPocketExternalWaterCull.setupForShipTranslucentPass(shader, level,
                        ShipWaterPocketExternalWaterCullRenderContext.getCamX(),
                        ShipWaterPocketExternalWaterCullRenderContext.getCamY(),
                        ShipWaterPocketExternalWaterCullRenderContext.getCamZ(),
                        shipId,
                        shipSpaceCoords,
                        cameraInWater);
                } else {
                    ShipWaterPocketExternalWaterCull.setupForWorldTranslucentPass(shader, level,
                        ShipWaterPocketExternalWaterCullRenderContext.getCamX(),
                        ShipWaterPocketExternalWaterCullRenderContext.getCamY(),
                        ShipWaterPocketExternalWaterCullRenderContext.getCamZ());
                }
                ShipWaterPocketExternalWaterCull.setShipPass(shader, ShipWaterPocketExternalWaterCullRenderContext.isInShipRender());
                ShipWaterPocketExternalWaterCull.setShipWaterTintEnabled(shader, shipTintActive);
                ShipWaterPocketExternalWaterCull.setShipWaterTint(shader, shipTintRgb);
                return;
            }
        }

        // Ensure we don't affect other uses of the translucent shader outside the world chunk translucent pass.
        ShipWaterPocketExternalWaterCull.disable(shader);

        // Vanilla ship rendering can occur outside the world chunk translucent pass, so set up ship-specific effects
        // (ship water tint, underwater-view glass tint) using the current camera position.
        if (shipRenderActive && shipId != 0L && mc.level != null && mc.gameRenderer != null) {
            final var camPos = mc.gameRenderer.getMainCamera().getPosition();
            ShipWaterPocketExternalWaterCull.setupForShipTranslucentPass(shader, mc.level,
                camPos.x, camPos.y, camPos.z, shipId, shipSpaceCoords, cameraInWater);
            ShipWaterPocketExternalWaterCull.setShipPass(shader, true);
        }

        if (shipTintActive) {
            ShipWaterPocketExternalWaterCull.setShipWaterTintEnabled(shader, true);
            ShipWaterPocketExternalWaterCull.setShipWaterTint(shader, shipTintRgb);
        }
    }
}
