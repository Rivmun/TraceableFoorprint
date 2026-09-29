package com.rimo.traceableprint.entity;

import com.rimo.traceableprint.ClientHighlights;
import com.rimo.traceableprint.Common;
import com.rimo.traceableprint.VersionUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.NonNull;

import java.util.Optional;
import java.util.UUID;

/**
 * 脚印实体：不可移动、不可破坏的纯标记实体。
 * PARENT_UUID 记录生成它的父生物；NEXT_UUID 记录同一条足迹链中的下一个脚印（由服务端生成时写入），
 * 玩家点击脚印即依链寻踪；无后继且确为链尾时跳向父生物本体。
 *
 * 存续采用“自毁”而非链上限：genTime 记录生成时的绝对游戏时间并持久化，
 * 服务端每秒比对 genTime+lifetime，超时即 discard；从存档加载的过期脚印同样自毁，无需外部管理器维护。
 */
public class FootprintEntity extends Entity {
	// 该脚印所属的父生物 UUID（26.1 已移除 OPTIONAL_UUID 序列化器，改用字符串存储）
	private static final EntityDataAccessor<String> PARENT_UUID =
			SynchedEntityData.defineId(FootprintEntity.class, EntityDataSerializers.STRING);
	// 同一条链中的下一个脚印 UUID（最后一个脚印此值为空，此时应跳向父生物）
	private static final EntityDataAccessor<String> NEXT_UUID =
			SynchedEntityData.defineId(FootprintEntity.class, EntityDataSerializers.STRING);
	// 是否为足迹链当前链尾（服务端维护并同步）。
	// 客户端点击脚印沿链前进到无可走时，仅当自己确为链尾才跳向父生物（断链验证）。
	// 链尾指针不能放在父生物 LivingEntity 的同步数据上（26.1 会与 Mob 等原版子类 id 撞号），
	// 故下放到我们完全掌控的 FootprintEntity 自身，随实体同步。
	private static final EntityDataAccessor<Boolean> IS_TAIL =
			SynchedEntityData.defineId(FootprintEntity.class, EntityDataSerializers.BOOLEAN);
	// 生成时的绝对游戏时间（服务端 level 时钟），走同步数据：
	// 服务端 tick 据此判定过期自毁，客户端渲染器据此计算淡出透明度。
	private static final EntityDataAccessor<Long> GEN_TIME =
			SynchedEntityData.defineId(FootprintEntity.class, EntityDataSerializers.LONG);
	// 脚印贴图缩放倍率（服务端生成时按父生物类型/体型算好并同步）：仅缩放客户端贴图四边形，不改实体碰撞箱。
	private static final EntityDataAccessor<Float> TEX_SCALE =
			SynchedEntityData.defineId(FootprintEntity.class, EntityDataSerializers.FLOAT);
	// 脚印贴图名（服务端生成时按父生物注册名在 config.textureList 命中后随机选定并同步）：
	// 存字符串而非索引，是为了不要求两端配置一致——客户端拿到名字后自行组装 Identifier 并校验资源包里是否存在，
	// 取不到就退回默认 footprint.png。空串表示“没有定制贴图”，直接走默认。
	private static final EntityDataAccessor<String> TEX_NAME =
			SynchedEntityData.defineId(FootprintEntity.class, EntityDataSerializers.STRING);

	// 高亮倒计时：纯客户端本地状态（谁点击谁知道），由交互直接写入、客户端 tick 递减，驱动渲染器金色脉冲。
	private int clientHighlightTicks = 0;

	// 从存档读入的生成时间：仅当读入时机早于 entityData 就绪时兜底，
	// 首次服务端 tick 补写（正常路径在 readAdditionalSaveData / 构造器已直接写入）。
	private Long pendingGenTime;

	// 支撑探测深度：从脚印实体位置正下方向下探测支撑方块的深度（供存续检测用）。
	private static final double SUPPORT_PROBE_DEPTH = 0.2;

