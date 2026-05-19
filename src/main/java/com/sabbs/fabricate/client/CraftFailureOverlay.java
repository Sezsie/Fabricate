package com.sabbs.fabricate.client;

import java.util.List;

import com.sabbs.fabricate.Fabricate;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Lightweight red text overlay shown above crafting/container screens when
 * Fabricate cannot craft the requested item.
 *
 * <p>This renders in ScreenEvent.Render.Post instead of RenderGuiOverlayEvent
 * so it appears above the active inventory/crafting screen and above vignette-
 * style overlays that would otherwise visually sit over HUD-layer text.
 */
@Mod.EventBusSubscriber(modid = Fabricate.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CraftFailureOverlay {

    private CraftFailureOverlay() {}

    /**
     * Absolute max text width in scaled GUI pixels. The configurable
     * fraction in {@link com.sabbs.fabricate.ModConfig#OVERLAY_MAX_WIDTH_FRACTION}
     * is clamped against this to keep the message from spanning the entire
     * width of an ultrawide monitor when the player has set a large fraction.
     */
    private static final int MAX_TEXT_WIDTH_PIXELS = 360;

    /**
     * Minimum wrap width in scaled GUI pixels. Prevents the text from
     * wrapping every word when {@code maxWidthFraction} is set very small
     * or the screen is very narrow.
     */
    private static final int MIN_TEXT_WIDTH_PIXELS = 120;

    /**
     * Space between rendered lines.
     */
    private static final int LINE_HEIGHT = 10;

    private static Component title = Component.empty();
    private static Component detail = Component.empty();
    private static long visibleUntilMs = 0L;

    /**
     * Show a new failure message.
     */
    public static void show(Component newTitle, Component newDetail) {
        title = newTitle == null ? Component.empty() : newTitle;
        detail = newDetail == null ? Component.empty() : newDetail;
        visibleUntilMs = System.currentTimeMillis() + com.sabbs.fabricate.FabricateLimits.FAILURE_DISPLAY_MS;
    }

    /**
     * Render after the active screen renders.
     *
     * <p>This is the correct layer for messages that should sit above the
     * crafting table / inventory UI itself.
     */
    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null) return;
        if (!(event.getScreen() instanceof AbstractContainerScreen<?>)) return;
        if (System.currentTimeMillis() > visibleUntilMs) return;

        GuiGraphics graphics = event.getGuiGraphics();

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        double xFraction = com.sabbs.fabricate.ModConfig.OVERLAY_X_FRACTION.get();
        double yFraction = com.sabbs.fabricate.ModConfig.OVERLAY_Y_FRACTION.get();
        double maxWidthFraction = com.sabbs.fabricate.ModConfig.OVERLAY_MAX_WIDTH_FRACTION.get();
        float textScale = (float) (double) com.sabbs.fabricate.ModConfig.OVERLAY_TEXT_SCALE.get();

        // Desired on-screen position (where the text center / top should land).
        int screenX = (int) (screenWidth * xFraction);
        int screenY = (int) (screenHeight * yFraction);

        int wrapWidth = Math.min(
            MAX_TEXT_WIDTH_PIXELS,
            Math.max(MIN_TEXT_WIDTH_PIXELS, (int) (screenWidth * maxWidthFraction))
        );

        Component styledTitle = title.copy().withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        Component styledDetail = detail.copy().withStyle(ChatFormatting.RED);

        // Scale the pose so the font renders at textScale x its natural size.
        // After scaling, drawing at (screenX/scale, screenY/scale) in scaled
        // space lands the text at (screenX, screenY) in actual screen pixels.
        com.mojang.blaze3d.vertex.PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.scale(textScale, textScale, 1.0F);

        int scaledX = Math.round(screenX / textScale);
        int scaledY = Math.round(screenY / textScale);

        int nextY = drawWrappedCentered(graphics, mc, styledTitle, scaledX, scaledY, wrapWidth);

        if (!detail.getString().isBlank()) {
            drawWrappedCentered(graphics, mc, styledDetail, scaledX, nextY + 2, wrapWidth);
        }

        pose.popPose();
    }

    /**
     * Draw a component centered and wrapped to {@code wrapWidth}.
     *
     * @return the y coordinate immediately after the last rendered line.
     */
    private static int drawWrappedCentered(
        GuiGraphics graphics,
        Minecraft mc,
        Component text,
        int centerX,
        int startY,
        int wrapWidth
    ) {
        List<FormattedCharSequence> lines = mc.font.split(text, wrapWidth);

        int y = startY;

        for (FormattedCharSequence line : lines) {
            graphics.drawCenteredString(mc.font, line, centerX, y, 0xFFFF5555);
            y += LINE_HEIGHT;
        }

        return y;
    }
}