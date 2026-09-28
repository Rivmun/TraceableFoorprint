//? if forge {
/*package com.rimo.example.loaders.forge;

import com.rimo.example.Client;
import com.rimo.example.Common;
import com.rimo.example.DedicatedServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
//? if = 1.16.5 {
/^import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.network.FMLNetworkConstants;
import org.apache.commons.lang3.tuple.Pair;
^///? } else {
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.network.NetworkConstants;
//? }
//? if = 1.18.2 {
/^import net.minecraftforge.client.ConfigGuiHandler;
^///? } else if > 1.19 {
import net.minecraftforge.client.ConfigScreenHandler;
//? }

@Mod(Common.MOD_ID)
@Mod.EventBusSubscriber(modid = Common.MOD_ID)
public class Platform {
	public Platform() {
		Common.init();
		DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> Client::init);
		DistExecutor.safeRunWhenOn(Dist.DEDICATED_SERVER, () -> DedicatedServer::init);

		// register configScreen
	//? if ! 1.16.5 {
		if (isModLoaded("cloth_config")) {
			ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class, () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (a, b) -> true));
			DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> Platform::registerModsPage);
		}
	}

	public static void registerModsPage() {
		//~ if < 1.19 'ConfigScreenHandler.ConfigScreenFactory' -> 'ConfigGuiHandler.ConfigGuiFactory'
		ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class, () -> new ConfigScreenHandler.ConfigScreenFactory((client, parent) -> {
			return null;
		}));
	}
	//? } else {
		/^if (isModLoaded("cloth-config")) {
			ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.DISPLAYTEST, () -> Pair.of(() -> FMLNetworkConstants.IGNORESERVERONLY, (a, b) -> true));
			DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> Platform::registerModsPage);
		}
	}

	public static void registerModsPage() {
		ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.CONFIGGUIFACTORY, () -> (client, parent) -> {
			return null;
		});
	}
	^///? }

	// - - - - - Platform specific function - - - - -
	public static boolean isModLoaded(String id) {
		return ModList.get().isLoaded(id);
	}
}
*///? }
