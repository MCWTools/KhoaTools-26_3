package khoa.tools.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.PlayerRenderState;

import khoa.tools.client.feature.totemtweaks.TotemTweaksFeature;

/** Totem Tweaks: the pop animation shows another item and/or is drawn smaller. */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {
	@Inject(method = "renderItemActivationAnimation", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/vertex/PoseStack;scale(FFF)V", shift = At.Shift.AFTER))
	private void khoa$totemSize(PlayerRenderState playerState, PoseStack poseStack, float partialTick, SubmitNodeCollector collector, CallbackInfo ci) {
		if (TotemTweaksFeature.popping && TotemTweaksFeature.enabled()) {
			float scale = TotemTweaksFeature.scale();
			poseStack.scale(scale, scale, scale);
		}
	}
}
