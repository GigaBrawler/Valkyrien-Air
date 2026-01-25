package org.valkyrienskies.valkyrienair.client.feature.ship_water_pockets;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Objects;
import java.util.function.IntSupplier;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.joml.primitives.AABBdc;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.core.api.ships.LoadedShip;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.valkyrienair.config.ValkyrienAirConfig;
import org.valkyrienskies.valkyrienair.feature.ship_water_pockets.ShipWaterPocketManager;

/**
 * Screen-space underwater fog when looking through submerged ship windows/holes.
 *
 * <p>This renders a depth-only "entry surface" mask (in world space) and then composites underwater fog in a fullscreen
 * pass based on the amount of water along the view ray: {@code waterLen = sceneDepth - entryDepth}.</p>
 */
public final class ShipWaterPocketUnderwaterViewFog {

    private ShipWaterPocketUnderwaterViewFog() {}

    private static final Logger LOGGER = LogManager.getLogger("ValkyrienAir UnderwaterViewFog");

    private static final ResourceLocation SHADER_ID = new ResourceLocation("valkyrienair", "ship_underwater_fog");

    private static boolean shaderLoadAttempted = false;
    private static ShaderInstance shader = null;

    // Offscreen buffers/textures (render-thread only).
    private static int targetWidth = 0;
    private static int targetHeight = 0;

    private static int opaqueDepthFbo = 0;
    private static int opaqueDepthTex = 0;
    private static int opaqueDepthInternalFormat = 0;
    private static boolean opaqueDepthIsDepthStencil = false;
    private static boolean opaqueDepthValid = false;

    private static int entryFbo = 0;
    private static int entryColorTex = 0;
    private static int entryDepthTex = 0;

    private static long lastOpaqueDepthCaptureKey = Long.MIN_VALUE;
    private static boolean loggedCompositeActive = false;
    private static boolean loggedDepthProbe = false;
    private static boolean loggedDepthBlitError = false;

    public static void clear() {
        if (shader != null) {
            shader.close();
            shader = null;
        }
        shaderLoadAttempted = false;
        loggedCompositeActive = false;

        destroyBuffers();
        lastOpaqueDepthCaptureKey = Long.MIN_VALUE;
    }

    private static void destroyBuffers() {
        if (opaqueDepthFbo != 0) {
            GlStateManager._glDeleteFramebuffers(opaqueDepthFbo);
            opaqueDepthFbo = 0;
        }
        if (entryFbo != 0) {
            GlStateManager._glDeleteFramebuffers(entryFbo);
            entryFbo = 0;
        }

        if (opaqueDepthTex != 0) {
            TextureUtil.releaseTextureId(opaqueDepthTex);
            opaqueDepthTex = 0;
        }
        if (entryColorTex != 0) {
            TextureUtil.releaseTextureId(entryColorTex);
            entryColorTex = 0;
        }
        if (entryDepthTex != 0) {
            TextureUtil.releaseTextureId(entryDepthTex);
            entryDepthTex = 0;
        }

        targetWidth = 0;
        targetHeight = 0;
        opaqueDepthInternalFormat = 0;
        opaqueDepthIsDepthStencil = false;
        opaqueDepthValid = false;
        lastOpaqueDepthCaptureKey = Long.MIN_VALUE;
    }

    private static int[] getViewport() {
        final int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        return viewport;
    }

    private static ShaderInstance getShader() {
        if (shader != null) return shader;
        if (shaderLoadAttempted) return null;
        shaderLoadAttempted = true;

        final Minecraft mc = Minecraft.getInstance();
        try {
            shader = new ShaderInstance(mc.getResourceManager(), SHADER_ID.toString(), DefaultVertexFormat.POSITION_TEX);
            shader.setSampler("OpaqueDepthTex", (IntSupplier) () -> opaqueDepthTex);
            shader.setSampler("EntryDepthTex", (IntSupplier) () -> entryDepthTex);
            shader.setSampler("EntryMaskTex", (IntSupplier) () -> entryColorTex);
            LOGGER.info("Loaded ship underwater-view fog shader");
        } catch (final IOException e) {
            LOGGER.error("Failed to load ship underwater-view fog shader", e);
            shader = null;
        }
        return shader;
    }

