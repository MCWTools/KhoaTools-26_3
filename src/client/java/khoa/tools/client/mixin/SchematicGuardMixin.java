package khoa.tools.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;

import khoa.tools.client.feature.schematicguard.SchematicGuardFeature;

/** Schematic Guard: a placement outside the Litematica layer is never sent. */
@Mixin(MultiPlayerGameMode.class)
public abstract class SchematicGuardMixin {
	@Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
	private void khoatools$schematicGuard(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		if (SchematicGuardFeature.blocks(Minecraft.getInstance(), player, hand, hit)) {
			cir.setReturnValue(InteractionResult.FAIL);
		}
	}
}
