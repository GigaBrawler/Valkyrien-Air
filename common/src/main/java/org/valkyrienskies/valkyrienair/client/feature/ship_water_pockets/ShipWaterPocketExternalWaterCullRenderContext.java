package org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets;

import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Render-thread state for the external-world water culling shader integration.
 *
 * <p>We avoid direct INVOKE-based injections into chunk-layer render methods because other renderers can overwrite those
 * methods. Instead, we track when the world translucent chunk layer is being rendered and update uniforms when the
 * translucent shader is actually applied (see {@code MixinShaderInstance}).
 */
public final class ShipWaterPocketExternalWaterCullRenderContext {

    private ShipWaterPocketExternalWaterCullRenderContext() {}

    private static int worldTranslucentDepth = 0;
    private static int shipRenderDepth = 0;
    private static @Nullable ClientLevel level = null;
    private static double camX;
    private static double camY;
    private static double camZ;

    private static final int MAX_WORLD_STACK_DEPTH = 8;
    private static final @Nullable ClientLevel[] LEVEL_STACK = new ClientLevel[MAX_WORLD_STACK_DEPTH];
    private static final double[] CAM_X_STACK = new double[MAX_WORLD_STACK_DEPTH];
    private static final double[] CAM_Y_STACK = new double[MAX_WORLD_STACK_DEPTH];
    private static final double[] CAM_Z_STACK = new double[MAX_WORLD_STACK_DEPTH];

    public static void beginWorldTranslucentChunkLayer(final ClientLevel level, final double camX, final double camY,
        final double camZ) {
        if (ShipWaterPocketExternalWaterCullRenderContext.worldTranslucentDepth < MAX_WORLD_STACK_DEPTH) {
            final int idx = ShipWaterPocketExternalWaterCullRenderContext.worldTranslucentDepth;
            LEVEL_STACK[idx] = ShipWaterPocketExternalWaterCullRenderContext.level;
            CAM_X_STACK[idx] = ShipWaterPocketExternalWaterCullRenderContext.camX;
            CAM_Y_STACK[idx] = ShipWaterPocketExternalWaterCullRenderContext.camY;
            CAM_Z_STACK[idx] = ShipWaterPocketExternalWaterCullRenderContext.camZ;
        }

        ShipWaterPocketExternalWaterCullRenderContext.worldTranslucentDepth++;
        ShipWaterPocketExternalWaterCullRenderContext.level = level;
        ShipWaterPocketExternalWaterCullRenderContext.camX = camX;
        ShipWaterPocketExternalWaterCullRenderContext.camY = camY;
        ShipWaterPocketExternalWaterCullRenderContext.camZ = camZ;
    }

    public static void endWorldTranslucentChunkLayer() {
        ShipWaterPocketExternalWaterCullRenderContext.worldTranslucentDepth--;
        if (ShipWaterPocketExternalWaterCullRenderContext.worldTranslucentDepth <= 0) {
            ShipWaterPocketExternalWaterCullRenderContext.worldTranslucentDepth = 0;
            ShipWaterPocketExternalWaterCullRenderContext.level = null;
            ShipWaterPocketExternalWaterCullRenderContext.camX = 0.0;
            ShipWaterPocketExternalWaterCullRenderContext.camY = 0.0;
            ShipWaterPocketExternalWaterCullRenderContext.camZ = 0.0;
            return;
        }

        final int idx = ShipWaterPocketExternalWaterCullRenderContext.worldTranslucentDepth;
        if (idx >= 0 && idx < MAX_WORLD_STACK_DEPTH) {
            ShipWaterPocketExternalWaterCullRenderContext.level = LEVEL_STACK[idx];
            ShipWaterPocketExternalWaterCullRenderContext.camX = CAM_X_STACK[idx];
            ShipWaterPocketExternalWaterCullRenderContext.camY = CAM_Y_STACK[idx];
            ShipWaterPocketExternalWaterCullRenderContext.camZ = CAM_Z_STACK[idx];
            LEVEL_STACK[idx] = null;
        }
    }

    public static void beginShipRender() {
        ShipWaterPocketExternalWaterCullRenderContext.shipRenderDepth++;
    }

    public static void endShipRender() {
        ShipWaterPocketExternalWaterCullRenderContext.shipRenderDepth--;
        if (ShipWaterPocketExternalWaterCullRenderContext.shipRenderDepth < 0) {
            ShipWaterPocketExternalWaterCullRenderContext.shipRenderDepth = 0;
        }
    }

    public static void clear() {
        ShipWaterPocketExternalWaterCullRenderContext.worldTranslucentDepth = 0;
        ShipWaterPocketExternalWaterCullRenderContext.level = null;
        ShipWaterPocketExternalWaterCullRenderContext.camX = 0.0;
        ShipWaterPocketExternalWaterCullRenderContext.camY = 0.0;
        ShipWaterPocketExternalWaterCullRenderContext.camZ = 0.0;
        java.util.Arrays.fill(LEVEL_STACK, null);
        ShipWaterPocketExternalWaterCullRenderContext.shipRenderDepth = 0;
    }

    public static boolean isInWorldTranslucentChunkLayer() {
        return worldTranslucentDepth > 0;
    }

    public static boolean isInShipRender() {
        return shipRenderDepth > 0;
    }

    public static @Nullable ClientLevel getLevel() {
        return level;
    }

    public static double getCamX() {
        return camX;
    }

    public static double getCamY() {
        return camY;
    }

    public static double getCamZ() {
        return camZ;
    }
}
