package com.rimo.traceableprint.mixin.client;

import com.rimo.traceableprint.util.ClientHighlightHolder;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LivingEntity 客户端侧注入（mixins.json 的 client 段）：父生物的仅本客户端可见高亮。
 *
 * 高亮不走服务端 GLOWING 效果（那会广播给所有客户端）：收到定向包时由 ClientHighlights
 * 写入 @Unique 倒计时，本类在 tick 里每帧维护——归零即熄灭，期间未亮则点亮，
 * 若被服务端 shared flags 同步顶掉 glowing 位也会重新断言。
 * 潜行即消：倒计时内目标进入潜行（isCrouching，客户端读同步 Pose，对其它玩家生效）
 * 则就地熄灭并撤销倒计时——一次性取消，起身后需重新点击脚印才会再点亮。
 *
 * 【26.1 关键坑】Entity#setGlowingTag 在客户端是无效的：它的实现是
 * hasGlowingTag=值; setSharedFlag(6, isCurrentlyGlowing())，而 isCurrentlyGlowing()
 * 客户端读的是 shared flag 当前位——等于拿旧值回写自己，永远 no-op。
 * 客户端点亮/熄灭必须直写 setSharedFlag(6, ...)（flag 6 = glowing 位），
 * 该 protected 方法由同包接口型访问器 mixin.client.EntityAccessor（@Mixin(Entity.class)）桥接。
 *
 * 跨类写入通过 @Implements 挂到 LivingEntity 上的 ClientHighlightHolder 接口强转完成
 * （(本mixin类) entity 这种“强转 mixin 类”运行时必抛 ClassCastException，mixin 类非 target 父类型）。
 * 注意：@Implements(prefix="traceableprint$") 会把所有该前缀方法判为接口软实现，
 * 因此本类中 @Inject 方法一律不带前缀；非 inject 成员仍按约定带 traceableprint$ 前缀。
 */
@Mixin(LivingEntity.class)
@Implements(@Interface(iface = ClientHighlightHolder.class, prefix = "traceableprint$"))
public abstract class LivingEntityMixin {
	// 剩余高亮 tick（>0 表示 glowing 由本客户端本地点亮，归零时负责熄灭）
	@Unique private int traceableprint$clientHighlightTicks = 0;

	// ClientHighlightHolder 的实现：供 ClientHighlights 强转写入倒计时。
	// 必须 public：@Implements 软实现方法不可见时 Mixin 会拒挂整个 mixin（运行时硬报错）
	@Unique public void traceableprint$setClientHighlightTicks(int ticks) {
		this.traceableprint$clientHighlightTicks = ticks;
	}

	// ClientHighlightHolder 的实现：排他切换时清除本客户端高亮并立即熄灭。
	// 仅当倒计时>0（确认 glowing 由本客户端点亮）才写 flag，不误关服务端原生发光。同样必须 public。
	@Unique public void traceableprint$clearClientHighlight() {
		if (this.traceableprint$clientHighlightTicks <= 0) return;
		this.traceableprint$clientHighlightTicks = 0;
		LivingEntity entity = (LivingEntity) (Object) this;
		if (!entity.level().isClientSide()) return; // 集成服务端逻辑侧同一份织入代码，须按侧守卫
		((EntityAccessor) entity).traceableprint$setSharedFlag(6, false);
	}

	// 桥接 Entity#setSharedFlag（protected）：由接口型访问器 EntityAccessor 提供实现（@Mixin(Entity.class)，
	// @Invoker 不能跨类定位到父类方法，故不能写在 LivingEntity 的 mixin 里）

	@Inject(method = "tick", at = @At("TAIL"))
	private void clientGlowTick(CallbackInfo ci) {
		if (this.traceableprint$clientHighlightTicks <= 0) return;
		LivingEntity entity = (LivingEntity) (Object) this;
		// 单人模式下集成服务端的逻辑侧 tick 走的是同一份织入代码，须按侧守卫
		if (!entity.level().isClientSide()) return;

		if (--this.traceableprint$clientHighlightTicks == 0) {
			((EntityAccessor) entity).traceableprint$setSharedFlag(6, false); // 到期熄灭
		} else if (entity.isCrouching()) {
			// 潜行取消高亮：熄灭并把倒计时归零（一次性，起身不自动恢复；后续重新点击走 apply 的潜行跳过）
			this.traceableprint$clientHighlightTicks = 0;
			((EntityAccessor) entity).traceableprint$setSharedFlag(6, false);
		} else if (!entity.isCurrentlyGlowing()) {
			((EntityAccessor) entity).traceableprint$setSharedFlag(6, true); // 未亮/被服务端 flags 同步顶掉：点亮或重新断言
		}
	}
}
