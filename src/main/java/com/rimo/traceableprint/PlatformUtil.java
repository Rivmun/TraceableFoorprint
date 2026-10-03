package com.rimo.traceableprint;

import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

/**
 * {@link IPlatform} 的公共入口/持有者。
 *
 * <p>{@link #PLATFORM} 是唯一的加载器实现单例，由各 {@code loaders.<平台>.Platform} 在类加载时
 * （静态初始化块）注册。注册发生在任何公共类（含 {@code Common}→{@code Config}）被初始化之前，
 * 因此 {@code Config} 在 {@code <clinit>} 中调用 {@code PLATFORM.getConfigFolder()} 是安全的。</p>
 *
 * <p>公共代码统一写 {@code Platform.PLATFORM.xxx()}；这样一来本类是唯一被公共部分 import 的
 * "Platform"，不再有 {@code //? if} 按加载器切换 import 的需要。</p>
 */
public final class PlatformUtil {
	/** 当前运行加载器的实现；由加载器初始化时赋值，之后只读。 */
	public static IPlatform PLATFORM;

	private PlatformUtil() {
	}

	/**
	 * 跨加载器（Fabric / NeoForge / Forge）能力抽象。
	 *
	 * <p>公共代码只依赖本接口，不再直接引用各 {@code loaders.<平台>.Platform}，从而消除
	 * {@code //? if fabric/forge/neoforge} 的导入桥接。方法签名刻意只用跨版本稳定类型
	 * （{@code String/boolean/Path/ServerPlayer}）：版本敏感的 payload 组装、通道/类型判定等都下放到
	 * 各平台实现内部（那里本就有 {@code //? if <= 1.20.1} 等版本分支），接口层不体现。</p>
	 *
	 * <p>由各加载器在自身 {@code Platform} 类的静态初始化块中，把对应实现注册进
	 * {@link PlatformUtil#PLATFORM} 单例；公共代码经 {@code Platform.PLATFORM.xxx()} 调用。</p>
	 */
	public interface IPlatform {
		/** Cloth Config 是否已安装（模组 id 逐加载器不同，故封装在此而非公共调用点）。 */
		boolean isClothConfigLoaded();

		/** 配置目录（{@code config/}），供 {@code traceableprint.json} 落盘。 */
		Path getConfigFolder();

		/** 服务端向玩家客户端下发 S2C 空邀约 {@code UploadRequestPayload}。 */
		void sendUploadRequest(ServerPlayer player);

		/** 客户端把序列化后的本地配置作为 C2S {@code UploadConfigPayload} 回传服务端。 */
		void sendUploadConfig(String json);
	}
}
