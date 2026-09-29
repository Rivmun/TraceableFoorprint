package com.rimo.traceableprint.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.rimo.traceableprint.Common;
//~ if neoforge 'fabric' -> 'neoforge'
import com.rimo.traceableprint.loaders.fabric.Platform;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 模组可调参数（纯 Java，零 loader 依赖，双端共用）。
 *
 * 由 Common.CONFIG 暴露单例，其它模块经 Common.CONFIG.getXxx() 读取。
 * 读写经 Gson 序列化到写死路径 {@code Platform.getConfigFolder()/traceableprint.json}：{@link #load()} 于构造单例时调用，
 * {@link #save()} 供配置变更后落盘。集合字段非 final，便于 Gson 直接反序列化填充。
 */
public class Config {
	// - - - - 默认值 - - - -
	public static final long DEFAULT_FOOTPRINT_LIFETIME_TICKS = 1200L; // 脚印存活时长（tick，1200 = 60 秒），超时自毁
	public static final double DEFAULT_MIN_SPAWN_DISTANCE = 5.0;        // 与上一脚印的最小间距（方块），过近则跳过生成
	public static final int DEFAULT_SPAWN_INTERVAL_TICKS = 40;         // 移动检测/生成尝试的固定间隔（tick）
	public static final int DEFAULT_HIGHLIGHT_TICKS = 200;             // 单次点击的高亮时长（tick，10 秒）
	public static final float DEFAULT_FOOTPRINT_Y_OFFSET = 0.0F;            // 脚印抬高量（避免与地面 z-fight）
	// 注：默认左右/前后偏移幅度不再开放给玩家，已硬编码进 LivingEntityMixin（逐生物覆写表命中时优先用表值，二者最终都乘综合缩放倍率）。
	public static final float DEFAULT_FOOTPRINT_TEXTURE_SIZE = 0.3125F;  // 脚印贴图默认尺寸（方块，正方形边长），5/16 = 匹配原版像素大小；逐生物缩放表在此基础上再乘
	public static final float DEFAULT_HARDNESS_GATE = 3.0F;            // 硬度门槛：|defaultDestroyTime| < gate 才可生成
	public static final boolean DEFAULT_NOTIFY_TRACED = true;          // 被追踪提示开关：服务端在有人追到链尾时给父玩家发 action bar 提示
	public static final boolean DEFAULT_PRINTS_FOR_INVISIBLE = true;   // 隐形实体（隐身效果 / invisible 标志）是否仍留脚印
	public static final boolean DEFAULT_ENTITY_LIST_INVERTED = false;  // 生物名单反转：关=名单作黑名单，开=名单作白名单
	public static final WorkMode DEFAULT_ENABLE_MOD = WorkMode.ALL;       // 模组总开关默认档：所有生物（仍受方块过滤/生物名单约束）

	private long footprintLifetimeTicks = DEFAULT_FOOTPRINT_LIFETIME_TICKS;
	private double minSpawnDistance = DEFAULT_MIN_SPAWN_DISTANCE;
	private int spawnIntervalTicks = DEFAULT_SPAWN_INTERVAL_TICKS;
	private int highlightTicks = DEFAULT_HIGHLIGHT_TICKS;
	private float footprintYOffset = DEFAULT_FOOTPRINT_Y_OFFSET;
	private float footprintTextureSize = DEFAULT_FOOTPRINT_TEXTURE_SIZE;
	private float hardnessGate = DEFAULT_HARDNESS_GATE;
	private boolean notifyTraced = DEFAULT_NOTIFY_TRACED;
	private boolean printsForInvisible = DEFAULT_PRINTS_FOR_INVISIBLE;
	private boolean entityListInverted = DEFAULT_ENTITY_LIST_INVERTED;
	// 模组总开关（三档）：所有生物 / 仅玩家 / 禁用
	private WorkMode enableMod = DEFAULT_ENABLE_MOD;

	// 生成白名单（方块ID "namespace:path"，或 "#namespace:tag" 标签）：命中即跳过硬度判定直接放行，优先级最高
	private Set<String> applyBlocks = new HashSet<>();

	// 生物名单（实体类型ID "namespace:path"，或 "#namespace:tag" 标签）：默认作黑名单，entityListInverted 开启后反转为白名单
	private Set<String> entityList = new HashSet<>();

	// - - - 逐生物偏移初始值（复刻参考工程 FootprintParticle）- - -
	// horseLikeMobs→前后（前进方向）偏移：无 float 的条目取参考中“命中但无幅度”的默认 0.75，已带 float 的保留原值。
	public static final List<String> DEF_FORWARD_OFFSET_LIKE = Arrays.asList(
			"minecraft:horse,0.75",
			"minecraft:donkey,0.75",
			"minecraft:mule,0.75",
			"minecraft:zombie_horse,0.75",
			"minecraft:skeleton_horse,0.75",
			"minecraft:camel,0.75",
			"minecraft:sniffer,0.8",
			"minecraft:ravager,0.5",
			"minecraft:creeper,0.3"
	);
	// spiderLikeMobs→左右（垂直前进方向）偏移：无 float 的条目取参考中默认 0.9，已带 float 的保留原值。
	public static final List<String> DEF_SIDE_OFFSET_LIKE = Arrays.asList(
			"minecraft:spider,0.9",
			"minecraft:cave_spider,0.9",
			"minecraft:camel,0.3",
			"minecraft:sniffer,0.3",
			"minecraft:iron_golem,0.3",
			"minecraft:ravager,0.3"
	);
	// sizePerMob→脚印贴图缩放倍率（复刻参考工程 DEF_SIZE）：命中条目的 float 连乘，另叠加幼体 0.66 与实体自身 getScale()。
	public static final List<String> DEF_SIZE_PER_MOB = Arrays.asList(
			"minecraft:chicken,0.6",
			"minecraft:pig,0.8",
			"minecraft:cat,0.5",
			"minecraft:ocelot,0.5",
			"minecraft:wolf,0.6",
			"minecraft:sniffer,1.6",
			"minecraft:enderman,0.6",
			"minecraft:slime,2",
			"minecraft:magma_cube,2",
			"minecraft:creeper,0.8",
			"minecraft:iron_golem,1.2",
			"minecraft:ravager,2",
			"minecraft:armadillo,0.7"
	);
	// blockHeight→脚印在特定方块上生成时的额外 Y 抬升（复刻参考工程 DEF_BLOCKHEIGHT）：雪层/灵魂沙/泥等视觉高度与碰撞箱不符、
	// 实体踩上去会下沉，脚印需相应抬高以免被非完整方块遮挡。支持 "namespace:path" 与 "#namespace:tag" 两种写法。
	public static final List<String> DEF_BLOCK_HEIGHT = Arrays.asList(
			"minecraft:snow,0.125",
			"minecraft:soul_sand,0.125",
			"minecraft:mud,0.125"
	);
	// textureList→格式提示条目（不删、也不当配置读）：本模组的 json 配置没有注释能力，留一条样例让直接改文件的玩家
	// 一眼看懂格式；它永不命中（mod_id:mob_id 不是任何已注册实体），因此不产生任何效果。
	// 退一步讲，即使真有模组注册了这个 id，候选名也找不到对应贴图，客户端存在性校验会退回默认贴图，不会崩、也不会粉黑块。
	public static final List<String> DEF_TEXTURE_LIST = Arrays.asList(
			"mod_id:mob_id,textureName1,textureName2"
	);

	// 默认值列表以下列 public 常量形式暴露，供配置界面的“重置”按钮作 setDefaultValue 使用。
	// 逐生物左右偏移覆写表：条目格式 "modid:mobid,float"（如 "minecraft:zombie,0.3"），命中实体注册名则用该 float 幅度。
	// 内部存为可编辑的 List<String>（供 ClothConfig 等配置库直接展示/编辑）；只按 namespace:path 精确匹配，不支持标签。
	private List<String> sideOffsetList = new ArrayList<>(DEF_SIDE_OFFSET_LIKE);
	// 逐生物前后偏移覆写表：条目格式同 "modid:mobid,float"，命中则用该 float 沿前进方向的幅度。
	private List<String> forwardOffsetList = new ArrayList<>(DEF_FORWARD_OFFSET_LIKE);
	// 逐生物脚印贴图缩放表：条目格式同 "modid:mobid,float"，仅缩放脚印贴图（不改实体碰撞箱）；只按 namespace:path 精确匹配。
	private List<String> sizeList = new ArrayList<>(DEF_SIZE_PER_MOB);
	// 方块额外抬高表：条目格式 "blockid,float" 或 "#tagid,float"，脚印落脚方块命中时对其 Y 叠加 float 抬升。
	private List<String> blockHeightList = new ArrayList<>(DEF_BLOCK_HEIGHT);
	// 逐生物脚印贴图覆写表：条目格式 "modid:mobid,textureName1,textureName2,..."——首个逗号前是实体注册名，
	// 其后是候选贴图名，服务端生成脚印时随机取一个并同步给客户端；命中不到（或列表为空）就用默认 footprint.png。
	// 贴图名默认相对本模组资源目录（{@code traceableprint:textures/entity/<名字>.png}），也可写完整 "namespace:path"
	// 让整合包作者把贴图放在自己的命名空间下；名字→Identifier 的组装与资源包存在性校验在客户端 FootprintTextures。
	private List<String> textureList = new ArrayList<>(DEF_TEXTURE_LIST);

	public long getFootprintLifetimeTicks() {
		return footprintLifetimeTicks;
	}
	public void setFootprintLifetimeTicks(long ticks) {
		this.footprintLifetimeTicks = Math.max(0, ticks);
	}

	public double getMinSpawnDistance() {
		return minSpawnDistance;
	}
	public void setMinSpawnDistance(double distance) {
		this.minSpawnDistance = Math.max(0, distance);
	}

	public int getSpawnIntervalTicks() {
		return spawnIntervalTicks;
	}
	public void setSpawnIntervalTicks(int ticks) {
		this.spawnIntervalTicks = Math.max(1, ticks);
	}

	public int getHighlightTicks() {
		return highlightTicks;
	}
	public void setHighlightTicks(int ticks) {
		this.highlightTicks = Math.max(1, ticks);
	}

	/** 脚印抬高量，单位为百分之一方块（渲染/生成侧除以 100 使用） */
	public float getFootprintYOffset() {
		return footprintYOffset;
	}
	public void setFootprintYOffset(float offset) {
		this.footprintYOffset = offset;
	}

	/** 脚印贴图默认尺寸（方块，正方形边长）：默认 5/16 匹配原版像素大小；逐生物缩放表（sizeList）与实体 getScale() 在此基准上再乘 */
	public float getFootprintTextureSize() {
		return footprintTextureSize;
	}
	public void setFootprintTextureSize(float size) {
		this.footprintTextureSize = Math.max(0, size);
	}

	public float getHardnessGate() {
		return hardnessGate;
	}
	public void setHardnessGate(float gate) {
		this.hardnessGate = Math.max(0, gate);
	}

	/** 被追踪提示开关（多人服务器：有人通过脚印追到你时，向被追踪者发 action bar 提示） */
	public boolean isNotifyTraced() {
		return notifyTraced;
	}
	public void setNotifyTraced(boolean notifyTraced) {
		this.notifyTraced = notifyTraced;
	}

	/**
	 * 隐形实体是否仍留脚印：开（默认）= 隐身≠无迹可寻，隐形玩家/生物照常踩出脚印；
	 * 关 = 处于隐身效果或自带 invisible 标志的实体不留脚印（潜行与否不受本开关管制，一律不留）。
	 */
	public boolean isPrintsForInvisible() {
		return printsForInvisible;
	}
	public void setPrintsForInvisible(boolean printsForInvisible) {
		this.printsForInvisible = printsForInvisible;
	}

	/**
	 * 生物名单反转：
	 * 关（默认）= entityList 是黑名单，名单内实体不留脚印，其余照常；
	 * 开 = entityList 是白名单，只为名单内实体留脚印，其余一律不留。
	 * 注意：反转且名单为空时按字面语义理解 = 谁都不留脚印（不是“忽略过滤”）。
	 */
	public boolean isEntityListInverted() {
		return entityListInverted;
	}
	public void setEntityListInverted(boolean entityListInverted) {
		this.entityListInverted = entityListInverted;
	}

	/** 模组总开关：见 {@link WorkMode}。{@code DISABLED} 时服务端不生成任何脚印；{@code PLAYER_ONLY} 仅玩家生成。 */
	public WorkMode getEnableMod() {
		return enableMod;
	}
	public void setEnableMod(WorkMode enableMod) {
		this.enableMod = enableMod == null ? DEFAULT_ENABLE_MOD : enableMod;
	}

	/**
	 * 模组总开关三档：{@code ALL} 所有生物都生成脚印（仍受方块过滤与生物名单约束）、
	 * {@code PLAYER_ONLY} 仅玩家生成、{@code DISABLED} 完全禁用。
	 * 只存翻译键、不引 MC 的 Component，以保持 Config 零 loader/MC 依赖；翻译键在配置界面（fabric 侧）转成 Component。
	 */
	public enum WorkMode {
		DISABLED("text.traceableprint.work_mode.disabled"),
		PLAYER_ONLY("text.traceableprint.work_mode.player_only"),
		ALL("text.traceableprint.work_mode.all");

		private final String translationKey;
		WorkMode(String translationKey) {
			this.translationKey = translationKey;
		}
		public String getTranslationKey() {
			return translationKey;
		}
	}

	public void setApplyBlocks(List<String> blocks) {
		applyBlocks.clear();
		applyBlocks.addAll(blocks);
	}
	public List<String> getApplyBlocks() {
		return applyBlocks.stream().toList();
	}

	public void setEntityList(List<String> entities) {
		entityList.clear();
		entityList.addAll(entities);
	}
	/** 生物名单（实体类型ID 或 "#tag"），黑名单还是白名单由 isEntityListInverted() 决定 */
	public List<String> getEntityList() {
		return entityList.stream().toList();
	}

	/**
	 * 逐生物左右偏移覆写表（条目 "modid:mobid,float"）：供配置库展示/编辑，getter 返回不可变副本。
	 */
	public void setSideOffsetList(List<String> entries) {
		sideOffsetList.clear();
		sideOffsetList.addAll(entries);
	}
	public List<String> getSideOffsetList() {
		return List.copyOf(sideOffsetList);
	}

	/** 逐生物前后偏移覆写表（条目 "modid:mobid,float"）：供配置库展示/编辑，getter 返回不可变副本。 */
	public void setForwardOffsetList(List<String> entries) {
		forwardOffsetList.clear();
		forwardOffsetList.addAll(entries);
	}
	public List<String> getForwardOffsetList() {
		return List.copyOf(forwardOffsetList);
	}

	/**
	 * 逐生物脚印贴图缩放表（条目 "modid:mobid,float"）：供配置库展示/编辑，getter 返回不可变副本。
	 */
	public void setSizeList(List<String> entries) {
		sizeList.clear();
		sizeList.addAll(entries);
	}
	public List<String> getSizeList() {
		return List.copyOf(sizeList);
	}

	/**
	 * 方块额外抬高表（条目 "blockid,float" 或 "#tagid,float"）：供配置库展示/编辑，getter 返回不可变副本。
	 * 命中逻辑（同时支持 id 与标签）在服务端生成侧（LivingEntityMixin）根据落脚方块查询。
	 */
	public void setBlockHeightList(List<String> entries) {
		blockHeightList.clear();
		blockHeightList.addAll(entries);
	}
	public List<String> getBlockHeightList() {
		return List.copyOf(blockHeightList);
	}

	/**
	 * 逐生物脚印贴图覆写表（条目 "modid:mobid,texture1,texture2,..."）：供配置库展示/编辑，getter 返回不可变副本。
	 *
	 * <p>【接入配置界面时，本项 tooltip 必须包含以下要点，不能只写“自定义脚印贴图”】
	 * 候选贴图是「服务端」从它自己那份 textureList 里抽的，抽完把名字同步下来，所以多人游戏下服务端优先：
	 * 与客户端不一致时，客户端这份列表完全不参与选取（既盖不了服务端选定的图，也补不上服务端没配的生物），
	 * 客户端唯一保留的话语权是资源存在性——同步来的贴图名在本机资源包里找不到时，退回默认 footprint.png。
	 * 单人与自己的内嵌服读的是同一个 json，不存在差异（别把上面这句写成“客户端配置无用”以免误导单人玩家）。
	 * 参考文案（英文同步写给 en_us）：
	 * 「注意：具体用哪张贴图由服务端的这份配置决定。多人游戏中若服务端配置与本机不同，以服务端为准，
	 * 本机列表不参与选取（仅当同步来的贴图在本机资源包里找不到时退回默认 footprint.png）。」
	 */
	public void setTextureList(List<String> entries) {
		textureList.clear();
		textureList.addAll(entries);
	}
	public List<String> getTextureList() {
		return List.copyOf(textureList);
	}

	/**
	 * 收集实体注册名（namespace:path）在贴图覆写表中的全部候选贴图名：把每个命中条目首个逗号之后的各段
	 * （去空白、跳过空段）合并成一个候选池，因此同一生物可以写多行来扩充候选。未命中返回空列表，
	 * 由调用方回退默认贴图。只按 id 精确匹配，不匹配标签。
	 * 本方法只在生成侧（服务端）被查一次；客户端拿到名字后不再二查本表（服务端优先的利弊见 getTextureList 说明）。
	 */
	public List<String> resolveTextureCandidates(String entityId) {
		if (entityId == null || textureList.isEmpty()) return List.of();
		List<String> candidates = new ArrayList<>();
		for (String entry : textureList) {
			int comma = entry.indexOf(',');
			if (comma < 0) continue;
			if (!entry.substring(0, comma).trim().equals(entityId)) continue;
			for (String name : entry.substring(comma + 1).split(",")) {
				String trimmed = name.trim();
				if (!trimmed.isEmpty()) candidates.add(trimmed);
			}
		}
		return candidates;
	}

	/**
	 * 按实体注册名（namespace:path）计算脚印贴图缩放倍率：对齐参考工程 getEntityScale——初始 1，
	 * 将每个命中条目的 float 连乘（格式非法的 float 跳过），不匹配标签。幼体/实体自身 scale 由调用方另乘。
	 */
	public float resolveSizeMultiplier(String entityId) {
		float scale = 1.0F;
		if (entityId == null) return scale;
		for (String entry : sizeList) {
			int comma = entry.indexOf(',');
			if (comma < 0) continue;
			if (!entry.substring(0, comma).trim().equals(entityId)) continue;
			try {
				scale *= Float.parseFloat(entry.substring(comma + 1).trim());
			} catch (NumberFormatException e) {
				// float 非法：跳过本条目
			}
		}
		return scale;
	}

	/**
	 * 在左右偏移表中按实体注册名（namespace:path）查找自定义幅度。
	 * 命中返回该条目 float（可能为正或负，调用方自行取绝对值再随机符号）；未命中/格式非法返回 null（由调用方回退全局默认）。
	 * 只按 id 精确匹配，不匹配标签。空表走快通道。
	 */
	public Float findSideOffset(String entityId) {
		return matchOffsetEntry(sideOffsetList, entityId);
	}

	/** 在前后偏移表中按实体注册名（namespace:path）查找自定义幅度；语义同 {@link #findSideOffset(String)}。 */
	public Float findForwardOffset(String entityId) {
		return matchOffsetEntry(forwardOffsetList, entityId);
	}

	/** 解析 "modid:mobid,float" 条目并与 entityId 精确比对：命中首个条目返回其 float，否则 null。 */
	private static Float matchOffsetEntry(List<String> list, String entityId) {
		if (list.isEmpty() || entityId == null) return null;
		for (String entry : list) {
			int comma = entry.indexOf(',');
			if (comma < 0) continue;
			if (!entry.substring(0, comma).trim().equals(entityId)) continue;
			try {
				return Float.parseFloat(entry.substring(comma + 1).trim());
			} catch (NumberFormatException e) {
				return null; // float 部分非法：视为未命中，回退默认
			}
		}
		return null;
	}

	/* - - - - - IO（Gson 序列化，参考 SuperFancyClouds SharedConfig，仅保留 load/save）- - - - - */

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	// 配置文件路径在类初始化时写死：{@code config/traceableprint.json}。
	private static final Path CONFIG_PATH = Platform.getConfigFolder().resolve(Common.MOD_ID + ".json");

	/**
	 * 从 {@code CONFIG_PATH} 载入并回填到当前实例，返回 {@code this} 以便链式初始化。
	 * 文件不存在时写入一份默认配置；读取/解析失败则保留当前值（默认）不影响运行。
	 * Gson 会新建一个 Config 反序列化（未出现的键保持默认值，天然向后兼容新增字段），再复制到本实例。
	 */
	public Config load() {
		if (Files.exists(CONFIG_PATH)) {
			try (BufferedReader reader = Files.newBufferedReader(CONFIG_PATH)) {
				Config loaded = GSON.fromJson(reader, Config.class);
				if (loaded != null) {
					this.copyFrom(loaded);
				}
			} catch (IOException | JsonParseException e) {
				Common.LOGGER.error("[TraceablePrint] Failed to read config file: {}, using current/default config", CONFIG_PATH, e);
			}
		} else {
			save();
		}
		return this;
	}

	/** 将当前实例序列化写入 {@code CONFIG_PATH}（目录缺失自动创建）。 */
	public void save() {
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			try (BufferedWriter writer = Files.newBufferedWriter(CONFIG_PATH)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			Common.LOGGER.error("[TraceablePrint] Failed to write config file: {}", CONFIG_PATH, e);
		}
	}

	/** 把已反序列化的 {@code src} 各字段值覆写进本实例（集合直接引用其新实例）。 */
	private void copyFrom(Config src) {
		this.footprintLifetimeTicks = src.footprintLifetimeTicks;
		this.minSpawnDistance = src.minSpawnDistance;
		this.spawnIntervalTicks = src.spawnIntervalTicks;
		this.highlightTicks = src.highlightTicks;
		this.footprintYOffset = src.footprintYOffset;
		this.footprintTextureSize = src.footprintTextureSize;
		this.hardnessGate = src.hardnessGate;
		this.notifyTraced = src.notifyTraced;
		this.printsForInvisible = src.printsForInvisible;
		this.entityListInverted = src.entityListInverted;
		this.enableMod = src.enableMod;
		this.applyBlocks = src.applyBlocks;
		this.entityList = src.entityList;
		this.sideOffsetList = src.sideOffsetList;
		this.forwardOffsetList = src.forwardOffsetList;
		this.sizeList = src.sizeList;
		this.blockHeightList = src.blockHeightList;
		this.textureList = src.textureList;
	}
}
