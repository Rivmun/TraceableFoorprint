package com.rimo.traceableprint.entity;

import com.rimo.traceableprint.ClientHighlights;
import com.rimo.traceableprint.Common;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
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
	// 视觉左右偏移（前进方向的左侧/右侧，模拟左右脚错位）。
	// 必须走 SynchedEntityData 同步到客户端，否则服务端设置的值渲染端永远读不到。
	private static final EntityDataAccessor<Float> VISUAL_OFFSET =
			SynchedEntityData.defineId(FootprintEntity.class, EntityDataSerializers.FLOAT);
	// 是否为足迹链当前链尾（服务端维护并同步）。
	// 客户端点击脚印沿链前进到无可走时，仅当自己确为链尾才跳向父生物（断链验证）。
	// 链尾指针不能放在父生物 LivingEntity 的同步数据上（26.1 会与 Mob 等原版子类 id 撞号），
	// 故下放到我们完全掌控的 FootprintEntity 自身，随实体同步。
	private static final EntityDataAccessor<Boolean> IS_TAIL =
			SynchedEntityData.defineId(FootprintEntity.class, EntityDataSerializers.BOOLEAN);

	// 高亮倒计时：纯客户端本地状态（谁点击谁知道），由交互直接写入、客户端 tick 递减，驱动渲染器金色脉冲。
	private int clientHighlightTicks = 0;

	// 生成时的绝对游戏时间（服务端 level 时钟），持久化到 NBT；服务端 tick 据此判定过期自毁。
	private long genTime;

	// 支撑探测深度：从脚印实体位置正下方向下探测支撑方块的深度（供存续检测用）。
	private static final double SUPPORT_PROBE_DEPTH = 0.2;

	public FootprintEntity(EntityType<? extends Entity> type, Level level) {
		super(type, level);
		this.setNoGravity(true); // 脚印悬于地面之上，不受重力
		this.genTime = level.getLevelData().getGameTime();
	}

	// 供服务端生成时调用的便捷构造
	public FootprintEntity(ServerLevel level, UUID parentId) {
		this(Common.FOOTPRINT, level);
		this.setParentUUID(parentId);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		//【关键】所有 EntityDataAccessor 必须在实体数据构建时注册，否则 entityData.set 会直接崩溃
		builder.define(PARENT_UUID, "");
		builder.define(NEXT_UUID, "");
		builder.define(VISUAL_OFFSET, 0.0F);
		builder.define(IS_TAIL, false);
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

	// 设置视觉偏移量（用于渲染时的左右平移）
	public void setVisualOffset(double offset) {
		this.entityData.set(VISUAL_OFFSET, (float) offset);
	}

	public float getVisualOffsetX() {
		return this.entityData.get(VISUAL_OFFSET);
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

	// 26.1 的 NBT 读写改成了 ValueInput / ValueOutput 流式接口
	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		parseUuid(input.getStringOr("ParentUUID", ""))
				.ifPresent(this::setParentUUID);
		parseUuid(input.getStringOr("NextUUID", ""))
				.ifPresent(this::setNextUUID);
		this.setVisualOffset(input.getDoubleOr("VisualOffsetX", 0.0D));
		// 恢复生成时间；缺失时按“当前时刻”兜底，避免因无字段而立即自毁
		this.genTime = input.getLongOr("GenTime", this.level().getLevelData().getGameTime());
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putString("ParentUUID", getParentUUID().map(UUID::toString).orElse(""));
		output.putString("NextUUID", getNextUUID().map(UUID::toString).orElse(""));
		output.putDouble("VisualOffsetX", getVisualOffsetX());
		output.putLong("GenTime", this.genTime);
	}

	// 26.1 的 Entity.interact 是 (Player, InteractionHand, Vec3) 三参数签名
	@Override
	public @NonNull InteractionResult interact(@NonNull Player player, @NonNull InteractionHand hand, net.minecraft.world.phys.@NonNull Vec3 location) {
		// 交互在客户端完全封闭：链数据取 SynchedEntityData 本地副本，高亮是仅点击者可见的本地状态。
		// 主手本地调用直接 CONSUME，客户端不再重试副手；服务端不参与交互裁决。
		if (this.level() instanceof ClientLevel level) {
			this.traceAndHighlight(level);
			return InteractionResult.CONSUME;
		}
		return InteractionResult.PASS;
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
			Entity parent = this.getParentUUID().map(level::getEntity).orElse(null);
			if (parent instanceof LivingEntity living && !living.isRemoved()) {
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
		if (this.tickCount % 20 == 0) {
			long now = this.level().getLevelData().getGameTime();
			if (now > this.genTime + Common.CONFIG.getFootprintLifetimeTicks()) {
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
