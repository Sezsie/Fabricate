package com.sabbs.fabricate.network;

import com.sabbs.fabricate.Fabricate;
import com.sabbs.fabricate.planner.PlannerService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Client -> server "I clicked X in the recipe viewer; craft it" request.
 * The server runs {@link PlannerService#planAndExecute} against the player's
 * inventory; on success, materials are consumed and the target output is
 * delivered (cursor or inventory depending on {@code toCursor}).
 *
 * <p>This replaces the legacy CraftPacket's "look up recipe by id" path.
 * The client doesn't need to know which recipe was used. The planner decides
 * at execute time based on what the inventory actually contains.
 *
 * <p>Important safety note:
 * This packet is treated as a request, not a trusted instruction. The server
 * clamps requested quantities, rate-limits craft requests per player, and
 * prevents overlapping planner work from the same player.
 */
public class PlannerCraftPacket {

    private static final long CRAFT_COOLDOWN_MS = 150L;

    private static final Map<UUID, Long> LAST_CRAFT_REQUEST =
        new ConcurrentHashMap<>();

    private static final Set<UUID> ACTIVE_CRAFT_REQUESTS =
        ConcurrentHashMap.newKeySet();

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
                Fabricate.LOGGER.info("[FAB-packet] rejected opted-out player: player={}, target={}, qty={}, toCursor={}",
                    player.getGameProfile().getName(),
                    msg.targetItemId,
                    msg.qty,
                    msg.toCursor);
                return;
            }

            Item target = ForgeRegistries.ITEMS.getValue(msg.targetItemId);

            if (target == null) {
                Fabricate.LOGGER.warn("[FAB-packet] PlannerCraftPacket: unknown item id {}", msg.targetItemId);
                return;
            }

            Fabricate.LOGGER.info("[FAB-packet] request from {}: qty={}, targetItemId={}, resolvedItem={}, toCursor={}",
                player.getGameProfile().getName(),
                msg.qty,
                msg.targetItemId,
                ForgeRegistries.ITEMS.getKey(target),
                msg.toCursor);

            int safeQty = sanitizeQuantity(player, target, msg);

            if (safeQty < 1) {
                return;
            }

            if (isOnCooldown(player)) {
                Fabricate.LOGGER.info("[FAB-packet] rejected craft spam: player={}, qty={}, target={}, toCursor={}",
                    player.getGameProfile().getName(),
                    safeQty,
                    msg.targetItemId,
                    msg.toCursor);
                return;
            }

            if (!tryEnterCraft(player)) {
                Fabricate.LOGGER.info("[FAB-packet] rejected overlapping craft request: player={}, qty={}, target={}, toCursor={}",
                    player.getGameProfile().getName(),
                    safeQty,
                    msg.targetItemId,
                    msg.toCursor);
                return;
            }

            try {
                PlannerService.DeliveryMode mode = msg.toCursor
                    ? PlannerService.DeliveryMode.CURSOR_FIRST
                    : PlannerService.DeliveryMode.INVENTORY;

                PlannerService.ExecuteResult result =
                    PlannerService.planAndExecute(player, target, safeQty, mode);

                if (!result.ok()) {
                    Fabricate.LOGGER.info("[FAB-packet] rejected request from {}: qty={}, target={}, mode={}, reason={}",
                        player.getGameProfile().getName(),
                        safeQty,
                        msg.targetItemId,
                        mode,
                        result.reason());

                    PlannerService.FailureFeedback feedback = PlannerService.explainFailure(player, target, safeQty);
                    NetworkHandler.sendToPlayer(player, new CraftFailurePacket(feedback.title(), feedback.detail()));

                    return;
                }

                Fabricate.LOGGER.info("[FAB-packet] crafted successfully: player={}, qty={}, target={}, consumed={}, refund={}",
                    player.getGameProfile().getName(),
                    safeQty,
                    msg.targetItemId,
                    result.plan().baseCost(),
                    result.plan().byproducts());
            } finally {
                exitCraft(player);
            }
        });

        ctx.get().setPacketHandled(true);
    }

    /**
     * Validate and clamp the client-requested quantity.
     *
     * <p>The client normally sends sane values, such as 1 or max stack size,
     * but the server must not trust that. A malicious client can send any int.
     *
     * @return a safe quantity in the range 1..64, further capped by the
     * target item's max stack size; returns -1 when the request should be
     * rejected outright.
     */
    private static int sanitizeQuantity(ServerPlayer player, Item target, PlannerCraftPacket msg) {
        if (msg.qty < 1) {
            Fabricate.LOGGER.info("[FAB-packet] rejected invalid qty: player={}, qty={}, target={}",
                player.getGameProfile().getName(),
                msg.qty,
                msg.targetItemId);
            return -1;
        }

        int itemMaxStack = Math.max(1, target.getDefaultInstance().getMaxStackSize());
        int maxQty = Math.min(64, itemMaxStack);
        int safeQty = Math.min(msg.qty, maxQty);

        if (safeQty != msg.qty) {
            Fabricate.LOGGER.info("[FAB-packet] clamped qty: player={}, target={}, requested={}, safe={}",
                player.getGameProfile().getName(),
                msg.targetItemId,
                msg.qty,
                safeQty);
        }

        return safeQty;
    }

    /**
     * Returns true when this player has sent an accepted craft request too
     * recently.
     *
     * <p>The timestamp is updated only when the request is allowed through.
     * Rejected spam does not keep extending the cooldown forever.
     */
    private static boolean isOnCooldown(ServerPlayer player) {
        long now = System.currentTimeMillis();
        UUID id = player.getUUID();

        Long last = LAST_CRAFT_REQUEST.get(id);

        if (last != null && now - last < CRAFT_COOLDOWN_MS) {
            return true;
        }

        LAST_CRAFT_REQUEST.put(id, now);
        return false;
    }

    /**
     * Try to mark this player as actively processing a Fabricate craft.
     *
     * @return true if the player was not already processing another request.
     */
    private static boolean tryEnterCraft(ServerPlayer player) {
        return ACTIVE_CRAFT_REQUESTS.add(player.getUUID());
    }

    /** Clear this player's active craft marker. */
    private static void exitCraft(ServerPlayer player) {
        ACTIVE_CRAFT_REQUESTS.remove(player.getUUID());
    }
}