	// 被追踪提示的节流窗口（tick）：同一链尾脚印在此期间重复被点击只提示一次，防刷。
	private static final int TRACE_NOTIFY_COOLDOWN_TICKS = 200;
	// 下次允许提示的绝对游戏时间（仅服务端使用，无需同步/持久化）。
	private long traceNotifyReadyTime = 0L;

	public FootprintEntity(EntityType<? extends Entity> type, Level level) {
		super(type, level);
		this.setNoGravity(true); // 脚印悬于地面之上，不受重力
		// GEN_TIME 在首次服务端 tick 播种为当前游戏时间（构造期 entityData 尚未就绪）
	}

	// 供服务端生成时调用的便捷构造
	public FootprintEntity(ServerLevel level, UUID parentId) {
		this(Common.FOOTPRINT, level);
		this.setParentUUID(parentId);
		// super() 返回后 entityData 已就绪，构造时直接播种生成时间
		// （不能留到 tickCount==0 再播种：baseTick 会先把 tickCount 递增到 1，首帧判断永远不命中）
		this.entityData.set(GEN_TIME, level.getLevelData().getGameTime());
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		//【关键】所有 EntityDataAccessor 必须在实体数据构建时注册，否则 entityData.set 会直接崩溃
		builder.define(PARENT_UUID, "");
		builder.define(NEXT_UUID, "");
		builder.define(IS_TAIL, false);
		builder.define(GEN_TIME, 0L);
		builder.define(TEX_SCALE, 1.0F);
		builder.define(TEX_NAME, "");
	}

	// 设置脚印贴图缩放倍率（仅服务端生成时写入，随实体同步到客户端）
	public void setTexScale(float scale) {
		this.entityData.set(TEX_SCALE, scale);
	}

	// 获取脚印贴图缩放倍率（双端可读）
	public float getTexScale() {
		return this.entityData.get(TEX_SCALE);
	}

	// 设置脚印贴图名（仅服务端生成时写入，随实体同步到客户端）；空串 = 使用默认贴图
	public void setTextureName(String name) {
		this.entityData.set(TEX_NAME, name == null ? "" : name);
	}

	// 获取脚印贴图名（双端可读，客户端渲染器据此检索资源包贴图）
	public String getTextureName() {
		return this.entityData.get(TEX_NAME);
	}

	public void setParentUUID(UUID uuid) {
		this.entityData.set(PARENT_UUID, uuid.toString());
	}

	public Optional<UUID> getParentUUID() {
		return parseUuid(this.entityData.get(PARENT_UUID));
	}

	public void setNextUUID(UUID uuid) {
		this.entityData.set(NEXT_UUID, uuid.toString());
	}

	// 标记/清除本脚印的链尾身份（服务端生成新链尾时：新脚印置 true，旧的链尾置 false）
	public void setChainTail(boolean tail) {
		this.entityData.set(IS_TAIL, tail);
	}

	public boolean isChainTail() {
		return this.entityData.get(IS_TAIL);
	}

	public Optional<UUID> getNextUUID() {
		return parseUuid(this.entityData.get(NEXT_UUID));
	}

