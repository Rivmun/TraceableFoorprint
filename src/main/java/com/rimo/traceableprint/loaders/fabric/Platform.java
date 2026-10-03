//? if fabric {
package com.rimo.traceableprint.loaders.fabric;

import com.rimo.traceableprint.*;
import com.rimo.traceableprint.entity.FootprintEntityRenderer;
import net.fabricmc.api.*;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
//~ if >= 26.1 'ClientCommandManager' -> 'ClientCommands'
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
//? if <= 1.20.1 {
/*import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
*///? } else {
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
//? }
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
//? if > 1.21.1 {
import net.minecraft.client.renderer.entity.EntityRenderers;
//? } else {
/*import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
*///? }
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
//? if <= 1.20.1 {
//? } else {
//? }
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

public class Platform implements ModInitializer {
	// 类加载时把本加载器实现注册进公共接口单例，供公共部分经 Platform.PLATFORM 调用。
	// 静态初始化先于任何方法（含 Common→Config 的 <clinit>），确保 getConfigFolder 可用。
	static {
		PlatformUtil.PLATFORM = new PlatformUtil.IPlatform() {
			@Override
			public boolean isClothConfigLoaded() {
				return FabricLoader.getInstance().isModLoaded("cloth-config2");
			}

			@Override
			public Path getConfigFolder() {
				return FabricLoader.getInstance().getConfigDir();
			}

			@Override
			public void sendUploadRequest(ServerPlayer player) {
				//? if <= 1.20.1 {
				/*ServerPlayNetworking.send(player, Common.UploadRequestPayload.TYPE, new FriendlyByteBuf(Unpooled.buffer()));
				*///? } else {
				ServerPlayNetworking.send(player, new Common.UploadRequestPayload());
				//? }
			}

			@Override
			@Environment(EnvType.CLIENT)
			public void sendUploadConfig(String json) {
				//? if <= 1.20.1 {
				/*FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
				buf.writeUtf(json);
				ClientPlayNetworking.send(Common.UploadConfigPayload.TYPE, buf);
				*///? } else {
				ClientPlayNetworking.send(new Common.UploadConfigPayload(json));
				//? }
			}
		};
	}

	@Override
	public void onInitialize() {
		Common.init();
		// 注册脚印实体类型到内置注册表（Registries.ENTITY_TYPE 现在是 ResourceKey，实例在 BuiltInRegistries）
		Registry.register(BuiltInRegistries.ENTITY_TYPE,
				VersionUtil.getId("footprint"), Common.FOOTPRINT);
		// 配置上传：注册两个方向的 payload codec（双端都要）；服务端 C2S 接收器在 ServerInit 挂
		// 1.20.1 无 PayloadTypeRegistry/CustomPacketPayload：走 fabric 通道式 API，收发在 Platform 收发函数、接收器在 ClientInit/ServerInit
		//? if <= 1.20.1 {
		//? } else {
		//~ if >= 26.1 'playS2C' -> 'clientboundPlay'
		PayloadTypeRegistry.clientboundPlay().register(Common.UploadRequestPayload.TYPE, Common.UploadRequestPayload.CODEC);
		//~ if >= 26.1 'playC2S' -> 'serverboundPlay'
		PayloadTypeRegistry.serverboundPlay().register(Common.UploadConfigPayload.TYPE, Common.UploadConfigPayload.CODEC);
		//? }
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
					//~ if >= 26.1 'ClientCommandManager' -> 'ClientCommands'
					dispatcher.register(ClientCommands.literal("traceableprintconfig")
							.executes(ctx -> {
								Client.openConfigScreen();
								return 1;
							})));
			// 注册脚印实体渲染器。
			//? if > 1.21.1 {
			// 新版：原 EntityRendererRegistry 已弃用，改用通过 Fabric TAW 公开的原版 EntityRenderers.register
			EntityRenderers.register(Common.FOOTPRINT, FootprintEntityRenderer::new);
			//? } else {
			/*// 1.21.1：EntityRenderers.register 仍为 private（无 TAW 公开），走 fabric-api 的 EntityRendererRegistry
			EntityRendererRegistry.register(Common.FOOTPRINT, FootprintEntityRenderer::new);
			*///? }
			// 配置上传：绑定 S2C 邀约接收器（仅客户端）
			//? if <= 1.20.1 {
			/*ClientPlayNetworking.registerGlobalReceiver(Common.UploadRequestPayload.TYPE,
					(client, handler, buf, sender) -> client.execute(Client::handleUploadRequestPayload));
			*///? } else {
			ClientPlayNetworking.registerGlobalReceiver(Common.UploadRequestPayload.TYPE,
					(payload, context) -> Client.handleUploadRequestPayload());
			//? }
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
			//? if <= 1.20.1 {
			/*ServerPlayNetworking.registerGlobalReceiver(Common.UploadConfigPayload.TYPE,
					(server, player, handler, buf, sender) -> {
						String json = buf.readUtf();
						server.execute(() -> DedicatedServer.handleUploadConfigPayload(new Common.UploadConfigPayload(json), player));
					});
			*///? } else {
			ServerPlayNetworking.registerGlobalReceiver(Common.UploadConfigPayload.TYPE,
					(payload, context) -> DedicatedServer.handleUploadConfigPayload(payload, context.player()));
			//? }
		}
	}
}
//? }
