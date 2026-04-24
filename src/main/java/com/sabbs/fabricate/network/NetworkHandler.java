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
        CHANNEL.registerMessage(0, CraftPacket.class,
            CraftPacket::encode, CraftPacket::decode, CraftPacket::handle);
        CHANNEL.registerMessage(1, OptOutPacket.class,
            OptOutPacket::encode, OptOutPacket::decode, OptOutPacket::handle);
    }

    public static void sendToServer(CraftPacket packet) {
        CHANNEL.sendToServer(packet);
    }

    public static void sendToServer(OptOutPacket packet) {
        CHANNEL.sendToServer(packet);
    }
}
