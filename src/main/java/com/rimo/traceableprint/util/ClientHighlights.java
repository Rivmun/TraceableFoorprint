package com.rimo.traceableprint.util;

import com.rimo.traceableprint.entity.FootprintEntity;
import com.rimo.traceableprint.mixin.client.EntityAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * 客户端本地高亮的公共落地逻辑（由 FootprintEntity.interact 依链寻踪后直接调用，保持 loader 无关）。
 *
 * - 脚印：写实体本地倒计时，渲染器读 isHighlighted() 走脉冲管线；
 * - 父生物：写倒计时 + 经 EntityAccessor 直写 setSharedFlag(6,true) 立即点亮；
 *   后续每帧维护/到期熄灭由 mixin.client.LivingEntityMixin 接管
 *   （26.1 的 setGlowingTag 在客户端是 no-op，必须直写 shared flag，见该类注释）；
 *   若目标在服务端数据里本来就在发光（全局可见），直接跳过，避免到期时误关别人的原生高亮；
 *   目标处于潜行状态时同样跳过（与 mixin.client.LivingEntityMixin 的“潜行即消”同一套规则：
 *   点亮时已在潜行不点，点亮后才潜行就地撤销）。
 *
 * 高亮是仅点击者可见的纯客户端本地状态，不经任何网络包；实体尚未进入本客户端追踪范围时高亮会丢失（可接受）。
 *
 * 全局同时只允许一个高亮：点亮新目标前先把上一个高亮实体（脚印/父生物）恢复常规状态。
 */
public final class ClientHighlights {
	// 当前高亮实体 id（仅客户端有意义；集成服双端同 JVM，靠 apply 只在客户端侧调用保证不串）
	private static int highlightedId = -1;

	private ClientHighlights() {
	}

	/**
	 * 点亮目标（限同时仅一个）：先把上一个高亮实体恢复常规，再点亮新目标；
	 * 重复点击同一目标不熄灭，仅续期。
	 */
	public static void apply(ClientLevel level, int entityId, int durationTicks) {
		if (durationTicks <= 0) return;
		Entity entity = level.getEntity(entityId);
		if (entity == null || entity.isRemoved()) return;

		// 排他：上一个高亮不是本次目标时先熄灭，回常规状态
		if (highlightedId != -1 && highlightedId != entityId) {
			Entity previous = level.getEntity(highlightedId);
			if (previous instanceof FootprintEntity footprint) {
				footprint.clearClientHighlight();
			} else if (previous instanceof ClientHighlightHolder holder) {
				holder.clearClientHighlight();
			}
			// 上一目标已不在本地副本（移出追踪范围/被移除）：其客户端本地状态随实体一同消亡，无需处理
		}
		highlightedId = entityId;

		if (entity instanceof FootprintEntity footprint) {
			footprint.applyClientHighlight(durationTicks);
		} else if (entity instanceof LivingEntity living) {
			if (living.isCurrentlyGlowing()) return; // 服务端原生发光，本就全局可见，不接管
			if (living.isCrouching()) return; // 潜行者不点亮高亮（起身后重新点击可再亮）
			if (living instanceof ClientHighlightHolder holder) {
				holder.setClientHighlightTicks(durationTicks); // 先挂倒计时（后续维护/到期熄灭归 mixin 管）
				((EntityAccessor) living).traceableprint$setSharedFlag(6, true); // 立即点亮（接口型访问器，Entity 已实现）
			}
		}
	}
}
