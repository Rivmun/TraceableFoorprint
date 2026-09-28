//? if fabric {
package com.rimo.example.loaders.fabric;

import com.rimo.example.Client;
import com.rimo.example.Common;
import com.rimo.example.DedicatedServer;
import net.fabricmc.api.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

public class Platform implements ModInitializer {
	@Override
	public void onInitialize() {
		Common.init();
	}

	@Environment(EnvType.CLIENT)
	public static class ClientInit implements ClientModInitializer {
		@Override
		public void onInitializeClient() {
			Client.init();
		}
	}

	@Environment(EnvType.SERVER)
	public static class ServerInit implements DedicatedServerModInitializer {
		@Override
		public void onInitializeServer() {
			DedicatedServer.init();
		}
	}

	// - - - - - Platform specific function - - - - -
	public static boolean canReceive(ServerPlayer player, CustomPacketPayload.Type<?> type) {
		return ServerPlayNetworking.canSend(player, type);
	}
	public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
		ServerPlayNetworking.send(player, payload);
	}
	@Environment(EnvType.CLIENT)
	public static void sendToServer(CustomPacketPayload payload) {
		ClientPlayNetworking.send(payload);
	}
	public static boolean isModLoaded(String id) {
		return FabricLoader.getInstance().isModLoaded(id);
	}
	public static Path getConfigFolder() {
		return FabricLoader.getInstance().getConfigDir();
	}
	public static boolean isFabric() {
		return true;
	}
}
//? }
