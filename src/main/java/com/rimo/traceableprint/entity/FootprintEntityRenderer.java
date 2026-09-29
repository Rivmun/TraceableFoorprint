package com.rimo.traceableprint.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.rimo.traceableprint.Common;
import com.rimo.traceableprint.VersionUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 26.1 实体渲染器（RenderState 模式），采用与画（Painting）相同的直接四边形提交方式：
 * 对“贴图薄片”类实体，直接提交几何才是原版常规做法。
 * 碰撞箱(EntityType.sized)与贴图解耦；贴图沿实体 yaw 旋转、随存续时间从 0.02 下沉到 0.01。
 * 左右脚偏移已烘入实体真实坐标（见 LivingEntityMixin），贴图始终在实体原点居中。
 * 贴图本身可按生物替换：服务端生成时按 config.textureList 命中生物并随机选定一个贴图名同步下来，
 *   客户端逐帧经 FootprintTextures 解析成实际路径（见 state.texture），未定制/资源包缺图则用默认 footprint.png。
 *
 * 高亮：走自定义脉冲管线（见 FootprintRenderTypes，core/footprint_pulse 在 footprint 原色与纯白间随时间闪烁，
 *   关深度穿墙、不采样光照）。非高亮：走公共 RenderTypes.entityTranslucent，采样世界 lightmap
 *   实现天光昼夜变暗；顶点 UV2 把方块光锁 0 只留天光（规避火把暖光/红色 overlay 行导致的偏红）。
 *   非高亮选 translucent 而非 cutout：仅启用 alpha 混合才能把顶点 alpha 当不透明度用，实现存活末段渐淡。
 *
 * 瞄准判定框：准星指在本脚印上时，用与原版“选中方块”同一套线框样式（RenderTypes.lines() /
 *   secondaryBlockOutline()、同色同宽、同样受“高对比度方块外框”选项影响）画出自家 AABB。
 *   为何要自绘：26.1 已把实体的瞄准高亮框并入 F3+B 的 Gizmos 调试体系（EntityHitboxDebugRenderer），
 *   LevelRenderer.extractBlockOutline 只认 BlockHitResult，准星指实体时原版不再画任何框；脚印要的是
 *   “像方块那样常显”、不依赖调试按键，于是在自己的渲染器里复刻方块框的画法：线几何走
 *   submitCustomGeometry（与吊钩的钓线同一条通路），坐标用实体局部系且不施加本渲染器的 yaw 旋转
 *   （判定本就轴对齐）。零 mixin、不碰原版私有管线。
 */
public class FootprintEntityRenderer extends EntityRenderer<FootprintEntity, FootprintEntityRenderer.FootprintRenderState> {
	// 贴图水平尺寸（正方形边长）由配置 Common.CONFIG.getFootprintTextureSize() 驱动（默认 5/16，匹配原版像素大小）；
	// 渲染时取其一半作为局部半宽/半长，逐生物 texScale 再通过位堆栈缩放乘在此基准上。
	// 贴图抬高量：生成时 0.02，随存续时间线性下沉，销毁前落到 0.01（而非固定值 + 随机抖动）。
	// 下限 0.01 仍是为了避开与地面方块顶面共面的 z-fight；上限降到 0.02 则为了不再看起来悬浮在空中。
	private static final float RENDER_Y_AT_BIRTH = 0.02F;
	private static final float RENDER_Y_AT_DEATH = 0.01F;

