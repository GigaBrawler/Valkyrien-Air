package org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets;

import net.minecraft.client.renderer.RenderType;
import org.jetbrains.annotations.Nullable;
import org.valkyrienskies.valkyrienair.mixin.client.embeddium.TerrainRenderPassAccessor;

/**
 * Tracks the current Embeddium/Sodium chunk render pass on the render thread so we can update uniforms immediately
 * after the chunk shader program is bound.
 */
public final class EmbeddiumChunkRenderContext {

    private EmbeddiumChunkRenderContext() {}

    private static int depth = 0;
    private static @Nullable RenderType currentLayer = null;

    public static void begin(final Object terrainRenderPass) {
        depth++;
        if (terrainRenderPass instanceof final TerrainRenderPassAccessor passAccessor) {
            currentLayer = passAccessor.valkyrienair$getLayer();
        } else {
            currentLayer = null;
        }
    }

    public static void end() {
        depth--;
        if (depth <= 0) {
            depth = 0;
            currentLayer = null;
        }
    }

    public static boolean isActive() {
        return depth > 0;
    }

    public static @Nullable RenderType getCurrentLayer() {
        return currentLayer;
    }
}

