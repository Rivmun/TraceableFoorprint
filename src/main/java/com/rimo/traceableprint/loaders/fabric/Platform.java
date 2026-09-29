//? if fabric {
package com.rimo.traceableprint.loaders.fabric;

import com.rimo.traceableprint.Client;
import com.rimo.traceableprint.Common;
import com.rimo.traceableprint.DedicatedServer;
import com.rimo.traceableprint.VersionUtil;
import com.rimo.traceableprint.entity.FootprintEntityRenderer;
import net.fabricmc.api.*;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

public class Platform implements ModInitializer {
	@Override
	public void onInitialize() {
		Common.init();
		// 注册脚印实体类型到内置注册表（Registries.ENTITY_TYPE 现在是 ResourceKey，实例在 BuiltInRegistries）
		Registry.register(BuiltInRegistries.ENTITY_TYPE,
				VersionUtil.getId("footprint"), Common.FOOTPRINT);
		// 配置上传：注册两个方向的 payload codec（双端都要）；服务端 C2S 接收器在 ServerInit 挂
		PayloadTypeRegistry.clientboundPlay().register(Common.UploadRequestPayload.TYPE, Common.UploadRequestPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Common.UploadConfigPayload.TYPE, Common.UploadConfigPayload.CODEC);
		Common.LOGGER.info("[TraceablePrint] Footprint entity registered");
	}

	@Environment(EnvType.CLIENT)
	public static class ClientInit implements ClientModInitializer {
		@Override
		public void onInitializeClient() {
			Client.init();
			// 仅客户端命令 /traceableprintconfig：游戏内直接打开配置屏。
			// 命令名刻意不含空格——带空格的命令会因命令前缀与服务端专用命令冲突而被覆盖。
			ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
					dispatcher.register(ClientCommands.literal("traceableprintconfig")
							.executes(ctx -> {
								Client.openConfigScreen();
								return 1;
							})));
			// 注册脚印实体渲染器（RenderState 模式）。
			// 原 EntityRendererRegistry 已弃用，改用通过 Fabric TAW 公开的原版 EntityRenderers.register
			EntityRenderers.register(Common.FOOTPRINT, FootprintEntityRenderer::new);
			// 配置上传：绑定 S2C 邀约接收器（仅客户端）
			ClientPlayNetworking.registerGlobalReceiver(Common.UploadRequestPayload.TYPE,
					(payload, context) -> Client.handleUploadRequestPayload());
		}
	}

	@Environment(EnvType.SERVER)
	public static class ServerInit implements DedicatedServerModInitializer {
		@Override
		public void onInitializeServer() {
			DedicatedServer.init();
			// 仅专用服务端注册 /traceablefp 命令与 C2S 配置接收器（集成服不走这条初始化路径）
			CommandRegistrationCallback.EVENT.register(
					(dispatcher, registryAccess, environment) -> DedicatedServer.registerCommand(dispatcher));
			ServerPlayNetworking.registerGlobalReceiver(Common.UploadConfigPayload.TYPE,
					(payload, context) -> DedicatedServer.handleUploadConfigPayload(payload, context.player()));
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