	private static Optional<UUID> parseUuid(String str) {
		if (str == null || str.isEmpty()) return Optional.empty();
		try {
			return Optional.of(UUID.fromString(str));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	// 生成时的绝对游戏时间（双端可读，走同步数据）
	public long getGenTime() {
		return this.entityData.get(GEN_TIME);
	}

	/**
	 * 存续进度 0..1：(当前游戏时间 - 生成时间) / 总时长。与 getFadeAlpha() 同源于一条时间轴，
	 * 供渲染把贴图从生成时的抬高量线性下沉到销毁前（“陷入地里”而不是原地变透明）。
	 * 未播种 / 时长非法时按 0（刚生成）处理。
	 */
	public float getLifeProgress() {
		long genTime = this.getGenTime();
		long lifetime = Common.CONFIG.getFootprintLifetimeTicks();
		if (genTime <= 0 || lifetime <= 0) return 0.0F;
		long now = this.level().getLevelData().getGameTime();
		return Math.clamp((float) (now - genTime) / (float) lifetime, 0.0F, 1.0F);
	}

	/**
	 * 存续淡出系数：透明度 = min(1, 剩余时长 / (总时长 / 2))。
	 * 等价于 min(1, 2×(1 - 存续进度))：前一半生命保持完全不透明，后一半线性淡出至全透明（客户端渲染用）。
	 */
	public float getFadeAlpha() {
		return Math.min(1.0F, 2.0F * (1.0F - this.getLifeProgress()));
	}

	// 是否处于高亮状态（仅客户端有意义：读本地倒计时，服务端永远为 false）
	public boolean isHighlighted() {
		return this.clientHighlightTicks > 0;
	}

	/**
	 * 激活客户端本地高亮（重复点击续期）。
	 */
	public void applyClientHighlight(int ticks) {
		if (this.level().isClientSide()) {
			this.clientHighlightTicks = Math.max(this.clientHighlightTicks, ticks);
		}
	}

	/**
	 * 清除客户端本地高亮（排他切换：点亮其它脚印时回到常规渲染管线）。
	 */
	public void clearClientHighlight() {
		if (this.level().isClientSide()) {
			this.clientHighlightTicks = 0;
		}
	}

	// 26.1 的 NBT 读写改成了 ValueInput / ValueOutput 流式接口
	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		parseUuid(input.getStringOr("ParentUUID", ""))
				.ifPresent(this::setParentUUID);
		parseUuid(input.getStringOr("NextUUID", ""))
				.ifPresent(this::setNextUUID);
		// 恢复生成时间：entityData 此时已就绪则直接写入；
		// 缺失时按“当前时刻”兜底，避免因无字段而立即自毁
		long genTime = input.getLongOr("GenTime", this.level().getLevelData().getGameTime());
		try {
			this.entityData.set(GEN_TIME, genTime);
		} catch (IllegalStateException e) {
			// 极端情况：读档时机早于同步数据构建，缓到首次服务端 tick 补写
			this.pendingGenTime = genTime;
		}
		// 恢复贴图缩放：缺失时按 1.0（默认大小）兜底
		this.setTexScale((float) input.getDoubleOr("TexScale", 1.0D));
		// 恢复贴图名：缺失时按空串（默认 footprint 贴图）兜底。存档里的名字可能是旧配置留下的，
		// 如今已从资源包删除也没关系——客户端解析时校验存在性，取不到自然退回默认。
		this.setTextureName(input.getStringOr("TextureName", ""));
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putString("ParentUUID", getParentUUID().map(UUID::toString).orElse(""));
		output.putString("NextUUID", getNextUUID().map(UUID::toString).orElse(""));
		output.putLong("GenTime", this.getGenTime());
		output.putDouble("TexScale", this.getTexScale());
		String textureName = this.getTextureName();
		if (!textureName.isEmpty()) {
			output.putString("TextureName", textureName);
		}
	}

	// 26.1 的 Entity.interact 是 (Player, InteractionHand, Vec3) 三参数签名
	@Override
	public @NonNull InteractionResult interact(@NonNull Player player, @NonNull InteractionHand hand, net.minecraft.world.phys.@NonNull Vec3 location) {
		// 交互在客户端完全封闭：链数据取 SynchedEntityData 本地副本，高亮是仅点击者可见的本地状态。
		// 主手本地调用直接 CONSUME，客户端不再重试副手。
		if (this.level() instanceof ClientLevel level) {
			this.traceAndHighlight(level);
			return InteractionResult.CONSUME;
		}
		// 服务端：右键实体的包本就会送达服务端并回调此处（客户端返回 CONSUME 只阻止副手重试，不拦服务端）。
		// 在此权威地判定“本次点击是否把人追到了父玩家”，若是则向被追踪者发 action bar 提示。
		// 非模组客户端可能主/副手各发一包，故只认主手。
		if (hand == InteractionHand.MAIN_HAND) {
			this.notifyParentIfTraced((ServerPlayer) player);
		}
		return InteractionResult.PASS;
	}

	/**
	 * 服务端：若本次点击把足迹链追到了“父玩家”（本脚印为链尾、无存活后继、且父 UUID 对应一名在线玩家），
	 * 按“追到的是谁”分两种提示：追到别人→通知被追踪者“有人正在寻找你的踪迹…”（受 notifyTraced 开关控制，
	 * 不暴露追踪者身份）；追到自己（含单人）→给点击者自己一条“这似乎是你自己的足迹…”（纯本地反馈，
	 * 不受该开关影响）。两者走同一节流。
	 * 走服务端权威的 isChainTail/NEXT/PARENT 同步数据 + 全局按 UUID 查玩家，不受追踪者客户端渲染距离限制。
	 */
	private void notifyParentIfTraced(ServerPlayer tracer) {
		// 仅当链上无存活后继、且自己确为链尾时，本次点击才会“跳向父实体”（与客户端 traceAndHighlight 判定一致）
		if (hasLiveNext() || !this.isChainTail()) return;
		UUID parentId = this.getParentUUID().orElse(null);
		if (parentId == null) return;
		ServerPlayer target = this.level().getServer().getPlayerList().getPlayer(parentId);
		if (target == null) return; // 父实体不是在线玩家（其它生物 / 已卸载或离线玩家）：静默
		if (target == tracer) {
			this.sendTraceHint(tracer, "traceableprint.message.own_footprints");
			return;
		}
		if (Common.CONFIG.isNotifyTraced()) {
			this.sendTraceHint(target, "traceableprint.message.be_tracked");
		}
	}

	/** 发一条“追到人”的 action bar 提示；同一脚印带节流，防连点刷屏。 */
	private void sendTraceHint(ServerPlayer recipient, String translationKey) {
		long now = this.level().getGameTime();
		if (now < this.traceNotifyReadyTime) return; // 节流窗口内，忽略重复点击
		this.traceNotifyReadyTime = now + TRACE_NOTIFY_COOLDOWN_TICKS;
		// 经 VersionUtil 封装 action bar 发送（26.1 为 ServerPlayer#sendOverlayMessage，旧版自动回退 displayClientMessage）。
		VersionUtil.sendActionBar(recipient, Component.translatable(translationKey));
	}

	// 本脚印是否仍有存活的直接后继（有则本次点击只推进到下一个脚印，尚未追到人）。
	private boolean hasLiveNext() {
		UUID next = this.getNextUUID().orElse(null);
		if (next == null) return false;
		return this.level().getEntity(next) instanceof FootprintEntity fp && !fp.isRemoved();
	}

	/**
	 * 依链寻踪（纯客户端）：沿 NEXT_UUID 在本地副本中前进一格，点亮下一个仍存活的脚印；
	 * 无后继/后继已消失时，仅当“父实体记录的链尾正是自己”才跳向父实体（断链验证）——
	 * 否则说明后继是被炸断的悬空指针，静默跳过，避免从断链中段错误地跳到父实体。
	 * 未被追踪的实体不在客户端副本里，自然取不到，同样静默。
	 */
	private void traceAndHighlight(ClientLevel level) {
		Entity target = null;
		Entity next = this.getNextUUID().map(level::getEntity).orElse(null);
		if (next instanceof FootprintEntity nextFootprint && !nextFootprint.isRemoved()) {
			target = nextFootprint;
		} else if (this.isChainTail()) {
			// 仅当自己确为链尾时，才跳向父实体（否则为被炸断的悬空中段，静默跳过）
			// 父实体是自己时不高亮（第一人称下描边无意义）：服务端已给点击者一条“这是你自己的足迹”提示，文字提示足够。
			// 26.1 的 ClientLevel 已不再有 player 字段（只有 players() 列表），本地玩家取 Minecraft#player；
			// 本方法只在客户端分支被调（instanceof ClientLevel 已守卫），取不到玩家时为 null，不影响判等。
			Entity parent = this.getParentUUID().map(level::getEntity).orElse(null);
			if (parent instanceof LivingEntity living && living != Minecraft.getInstance().player && !living.isRemoved()) {
				target = living;
			}
		}
		if (target != null) {
			ClientHighlights.apply(level, target.getId(), Common.CONFIG.getHighlightTicks());
		}
	}

	// - - - - 存续规则 - - - -

	@Override
	public void tick() {
		super.tick();
		// 高亮倒计时（纯客户端本地，每个玩家只维护自己的）：归零后渲染器自然回到非高亮管线
		if (this.level().isClientSide()) {
			if (this.clientHighlightTicks > 0) {
				this.clientHighlightTicks--;
			}
			return;
		}
		// 服务端：过期自毁（每秒一次）——超时即销毁，无需检查方块，省开销
		// 兜底补写：仅当播种路径未生效（GEN_TIME 仍为默认 0）时写入，不依赖 tickCount 首帧时序
		if (this.getGenTime() <= 0) {
			long now = this.level().getLevelData().getGameTime();
			this.entityData.set(GEN_TIME, this.pendingGenTime != null ? this.pendingGenTime : now);
			this.pendingGenTime = null;
		}
		if (this.tickCount % 20 == 0) {
			long now = this.level().getLevelData().getGameTime();
			if (now > this.getGenTime() + Common.CONFIG.getFootprintLifetimeTicks()) {
				this.discard();
				return;
			}
		}
		// 服务端：存续检测（低频）——脚下失去支撑或被完整方块覆盖时销毁自身
		if (this.tickCount % 10 == 0 && !isFootprintValid(this.level(), this.getX(), this.getY(), this.getZ())) {
			this.discard();
		}
	}

	/**
	 * 脚印能否立足（结构判定）：用实体真实坐标而非整数方块坐标。
	 * 支撑：实体位置正下方 SUPPORT_PROBE_DEPTH 处必须有碰撞形状（贴地）；
	 * 覆盖：脚印体所在格不得是“完整方块”——非完整方块（雪层、半砖、植物等）允许立足。
	 */
	private static boolean isFootprintValid(Level world, double x, double y, double z) {
		BlockPos supportPos = BlockPos.containing(x, y - SUPPORT_PROBE_DEPTH, z);
		BlockState support = world.getBlockState(supportPos);
		if (support.getCollisionShape(world, supportPos).isEmpty()) {
			return false; // 下方悬空
		}
		BlockPos ownPos = BlockPos.containing(x, y + 0.05, z);
		BlockState own = world.getBlockState(ownPos);
		return !isFullCube(world, own, ownPos);
	}

	/**
	 * 是否完整方块（碰撞形状覆盖整格 [0,1]³）。用于“所在方块是否把脚印埋住”的判断。
	 */
	private static boolean isFullCube(Level world, BlockState state, BlockPos pos) {
		VoxelShape shape = state.getCollisionShape(world, pos);
		if (shape.isEmpty()) {
			return false; // 空气等无碰撞方块不是完整方块（空 shape 也不能调 bounds）
		}
		AABB ab = shape.bounds();
		return ab.minX <= 0.0001 && ab.minY <= 0.0001 && ab.minZ <= 0.0001
				&& ab.maxX >= 0.9999 && ab.maxY >= 0.9999 && ab.maxZ >= 0.9999;
	}

	// - - - - 不可破坏 / 不可移动 - - - -

	@Override
	public boolean isInvulnerable() {
		return true;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		// 爆炸伤害（TNT、苦力帕等）：清除自身脚印（仅移除自己，链上其它脚印由客户端断链验证自然兜住）；其余伤害一概无效
		if (source.is(DamageTypeTags.IS_EXPLOSION)) {
			this.discard();
			return true;
		}
		return false;
	}

	@Override
	public boolean isSilent() {
		return true;
	}

	@Override
	public boolean isPushable() {
		return false; // 不会被推动
	}

	@Override
	public boolean canBeCollidedWith(Entity entity) {
		return false; // 不阻挡移动、不与其他实体碰撞
	}

	@Override
	public boolean isPickable() {
		//【关键】Entity 默认 isPickable() 为 false，不覆写则玩家射线永远选不中脚印，右键交互失效
		return true;
	}

	@Override
	public boolean isNoGravity() {
		return true;
	}

	// 让渲染器在很远处也绘制（脚印是寻踪线索，不应因距离被剔除）
	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return true;
	}
}
