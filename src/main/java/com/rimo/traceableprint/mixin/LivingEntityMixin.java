package com.rimo.traceableprint.mixin;

import com.rimo.traceableprint.Common;
import com.rimo.traceableprint.entity.FootprintEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;
import java.util.UUID;

/**
 * LivingEntity 服务端侧注入：脚印生成（跳跃/移动检测）、链尾指针维护与持久化。
 * 客户端侧的本地高亮在 mixin.client.LivingEntityMixin，两边各归各的加载段。
 *
 * 架构（去 FootprintManager 后）：
 * - 父生物链尾 UUID 仅服务端维护（mixin @Unique 字段 + NBT 持久化）：
 *   生成新脚印时把上一个脚印的 NEXT_UUID 接上、再把链尾更新为新脚印；
 *   不再用 SynchedEntityData 同步到客户端（26.1 ClassTreeIdRegistry 下，向 LivingEntity 新增
 *   同步字段会与 Mob 等原版子类在其 clinit 固化的 id 撞号）；客户端“是否链尾”验证改由
 *   FootprintEntity.IS_TAIL 承载（见 spawnFootprint 里的链尾翻转）；
 * - 数量控制不再靠链上限，改由脚印实体自身的 genTime+lifetime 过期自毁（见 FootprintEntity）；
 * - 服务端只负责生成/串链，客户端负责交互与高亮，两端完全解绑。
 *
 * 非 @Inject 的成员统一 traceableprint$ 前缀防撞名（本 mixin 无 @Implements，@Inject 方法也带前缀不影响软实现判定）。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	// 尝试生成脚印的固定间隔（tick）由 Common.CONFIG.getSpawnIntervalTicks() 提供，此处只做倒计时
	@Unique private int traceableprint$footprintCooldown = 0;
	@Unique private boolean traceableprint$wasOnGround = true;
	// 记录上一次检测时的位置，用真实位移判断“是否在移动”（玩家服务端 deltaMovement 不可靠）
	@Unique private double traceableprint$lastCheckX;
	@Unique private double traceableprint$lastCheckZ;
	@Unique private boolean traceableprint$lastCheckInit = false;
	// 链尾脚印 UUID：仅服务端权威维护，不走 SynchedEntityData（避免 26.1 与原版 Mob 子类 id 撞号），
	// 经 addAdditionalSaveData/readAdditionalSaveData 写读 NBT，卸载重载后链不丢。
	@Unique private String traceableprint$lastFootprint = "";

	// 跳跃：离地瞬间额外留一个脚印（不受冷却限制，与 FootprintParticle 参考工程一致）
	@Inject(method = "jumpFromGround", at = @At("TAIL"))
	private void traceableprint$onJump(CallbackInfo ci) {
		LivingEntity entity = (LivingEntity) (Object) this;
		if (entity.level().isClientSide()) return;
		traceableprint$spawnFootprint(entity, Vec3.ZERO);
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void traceableprint$onTick(CallbackInfo ci) {
		LivingEntity entity = (LivingEntity) (Object) this;
		// 脚印只在服务端生成
		if (entity.level().isClientSide()) return;

		if (this.traceableprint$footprintCooldown > 0) {
			this.traceableprint$footprintCooldown--;
			return;
		}
		double cx = entity.getX();
		double cz = entity.getZ();
		// 用两次检测间隔内的真实位移平方判断移动（阈值 0.01≈0.1 格），比 getDeltaMovement() 对玩家更可靠
		double dx = this.traceableprint$lastCheckInit ? cx - this.traceableprint$lastCheckX : 0.0;
		double dz = this.traceableprint$lastCheckInit ? cz - this.traceableprint$lastCheckZ : 0.0;
		boolean moving = (dx * dx + dz * dz) > 1.0E-2;
		// 触发条件（参照 FootprintParticle）：A. 贴地且有水平移动；B. 落地瞬间
		boolean justLanded = !this.traceableprint$wasOnGround && entity.onGround();
		if ((moving && entity.onGround()) || justLanded) {
			// 把真实位移方向传入，用于计算脚印 yaw（比玩家服务端 getDeltaMovement 更准）
			traceableprint$spawnFootprint(entity, new Vec3(dx, 0.0, dz));
		}
		this.traceableprint$lastCheckX = cx;
		this.traceableprint$lastCheckZ = cz;
		this.traceableprint$lastCheckInit = true;
		this.traceableprint$wasOnGround = entity.onGround();
		this.traceableprint$footprintCooldown = Common.CONFIG.getSpawnIntervalTicks();
	}

	// 持久化链尾 UUID：写入生物 NBT，卸载重载/服务端重启后链头不丢（值取自服务端 @Unique 字段）
	@Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
	private void traceableprint$saveLastFootprint(ValueOutput output, CallbackInfo ci) {
		if (!this.traceableprint$lastFootprint.isEmpty()) output.putString("TraceablePrintLastFootprint", this.traceableprint$lastFootprint);
	}

	@Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
	private void traceableprint$loadLastFootprint(ValueInput input, CallbackInfo ci) {
		this.traceableprint$lastFootprint = input.getStringOr("TraceablePrintLastFootprint", "");
	}

	/**
	 * 尝试生成脚印：区块加载检查 → 朝向/身后落点解算 → 落地方块判定 → 最小间距过滤
	 * → 创建实体并串链（回写上一脚印 NEXT，再把父实体链尾更新为新脚印）→ addFreshEntity。
	 * 判定/间距不通过时不产生任何副作用（不腾位、不改链尾）。
	 *
	 * @param movementHint 调用方提供的真实位移方向（玩家服务端 getDeltaMovement 不可靠）；为零向量时回退实体自身速度
	 */
	@Unique
	private void traceableprint$spawnFootprint(LivingEntity parent, Vec3 movementHint) {
		if (!(parent.level() instanceof ServerLevel world)) return;

		// 检查父实体所在区块是否已加载
		if (!world.getChunkSource().hasChunk(
				SectionPos.blockToSectionCoord(parent.getBlockX()),
				SectionPos.blockToSectionCoord(parent.getBlockZ()))) {
			return;
		}

		// 计算移动朝向：优先用真实位移，其次实体自身速度，最后回退实体朝向
		// 方向向量与 yaw 的关系：dir = (-sin(yaw), 0, cos(yaw))，反推 yaw = atan2(-x, z)
		Vec3 movement = movementHint.horizontalDistanceSqr() > 1.0E-4 ? movementHint : parent.getDeltaMovement();
		float targetYaw = parent.getYRot();
		if (movement.horizontalDistanceSqr() > 1.0E-4) {
			targetYaw = (float) Math.toDegrees(Math.atan2(-movement.x, movement.z));
		}

		// 脚印放在父实体身后约 0.35 格处，贴地放置（抬高量走配置，减少与地面 z-fight）
		double rad = Math.toRadians(targetYaw);
		double behindX = parent.getX() - Math.sin(rad) * 0.35;
		double behindZ = parent.getZ() - Math.cos(rad) * 0.35;
		double footY = parent.getY() + Common.CONFIG.getFootprintYOffset() / 100.0;

		// 生成位置预检：落脚格实心→否则回退下一格完整方块；判定通过前绝不改动任何状态
		if (!traceableprint$resolveSpawnPosition(world, behindX, footY, behindZ)) {
			return;
		}

		UUID parentId = parent.getUUID();
		UUID lastId = traceableprint$parseUuid(this.traceableprint$lastFootprint);

		// 最小间距：与上一个脚印（若仍在世界）过近则跳过，防原地跳跃/慢蹭刷屏（上一脚印卸载/取不到则放行）
		double minDist = Common.CONFIG.getMinSpawnDistance();
		if (minDist > 0 && lastId != null && world.getEntity(lastId) instanceof FootprintEntity prevFp) {
			double ddx = prevFp.getX() - behindX;
			double ddz = prevFp.getZ() - behindZ;
			if (ddx * ddx + ddz * ddz < minDist * minDist) {
				return;
			}
		}

		FootprintEntity footprint = new FootprintEntity(world, parentId);
		footprint.setPos(behindX, footY, behindZ);
		footprint.setYRot(targetYaw);
		footprint.setXRot(parent.getXRot());
		// 视觉左右错开 ±0.15 方块，模拟左右脚
		footprint.setVisualOffset(parent.getRandom().nextBoolean() ? 0.15 : -0.15);
		// 新脚印即当前链尾
		footprint.setChainTail(true);

		// 串链：新脚印 UUID 写给上一个脚印（若仍在世界），并把它从链尾降为普通节点，再把父实体链尾更新为新脚印
		if (lastId != null && world.getEntity(lastId) instanceof FootprintEntity prevFp) {
			prevFp.setNextUUID(footprint.getUUID());
			prevFp.setChainTail(false);
		}

		world.addFreshEntity(footprint);
		this.traceableprint$lastFootprint = footprint.getUUID().toString();
	}

	@Unique
	private static UUID traceableprint$parseUuid(String str) {
		if (str == null || str.isEmpty()) return null;
		try {
			return UUID.fromString(str);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * 生成位置判定（一比一复刻 FootprintParticle）：
	 * 1) 落脚格：方块允许生成 且 canOcclude → 成功；
	 * 2) 否则回退下一格：方块允许生成 且 canOcclude 且碰撞形状为完整方块 → 成功；
	 * 3) 都失败 → 取消生成。保证脚印落在实体方块表面而不陷进草丛。
	 */
	@Unique
	private static boolean traceableprint$resolveSpawnPosition(ServerLevel world, double x, double footY, double z) {
		BlockPos probe = BlockPos.containing(x, footY, z);
		BlockState state = world.getBlockState(probe);
		if (traceableprint$isBlockAllowed(world, probe) && state.canOcclude()) {
			return true;
		}
		BlockPos below = probe.below();
		BlockState belowState = world.getBlockState(below);
		return traceableprint$isBlockAllowed(world, below) && belowState.canOcclude()
				&& Block.isShapeFullBlock(belowState.getCollisionShape(world, below));
	}

	/**
	 * 方块允许判定：白名单(支持 "#tag")优先；其后硬度门槛 |defaultDestroyTime| < gate。
	 */
	@Unique
	private static boolean traceableprint$isBlockAllowed(ServerLevel world, BlockPos pos) {
		BlockState block = world.getBlockState(pos);
		String id = BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
		Holder<Block> holder = block.typeHolder();
		Set<String> apply = Common.CONFIG.getApplyBlocks();

		boolean canGen = apply.contains(id);
		if (!canGen) {
			for (TagKey<Block> tag : holder.tags().toList()) {
				if (apply.contains("#" + tag.location())) {
					canGen = true;
					break;
				}
			}
		}
		if (!canGen) {
			float gate = Common.CONFIG.getHardnessGate();
			canGen = gate > 0 && Mth.abs(block.getBlock().defaultDestroyTime()) < gate;
		}
		return canGen;
	}
}
