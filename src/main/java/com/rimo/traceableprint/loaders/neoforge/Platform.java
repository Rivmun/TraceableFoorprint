//? if neoforge {
/*package com.rimo.traceableprint.loaders.neoforge;

import com.rimo.traceableprint.*;
import com.rimo.traceableprint.config.ConfigScreen;
import com.rimo.traceableprint.config.MissingDependencyScreen;
import com.rimo.traceableprint.entity.FootprintEntityRenderer;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
//? if <= 1.21.1 {
//? } else {
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
//? }
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.nio.file.Path;

@Mod(Common.MOD_ID)
@EventBusSubscriber(modid = Common.MOD_ID)
public class Platform {
	// 类加载时把本加载器实现注册进公共接口单例（@Mod 实例化先于任何事件/注册），供公共部分经 PlatformUtil.PLATFORM 调用。
	static {
		PlatformUtil.PLATFORM = new PlatformUtil.IPlatform() {
			@Override
			public boolean isClothConfigLoaded() {
				return ModList.get().isLoaded("cloth_config");
			}

			@Override
			public Path getConfigFolder() {
				return FMLPaths.CONFIGDIR.get();
			}

			@Override
			public void sendUploadRequest(ServerPlayer player) {
				PacketDistributor.sendToPlayer(player, new Common.UploadRequestPayload());
			}

			@Override
			@OnlyIn(Dist.CLIENT)
			public void sendUploadConfig(String json) {
				Common.UploadConfigPayload payload = new Common.UploadConfigPayload(json);
				//? if <= 1.21.1 {
				/^PacketDistributor.sendToServer(payload); // 1.21.1（NeoForge 21.1）无 ClientPacketDistributor，sendToServer 仍在 PacketDistributor 上
				^///? } else {
				ClientPacketDistributor.sendToServer(payload);
				//? }
			}
		};
	}

	@SubscribeEvent
	public static void init(FMLCommonSetupEvent event) {
		Common.init();
	}

	@SubscribeEvent
	public static void onRegister(RegisterEvent event) {
		if (event.getRegistryKey().equals(Registries.ENTITY_TYPE)) {
			// 注册脚印实体
			event.register(Registries.ENTITY_TYPE, VersionUtil.getId("footprint"), () -> Common.FOOTPRINT);
		}
	}

	@OnlyIn(Dist.CLIENT)
	@EventBusSubscriber(modid = Common.MOD_ID, value = Dist.CLIENT)
	public static class ClientInit {
		@SubscribeEvent
		public static void clientInit(FMLClientSetupEvent event) {
			Client.init();

			// 注册配置屏入口：NeoForge 的内建模组列表只有在注册了 IConfigScreenFactory 后才显示「配置」按钮，
			// 且不像 ModMenu 那样把入口异常渲染成悬停提示。所以这里【无条件注册】，把 Cloth Config 的有无判断
			// 挪到 createScreen 里：装了返回 ClothConfig 配置屏，没装返回 cloth 无关的降级提示屏。
			// ConfigScreen 的引用只发生在「已装 cloth」分支，配合 JVM 按需类加载，cloth 缺席时该类不会被解析。
			ModList.get().getModContainerById(Common.MOD_ID).ifPresent(container ->
					container.registerExtensionPoint(IConfigScreenFactory.class, (modContainer, parentScreen) ->
						ModList.get().isLoaded("cloth_config")
									? ConfigScreen.create(parentScreen)
									: new MissingDependencyScreen(parentScreen))
			);
		}

		/^*
		 * 注册仅客户端命令 {@code /traceableprintconfig}：在游戏内直接打开配置屏，不经模组列表中转。
		 * 名字刻意不含空格——Fabric 上带空格的命令会因命令前缀与服务端专用命令冲突而被覆盖。
		 * 本类带 {@code @EventBusSubscriber(value = Dist.CLIENT)}，NeoForge 按事件类型自动路由到客户端游戏总线，无需手动 addListener。
		 ^/
		@SubscribeEvent
		public static void registerClientCommands(RegisterClientCommandsEvent event) {
			event.getDispatcher().register(Commands.literal("traceableprintconfig")
					.executes(ctx -> {
						Client.openConfigScreen();
						return 1;
					}));
		}

		@SubscribeEvent
		public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
			event.registerEntityRenderer(Common.FOOTPRINT, FootprintEntityRenderer::new);
		}
	}

	@OnlyIn(Dist.DEDICATED_SERVER)
	@EventBusSubscriber(modid = Common.MOD_ID, value = Dist.DEDICATED_SERVER)
	public static class ServerInit {
		@SubscribeEvent
		public static void init(FMLDedicatedServerSetupEvent event) {
			DedicatedServer.init();
		}
	}
}
*///? }
