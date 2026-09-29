package com.rimo.traceableprint.entity;

import com.rimo.traceableprint.Common;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把配置（{@code textureList}）里的脚印贴图名解析成真正能用于渲染的 {@link Identifier}，并在资源包里验真。
 *
 * 名字→路径的组装规则（写在教程里给玩家/整合包作者）：
 * - 只写文件名（不含冒号）：视为本模组资源目录下的文件，得到 {@code traceableprint:textures/entity/<名字>.png}；
 *   名字里可以带子目录，如 {@code animals/paw} → {@code traceableprint:textures/entity/animals/paw.png}。
 * - 写完整 {@code namespace:path}：整合包作者想把贴图放在自己的命名空间下时用；
 *   path 不以 {@code textures/} 开头则补成 {@code textures/entity/}，扩展名 {@code .png} 可省略。
 * 两种写法都忽略大小写与首尾空白（原版标识符必须小写，这里直接统一转小写以容错）。
 *
 * 为什么要在客户端验真：贴图是否存在取决于「这个客户端」加载了哪些资源包，服务端无从得知；
 * 配置写错或作者忘了发资源包时，与其渲染出一块粉黑MissingNo.，不如退回默认 footprint.png 并留一条日志。
 *
 * 缓存策略（本类只在渲染线程被调用，用并发容器只是求个稳妥）：
 * - 命中的名字长期缓存：资源包在一次游戏内基本不变。
 * - 没命中的名字只记一次告警，并在 10 秒后重新探测：作者边改包边按 F3+T 重载时不必重启游戏就能看到效果。
 */
public final class FootprintTextures {
	/** 名字 → 已确认存在的贴图路径 */
	private static final Map<String, Identifier> RESOLVED = new ConcurrentHashMap<>();
	/** 名字 → 下次允许重新探测的时间戳（System.nanoTime）；只缓存“没找到”，找到后不会走这条 */
	private static final Map<String, Long> RETRY_AT = new ConcurrentHashMap<>();
	/** 已经告警过的信息，避免每 10 秒重复刷屏 */
	private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
	private static final long RETRY_INTERVAL_NANOS = 10_000_000_000L;

	/**
	 * 解析配置里的贴图名。{@code null}/空串/非法标识符/资源包里找不到时，一律返回默认脚印贴图
	 * {@link FootprintRenderTypes#TEXTURE}。
	 */
	public static Identifier resolve(String name) {
		if (name == null || name.isEmpty()) return FootprintRenderTypes.TEXTURE;

		Identifier cached = RESOLVED.get(name);
		if (cached != null) return cached;

		long now = System.nanoTime();
		Long retryAt = RETRY_AT.get(name);
		if (retryAt != null && now < retryAt) return FootprintRenderTypes.TEXTURE; // 刚探测过：安静走默认，不查资源也不刷日志

		Identifier id = build(name);
		if (id == null) {
			// 组装不出合法标识符（写了非法字符），与资源包无关，重试也没意义：永久否定，只提示一次
			warn("Invalid footprint texture name in config: " + name
					+ " (identifier allows only lowercase a-z, digits and / . _ -), falling back to the default texture");
			RETRY_AT.put(name, Long.MAX_VALUE);
			return FootprintRenderTypes.TEXTURE;
		}
		if (exists(id)) {
			RESOLVED.put(name, id);
			RETRY_AT.remove(name);
			return id;
		}
		warn("Footprint texture " + name + " not found in any loaded resource pack (expected " + id
				+ "), falling back to the default texture");
		RETRY_AT.put(name, now + RETRY_INTERVAL_NANOS);
		return FootprintRenderTypes.TEXTURE;
	}

	/** 名字 → 标识符：按上文规则补全命名空间、目录前缀与 .png 扩展名；非法写法返回 null。 */
	private static Identifier build(String name) {
		String cleaned = name.trim().toLowerCase(Locale.ROOT);
		if (cleaned.endsWith(".png")) cleaned = cleaned.substring(0, cleaned.length() - ".png".length());
		if (cleaned.isEmpty()) return null;

		int sep = cleaned.indexOf(':');
		if (sep < 0) {
			// 只写文件名：默认落在本模组的 assets 目录下，玩家往 traceableprint 的资源包里加图就能用
			return Identifier.tryParse(Common.MOD_ID + ":textures/entity/" + cleaned + ".png");
		}
		String namespace = cleaned.substring(0, sep);
		String path = cleaned.substring(sep + 1);
		if (path.isEmpty()) return null;
		if (!path.startsWith("textures/")) path = "textures/entity/" + path;
		if (!path.endsWith(".png")) path = path + ".png";
		return Identifier.tryParse(namespace + ":" + path);
	}

	/** 资源包里是否存在该文件（含玩家后加的资源包、内置包与开发期热重载的目录）。 */
	private static boolean exists(Identifier id) {
		return Minecraft.getInstance().getResourceManager().getResource(id).isPresent();
	}

	/** 同一条告警只打一次（按消息内容去重），防止每个未命中的名字每 10 秒刷一行日志。 */
	private static void warn(String message) {
		if (WARNED.add(message)) {
			Common.LOGGER.warn("[TraceablePrint] {}", message);
		}
	}

	private FootprintTextures() {
	}
}
