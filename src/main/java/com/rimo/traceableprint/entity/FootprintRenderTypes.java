package com.rimo.traceableprint.entity;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.rimo.traceableprint.Common;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * 脚印的两个渲染类型。
 *
 * 非高亮：用公共 {@link RenderTypes#entityTranslucent(Identifier)} —— 同样走会采样世界 lightmap 的 entity 管线，
 *   配合顶点 UV2 把方块光通道锁 0（见 FootprintEntityRenderer），得到“白天亮、夜里随天光变暗、且不受火把暖光(不偏红)”
 *   的效果。**必须用 translucent 而非 cutout**：cutout 只开深度测试不开 alpha 混合，顶点 alpha 再低也会写回
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
	/** 高亮用穿墙渲染类型：原色↔纯白脉冲闪烁 + 关闭深度测试，懒注册。 */
	private static RenderType footprintSeeThrough;

	static final Identifier TEXTURE =
			Identifier.fromNamespaceAndPath(Common.MOD_ID, "textures/entity/footprint.png");
	// 自定义着色器（解析到 assets/traceableprint/shaders/core/footprint_pulse.vsh/.fsh）
	private static final Identifier PULSE_SHADER =
			Identifier.fromNamespaceAndPath(Common.MOD_ID, "core/footprint_pulse");

	/** 非高亮：应用天光、被方块正常遮挡、且支持顶点 alpha 渐淡的原版实体半透明渲染类型。 */
	public static RenderType footprint() {
		return RenderTypes.entityTranslucent(TEXTURE);
	}

	/** 高亮：穿墙原色↔纯白脉冲闪烁，懒注册。 */
	public static synchronized RenderType footprintSeeThrough() {
		if (footprintSeeThrough == null) {
			footprintSeeThrough = RenderType.create("traceableprint:footprint_pulse_see_through", pulseSetup());
		}
		return footprintSeeThrough;
	}

	/**
	 * 复制 TEXT_SEE_THROUGH 的 uniform/顶点格式/混合/多边形状态，替换成自定义脉冲着色器，并关闭深度测试（穿墙）。
	 */
	private static RenderSetup pulseSetup() {
		RenderPipeline base = RenderPipelines.TEXT_SEE_THROUGH;
		var builder = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath(Common.MOD_ID, "pipeline/footprint_pulse_see_through"))
				.withVertexShader(PULSE_SHADER)
				.withFragmentShader(PULSE_SHADER)
				.withSampler("Sampler0")
				.withCull(false);
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
		if (base.getPolygonMode() != null) {
			builder.withPolygonMode(base.getPolygonMode());
		}
		builder.withDepthStencilState(Optional.empty()); // 关闭深度测试 → 穿墙
		return RenderSetup.builder(builder.build())
				.withTexture("Sampler0", TEXTURE)
				.createRenderSetup();
	}

	private FootprintRenderTypes() {
	}
}
