package khoa.tools.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;

import khoa.tools.client.feature.fakeplayer.FakePlayerEntity;

/** Fake players keep the exact walking / swinging frame they were saved with (no limb reset, no idle sway). */
@Mixin(AvatarRenderer.class)
public class FakePlayerAvatarRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
		at = @At("TAIL"))
	private void khoatools$freezeFakePlayer(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
		if (avatar instanceof FakePlayerEntity f && !f.physics()) {
			state.walkAnimationPos = f.frozenWalkPos;
			state.walkAnimationSpeed = f.frozenWalkSpeed;
			state.wornHeadAnimationPos = f.frozenWalkPos;
			state.swingAnimation = f.frozenSwing;
			state.ageInTicks = f.frozenAge;
		}
	}
}
