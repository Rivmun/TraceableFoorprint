package com.rimo.traceableprint.config;

import com.rimo.traceableprint.Common;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;

/**
 * 基于 ClothConfig 的配置界面
 *
 * <p>约定：所有 setSaveConsumer 直接写入活单例 {@code Common.CONFIG}，ClothConfig 在点击「完成」时统一回调这些消费者，
 * 随后 {@link ConfigBuilder#setSavingRunnable} 触发 {@code CONFIG.save()} 落盘到 config/traceableprint.json。
 * 数值/文本项配 {@code setDefaultValue}，界面右上角「重置」可回到代码里的默认常量（列表项默认值取 Config 的 public DEF_* 常量）。
 *
 * <p>提示文案全部走翻译键（前缀 {@code text.traceableprint.}）；tooltip 只放一两句短说明，
 * 像自定义贴图那种多约束的复杂解释改用内联的文本描述项（startTextDescription）逐条展示，避免 tooltip 过长难以阅读。
 */
public class ConfigScreen {
	// Common.CONFIG 活单例的局部别名，缩短下面消费者/初值表达的重复前缀
	private static final Config CONFIG = Common.CONFIG;

	public static Screen create(Screen parent) {
		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Component.translatable("text.traceableprint.title"))
				.setTransparentBackground(true);
		builder.setSavingRunnable(CONFIG::save);
		ConfigEntryBuilder eb = builder.entryBuilder();

		buildGeneral(builder, eb);
		buildSpawn(builder, eb);
		buildAppearance(builder, eb);
		buildPerMob(builder, eb);
		buildTexture(builder, eb);

