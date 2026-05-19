package com.sabbs.fabricate.network;

import com.sabbs.fabricate.ModConfig;
import com.sabbs.fabricate.client.CraftFailureOverlay;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> client packet shown when Fabricate cannot complete a click-to-craft
 * request.
 *
 * <p>The server owns the real craft attempt, so the server also owns the
 * failure reason. The client only displays the message.
 *
 * <p>Where it's displayed is a client-side choice driven by
 * {@link ModConfig#FAILURE_DISPLAY}: either the transient red overlay above
 * the active container screen, or a normal chat message that persists in
 * the chat log.
 */
public final class CraftFailurePacket {

    private final Component title;
    private final Component detail;

    public CraftFailurePacket(Component title, Component detail) {
        this.title = title;
        this.detail = detail;
    }

    public static void encode(CraftFailurePacket msg, FriendlyByteBuf buf) {
        buf.writeComponent(msg.title);
        buf.writeComponent(msg.detail);
    }

    public static CraftFailurePacket decode(FriendlyByteBuf buf) {
        return new CraftFailurePacket(
            buf.readComponent(),
            buf.readComponent()
        );
    }

    public static void handle(CraftFailurePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> deliver(msg));
        ctx.get().setPacketHandled(true);
    }

    /**
     * Dispatch the failure to whichever surface the player has configured.
     * Defaults to overlay if the config isn't loaded yet (very early client
     * states).
     */
    private static void deliver(CraftFailurePacket msg) {
        ModConfig.FailureDisplay target;
        try {
            target = ModConfig.FAILURE_DISPLAY.get();
        } catch (Throwable t) {
            target = ModConfig.FailureDisplay.OVERLAY;
        }

        switch (target) {
            case CHAT -> deliverToChat(msg);
            case OVERLAY -> CraftFailureOverlay.show(msg.title, msg.detail);
        }
    }

    /**
     * Send the failure as a single red chat line: "{title}: {detail}", or
     * just "{title}" when the detail is empty.
     */
    private static void deliverToChat(CraftFailurePacket msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            // Fall back to overlay if there's no player yet (shouldn't happen
            // in practice, but cheap to guard).
            CraftFailureOverlay.show(msg.title, msg.detail);
            return;
        }

        MutableComponent line = msg.title.copy().withStyle(ChatFormatting.RED);
        if (msg.detail != null && !msg.detail.getString().isBlank()) {
            line = line
                .append(Component.literal(": ").withStyle(ChatFormatting.RED))
                .append(msg.detail.copy().withStyle(ChatFormatting.RED));
        }

        // false = surface as a normal chat message, not the action bar.
        mc.player.displayClientMessage(line, false);
    }
}