package khoa.tools.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;

import khoa.tools.client.feature.blocktext.BlockTextFeature;

/** Shift + right click on a block text opens its editor instead of using the block. */
@Mixin(Minecraft.class)
public abstract class BlockTextMixin {
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void khoatools$blockText(CallbackInfo ci) {
		if (BlockTextFeature.onUse((Minecraft) (Object) this)) {
			ci.cancel();
		}
	}
}
