package com.rimo.traceableprint.config;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Cloth Config 未安装时的降级提示屏——Fabric / NeoForge 共用。
 *
 * <p>无论哪端，配置入口工厂在 cloth 缺席时都返回本屏：一行提示 + 一个返回按钮，玩家点「配置」
 * 后直接可见（Fabric 早期试过“抛异常复用 ModMenu 悬停 tooltip”，但需先点一次才填充、链接也不可点，
 * 故改回与 NeoForge 一致的第二屏幕写法）。提示文案走翻译键 {@code text.traceableprint.config.missing_dependency}。
 *
 * <p>刻意只用原版客户端类，不引用任何 cloth 类型，故 Cloth Config 缺席时该类也能安全加载。
 */
public class MissingDependencyScreen extends Screen {
	private final Screen parent;

	public MissingDependencyScreen(Screen parent) {
		// 无标题：正文那一行提示已足够说明问题
		super(Component.empty());
		this.parent = parent;
	}

	@Override
	protected void init() {
		super.init();
		// 一行提示：MultiLineTextWidget 宽度自适应，超宽自动换行
		MultiLineTextWidget label = new MultiLineTextWidget(
				Component.translatable("text.traceableprint.config.missing_dependency"), this.font)
				.setMaxWidth(this.width - 60);
		label.setX((this.width - label.getWidth()) / 2);
		label.setY(this.height / 2 - 24);
		this.addRenderableWidget(label);
		// 退出按钮：返回父界面
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
				.bounds((this.width - 100) / 2, this.height / 2 + 6, 100, 20)
				.build());
	}

	@Override
	public void onClose() {
		//~ if < 26.2 'minecraft.gui.setScreen' -> 'minecraft.setScreen'
		this.minecraft.gui.setScreen(this.parent);
	}
}
