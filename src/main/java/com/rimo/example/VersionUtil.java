package com.rimo.example;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
//~ if < 1.21.11 'Identifier' -> 'ResourceLocation'
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

import static com.rimo.example.Common.MOD_ID;

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
}
