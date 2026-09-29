package com.rimo.traceableprint;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.rimo.traceableprint.config.Config;
//~ if neoforge 'fabric' -> 'neoforge'
import com.rimo.traceableprint.loaders.fabric.Platform;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.Permissions;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.commands.Commands.literal;

/**
 * 专用服务端逻辑：{@code /traceablefp} 命令树 + 配置上传的服务端接收器（服务端权威）。
 *
 * <p>只在物理专用服务端挂载（fabric 的 DedicatedServerModInitializer 才注册这里的命令与 C2S 接收器）：
 * 专用服务端没有可见图形配置界面，只能靠远程命令控制；单人/客户端有 ClothConfig 界面，无需命令。</p>
 *
 * <p>握手（防刷）：{@code /upload} 校验 op 通过后下发空 {@link Common.UploadRequestPayload} 邀约并登记一次性令牌；
 * 客户端收到才回传 {@link Common.UploadConfigPayload}；服务端仅接受「命中未过期令牌且发送方有 op 权限」的回包。</p>
 *
 * <p>消息一律走 {@link VersionUtil}（跨版本抽象），收发包一律走 {@link Platform}（跨 loader 抽象）。</p>
 */
public class DedicatedServer {
	// 上传/开关所需最低权限：op 等级 2（GAMEMASTER，与多数影响玩法的命令一致）
	private static final Permission REQUIRED_PERMISSION = Permissions.COMMANDS_GAMEMASTER;
	private static final long SOLICIT_TTL_MS = 20_000L;    // 邀约令牌有效期
	private static final long UPLOAD_COOLDOWN_MS = 3_000L; // /upload 命令冷却，防连点重复邀约
	private static final int MAX_JSON_LEN = 256 * 1024;    // 上传配置 JSON 体积上限（256 KiB）

	// 玩家 UUID -> 邀约到期时间戳(ms)
	private static final Map<UUID, Long> pendingByPlayer = new ConcurrentHashMap<>();
	// 玩家 UUID -> 上次成功邀约时间戳(ms)，用于命令冷却
	private static final Map<UUID, Long> lastUploadByPlayer = new ConcurrentHashMap<>();

	public static void init() {
		//
	}

	// - - - - - 命令注册 - - - - -

	public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal(Common.MOD_ID)
				.then(literal("setEnable")
						.then(literal("off").executes(ctx -> setEnable(ctx, Config.WorkMode.DISABLED)))
						.then(literal("player").executes(ctx -> setEnable(ctx, Config.WorkMode.PLAYER_ONLY)))
						.then(literal("all").executes(ctx -> setEnable(ctx, Config.WorkMode.ALL))))
				.then(literal("upload").executes(DedicatedServer::upload)));
	}

	/** 设置模组总开关并落盘（控制台/玩家皆可，仅需权限）。权限校验刻意放执行体内，以便回自定义提示。 */
	private static int setEnable(CommandContext<CommandSourceStack> ctx, Config.WorkMode mode) {
		if (!permitted(ctx)) {
			VersionUtil.sendSystemMessage(ctx, "Your permission is not enough");
			return 0;
		}
		Common.CONFIG.setEnableMod(mode);
		Common.CONFIG.save();
		VersionUtil.sendSystemMessage(ctx, "TraceablePrint work mode set to: " + mode.name());
		return 1;
	}

	/**
	 * 校验权限与冷却后，向执行者客户端下发一个空的 {@link Common.UploadRequestPayload} 邀约并登记一次性令牌。
	 * 真正的配置回传发生在客户端收到邀约之后（见 {@link Client#handleUploadRequestPayload}）。
	 */
	private static int upload(CommandContext<CommandSourceStack> ctx) {
		if (!permitted(ctx)) {
			VersionUtil.sendSystemMessage(ctx, "Your permission is not enough");
			return 0;
		}
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			VersionUtil.sendSystemMessage(ctx, "Config upload must be run by a player (its config lives on the client).");
			return 0;
		}
		long now = System.currentTimeMillis();
		Long last = lastUploadByPlayer.get(player.getUUID());
		if (last != null && now - last < UPLOAD_COOLDOWN_MS) {
			VersionUtil.sendSystemMessage(ctx, "Please wait a moment before uploading again.");
			return 0;
		}
		if (!Platform.canReceive(player, Common.UploadRequestPayload.TYPE)) {
			VersionUtil.sendSystemMessage(ctx, "Your client does not accept config uploads (is TraceablePrint installed on it?).");
			return 0;
		}
		lastUploadByPlayer.put(player.getUUID(), now);
		pendingByPlayer.put(player.getUUID(), now + SOLICIT_TTL_MS);
		Platform.sendToPlayer(player, new Common.UploadRequestPayload());
		VersionUtil.sendSystemMessage(ctx, "Upload requested — your client is now sending its config to the server...");
		return 1;
	}

	// - - - - - 服务端：收到配置回传 -> 校验令牌与权限 -> 落盘 - - - - -

	/**
	 * 服务端收到 {@link Common.UploadConfigPayload} 后调用（server 线程）。
	 * 仅在「发送方有 op 权限」且「命中未过期邀约令牌」时应用并落盘；权限不足按约定固定语句报错。
	 */
	public static void handleUploadConfigPayload(Common.UploadConfigPayload payload, ServerPlayer player) {
		if (!player.permissions().hasPermission(REQUIRED_PERMISSION)) {
			Common.LOGGER.error("We're receiving a config but that client hasn't permission to do so, it's probably a bug.");
			return;
		}
		UUID id = player.getUUID();
		Long until = pendingByPlayer.get(id);
		long now = System.currentTimeMillis();
		if (until == null || now > until) {
			// 未被邀约 / 令牌过期：视为无效回传（可能是绕过命令的直接发包），静默丢弃
			Common.LOGGER.warn("[TraceablePrint] Ignored unsolicited or expired config upload from {}", player.getName().getString());
			return;
		}
		pendingByPlayer.remove(id); // 一次性令牌，用后即焚

		String json = payload.json();
		if (json == null || json.length() > MAX_JSON_LEN) {
			VersionUtil.sendMessage(player, "Uploaded config is empty or too large; rejected.");
			return;
		}
		if (Common.CONFIG.applyJson(json)) {
			Common.CONFIG.save();
			Common.LOGGER.info("[TraceablePrint] Applied config uploaded by {}", player.getName().getString());
			VersionUtil.sendMessage(player, "Config uploaded and applied to the server.");
		} else {
			VersionUtil.sendMessage(player, "Uploaded config failed to parse; nothing was changed.");
		}
	}

	private static boolean permitted(CommandContext<CommandSourceStack> ctx) {
		return ctx.getSource().permissions().hasPermission(REQUIRED_PERMISSION);
	}
}
