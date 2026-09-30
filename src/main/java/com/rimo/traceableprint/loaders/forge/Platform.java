//? if forge {
/*package com.rimo.traceableprint.loaders.forge;

import com.rimo.traceableprint.Client;
import com.rimo.traceableprint.Common;
import com.rimo.traceableprint.DedicatedServer;
import com.rimo.traceableprint.VersionUtil;
import com.rimo.traceableprint.config.ConfigScreen;
import com.rimo.traceableprint.config.MissingDependencyScreen;
import com.rimo.traceableprint.entity.FootprintEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkConstants;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Path;

// 1.20.1 Forge 入口：老 Forge（47.x）用 SimpleChannel + FriendlyByteBuf（PacketBuffer）通道式网络，
// 与 fabric 的 channel+FriendlyByteBuf 语义对齐；实体走 DeferredRegister，其余（网络/渲染器/命令/配置屏）全部走 @SubscribeEvent 事件订阅。
// 注：1.20.1 javafml 只支持无参构造器（IEventBus 注入是 1.20.4+），mod 总线须经 FMLJavaModLoadingContext 自取。
@Mod(Common.MOD_ID)
public class Platform {
	// - - - - - 网络：单通道 SimpleChannel，两类 payload 各占一个 discriminator - - - - -
	private static final String PROTOCOL_VERSION = "1";
	private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
			VersionUtil.getId("main"),
			() -> PROTOCOL_VERSION,
			PROTOCOL_VERSION::equals,
			PROTOCOL_VERSION::equals);

	// 实体类型注册：Common.FOOTPRINT 已在 Common 里用 EntityType.Builder 构建，这里只把它登记进 Forge 注册表。
	// DeferredRegister.register(bus) 本质就是订阅 MOD 总线的 RegisterEvent。
	private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
			DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Common.MOD_ID);

	static {
		ENTITY_TYPES.register("footprint", () -> Common.FOOTPRINT);
	}

	// mod 构造期（双端都跑）：只挂实体总线 + 声明远程可连。
	// 【关键时序】老 Forge 会把 EntityType 构造视作注册操作（Forge patch 的 EntityType.<init> 进 NamespacedWrapper），
	// mod 构造期注册表窗口未开，触发 Common.<clinit>（内含 FOOTPRINT build）会直接抛 "Registry is already frozen"。
	// 故此处绝不触碰 Common 实例字段：Common 的类初始化由下方 DeferredRegister supplier 在 RegisterEvent 窗口内首次访问完成。
	public Platform() {
		IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
		ENTITY_TYPES.register(modEventBus);

		// 允许未装本模组的客户端连接（服务端可单人/联机进服），与 fabric/neoforge 的 IGNORESERVERONLY 一致。
		ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
				() -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (remote, isServer) -> true));
	}

	// 通用初始化（MOD 总线、双端）：注册 payload 收发。官方约定 registerMessage 在 commonSetup 时机做；
	// 此阶段实体注册表已冻结、Common 已由 RegisterEvent 完成初始化，访问其嵌套类/静态字段安全。
	@Mod.EventBusSubscriber(modid = Common.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
	public static class CommonEvents {
		@SubscribeEvent
		public static void onCommonSetup(FMLCommonSetupEvent event) {
			event.enqueueWork(Platform::registerNetworking);
		}
	}

	// S2C 邀约（无字段）与 C2S 配置（一个 utf 字符串）编解码；接收端各自切回主线程处理，与 fabric 接收器语义一致。
	private static void registerNetworking() {
		CHANNEL.registerMessage(0, Common.UploadRequestPayload.class,
				(payload, buf) -> { },
				buf -> new Common.UploadRequestPayload(),
				(payload, ctxSupplier) -> {
					NetworkEvent.Context ctx = ctxSupplier.get();
					ctx.enqueueWork(Client::handleUploadRequestPayload);
					ctx.setPacketHandled(true);
				});
		CHANNEL.registerMessage(1, Common.UploadConfigPayload.class,
				(payload, buf) -> buf.writeUtf(payload.json()),
				buf -> new Common.UploadConfigPayload(buf.readUtf()),
				(payload, ctxSupplier) -> {
					NetworkEvent.Context ctx = ctxSupplier.get();
					ServerPlayer sender = ctx.getSender();
					ctx.enqueueWork(() -> DedicatedServer.handleUploadConfigPayload(payload, sender));
					ctx.setPacketHandled(true);
				});
	}

	// 客户端初始化 + 渲染器/配置屏（MOD 总线、value=Dist.CLIENT：整个订阅类只在客户端加载，
	// 里面引用的 ConfigScreen/FootprintEntityRenderer 等客户端专属类不会在服务端被解析）。
	// 注：老 Forge 1.20.1 无 fabric 式「仅客户端命令」注册，配置屏改由 ConfigScreenFactory（模组列表「配置」按钮）进入。
	@Mod.EventBusSubscriber(modid = Common.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
	public static class ClientEvents {
		@SubscribeEvent
		public static void onClientSetup(FMLClientSetupEvent event) {
			Client.init();
			// 配置屏入口：老 Forge 内建模组列表仅在注册了 ConfigScreenFactory 后才显示「配置」按钮。
			// 这里【无条件注册】，把 Cloth Config 有无判断挪进工厂 lambda——装了返回配置屏，没装返回降级提示屏；
			// ConfigScreen 的引用只在「已装 cloth」分支出现，配合 JVM 按需类加载，cloth 缺席时该类不会被解析。
			ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
					() -> new ConfigScreenHandler.ConfigScreenFactory((client, parent) ->
							isModLoaded("cloth_config") ? ConfigScreen.create(parent) : new MissingDependencyScreen(parent)));
		}

		@SubscribeEvent
		public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
			event.registerEntityRenderer(Common.FOOTPRINT, FootprintEntityRenderer::new);
		}
	}

	// 命令注册（GAME 总线）：/traceablefp setEnable|upload 是仅服务端命令（fabric 端同样只在 DedicatedServerModInitializer 注册），
	// 而 RegisterCommandsEvent 在客户端也会触发（本地命令树），故用 DistExecutor 把含 DedicatedServer 引用的代码
	// 隔到独立 lambda 类里，仅物理服务端执行，客户端不解析该类。
	@Mod.EventBusSubscriber(modid = Common.MOD_ID)
	public static class GameEvents {
		@SubscribeEvent
		public static void onRegisterCommands(RegisterCommandsEvent event) {
			DistExecutor.runWhenOn(Dist.DEDICATED_SERVER, () -> () -> DedicatedServer.registerCommand(event.getDispatcher()));
		}
	}

	// - - - - - Platform specific function - - - - -
	// channel 参数与 fabric 签名对齐（本模组单通道，故忽略之，直接查本通道在远端连接上是否握手成功）。
	public static boolean canReceive(ServerPlayer player, ResourceLocation channel) {
		return CHANNEL.isRemotePresent(player.connection.connection);
	}

	public static void sendToPlayer(ServerPlayer player, Common.UploadRequestPayload payload) {
		CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
	}

	public static void sendToServer(Common.UploadConfigPayload payload) {
		CHANNEL.sendToServer(payload);
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
