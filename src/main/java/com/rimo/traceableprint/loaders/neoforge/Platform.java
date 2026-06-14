//? if neoforge {
/*package com.rimo.example.loaders.neoforge;

import com.rimo.example.Client;
import com.rimo.example.Common;
import com.rimo.example.DedicatedServer;
import com.rimo.example.entity.FootprintEntityRenderer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
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
import net.neoforged.neoforge.client.event.RegisterEntityRendererEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.nio.file.Path;

@Mod(Common.MOD_ID)
@EventBusSubscriber(modid = Common.MOD_ID)
public class Platform {
	@SubscribeEvent
	public static void init(FMLCommonSetupEvent event) {
		Common.init();
	}
	
	@SubscribeEvent
	public static void onRegister(RegisterEvent event) {
		if (event.getRegistry().key().equals(net.minecraft.core.registries.Registries.ENTITY_TYPE.key())) {
			// 注册脚印实体
			event.getRegistry().register(new net.minecraft.resources.ResourceLocation(Common.MOD_ID, "footprint"), Common.FOOTPRINT);
		}
	}

	@OnlyIn(Dist.CLIENT)
	@EventBusSubscriber(modid = Common.MOD_ID, value = Dist.CLIENT)
	public static class ClientInit {
		@SubscribeEvent
		public static void clientInit(FMLClientSetupEvent event) {
			Client.init();

			//register configScreen
//			ModList modList = ModList.get();
//			if (modList.isLoaded("cloth_config")) {
//				modList.getModContainerById(Common.MOD_ID).ifPresent(container ->
//						container.registerExtensionPoint(IConfigScreenFactory.class, (modContainer, parentScreen) ->
//								new ConfigScreen().build()
//						)
//				);
//			}
		}

		@SubscribeEvent
		public static void registerRenderers(RegisterEntityRendererEvent event) {
			event.getRegistry().register(Common.FOOTPRINT, ctx -> new FootprintEntityRenderer(ctx));
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

	// - - - - - Platform specific function - - - - -
	public static boolean canReceive(ServerPlayer player, CustomPacketPayload.Type<?> type) {
		return player.connection.hasChannel(type);
	}
	public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
		PacketDistributor.sendToPlayer(player, payload);
	}
	@OnlyIn(Dist.CLIENT)
	public static void sendToServer(CustomPacketPayload payload) {
		ClientPacketDistributor.sendToServer(payload);
	}
	public static boolean isModLoaded(String id) {
		return ModList.get().isLoaded(id);
	}
	public static Path getConfigFolder() {
		return FMLPaths.CONFIGDIR.get();
	}
	public static boolean isFabric() {
		return false;
	}
}
*///? }
