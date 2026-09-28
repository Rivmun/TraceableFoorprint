package com.rimo.traceableprint;

/**
 * 由 mixin.client.LivingEntityMixin 通过 @Implements 注入到 LivingEntity 的客户端高亮接口。
 *
 * 运行时 Mixin 会把本接口挂到 LivingEntity 上，故客户端可 (ClientHighlightHolder) entity 强转写入倒计时。
 * 保留单方法接口而非“强转 mixin 类”：后者运行时必抛 ClassCastException（mixin 类不是 target 的父类型）。
 */
public interface ClientHighlightHolder {
	/** 写入客户端本地高亮剩余 tick（>0 表示 glowing 由本客户端点亮，mixin tick 负责到期熄灭与重断言） */
	void setClientHighlightTicks(int ticks);

	/** 清除客户端本地高亮：倒计时归零并立即熄灭 glowing（仅限本客户端点亮的，不碰服务端原生发光） */
	void clearClientHighlight();
}
