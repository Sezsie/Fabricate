package com.sabbs.fabricate.network;

import com.sabbs.fabricate.Fabricate;
import com.sabbs.fabricate.OptOutRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client → server opt-out declaration. Sent once on login (see
 * {@code ClientEvents.onClientLoggedIn}) carrying the client's
 * {@code CLIENT_ENABLED} value. Server records it in {@link OptOutRegistry}
 * so downstream hooks (recipe-match filter, CraftPacket, ItemCraftedEvent)
 * can skip synthetic behavior for that player.
 *
 * <p>The flag is inverted on the wire: {@code optedOut = !CLIENT_ENABLED}.
 * Keeps the default (unset/false) aligned with the common case (mod active).
 */
public final class OptOutPacket {

    private final boolean optedOut;

    public OptOutPacket(boolean optedOut) {
        this.optedOut = optedOut;
    }

    public static void encode(OptOutPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.optedOut);
    }

    public static OptOutPacket decode(FriendlyByteBuf buf) {
        return new OptOutPacket(buf.readBoolean());
    }

    public static void handle(OptOutPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            OptOutRegistry.setOptedOut(player.getUUID(), msg.optedOut);
            Fabricate.LOGGER.info("[FAB-optout] {} {}",
                player.getGameProfile().getName(),
                msg.optedOut ? "opted out of synthetics" : "re-enabled synthetics");
        });
        ctx.get().setPacketHandled(true);
    }
}