	// - - - 瞄准判定框（复刻原版选中方块样式） - - -
	// 常规框：黑色 alpha=102，即原版 ARGB.black(102)
	private static final int OUTLINE_COLOR = ARGB.black(102);
	// 开启“高对比度方块外框”时的框色（原版 LevelRenderer 同值）
	private static final int OUTLINE_COLOR_HIGH_CONTRAST = -11010079;
	// 高对比度下的厚底衬：原版用 secondaryBlockOutline 画一层不透明纯黑粗线再画细线，使框在任何背景上都可辨
	private static final int OUTLINE_SECONDARY_COLOR = -16777216; // 不透明黑
	private static final float OUTLINE_SECONDARY_WIDTH = 7.0F;
	// 判定框微量外扩：底边否则与地面方块顶面共面，会闪（原版实体框同样留了这点余量）
	private static final double OUTLINE_INFLATE = 0.004;

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
		state.yawDeg = entity.getYRot();
		state.texScale = entity.getTexScale();
		// 脚印贴图：把服务端同步下来的贴图名解析成可用路径（未定制/资源包里没这个文件 → 默认 footprint.png）。
		// 两条渲染管线（天光半透明 / 高亮脉冲）都用这一个值，保证高亮前后贴图一致、不会一亮就跳回默认图。
		state.texture = FootprintTextures.resolve(entity.getTextureName());
		state.highlighted = entity.isHighlighted();
		// 全局同步的脉冲相位：用世界游戏时间（+插值）而非实体年龄，让所有高亮脚印同步闪烁
		state.gameTime = entity.level().getGameTime() + tickDelta;
		// 存续淡出：剩余时长不足总时长一半时线性变透明（min(1, 剩余/(总/2))）
		state.fadeAlpha = Math.clamp(entity.getFadeAlpha(), 0.0F, 1.0F);
		// 存续下沉：按整段生命线性插值（与 fadeAlpha 的“后半段才淡出”是两条独立曲线，但同源于存续进度）
		state.renderY = Mth.lerp(entity.getLifeProgress(), RENDER_Y_AT_BIRTH, RENDER_Y_AT_DEATH);
		// 瞄准判定框：与原版选中方块同源——直接读 Minecraft#hitResult（每客户端 tick 更新一次，
		// 故框随准星的滞后与原版方块框一致），命中实体为本脚印时才画
		state.aimed = Minecraft.getInstance().hitResult instanceof EntityHitResult hit && hit.getEntity() == entity;
		if (state.aimed) {
			// 世界系 AABB → 渲染局部系（减去插值位置），并微量外扩防底边与地面共面闪
			Vec3 pos = entity.getPosition(tickDelta);
			state.outlineBox = entity.getBoundingBox().inflate(OUTLINE_INFLATE).move(-pos.x, -pos.y, -pos.z);
		}
	}

	@Override
	public void submit(FootprintRenderState state, PoseStack poseStack,
			SubmitNodeCollector submitNodeCollector, CameraRenderState cameraRenderState) {
		// 判定框先画，且刻意在 pushPose/旋转之前：此时位堆栈正落在实体原点，AABB 是轴对齐的，
		// 不该跟着贴图一起绕 yaw 转（原版实体框 likewise 始终轴对齐）
		if (state.aimed && state.outlineBox != null) {
			AABB box = state.outlineBox;
			float lineWidth = VersionUtil.getBlockOutlineLineWidth();
			if (VersionUtil.isHighContrastBlockOutline()) {
				// 高对比度：先垫一层不透明纯黑粗线，再画细线（与原版两遍一致）
				submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.secondaryBlockOutline(),
						(pose, consumer) -> drawBoxEdges(pose, consumer, box, OUTLINE_SECONDARY_COLOR, OUTLINE_SECONDARY_WIDTH));
				submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.lines(),
						(pose, consumer) -> drawBoxEdges(pose, consumer, box, OUTLINE_COLOR_HIGH_CONTRAST, lineWidth));
			} else {
				submitNodeCollector.submitCustomGeometry(poseStack, RenderTypes.lines(),
						(pose, consumer) -> drawBoxEdges(pose, consumer, box, OUTLINE_COLOR, lineWidth));
			}
		}

		poseStack.pushPose();

		// 抬高并随存续下沉，既避免与地面重叠，又让脚印“陷进地里”而不是原地变透明
		poseStack.translate(0.0F, state.renderY, 0.0F);
		// 绕 Y 旋转对齐脚印朝向：用原版 EntityRenderDispatcher 同款约定 rotationDegrees(180 - yaw)。
		// 推导：绕 Y 转 θ 把局部 -Z 映到 (-sinθ, -cosθ)，而实体前进方向是 (-sin(yaw), cos(yaw))，
		// 只有 θ = 180 - yaw 两者才相等（直接用 yaw 会把 Z 分量镜像：贴图朝向与行进方向反）。
		// 对应关系：局部 -Z = 前进方向（贴图 v=0 那一侧）。
		poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - state.yawDeg));
		// 贴图沿实体 yaw 旋转后在局部 X 上关于原点对称（居中）：左右脚偏移已烘入实体坐标，此处不再平移。
		// 缩放仅作用于贴图四边形的局部 X/Z（水平面），抬高量 renderY 已在之前提交到矩阵、不受影响；实体碰撞箱/判定框不变。
		if (state.texScale > 0.0F) {
			poseStack.scale(state.texScale, 1.0F, state.texScale);
		}

		// 单个四边形：非高亮走原版实体半透明管线（采样 lightmap、应用天光、被方块遮挡、支持 alpha 混合）；
		// 高亮走自定义脉冲管线（关深度穿墙、原色↔纯白闪烁、不受光照）。
		// 光照：非高亮保留实体当前天光，把方块光通道锁 0（LightCoordsUtil.withBlock(...,0)）→ 白天亮、夜里暗、不偏红。
		// 顶点 alpha：非高亮=淡出系数×255（entityTranslucent 启用 SRC_ALPHA 混合，顶点 alpha 作为不透明度与背景叠加，
		// 实现“越接近自动销毁越透明”；cutout 无混合、此值无效，故必须走 translucent）；高亮=脉冲值（由 fsh 当插值因子）。
		int light = LightCoordsUtil.withBlock(state.lightCoords, 0);
		// 基准半尺寸：配置的正方形边长之半（默认 5/16 的一半）；texScale 已由位堆栈缩放乘上
		float half = Common.CONFIG.getFootprintTextureSize() * 0.5F;
		if (state.highlighted) {
			float pulse = (float) ((Math.sin(state.gameTime * 0.15) + 1.0) * 0.5); // 0..1 往复
			int vertexAlpha = (int) (pulse * 255.0F);
			drawFootprintQuad(poseStack, submitNodeCollector, FootprintRenderTypes.footprintSeeThrough(state.texture), light, vertexAlpha, half);
		} else {
			int fadeAlpha = (int) (state.fadeAlpha * 255.0F);
			drawFootprintQuad(poseStack, submitNodeCollector, FootprintRenderTypes.footprint(state.texture), light, fadeAlpha, half);
		}

		poseStack.popPose();
	}

	/**
	 * 画判定框的 12 条棱。顶点写法对齐原版 ShapeRenderer#renderShape（LINES 顶点格式）：
	 * addVertex(Pose, x, y, z) + setColor(ARGB) + setNormal(边方向) + setLineWidth。
	 * 法线取归一化的边方向（轴对齐盒子的每条边本就沿一个坐标轴）。
	 */
	private static void drawBoxEdges(PoseStack.Pose pose, VertexConsumer consumer, AABB box, int color, float lineWidth) {
		float minX = (float) box.minX, minY = (float) box.minY, minZ = (float) box.minZ;
		float maxX = (float) box.maxX, maxY = (float) box.maxY, maxZ = (float) box.maxZ;
		// 底面四边
		drawEdge(pose, consumer, color, lineWidth, minX, minY, minZ, maxX, minY, minZ);
		drawEdge(pose, consumer, color, lineWidth, maxX, minY, minZ, maxX, minY, maxZ);
		drawEdge(pose, consumer, color, lineWidth, maxX, minY, maxZ, minX, minY, maxZ);
		drawEdge(pose, consumer, color, lineWidth, minX, minY, maxZ, minX, minY, minZ);
		// 顶面四边
		drawEdge(pose, consumer, color, lineWidth, minX, maxY, minZ, maxX, maxY, minZ);
		drawEdge(pose, consumer, color, lineWidth, maxX, maxY, minZ, maxX, maxY, maxZ);
		drawEdge(pose, consumer, color, lineWidth, maxX, maxY, maxZ, minX, maxY, maxZ);
		drawEdge(pose, consumer, color, lineWidth, minX, maxY, maxZ, minX, maxY, minZ);
		// 四条竖棱
		drawEdge(pose, consumer, color, lineWidth, minX, minY, minZ, minX, maxY, minZ);
		drawEdge(pose, consumer, color, lineWidth, maxX, minY, minZ, maxX, maxY, minZ);
		drawEdge(pose, consumer, color, lineWidth, maxX, minY, maxZ, maxX, maxY, maxZ);
		drawEdge(pose, consumer, color, lineWidth, minX, minY, maxZ, minX, maxY, maxZ);
	}

	private static void drawEdge(PoseStack.Pose pose, VertexConsumer consumer, int color, float lineWidth,
			float x1, float y1, float z1, float x2, float y2, float z2) {
		float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len <= 0.0F) return;
		dx /= len; dy /= len; dz /= len;
		consumer.addVertex(pose, x1, y1, z1).setColor(color).setNormal(pose, dx, dy, dz).setLineWidth(lineWidth);
		consumer.addVertex(pose, x2, y2, z2).setColor(color).setNormal(pose, dx, dy, dz).setLineWidth(lineWidth);
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
			RenderType renderType, int light, int vertexAlpha, float half) {
		collector.submitCustomGeometry(poseStack, renderType, (pose, vertexConsumer) -> {
			var matrix = pose.pose();
			vertexConsumer.addVertex(matrix, -half, 0.0F, -half)
					.setColor(255, 255, 255, vertexAlpha).setUv(0.0F, 0.0F).setLight(light)
					.setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0.0F, 1.0F, 0.0F);
			vertexConsumer.addVertex(matrix, -half, 0.0F, half)
					.setColor(255, 255, 255, vertexAlpha).setUv(0.0F, 1.0F).setLight(light)
					.setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0.0F, 1.0F, 0.0F);
			vertexConsumer.addVertex(matrix, half, 0.0F, half)
					.setColor(255, 255, 255, vertexAlpha).setUv(1.0F, 1.0F).setLight(light)
					.setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0.0F, 1.0F, 0.0F);
			vertexConsumer.addVertex(matrix, half, 0.0F, -half)
					.setColor(255, 255, 255, vertexAlpha).setUv(1.0F, 0.0F).setLight(light)
					.setOverlay(OverlayTexture.NO_OVERLAY).setNormal(0.0F, 1.0F, 0.0F);
		});
	}

	public static class FootprintRenderState extends EntityRenderState {
		public float yawDeg;
		public float texScale;
		public float renderY;
		public Identifier texture = FootprintRenderTypes.TEXTURE;
		public boolean highlighted;
		public double gameTime;
		public float fadeAlpha;
		public boolean aimed;
		public AABB outlineBox;
	}
}
