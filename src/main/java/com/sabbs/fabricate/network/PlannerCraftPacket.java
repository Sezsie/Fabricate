package com.sabbs.fabricate.network;

import com.sabbs.fabricate.Fabricate;
import com.sabbs.fabricate.planner.PlannerService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.function.Supplier;

/**
 * Client -> server "I clicked X in the recipe viewer; craft it" request.
 * The server runs {@link PlannerService#planAndExecute} against the player's
 * inventory; on success, materials are consumed and the target output is
 * delivered (cursor or inventory depending on {@code toCursor}).
 *
 * <p>This replaces the legacy {@link CraftPacket}'s "look up recipe by id"
 * path. The client doesn't need to know which recipe was used - the planner
 * decides at execute time based on what the inventory actually contains.
 */
public class PlannerCraftPacket {

    private final ResourceLocation targetItemId;
    private final int qty;
    private final boolean toCursor;

    public PlannerCraftPacket(Item target, int qty, boolean toCursor) {
        this(ForgeRegistries.ITEMS.getKey(target), qty, toCursor);
    }

    private PlannerCraftPacket(ResourceLocation id, int qty, boolean toCursor) {
        this.targetItemId = id;
        this.qty = qty;
        this.toCursor = toCursor;
    }

    public static void encode(PlannerCraftPacket msg, FriendlyByteBuf buf) {
        buf.writeResourceLocation(msg.targetItemId);
        buf.writeVarInt(msg.qty);
        buf.writeBoolean(msg.toCursor);
    }

    public static PlannerCraftPacket decode(FriendlyByteBuf buf) {
        return new PlannerCraftPacket(
            buf.readResourceLocation(),
            buf.readVarInt(),
            buf.readBoolean()
        );
    }

    public static void handle(PlannerCraftPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                Fabricate.LOGGER.warn("[FAB-packet] PlannerCraftPacket with null sender");
                return;
            }

            if (com.sabbs.fabricate.OptOutRegistry.isOptedOut(player.getUUID())) {
                Fabricate.LOGGER.debug("[FAB-packet] PlannerCraftPacket from opted-out {}", player.getGameProfile().getName());
                return;
            }

            Item target = ForgeRegistries.ITEMS.getValue(msg.targetItemId);
            if (target == null) {
                Fabricate.LOGGER.warn("[FAB-packet] PlannerCraftPacket: unknown item id {}", msg.targetItemId);
                return;
            }

            PlannerService.DeliveryMode mode = msg.toCursor
                ? PlannerService.DeliveryMode.CURSOR_FIRST
                : PlannerService.DeliveryMode.INVENTORY;

            PlannerService.ExecuteResult result = PlannerService.planAndExecute(player, target, msg.qty, mode);
            if (!result.ok()) {
                Fabricate.LOGGER.debug("[FAB] {} rejected {}x {}: {}",
                    player.getGameProfile().getName(), msg.qty, msg.targetItemId, result.reason());
                return;
            }
            Fabricate.LOGGER.debug("[FAB] {} crafted {}x {}: consumed={} refund={}",
                player.getGameProfile().getName(), msg.qty, msg.targetItemId,
                result.plan().baseCost(), result.plan().byproducts());
        });
        ctx.get().setPacketHandled(true);
    }
}
