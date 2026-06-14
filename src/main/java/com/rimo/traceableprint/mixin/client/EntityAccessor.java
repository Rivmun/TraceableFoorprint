package com.rimo.traceableprint.mixin.client;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Entity 客户端侧访问器（mixins.json 的 client 段，接口型 mixin：目标类自动实现本接口）。
 *
 * 桥接 protected 的 Entity#setSharedFlag：26.1 里 setGlowingTag 在客户端是 no-op
 * （详见 client.LivingEntityMixin 注释），客户端点亮/熄灭 glowing 必须直写 shared flag 位
 * （flag 6 = glowing）。@Invoker 只能定位目标类自身声明的方法，setSharedFlag 声明在
 * Entity 而非 LivingEntity，故访问器必须 @Mixin(Entity.class)，不能塞进 LivingEntity 的 mixin 里。
 *
 * 用法：((EntityAccessor) entity).traceableprint$setSharedFlag(6, true);
 */
@Mixin(Entity.class)
public interface EntityAccessor {
	@Invoker("setSharedFlag")
	void traceableprint$setSharedFlag(int flag, boolean value);
}
