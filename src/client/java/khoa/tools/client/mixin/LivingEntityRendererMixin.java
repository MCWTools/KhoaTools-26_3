package khoa.tools.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

import khoa.tools.client.feature.farlod.FarLodFeature;

/** Far Mob LOD: far mobs skip every render layer (armor, glint, held item...). */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@Inject(method = "shouldRenderLayers", at = @At("HEAD"), cancellable = true)
	private void khoa$farLod(LivingEntityRenderState state, CallbackInfoReturnable<Boolean> cir) {
		if (FarLodFeature.isFar(state.distanceToCameraSq, state.boundingBoxWidth, state.boundingBoxHeight)) {
			cir.setReturnValue(false);
		}
	}
}
