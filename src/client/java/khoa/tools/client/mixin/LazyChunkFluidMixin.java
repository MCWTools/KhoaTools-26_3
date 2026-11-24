package khoa.tools.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import khoa.tools.client.feature.lazychunk.LazyChunkVisualFeature;

/** Lazy Chunk Visual for water / lava: fluids are meshed by their own renderer, so they get the same tint. */
@Mixin(FluidRenderer.class)
public class LazyChunkFluidMixin {
	/** Per mesh thread: the fluid being meshed right now is inside the lazy ring. */
	private static final ThreadLocal<Boolean> khoa$lazy = ThreadLocal.withInitial(() -> false);

	@WrapMethod(method = "tesselate")
	private void khoa$markLazy(BlockAndTintGetter level, BlockPos pos, FluidRenderer.Output output, BlockState blockState, FluidState fluidState,
		Operation<Void> original) {
		boolean previous = khoa$lazy.get();
		khoa$lazy.set(LazyChunkVisualFeature.isLazy(pos));
		try {
			original.call(level, pos, output, blockState, fluidState);
		} finally {
			khoa$lazy.set(previous);
		}
	}

	@ModifyVariable(method = "vertex", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private int khoa$tintVertex(int color) {
		return khoa$lazy.get() ? ARGB.multiply(color, LazyChunkVisualFeature.TINT) : color;
	}
}
