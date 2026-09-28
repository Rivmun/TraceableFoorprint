package com.rimo.example.mixin;

import com.rimo.example.Common;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Options.class)
public abstract class OptionsMixin {
	@Inject(method = "<clinit>", at = @At("TAIL"))
	private static void sfcr$exampleMixin(CallbackInfo ci) {
		Common.LOGGER.info("mixin injected!");
	}
}
