package com.rimo.traceableprint.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;

/**
 * 26.1 实体渲染器（RenderState 模式），采用与画（Painting）相同的直接四边形提交方式：
 * 对“贴图薄片”类实体，直接提交几何才是原版常规做法。
 * 碰撞箱(EntityType.sized)与贴图解耦；贴图沿实体 yaw 旋转、左右脚偏移、UUID 派生高度抖动。
 *
 * 高亮：走自定义脉冲管线（见 FootprintRenderTypes，core/footprint_pulse 在 footprint 原色与纯白间随时间闪烁，
 *   关深度穿墙、不采样光照）。非高亮：走公共 RenderTypes.entityTranslucent，采样世界 lightmap
 *   实现天光昼夜变暗；顶点 UV2 把方块光锁 0 只留天光（规避火把暖光/红色 overlay 行导致的偏红）。
 *   非高亮选 translucent 而非 cutout：仅启用 alpha 混合才能把顶点 alpha 当不透明度用，实现存活末段渐淡。
 */
public class FootprintEntityRenderer extends EntityRenderer<FootprintEntity, FootprintEntityRenderer.FootprintRenderState> {
	// 贴图水平尺寸（局部：x=左右，z=前后/朝
	private static final float HALF_WIDTH = 0.15F;
	private static final float HALF_LENGTH = 0.15F;
	// 贴图基础抬高（再叠加 UUID 派生抖动，错开多脚印与地面的 z-fight）
	private static final float RENDER_BASE_Y = 0.01F;

	public FootprintEntityRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.0F;
		this.shadowStrength = 0.0F;
	}

	@Override
	public FootprintRenderState createRenderState() {
		return new FootprintRenderState();
	}

	@Override
	public void extractRenderState(FootprintEntity entity, FootprintRenderState state, float tickDelta) {
		super.extractRenderState(entity, state, tickDelta);
		state.visualOffsetX = entity.getVisualOffsetX();
		state.yawDeg = entity.getYRot();
		state.highlighted = entity.isHighlighted();
		// 全局同步的脉冲相位：用世界游戏时间（+插值）而非实体年龄，让所有高亮脚印同步闪烁
		state.gameTime = entity.level().getGameTime() + tickDelta;
		// 存续淡出：剩余时长不足总时长一半时线性变透明（min(1, 剩余/(总/2))）
		state.fadeAlpha = Math.max(0.0F, Math.min(1.0F, entity.getFadeAlpha()));
		// 从脚印自身 UUID 派生稳定高度抖动（0~0.02），避免多脚印层叠 z-fight
		state.renderYOffset = ((entity.getUUID().getLeastSignificantBits() & 0xFF) / 255.0F) * 0.02F;
	}

	@Override
	public void submit(FootprintRenderState state, PoseStack poseStack,
			SubmitNodeCollector submitNodeCollector, CameraRenderState cameraRenderState) {
		poseStack.pushPose();

		// 抬高避免与地面重叠
		poseStack.translate(0.0F, RENDER_BASE_Y + state.renderYOffset, 0.0F);
		// 绕 Y 旋转对齐脚印朝向（移动方向）
		poseStack.mulPose(Axis.YP.rotationDegrees(state.yawDeg));
		// 沿实体“右侧”做视觉偏移（碰撞箱保持在移动轨迹中线上）
		poseStack.translate(state.visualOffsetX, 0.0F, 0.0F);

		// 单个四边形：非高亮走原版实体半透明管线（采样 lightmap、应用天光、被方块遮挡、支持 alpha 混合）；
		// 高亮走自定义脉冲管线（关深度穿墙、原色↔纯白闪烁、不受光照）。
		// 光照：非高亮保留实体当前天光，把方块光通道锁 0（LightCoordsUtil.withBlock(...,0)）→ 白天亮、夜里暗、不偏红。
		// 顶点 alpha：非高亮=淡出系数×255（entityTranslucent 启用 SRC_ALPHA 混合，顶点 alpha 作为不透明度与背景叠加，
		// 实现“越接近自动销毁越透明”；cutout 无混合、此值无效，故必须走 translucent）；高亮=脉冲值（由 fsh 当插值因子）。
		int light = LightCoordsUtil.withBlock(state.lightCoords, 0);
		if (state.highlighted) {
			float pulse = (float) ((Math.sin(state.gameTime * 0.15) + 1.0) * 0.5); // 0..1 往复
			int vertexAlpha = (int) (pulse * 255.0F);
			drawFootprintQuad(poseStack, submitNodeCollector, FootprintRenderTypes.footprintSeeThrough(), light, vertexAlpha);
		} else {
			int fadeAlpha = (int) (state.fadeAlpha * 255.0F);
			drawFootprintQuad(poseStack, submitNodeCollector, FootprintRenderTypes.footprint(), light, fadeAlpha);
		}

		poseStack.popPose();
	}

	/**
	 * 提交一张脚印四边形。
	 * 非高亮的 entityTranslucent 顶点格式含 UV1(overlay)/UV2(light)/Normal，必须显式给出：
	 * - setLight(light)：UV2 = 仅天光（block 锁 0），供 lightmap 采样得到昼夜亮度；
	 * - setOverlay(OverlayTexture.NO_OVERLAY)：走透明 overlay 行，避免 (0,0) 红色行导致偏红（历史坑）；
	 * - setNormal(0,1,0)：地面向上法线，供实体方向光计算。
	 * 高亮的脉冲管线格式(POSITION_COLOR_TEX_LIGHTMAP)不含 UV1/Normal，对应 setter 会被安全忽略；
	 * 其顶点色 alpha(vertexAlpha) 由 fsh 用作原色↔纯白的插值因子。
	 */
	private static void drawFootprintQuad(PoseStack poseStack, SubmitNodeCollector collector,
			RenderType renderType, int light, int vertexAlpha) {
		collector.submitCustomGeometry(poseStack, renderType, (pose, vertexConsumer) -> {
			var matrix = pose.pose();
			vertexConsumer.addVertex(matrix, -HALF_WIDTH, 0.0F, -HALF_LENGTH)
					.setColor(255, 255, 255, vertexAlpha).setUv(0.0F, 0.0F).setLight(light)
					.setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0.0F, 1.0F, 0.0F);
			vertexConsumer.addVertex(matrix, -HALF_WIDTH, 0.0F, HALF_LENGTH)
					.setColor(255, 255, 255, vertexAlpha).setUv(0.0F, 1.0F).setLight(light)
					.setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0.0F, 1.0F, 0.0F);
			vertexConsumer.addVertex(matrix, HALF_WIDTH, 0.0F, HALF_LENGTH)
					.setColor(255, 255, 255, vertexAlpha).setUv(1.0F, 1.0F).setLight(light)
					.setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0.0F, 1.0F, 0.0F);
			vertexConsumer.addVertex(matrix, HALF_WIDTH, 0.0F, -HALF_LENGTH)
					.setColor(255, 255, 255, vertexAlpha).setUv(1.0F, 0.0F).setLight(light)
					.setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0.0F, 1.0F, 0.0F);
		});
	}

	public static class FootprintRenderState extends EntityRenderState {
		public float visualOffsetX;
		public float yawDeg;
		public float renderYOffset;
		public boolean highlighted;
		public double gameTime;
		public float fadeAlpha;
	}
}
