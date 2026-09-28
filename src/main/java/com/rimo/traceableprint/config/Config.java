package com.rimo.traceableprint.config;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 模组可调参数（纯 Java，零 loader 依赖，双端共用）。
 *
 * 当前为静态持有：由 Common.CONFIG 暴露单例，其它模块经 Common.CONFIG.getXxx() 读取；
 * 后续接入配置文件/配置界面时只需替换读写端（load/save），字段与默认值不动。
 */
public class Config {
	// - - - - 默认值 - - - -
	public static final long DEFAULT_FOOTPRINT_LIFETIME_TICKS = 1200L; // 脚印存活时长（tick，1200 = 60 秒），超时自毁
	public static final double DEFAULT_MIN_SPAWN_DISTANCE = 5.0;        // 与上一脚印的最小间距（方块），过近则跳过生成
	public static final int DEFAULT_SPAWN_INTERVAL_TICKS = 40;         // 移动检测/生成尝试的固定间隔（tick）
	public static final int DEFAULT_HIGHLIGHT_TICKS = 200;             // 单次点击的高亮时长（tick，10 秒）
	public static final int DEFAULT_FOOTPRINT_Y_OFFSET = 1;            // 脚印抬高量（百分之一方块，避免与地面 z-fight）
	public static final float DEFAULT_HARDNESS_GATE = 3.0F;            // 硬度门槛：|defaultDestroyTime| < gate 才可生成
	public static final boolean DEFAULT_NOTIFY_TRACED = true;          // 被追踪提示开关：服务端在有人追到链尾时给父玩家发 action bar 提示

	private long footprintLifetimeTicks = DEFAULT_FOOTPRINT_LIFETIME_TICKS;
	private double minSpawnDistance = DEFAULT_MIN_SPAWN_DISTANCE;
	private int spawnIntervalTicks = DEFAULT_SPAWN_INTERVAL_TICKS;
	private int highlightTicks = DEFAULT_HIGHLIGHT_TICKS;
	private int footprintYOffset = DEFAULT_FOOTPRINT_Y_OFFSET;
	private float hardnessGate = DEFAULT_HARDNESS_GATE;
	private boolean notifyTraced = DEFAULT_NOTIFY_TRACED;

	// 生成白名单（方块ID "namespace:path"，或 "#namespace:tag" 标签）：命中即跳过硬度判定直接放行，优先级最高
	private final Set<String> applyBlocks = new HashSet<>();

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
	public int getFootprintYOffset() {
		return footprintYOffset;
	}
	public void setFootprintYOffset(int hundredths) {
		this.footprintYOffset = Math.max(0, hundredths);
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

	public void addApplyBlock(String idOrTag) {
		applyBlocks.add(idOrTag);
	}
	public void removeApplyBlock(String idOrTag) {
		applyBlocks.remove(idOrTag);
	}
	public Set<String> getApplyBlocks() {
		return Collections.unmodifiableSet(applyBlocks);
	}
}
