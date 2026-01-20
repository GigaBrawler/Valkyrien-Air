package org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets;

import net.minecraft.client.renderer.RenderType;
import org.valkyrienskies.valkyrienair.mixin.client.renderer.RenderTypeAccessor;

/**
 * Embeddium compatibility for external-world water surface culling.
 *
 * <p>Embeddium can replace the vanilla terrain rendering pipeline. To preserve the exact same behavior as vanilla
 * (including our patched {@code rendertype_translucent} shader), we expose a custom {@link RenderType} that mirrors
 * {@link RenderType#translucent()} and can be selected specifically for water when Embeddium is present.
 */
public final class ShipWaterPocketEmbeddiumRenderTypes {

    private ShipWaterPocketEmbeddiumRenderTypes() {}

    private static volatile RenderType embeddiumWaterTranslucent = null;

    /**
     * A RenderType with identical render-state to {@link RenderType#translucent()}.
     *
     * <p>It delegates setup/clear to the vanilla translucent RenderType, so it stays bit-for-bit consistent with vanilla
     * while still being a distinct RenderType instance.
     */
    public static RenderType embeddiumWaterTranslucent() {
        RenderType cached = embeddiumWaterTranslucent;
        if (cached != null) return cached;

        final RenderType base = RenderType.translucent();
        final boolean sortOnUpload = ((RenderTypeAccessor) base).valkyrienair$sortOnUpload();
        cached = new RenderType(
            "valkyrienair_translucent_embeddium",
            base.format(),
            base.mode(),
            base.bufferSize(),
            base.affectsCrumbling(),
            sortOnUpload,
            base::setupRenderState,
            base::clearRenderState
        ) {};

        embeddiumWaterTranslucent = cached;
        return cached;
    }

    public static boolean isWorldTranslucentPass(final RenderType renderType) {
        if (renderType == RenderType.translucent()) return true;
        return renderType == embeddiumWaterTranslucent();
    }
}
