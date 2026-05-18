package com.sabbs.fabricate.client;

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

import java.util.List;

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

    private static final long DISPLAY_MS = 10_000L;

    /**
     * Maximum width of the wrapped error text as a percentage of screen width.
     *
     * <p>0.75 means the text can use up to 75% of the screen width before
     * wrapping. It is also capped below so it does not become absurdly wide
     * on large monitors.
     */
    private static final float MAX_TEXT_WIDTH_SCREEN_FRACTION = 0.75F;

    /**
     * Absolute max text width in scaled GUI pixels.
     */
    private static final int MAX_TEXT_WIDTH_PIXELS = 360;

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
        visibleUntilMs = System.currentTimeMillis() + DISPLAY_MS;
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
        int x = screenWidth / 2;
        int y = 30; // somewhere in the upper-middle, but above the crafting grid

        int wrapWidth = Math.min(
            MAX_TEXT_WIDTH_PIXELS,
            Math.max(120, (int) (screenWidth * MAX_TEXT_WIDTH_SCREEN_FRACTION))
        );

        Component styledTitle = title.copy().withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        Component styledDetail = detail.copy().withStyle(ChatFormatting.RED);

        int nextY = drawWrappedCentered(graphics, mc, styledTitle, x, y, wrapWidth);

        if (!detail.getString().isBlank()) {
            drawWrappedCentered(graphics, mc, styledDetail, x, nextY + 2, wrapWidth);
        }
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