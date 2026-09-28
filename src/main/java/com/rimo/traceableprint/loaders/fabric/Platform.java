//? if fabric {
package com.rimo.traceableprint.loaders.fabric;

import com.rimo.traceableprint.Client;
import com.rimo.traceableprint.Common;
import com.rimo.traceableprint.DedicatedServer;
import com.rimo.traceableprint.VersionUtil;
import com.rimo.traceableprint.entity.FootprintEntityRenderer;
import net.fabricmc.api.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

public class Platform implements ModInitializer {
	@Override
	public void onInitialize() {
		Common.init();
		// 注册脚印实体类型到内置注册表（Registries.ENTITY_TYPE 现在是 ResourceKey，实例在 BuiltInRegistries）
		Registry.register(BuiltInRegistries.ENTITY_TYPE,
				VersionUtil.getId("footprint"), Common.FOOTPRINT);
		Common.LOGGER.info("[TraceablePrint] Footprint entity registered");
	}

	@Environment(EnvType.CLIENT)
	public static class ClientInit implements ClientModInitializer {
		@Override
		public void onInitializeClient() {
			Client.init();
			// 注册脚印实体渲染器（RenderState 模式）。
			// 原 EntityRendererRegistry 已弃用，改用通过 Fabric TAW 公开的原版 EntityRenderers.register
			EntityRenderers.register(Common.FOOTPRINT, FootprintEntityRenderer::new);
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
