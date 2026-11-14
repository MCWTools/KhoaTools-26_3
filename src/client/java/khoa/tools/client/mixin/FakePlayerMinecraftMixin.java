package khoa.tools.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;

import khoa.tools.client.feature.fakeplayer.FakePlayerManager;

/** Right clicking with a fake player's teleport item moves that fake player; the click never reaches the server. */
@Mixin(Minecraft.class)
public abstract class FakePlayerMinecraftMixin {
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void khoatools$fakePlayerWand(CallbackInfo ci) {
		if (FakePlayerManager.handleUse((Minecraft) (Object) this)) ci.cancel();
	}

	/** Left click on a fake player that has physics on: client-side hit (damage, knockback), nothing is sent to the server. */
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void khoatools$fakePlayerAttack(CallbackInfoReturnable<Boolean> cir) {
		if (FakePlayerManager.handleAttack((Minecraft) (Object) this)) cir.setReturnValue(true);
	}
}
