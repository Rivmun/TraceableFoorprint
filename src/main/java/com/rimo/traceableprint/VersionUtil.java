package com.rimo.traceableprint;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
//~ if < 1.21.11 'Identifier' -> 'ResourceLocation'
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import static com.rimo.traceableprint.Common.MOD_ID;

// use stonecutter to split some frequent use function that different in each version.
public class VersionUtil {
	//~ if < 1.21.11 'Identifier' -> 'ResourceLocation' {
	public static Identifier getId(String path) {
		//? if <= 1.20.1 {
		/*return new Identifier(MOD_ID, path);
		 *///? } else {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
		//? }
	}
	//~ }

	public static float getLastFrameDuration() {
		//? if <= 1.20.1 {
		/*return Minecraft.getInstance().getDeltaFrameTime();
		*///? } else if < 1.21.11 {
		/*return Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
		*///? } else {
		return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
		//?  }
	}

	public static void sendSystemMessage(CommandContext<CommandSourceStack> c, String message) {
		//? if < 1.19 {
		/*c.getSource().sendSuccess(Component.nullToEmpty(message), false);
		 *///? } else {
		c.getSource().sendSystemMessage(Component.nullToEmpty(message));
		//? }
	}

	public static void sendMessage(Player player, String message) {
		//? if <= 1.21.11 {
		/*player.displayClientMessage(Component.nullToEmpty(message), false);
		*///? } else {
		player.sendSystemMessage(Component.nullToEmpty(message));
		//? }
	}

	/**
	 * 向指定玩家发一条 action bar（快捷栏上方）消息。
	 * 旧版（<= 1.21.11）走 Player#displayClientMessage(component, true)；
	 * 新版已拆分为 ServerPlayer#sendOverlayMessage(component)。
	 */
	public static void sendActionBar(ServerPlayer player, Component message) {
		//? if <= 1.21.11 {
		/*player.displayClientMessage(message, true);
		*///? } else {
		player.sendOverlayMessage(message);
		//? }
	}

	/**
	 * 原版“准星选中方块”线框的线宽：跟随窗口缩放，高分辨率下不会细到看不见。
	 * 新版取自 WindowRenderState#appropriateLineWidth（每帧由窗口缩放算出）；
	 * 旧版（1.20.1）原版用的是固定线宽 0.0075。
	 */
	public static float getBlockOutlineLineWidth() {
		//? if <= 1.20.1 {
		/*return 0.0075F;
		*///? } else {
		//~ if < 26.2 'gameRenderState' -> 'getGameRenderState'
		return Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState.appropriateLineWidth;
		//? }
	}

	/**
	 * 玩家是否开启“高对比度方块外框”无障碍选项（与原版选中框行为对齐）。
	 * 旧版（1.20.1）尚无此选项，按未开启处理。
	 */
	public static boolean isHighContrastBlockOutline() {
		//? if <= 1.20.1 {
		/*return false;
		*///? } else {
		return Minecraft.getInstance().options.highContrastBlockOutline().get();
		//? }
	}
}
