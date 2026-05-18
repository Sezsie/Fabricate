package com.sabbs.fabricate.network;

import com.sabbs.fabricate.Fabricate;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class NetworkHandler {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(Fabricate.MOD_ID, "main"),
        () -> PROTOCOL_VERSION,
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals
    );

    public static void register() {
        // id 0 is reserved (was the legacy CraftPacket); kept free in case
        // we want to deprecate-and-replace cleanly on a wire-protocol bump.
        CHANNEL.registerMessage(1, OptOutPacket.class,
            OptOutPacket::encode, OptOutPacket::decode, OptOutPacket::handle);
        CHANNEL.registerMessage(2, PlannerCraftPacket.class,
            PlannerCraftPacket::encode, PlannerCraftPacket::decode, PlannerCraftPacket::handle);
    }

    public static void sendToServer(OptOutPacket packet) {
        CHANNEL.sendToServer(packet);
    }

    public static void sendToServer(PlannerCraftPacket packet) {
        CHANNEL.sendToServer(packet);
    }
}
