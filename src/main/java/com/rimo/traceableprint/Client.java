package com.rimo.traceableprint;

import com.rimo.traceableprint.config.ConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class Client {
	// 客户端侧节流：上次响应邀约回传配置的时间戳(ms)，防恶意服务端反复邀约
	private static final long CLIENT_SEND_THROTTLE_MS = 500L;
	private static volatile long lastClientSendMs = 0L;

	public static void init() {
		//
	}

	/**
	 * 供各平台「仅客户端命令」调用的公共动作：在游戏内直接打开配置屏，不经模组列表中转。
	 *
	 * <p>Cloth Config 缺席时不加载 {@link ConfigScreen}（其引用只在此检查之后出现，配合 JVM 按需类加载），
	 * 而是向本地玩家回一条系统提示，措辞与 Fabric/ModMenu、非 Fabric 降级屏复用同一翻译键。
	 */
	public static void openConfigScreen() {
		Minecraft mc = Minecraft.getInstance();
		if (! PlatformUtil.PLATFORM.isClothConfigLoaded()) {
			if (mc.player != null) {
				VersionUtil.sendMessage(mc.player,
						Component.translatable("text.traceableprint.config.missing_dependency").getString());
			}
			return;
		}
		//? if >=26.2 {
		mc.gui.setScreen(ConfigScreen.create(mc.gui.screen()));
		//? } else {
		/*mc.setScreen(ConfigScreen.create(mc.screen));
		*///? }
	}

	/**
	 * 客户端收到服务端 S2C 邀约 {@link Common.UploadRequestPayload} 后：
	 * 把本地 {@link Common#CONFIG} 序列化并经 {@link PlatformUtil.IPlatform#sendUploadConfig} 回传（带节流）。
	 * 真正的上传动作刻意藏在这里——只有被 /upload 邀约过的客户端才会回包。
	 */
	public static void handleUploadRequestPayload() {
		long now = System.currentTimeMillis();
		if (now - lastClientSendMs < CLIENT_SEND_THROTTLE_MS) {
			return; // 忽略短时间内的重复邀约
		}
		lastClientSendMs = now;
		PlatformUtil.PLATFORM.sendUploadConfig(Common.CONFIG.serializeJson());
	}
}
