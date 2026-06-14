package com.rimo.traceableprint;

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
 *   若目标在服务端数据里本来就在发光（全局可见），直接跳过，避免到期时误关别人的原生高亮。
 *
 * 高亮是仅点击者可见的纯客户端本地状态，不经任何网络包；实体尚未进入本客户端追踪范围时高亮会丢失（可接受）。
 */
public final class ClientHighlights {
	private ClientHighlights() {
	}

	public static void apply(ClientLevel level, int entityId, int durationTicks) {
		if (durationTicks <= 0) return;
		Entity entity = level.getEntity(entityId);
		if (entity == null || entity.isRemoved()) return;

		if (entity instanceof FootprintEntity footprint) {
			footprint.applyClientHighlight(durationTicks);
		} else if (entity instanceof LivingEntity living) {
			if (living.isCurrentlyGlowing()) return; // 服务端原生发光，本就全局可见，不接管
			if (living instanceof ClientHighlightHolder holder) {
				holder.setClientHighlightTicks(durationTicks); // 先挂倒计时（后续维护/到期熄灭归 mixin 管）
				((EntityAccessor) living).traceableprint$setSharedFlag(6, true); // 立即点亮（接口型访问器，Entity 已实现）
			}
		}
	}
}
