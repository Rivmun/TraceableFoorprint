package com.rimo.traceableprint.entity;

//~ if < 26.3 'renderpearl.api.' -> 'blaze3d.'
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.rimo.traceableprint.Common;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 脚印的两个渲染类型。
 *
 * 非高亮：用公共 {@link RenderTypes#entityTranslucent(Identifier)} —— 同样走会采样世界 lightmap 的 entity 管线，
 *   顶点 UV2 取实体处完整光照（见 FootprintEntityRenderer），得到“白天亮、夜里随天光变暗、火把旁被环境光暖照”
 *   的效果（发红元凶是 overlay 红色行，另由 NO_OVERLAY 规避，与方块光无关）。**必须用 translucent 而非 cutout**：cutout 只开深度测试不开 alpha 混合，顶点 alpha 再低也会写回
 *   完全不透明的像素（只能靠 discard 硬切边），故“按存活时间渐淡”在 cutout 上天然无效；translucent 启用
 *   SRC_ALPHA 混合，顶点 alpha 才真正与背景叠加。零自定义代码。
 *
 * 高亮：自定义 RenderType，用自定义着色器对 core/footprint_pulse（在 footprint 原色与纯白之间
 *   随时间脉冲闪烁），关深度测试 → 穿墙；不采样 lightmap → 不受世界光照（高亮本就无视光照）。
 *   仍靠 accesswidener 开放的包私有静态工厂 RenderType.create 懒注册（traceableprint(.unobf).accesswidener）。
 *   管线状态从公开的 RenderPipelines.TEXT_SEE_THROUGH 经公共 getter 逐项复制，只是把着色器换成我们的自定义脉冲对。
 *   （glowing 的描边环来自 entity_outline 后处理 box-blur，CustomFeatureRenderer 不向 OutlineBufferSource 提交自定义几何，
 *   故无法让 footprint 进入 glowing 描边 pass，退而用脉冲闪烁。）
 */
public final class FootprintRenderTypes {
	/** 高亮用穿墙渲染类型按贴图缓存：脉冲管线的贴图写死在 RenderSetup 里，每张贴图必须有自己的实例。 */
	private static final Map<Identifier, RenderType> SEE_THROUGH_CACHE = new HashMap<>();

	/** 默认脚印贴图：配置未命中该生物、或指定的贴图在资源包里不存在时使用。 */
	static final Identifier TEXTURE =
			Identifier.fromNamespaceAndPath(Common.MOD_ID, "textures/entity/footprint.png");
	// 自定义着色器（解析到 assets/traceableprint/shaders/core/footprint_pulse.vsh/.fsh）
	private static final Identifier PULSE_SHADER =
			Identifier.fromNamespaceAndPath(Common.MOD_ID, "core/footprint_pulse");

	/**
	 * 非高亮：应用天光、被方块正常遮挡、且支持顶点 alpha 渐淡的原版实体半透明渲染类型。
	 * 按贴图取任意 {@code textures/...} 路径即可：{@link RenderTypes#entityTranslucent(Identifier)} 走的是
	 * 按名字直接绑定纹理对象的通路（不进方块/实体图集），因此整合包新增的贴图无需注册图集；
	 * 该方法内部按 (贴图, 是否开背面剔除) 做了 memoize，逐帧重复取同一贴图不会重复分配渲染类型。
	 */
	public static RenderType footprint(Identifier texture) {
		return RenderTypes.entityTranslucent(texture);
	}

	/**
	 * 高亮：穿墙原色↔纯白脉冲闪烁，懒注册并按贴图缓存（同一张贴图全游戏只建一次）。
	 * 每张贴图的管线 location 带上贴图路径以免多个同定义不同贴图的管线撞名。
	 */
	public static synchronized RenderType footprintSeeThrough(Identifier texture) {
		return SEE_THROUGH_CACHE.computeIfAbsent(texture, FootprintRenderTypes::createSeeThrough);
	}

	/**
	 * 复制 TEXT_SEE_THROUGH 的 uniform/顶点格式/混合/多边形状态，替换成自定义脉冲着色器，并关闭深度测试（穿墙）。
	 * 经 accesswidener 开放的包私有静态工厂 {@code RenderType.create} 建立（见 traceableprint(.unobf).accesswidener）。
	 */
	private static RenderType createSeeThrough(Identifier texture) {
		RenderPipeline base = RenderPipelines.TEXT_SEE_THROUGH;
		var builder = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath(Common.MOD_ID,
						"pipeline/footprint_pulse_see_through/" + texture.getPath()))
				.withVertexShader(PULSE_SHADER)
				.withFragmentShader(PULSE_SHADER)
				.withCull(false);
		//? if <= 26.1 {
		/*builder.withSampler("Sampler0");
		for (RenderPipeline.UniformDescription uniform : base.getUniforms()) {
			if (uniform.textureFormat() != null) {
				builder.withUniform(uniform.name(), uniform.type(), uniform.textureFormat());
			} else {
				builder.withUniform(uniform.name(), uniform.type());
			}
		}
		builder.withVertexFormat(base.getVertexFormat(), base.getVertexFormatMode());
		if (base.getColorTargetState() != null) {
			builder.withColorTargetState(base.getColorTargetState());
		}
		*///? } else if <= 26.2 {
		/*for (com.mojang.blaze3d.pipeline.BindGroupLayout bgl : base.getBindGroupLayouts()) {
			builder.withBindGroupLayout(bgl);
		}
		var vertexFormats = base.getVertexFormatBindings();
		for (int i = 0; i < vertexFormats.length; i++) {
			builder.withVertexBinding(i, vertexFormats[i]);
		}
		builder.withPrimitiveTopology(base.getPrimitiveTopology());
		if (base.getColorTargetState() != null) {
			builder.withColorTargetState(base.getColorTargetState());
		}
		*///? } else {
		// 26.3：渲染管线迁移到 RenderPearly（com.mojang.renderpearl.api.pipeline）；顶点格式/颜色目标 getter 由数组/单数改为 List
		for (com.mojang.renderpearl.api.pipeline.BindGroupLayout bgl : base.getBindGroupLayouts()) {
			builder.withBindGroupLayout(bgl);
		}
		var vertexFormats = base.getVertexFormatBindings();
		for (int i = 0; i < vertexFormats.size(); i++) {
			builder.withVertexBinding(i, vertexFormats.get(i));
		}
		builder.withPrimitiveTopology(base.getPrimitiveTopology());
		var colorTargets = base.getColorTargetStates();
		for (int i = 0; i < colorTargets.size(); i++) {
			builder.withColorTargetState(i, colorTargets.get(i));
		}
		//? }
		if (base.getPolygonMode() != null) {
			builder.withPolygonMode(base.getPolygonMode());
		}
		builder.withDepthStencilState(Optional.empty()); // 关闭深度测试 → 穿墙
		RenderSetup setup = RenderSetup.builder(builder.build())
				.withTexture("Sampler0", texture)
				.createRenderSetup();
		return RenderType.create("traceableprint:footprint_pulse_see_through/" + texture.getPath(), setup);
	}

	private FootprintRenderTypes() {
	}
}