    private static void ensureBuffers(final int width, final int height, final int wantedOpaqueDepthInternalFormat,
        final boolean wantedOpaqueDepthIsDepthStencil) {
        if (width <= 0 || height <= 0) return;
        final int depthFormat = wantedOpaqueDepthInternalFormat != 0 ? wantedOpaqueDepthInternalFormat : GL11.GL_DEPTH_COMPONENT;
        if (width == targetWidth && height == targetHeight
            && opaqueDepthInternalFormat == depthFormat
            && opaqueDepthIsDepthStencil == wantedOpaqueDepthIsDepthStencil
            && opaqueDepthFbo != 0
            && entryFbo != 0) {
            return;
        }

        destroyBuffers();
        targetWidth = width;
        targetHeight = height;

        opaqueDepthInternalFormat = depthFormat;
        opaqueDepthIsDepthStencil = wantedOpaqueDepthIsDepthStencil;

        opaqueDepthTex = createDepthTex(width, height, opaqueDepthInternalFormat);
        opaqueDepthFbo = createFbo(0, opaqueDepthTex, opaqueDepthIsDepthStencil);

        entryColorTex = createColorTex(width, height);
        entryDepthTex = createDepthTex(width, height, GL11.GL_DEPTH_COMPONENT);
        entryFbo = createFbo(entryColorTex, entryDepthTex, false);
    }

