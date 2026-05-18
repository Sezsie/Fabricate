package com.sabbs.fabricate.network;

import com.sabbs.fabricate.client.CraftFailureOverlay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> client packet shown when Fabricate cannot complete a click-to-craft
 * request.
 *
 * <p>The server owns the real craft attempt, so the server also owns the
 * failure reason. The client only displays the message.
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
        ctx.get().enqueueWork(() -> CraftFailureOverlay.show(msg.title, msg.detail));
        ctx.get().setPacketHandled(true);
    }
}