		return builder.build();
	}

	// - - - 通用 - - -
	private static void buildGeneral(ConfigBuilder builder, ConfigEntryBuilder eb) {
		ConfigCategory cat = builder.getOrCreateCategory(Component.translatable("text.traceableprint.category.general"));

		// 模组总开关放最前：三档（所有生物 / 仅玩家 / 禁用），用下拉选择器展示
		cat.addEntry(eb.startEnumSelector(t("option.enableMod"), Config.WorkMode.class, CONFIG.getEnableMod())
				.setDefaultValue(Config.DEFAULT_ENABLE_MOD)
				.setEnumNameProvider(mode -> Component.translatable(((Config.WorkMode) mode).getTranslationKey()))
				.setTooltip(t("option.enableMod.@Tooltip"))
				.setSaveConsumer(CONFIG::setEnableMod)
				.build());

		cat.addEntry(eb.startBooleanToggle(t("option.notifyTraced"), CONFIG.isNotifyTraced())
				.setDefaultValue(Config.DEFAULT_NOTIFY_TRACED)
				.setTooltip(t("option.notifyTraced.@Tooltip"))
				.setSaveConsumer(CONFIG::setNotifyTraced)
				.build());

		cat.addEntry(eb.startBooleanToggle(t("option.printsForInvisible"), CONFIG.isPrintsForInvisible())
				.setDefaultValue(Config.DEFAULT_PRINTS_FOR_INVISIBLE)
				.setTooltip(t("option.printsForInvisible.@Tooltip"))
				.setSaveConsumer(CONFIG::setPrintsForInvisible)
				.build());

		cat.addEntry(eb.startIntSlider(t("option.highlightTicks"), CONFIG.getHighlightTicks(), 20, 1200)
				.setDefaultValue(Config.DEFAULT_HIGHLIGHT_TICKS)
				.setTextGetter(ConfigScreen::seconds)
				.setTooltip(t("option.highlightTicks.@Tooltip"))
				.setSaveConsumer(CONFIG::setHighlightTicks)
				.build());

		// 方向指示粒子与高亮时长同居“交互反馈”语义区，紧跟其后；纯客户端本地生效
		cat.addEntry(eb.startBooleanToggle(t("option.directionParticles"), CONFIG.isShowDirectionParticles())
				.setDefaultValue(Config.DEFAULT_SHOW_DIRECTION_PARTICLES)
				.setTooltip(t("option.directionParticles.@Tooltip"))
				.setSaveConsumer(CONFIG::setShowDirectionParticles)
				.build());

		// 生物名单：黑名单还是白名单由下面的反转开关决定，两者配在同屏相邻位置便于对照
		cat.addEntry(eb.startBooleanToggle(t("option.entityListInverted"), CONFIG.isEntityListInverted())
				.setDefaultValue(Config.DEFAULT_ENTITY_LIST_INVERTED)
				.setTooltip(t("option.entityListInverted.@Tooltip"))
				.setSaveConsumer(CONFIG::setEntityListInverted)
				.build());

		cat.addEntry(eb.startStrList(t("option.entityList"), CONFIG.getEntityList())
				.setDefaultValue(new ArrayList<>())
				.setTooltip(t("option.entityList.@Tooltip"))
				.setSaveConsumer(CONFIG::setEntityList)
				.build());

		cat.addEntry(eb.startStrList(t("option.applyBlocks"), CONFIG.getApplyBlocks())
				.setDefaultValue(new ArrayList<>())
				.setTooltip(t("option.applyBlocks.@Tooltip"))
				.setSaveConsumer(CONFIG::setApplyBlocks)
				.build());
	}

	// - - - 生成 - - -
	private static void buildSpawn(ConfigBuilder builder, ConfigEntryBuilder eb) {
		ConfigCategory cat = builder.getOrCreateCategory(Component.translatable("text.traceableprint.category.spawn"));

		cat.addEntry(eb.startLongSlider(t("option.lifetime"), CONFIG.getFootprintLifetimeTicks(), 600, 12000)
				.setDefaultValue(Config.DEFAULT_FOOTPRINT_LIFETIME_TICKS)
				.setTextGetter(ConfigScreen::seconds)
				.setTooltip(t("option.lifetime.@Tooltip"))
				.setSaveConsumer(CONFIG::setFootprintLifetimeTicks)
				.build());

		cat.addEntry(eb.startIntSlider(t("option.spawnInterval"), CONFIG.getSpawnIntervalTicks(), 10, 200)
				.setDefaultValue(Config.DEFAULT_SPAWN_INTERVAL_TICKS)
				.setTextGetter(ticks -> Component.nullToEmpty(ticks + "t"))
				.setTooltip(t("option.spawnInterval.@Tooltip"))
				.setSaveConsumer(CONFIG::setSpawnIntervalTicks)
				.build());

		cat.addEntry(eb.startDoubleField(t("option.minDistance"), CONFIG.getMinSpawnDistance())
				.setDefaultValue(Config.DEFAULT_MIN_SPAWN_DISTANCE)
				.setMin(1).setMax(16)
				.setTooltip(t("option.minDistance.@Tooltip"))
				.setSaveConsumer(CONFIG::setMinSpawnDistance)
				.build());

		cat.addEntry(eb.startFloatField(t("option.hardnessGate"), CONFIG.getHardnessGate())
				.setDefaultValue(Config.DEFAULT_HARDNESS_GATE)
				.setMin(0).setMax(20)
				.setTooltip(t("option.hardnessGate.@Tooltip"))
				.setSaveConsumer(CONFIG::setHardnessGate)
				.build());
	}

	// - - - 外观与位置 - - -
	private static void buildAppearance(ConfigBuilder builder, ConfigEntryBuilder eb) {
		ConfigCategory cat = builder.getOrCreateCategory(Component.translatable("text.traceableprint.category.appearance"));

		cat.addEntry(eb.startFloatField(t("option.textureSize"), CONFIG.getFootprintTextureSize())
				.setDefaultValue(Config.DEFAULT_FOOTPRINT_TEXTURE_SIZE)
				.setMin(0.01F).setMax(1.5F)
				.setTooltip(t("option.textureSize.@Tooltip"))
				.setSaveConsumer(CONFIG::setFootprintTextureSize)
				.build());

		cat.addEntry(eb.startFloatField(t("option.yOffset"), CONFIG.getFootprintYOffset())
				.setDefaultValue(Config.DEFAULT_FOOTPRINT_Y_OFFSET)
				.setMin(-1).setMax(1)
				.setTooltip(t("option.yOffset.@Tooltip"))
				.setSaveConsumer(CONFIG::setFootprintYOffset)
				.build());

		// 默认左右/前后偏移已不再开放（硬编码进 LivingEntityMixin）；逐生物的偏移仍在「逐生物覆写」分类里编辑。
	}

	// - - - 逐生物覆写表 - - -
	private static void buildPerMob(ConfigBuilder builder, ConfigEntryBuilder eb) {
		ConfigCategory cat = builder.getOrCreateCategory(Component.translatable("text.traceableprint.category.per_mob"));

		// 该分类整体是「条目字符串」列表，格式统一但字段各异，先在顶部放一段格式说明，比每个列表各写一遍更省版面
		cat.addEntry(eb.startTextDescription(t("per_mob.header")).build());

		cat.addEntry(eb.startStrList(t("option.sideOffsetList"), CONFIG.getSideOffsetList())
				.setDefaultValue(new ArrayList<>(Config.DEF_SIDE_OFFSET_LIKE))
				.setTooltip(t("option.sideOffsetList.@Tooltip"))
				.setSaveConsumer(CONFIG::setSideOffsetList)
				.build());

		cat.addEntry(eb.startStrList(t("option.forwardOffsetList"), CONFIG.getForwardOffsetList())
				.setDefaultValue(new ArrayList<>(Config.DEF_FORWARD_OFFSET_LIKE))
				.setTooltip(t("option.forwardOffsetList.@Tooltip"))
				.setSaveConsumer(CONFIG::setForwardOffsetList)
				.build());

		cat.addEntry(eb.startStrList(t("option.sizeList"), CONFIG.getSizeList())
				.setDefaultValue(new ArrayList<>(Config.DEF_SIZE_PER_MOB))
				.setTooltip(t("option.sizeList.@Tooltip"))
				.setSaveConsumer(CONFIG::setSizeList)
				.build());

		cat.addEntry(eb.startStrList(t("option.blockHeightList"), CONFIG.getBlockHeightList())
				.setDefaultValue(new ArrayList<>(Config.DEF_BLOCK_HEIGHT))
				.setTooltip(t("option.blockHeightList.@Tooltip"))
				.setSaveConsumer(CONFIG::setBlockHeightList)
				.build());
	}

	// - - - 自定义贴图 - - -
	private static void buildTexture(ConfigBuilder builder, ConfigEntryBuilder eb) {
		ConfigCategory cat = builder.getOrCreateCategory(Component.translatable("text.traceableprint.category.texture"));

		cat.addEntry(eb.startStrList(t("option.textureList"), CONFIG.getTextureList())
				.setDefaultValue(new ArrayList<>(Config.DEF_TEXTURE_LIST))
				.setSaveConsumer(CONFIG::setTextureList)
				.build());

		// 贴图项约束多且涉及「服务端优先」这一反直觉语义，用内联文本描述逐条摊开，而不是塞进一个超长 tooltip
		for (String key : new String[] {
				"texture.desc.format",
				"texture.desc.path",
				"texture.desc.match",
				"texture.desc.server",
				"texture.desc.single" }) {
			cat.addEntry(eb.startTextDescription(t(key)).build());
		}
	}

	/** 取翻译组件，统一前缀 {@code text.traceableprint.}，减少上文噪音。 */
	private static Component t(String key) {
		return Component.translatable("text.traceableprint." + key);
	}

	/** 把 tick 数格式化成「N 秒」用于滑条文本；20 tick = 1 秒（整除截断，滑条粒度足够）。 */
	private static Component seconds(Number ticks) {
		return Component.translatable("text.traceableprint.seconds", ticks.longValue() / 20L);
	}
}
