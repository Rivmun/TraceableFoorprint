//? if fabric {
package com.rimo.traceableprint.loaders.fabric;

import com.rimo.traceableprint.config.MissingDependencyScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu 配置入口。
 *
 * <p>ClothConfig 缺席时 {@code ConfigScreen} 类根本无法加载（引用了 cloth 的类型会抛
 * NoClassDefFoundError）。这里不在 {@code getModConfigScreenFactory()} 直接引用 {@code ConfigScreen}，
 * 而是把它藏在返回的 lambda 体内——JVM 只在该 lambda 真正执行、且确实走到「已装 cloth」分支时
 * 才按需解析这个类；未装 cloth 时走 else 分支，{@code ConfigScreen} 永不被加载。
 *
 * <p>依赖缺失时不再走「抛异常复用 ModMenu tooltip」那条路（tooltip 需先点一次才填充、URL 也点不动），
 * 而是与 NeoForge 端保持一致，统一返回 {@link MissingDependencyScreen}：玩家点「配置」后直接看到
 * 一行提示 + 返回按钮，两端行为与文案（同一翻译键）都对齐。
 */
public class ModMenuEntryPoint implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> Platform.isModLoaded("cloth-config2")
				? com.rimo.traceableprint.config.ConfigScreen.create(parent)
				: new MissingDependencyScreen(parent);
	}
}
//? }
