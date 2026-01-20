package org.valkyrienskies.valkyrienair.mixin.client.embeddium;

import java.lang.reflect.Method;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets.ShipWaterPocketEmbeddiumRenderTypes;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials", remap = false)
public abstract class MixinEmbeddiumDefaultMaterials {

    @Unique
    private static volatile Object valkyrienair$cachedTranslucentMaterial = null;

    @Unique
    private static volatile Method valkyrienair$forRenderLayerMethod = null;

    @Unique
    private static Object valkyrienair$getTranslucentMaterial() {
        Object cached = valkyrienair$cachedTranslucentMaterial;
        if (cached != null) return cached;

        try {
            final Class<?> defaultMaterialsClass = Class.forName(
                "me.jellysquid.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials"
            );

            Method method = valkyrienair$forRenderLayerMethod;
            if (method == null) {
                method = defaultMaterialsClass.getDeclaredMethod("forRenderLayer", RenderType.class);
                method.setAccessible(true);
                valkyrienair$forRenderLayerMethod = method;
            }

            cached = method.invoke(null, RenderType.translucent());
            if (cached != null) {
                valkyrienair$cachedTranslucentMaterial = cached;
            }
            return cached;
        } catch (final Throwable ignored) {
            return null;
        }
    }

    @Inject(method = "forRenderLayer", at = @At("HEAD"), cancellable = true, require = 0)
    private static void valkyrienair$mapCustomTranslucentLayerToVanilla(final RenderType renderType,
        final CallbackInfoReturnable<Object> cir) {
        if (renderType != ShipWaterPocketEmbeddiumRenderTypes.embeddiumWaterTranslucent()) return;

        final Object translucentMaterial = valkyrienair$getTranslucentMaterial();
        if (translucentMaterial != null) {
            cir.setReturnValue(translucentMaterial);
        }
    }
}