    private static int createColorTex(final int width, final int height) {
        final int prevBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            final int id = TextureUtil.generateTextureId();
            GlStateManager._bindTexture(id);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            return id;
        } finally {
            GlStateManager._bindTexture(prevBinding);
        }
    }

    private static int createDepthTex(final int width, final int height, final int internalFormat) {
        final int prevBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            final int id = TextureUtil.generateTextureId();
            GlStateManager._bindTexture(id);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);

            final boolean depthStencil =
                internalFormat == GL30.GL_DEPTH24_STENCIL8 || internalFormat == GL30.GL_DEPTH32F_STENCIL8;
            final int format = depthStencil ? GL30.GL_DEPTH_STENCIL : GL11.GL_DEPTH_COMPONENT;
            final int type;
            if (internalFormat == GL30.GL_DEPTH32F_STENCIL8) {
                type = GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV;
            } else if (depthStencil) {
                type = GL30.GL_UNSIGNED_INT_24_8;
            } else if (internalFormat == GL30.GL_DEPTH_COMPONENT32F || internalFormat == GL11.GL_DEPTH_COMPONENT) {
                type = GL11.GL_FLOAT;
            } else if (internalFormat == GL30.GL_DEPTH_COMPONENT16) {
                type = GL11.GL_UNSIGNED_SHORT;
            } else {
                type = GL11.GL_UNSIGNED_INT;
            }

            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, (ByteBuffer) null);
            return id;
        } finally {
            GlStateManager._bindTexture(prevBinding);
        }
    }

    private static int createFbo(final int colorTex, final int depthTex, final boolean depthStencil) {
        final int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        try {
            final int fbo = GlStateManager.glGenFramebuffers();
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);

            if (colorTex != 0) {
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorTex, 0);
                GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
                GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            } else {
                GL11.glDrawBuffer(GL11.GL_NONE);
                GL11.glReadBuffer(GL11.GL_NONE);
            }

            if (depthTex != 0) {
                final int attachment = depthStencil ? GL30.GL_DEPTH_STENCIL_ATTACHMENT : GL30.GL_DEPTH_ATTACHMENT;
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, attachment, GL11.GL_TEXTURE_2D, depthTex, 0);
            }

            final int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                LOGGER.error("UnderwaterViewFog framebuffer incomplete: 0x{}", Integer.toHexString(status));
            }

            return fbo;
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        }
    }

    /**
     * Capture the opaque-world depth buffer into {@code OpaqueDepthTex} right before the translucent world pass begins.
     */
    public static void captureOpaqueDepthIfNeeded(final net.minecraft.client.multiplayer.ClientLevel level) {
        if (!ValkyrienAirConfig.getEnableShipWaterPockets()) return;
        if (level == null) return;
        final Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) return;

        // If the camera is underwater, vanilla fog already applies and we don't need this path.
        final Camera camera = mc.gameRenderer.getMainCamera();
        if (camera.getFluidInCamera() == FogType.WATER) return;

        final var main = mc.getMainRenderTarget();

        // Use the currently bound draw framebuffer as the depth source; shader pipelines (Oculus) can render to a
        // different target than Minecraft's main render target.
        final int boundDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int srcFbo = boundDrawFbo != 0 ? boundDrawFbo : main.frameBufferId;

        DepthAttachmentInfo depthInfo = queryDepthAttachmentInfo(srcFbo);
        if (!depthInfo.hasDepthAttachment) {
            // Some pipelines bind a draw FBO without a depth buffer for specific passes; fall back to MC's main target.
            srcFbo = main.frameBufferId;
            depthInfo = queryDepthAttachmentInfo(srcFbo);
        }
        if (!depthInfo.hasDepthAttachment) {
            opaqueDepthValid = false;
            return;
        }
        ensureBuffers(main.width, main.height, depthInfo.internalFormat, depthInfo.isDepthStencil);
        if (opaqueDepthFbo == 0 || opaqueDepthTex == 0) return;

        final long frameKey = (level.getGameTime() << 8) + (long) (mc.getFrameTime() * 256.0f);
        if (frameKey == lastOpaqueDepthCaptureKey) return;
        lastOpaqueDepthCaptureKey = frameKey;

        final int prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        final int prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            // Clear any previous GL error so our error check reflects this blit, not unrelated renderer state.
            while (GL11.glGetError() != GL11.GL_NO_ERROR) {
                // no-op
            }
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, srcFbo);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, opaqueDepthFbo);
            GL30.glBlitFramebuffer(0, 0, targetWidth, targetHeight, 0, 0, targetWidth, targetHeight, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            final int err = GL11.glGetError();
            opaqueDepthValid = err == GL11.GL_NO_ERROR;
            if (err != GL11.GL_NO_ERROR && !loggedDepthBlitError) {
                loggedDepthBlitError = true;
                LOGGER.warn("UnderwaterViewFog depth blit GL error: 0x{}", Integer.toHexString(err));
            }
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
        }
    }

    public static void renderAndComposite(final net.minecraft.client.multiplayer.ClientLevel level, final Camera camera,
        final Matrix4f projectionMatrix, final Matrix4f modelViewMatrix) {
        if (!ValkyrienAirConfig.getEnableShipWaterPockets()) return;
        if (level == null || camera == null) return;

        if (camera.getFluidInCamera() == FogType.WATER) return;

        final Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) return;

        final ShaderInstance shader = getShader();
        if (shader == null) {
            // Fallback to the old geometry overlay.
            final var camPos = camera.getPosition();
            ShipWaterPocketUnderwaterViewOverlay.render(camPos.x, camPos.y, camPos.z);
            return;
        }

        final int prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        final int prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        final int[] prevViewport = getViewport();
        final var main = mc.getMainRenderTarget();
        final int targetW = main.width;
        final int targetH = main.height;

        // The scene we want to post-process is whatever framebuffer is currently bound at the end of world rendering.
        final int dstSceneFboId = prevDrawFbo;

        ensureBuffers(targetW, targetH, opaqueDepthInternalFormat, opaqueDepthIsDepthStencil);
        if (entryFbo == 0 || opaqueDepthTex == 0) return;

        try {
            // Render entry-depth mask into EntryDepthTex by drawing the same interface faces as the old overlay.
            final int faces = renderEntryMask(level, camera, projectionMatrix, modelViewMatrix);
            if (faces <= 0) return;
            if (!loggedCompositeActive) {
                loggedCompositeActive = true;
                LOGGER.info("UnderwaterViewFog composite active (faces={}, target={}x{})", faces, targetW, targetH);
            }
            if (!loggedDepthProbe) {
                loggedDepthProbe = true;
                debugProbeDepth("entry", entryFbo, targetW, targetH);
                debugProbeDepth("opaque", opaqueDepthFbo, targetW, targetH);
            }

            // Composite fullscreen fog onto the active scene framebuffer.
            compositeFog(level, camera, projectionMatrix, dstSceneFboId, targetW, targetH);
        } finally {
            // Never leave the render pipeline bound to our offscreen FBOs.
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
            RenderSystem.viewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        }
    }

    private static int renderEntryMask(final net.minecraft.client.multiplayer.ClientLevel level, final Camera camera,
        final Matrix4f projectionMatrix, final Matrix4f modelViewMatrix) {
        final int prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        final int prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        final int[] prevViewport = getViewport();

        final Matrix4f oldProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        final var oldVertexSorting = RenderSystem.getVertexSorting();
        final var modelViewStack = RenderSystem.getModelViewStack();

        // Save GL state; this pass must not leak state into the rest of the renderer.
        final boolean prevDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        final boolean prevCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        final boolean prevBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        final boolean prevScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        final boolean prevDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);

        final int prevDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        final int[] prevScissorBox = new int[4];
        if (prevScissor) {
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, prevScissorBox);
        }
        final ByteBuffer prevColorMask = BufferUtils.createByteBuffer(4);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, prevColorMask);

        // Bind our entry framebuffer and clear it.
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, entryFbo);
        RenderSystem.viewport(0, 0, targetWidth, targetHeight);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        RenderSystem.disableScissor();
        GL11.glClearColor(0f, 0f, 0f, 0f);
        GL11.glClearDepth(1.0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        // Apply world matrices so the overlay face emission works like normal world geometry.
        modelViewStack.pushPose();
        modelViewStack.setIdentity();
        modelViewStack.mulPoseMatrix(modelViewMatrix);
        RenderSystem.setProjectionMatrix(projectionMatrix, com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);
        RenderSystem.applyModelViewMatrix();

        try {
            final var camPos = camera.getPosition();
            // Render the interface geometry into our offscreen depth buffer.
            return ShipWaterPocketUnderwaterViewOverlay.renderDepthMask(camPos.x, camPos.y, camPos.z);
        } finally {
            // Restore GL state.
            RenderSystem.colorMask(prevColorMask.get(0) != 0, prevColorMask.get(1) != 0, prevColorMask.get(2) != 0, prevColorMask.get(3) != 0);

            if (prevBlend) RenderSystem.enableBlend();
            else RenderSystem.disableBlend();

            if (prevScissor) RenderSystem.enableScissor(prevScissorBox[0], prevScissorBox[1], prevScissorBox[2], prevScissorBox[3]);
            else RenderSystem.disableScissor();

            if (prevCull) RenderSystem.enableCull();
            else RenderSystem.disableCull();

            if (prevDepthTest) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
            RenderSystem.depthFunc(prevDepthFunc);
            RenderSystem.depthMask(prevDepthMask);

            modelViewStack.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(oldProj, oldVertexSorting);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
            RenderSystem.viewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        }
    }

    private static void compositeFog(final net.minecraft.client.multiplayer.ClientLevel level, final Camera camera,
        final Matrix4f projectionMatrix, final int dstFbo, final int targetW, final int targetH) {
        final Minecraft mc = Minecraft.getInstance();
        final ShaderInstance shader = Objects.requireNonNull(getShader());

        // Upload uniforms.
        final Matrix4f projInv = new Matrix4f(projectionMatrix).invert();
        if (shader.getUniform("ProjInv") != null) {
            shader.getUniform("ProjInv").set(projInv);
        }
        if (shader.getUniform("FarDepth01") != null) {
            shader.getUniform("FarDepth01").set(computeFarDepth01(projInv));
        }
        if (shader.getUniform("OpaqueDepthValid") != null) {
            shader.getUniform("OpaqueDepthValid").set(opaqueDepthValid ? 1.0f : 0.0f);
        }

        final int waterRgb = computeWaterTintRgb(level, camera);
        final float r = ((waterRgb >> 16) & 0xFF) / 255.0f;
        final float g = ((waterRgb >> 8) & 0xFF) / 255.0f;
        final float b = (waterRgb & 0xFF) / 255.0f;
        if (shader.getUniform("WaterFogColor") != null) {
            shader.getUniform("WaterFogColor").set(r, g, b);
        }

        final float time = (level.getGameTime() + mc.getFrameTime()) * 0.02f;
        if (shader.getUniform("Time") != null) {
            shader.getUniform("Time").set(time);
        }
        if (shader.getUniform("ScreenSize") != null) {
            shader.getUniform("ScreenSize").set((float) targetW, (float) targetH);
        }

        // Draw a fullscreen quad into the active scene framebuffer.
        final boolean prevDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        final boolean prevCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        final boolean prevBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        final boolean prevScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        final boolean prevDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);

        final int prevDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        final int prevBlendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        final int prevBlendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        final int prevBlendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        final int prevBlendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);

        final int[] prevScissorBox = new int[4];
        if (prevScissor) {
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, prevScissorBox);
        }

        final ByteBuffer prevColorMask = BufferUtils.createByteBuffer(4);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, prevColorMask);

        try {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, dstFbo);
            RenderSystem.viewport(0, 0, targetW, targetH);

            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();

            // Ensure the fullscreen quad isn't clipped by leftover UI scissoring.
            RenderSystem.disableScissor();

            RenderSystem.enableBlend();
            GlStateManager._blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            RenderSystem.colorMask(true, true, true, true);

            RenderSystem.setShader(() -> shader);

            final BufferBuilder bb = Tesselator.getInstance().getBuilder();
            bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            bb.vertex(-1.0f, -1.0f, 0.0f).uv(0.0f, 0.0f).endVertex();
            bb.vertex(1.0f, -1.0f, 0.0f).uv(1.0f, 0.0f).endVertex();
            bb.vertex(1.0f, 1.0f, 0.0f).uv(1.0f, 1.0f).endVertex();
            bb.vertex(-1.0f, 1.0f, 0.0f).uv(0.0f, 1.0f).endVertex();
            BufferUploader.drawWithShader(bb.end());
        } finally {
            // Restore GL state to avoid breaking downstream renderers.
            RenderSystem.colorMask(prevColorMask.get(0) != 0, prevColorMask.get(1) != 0, prevColorMask.get(2) != 0, prevColorMask.get(3) != 0);
            GlStateManager._blendFuncSeparate(prevBlendSrcRgb, prevBlendDstRgb, prevBlendSrcAlpha, prevBlendDstAlpha);

            if (prevBlend) RenderSystem.enableBlend();
            else RenderSystem.disableBlend();

            if (prevScissor) RenderSystem.enableScissor(prevScissorBox[0], prevScissorBox[1], prevScissorBox[2], prevScissorBox[3]);
            else RenderSystem.disableScissor();

            if (prevCull) RenderSystem.enableCull();
            else RenderSystem.disableCull();

            if (prevDepthTest) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
            RenderSystem.depthFunc(prevDepthFunc);

            RenderSystem.depthMask(prevDepthMask);
        }
    }

    private static void debugProbeDepth(final String label, final int fbo, final int w, final int h) {
        if (fbo == 0 || w <= 0 || h <= 0) return;

        final int prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            final FloatBuffer buf = BufferUtils.createFloatBuffer(1);

            float min = 1.0f;
            float max = 0.0f;
            int nonDefault = 0;

            // Sample a small grid; this is debug-only and runs once.
            final int samples = 8;
            for (int iy = 0; iy < samples; iy++) {
                final int y = (h * (iy * 2 + 1)) / (samples * 2);
                for (int ix = 0; ix < samples; ix++) {
                    final int x = (w * (ix * 2 + 1)) / (samples * 2);
                    buf.clear();
                    GL11.glReadPixels(x, y, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, buf);
                    final float d = buf.get(0);
                    min = Math.min(min, d);
                    max = Math.max(max, d);
                    if (d < 0.999999f) nonDefault++;
                }
            }

            LOGGER.info("UnderwaterViewFog depth probe {}: nonDefault={}, min={}, max={}", label, nonDefault, min, max);
        } catch (final Throwable t) {
            LOGGER.warn("UnderwaterViewFog depth probe {} failed", label, t);
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
        }
    }

    private static int computeWaterTintRgb(final net.minecraft.client.multiplayer.ClientLevel level, final Camera camera) {
        final Vec3 camPos = camera.getPosition();
        LoadedShip bestShip = null;
        double bestDistSq = Double.POSITIVE_INFINITY;

        for (final LoadedShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            final long shipId = ship.getId();
            final ShipWaterPocketManager.ClientWaterReachableSnapshot snapshot =
                ShipWaterPocketManager.getClientWaterReachableSnapshot(level, shipId);
            if (snapshot == null) continue;

            final double distSq = distanceSqToShipAabb(camPos, ship);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestShip = ship;
            }
        }

        if (bestShip != null) {
            final ShipTransform shipTransform = getShipTransform(bestShip);
            final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            final var worldPos = shipTransform.getPositionInWorld();
            pos.set(Mth.floor(worldPos.x()), Mth.floor(worldPos.y()), Mth.floor(worldPos.z()));
            return BiomeColors.getAverageWaterColor(level, pos);
        }

        // Fallback to camera position (should be rare).
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        pos.set(Mth.floor(camPos.x), Mth.floor(camPos.y), Mth.floor(camPos.z));
        return BiomeColors.getAverageWaterColor(level, pos);
    }

    private static double distanceSqToShipAabb(final Vec3 cameraPos, final LoadedShip ship) {
        final AABBdc shipWorldAabbDc = getShipWorldAabb(ship);
        if (shipWorldAabbDc == null) return Double.POSITIVE_INFINITY;

        final double closestX = Mth.clamp(cameraPos.x, shipWorldAabbDc.minX(), shipWorldAabbDc.maxX());
        final double closestY = Mth.clamp(cameraPos.y, shipWorldAabbDc.minY(), shipWorldAabbDc.maxY());
        final double closestZ = Mth.clamp(cameraPos.z, shipWorldAabbDc.minZ(), shipWorldAabbDc.maxZ());
        final double dx = closestX - cameraPos.x;
        final double dy = closestY - cameraPos.y;
        final double dz = closestZ - cameraPos.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static AABBdc getShipWorldAabb(final LoadedShip ship) {
        if (ship instanceof final ClientShip clientShip) {
            return clientShip.getRenderAABB();
        }
        return ship.getWorldAABB();
    }

    private static ShipTransform getShipTransform(final LoadedShip ship) {
        if (ship instanceof final ClientShip clientShip) {
            return clientShip.getRenderTransform();
        }
        return ship.getShipTransform();
    }

    private record DepthAttachmentInfo(int internalFormat, boolean isDepthStencil, boolean hasDepthAttachment) {}

    private static DepthAttachmentInfo queryDepthAttachmentInfo(final int fbo) {
        if (fbo == 0) return new DepthAttachmentInfo(GL11.GL_DEPTH_COMPONENT, false, false);

        final int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        try {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            int attachment = GL30.GL_DEPTH_ATTACHMENT;
            int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, attachment,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            if (type == GL11.GL_NONE) {
                attachment = GL30.GL_DEPTH_STENCIL_ATTACHMENT;
                type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, attachment,
                    GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            }
            if (type == GL11.GL_NONE) return new DepthAttachmentInfo(GL11.GL_DEPTH_COMPONENT, false, false);

            final int name = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER, attachment,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            if (name == 0) return new DepthAttachmentInfo(GL11.GL_DEPTH_COMPONENT, false, false);

            final int internalFormat;
            if (type == GL11.GL_TEXTURE) {
                final int prevBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                try {
                    GlStateManager._bindTexture(name);
                    internalFormat = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
                } finally {
                    GlStateManager._bindTexture(prevBinding);
                }
            } else if (type == GL30.GL_RENDERBUFFER) {
                final int prevRb = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
                try {
                    GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, name);
                    internalFormat = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_INTERNAL_FORMAT);
                } finally {
                    GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, prevRb);
                }
            } else {
                return new DepthAttachmentInfo(GL11.GL_DEPTH_COMPONENT, false, false);
            }

            final boolean depthStencil = internalFormat == GL30.GL_DEPTH24_STENCIL8 || internalFormat == GL30.GL_DEPTH32F_STENCIL8;
            final int fixedInternal = internalFormat != 0 ? internalFormat : GL11.GL_DEPTH_COMPONENT;
            return new DepthAttachmentInfo(fixedInternal, depthStencil, true);
        } catch (final Throwable t) {
            return new DepthAttachmentInfo(GL11.GL_DEPTH_COMPONENT, false, false);
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        }
    }

    private static float computeFarDepth01(final Matrix4f projInv) {
        // Determine which depth01 value maps to the far plane by comparing reconstructed view-space distances.
        final float d0 = reconstructViewDistance(projInv, 0.0f);
        final float d1 = reconstructViewDistance(projInv, 1.0f);
        if (!Float.isFinite(d0) || !Float.isFinite(d1)) return 1.0f;
        return d0 > d1 ? 0.0f : 1.0f;
    }

    private static float reconstructViewDistance(final Matrix4f projInv, final float depth01) {
        // uv center -> ndc x/y = 0
        final float ndcZ = depth01 * 2.0f - 1.0f;
        final Vector4f v = new Vector4f(0.0f, 0.0f, ndcZ, 1.0f);
        projInv.transform(v);
        final float w = Math.abs(v.w()) > 1e-6f ? v.w() : 1e-6f;
        final float x = v.x() / w;
        final float y = v.y() / w;
        final float z = v.z() / w;
        return (float) Math.sqrt(x * x + y * y + z * z);
    }
}
