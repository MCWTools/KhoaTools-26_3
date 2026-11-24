package khoa.tools.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.state.BlockState;

import khoa.tools.remote.RemoteSwitch;

/** Remote Switch: a pressure plate it switched on stays pressed (the plate's own tick would pop it back up). */
@Mixin(BasePressurePlateBlock.class)
public abstract class BasePressurePlateBlockMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void khoa$heldByRemote(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
		if (RemoteSwitch.isHeldPlate(level, pos)) {
			ci.cancel();
			level.scheduleTick(pos, state.getBlock(), 20);
		}
	}
}
