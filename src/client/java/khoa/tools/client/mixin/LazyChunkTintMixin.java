package khoa.tools.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

import khoa.tools.client.feature.lazychunk.LazyChunkVisualFeature;

/** Lazy Chunk Visual: quads of blocks inside the lazy ring get the yellow tint multiplied in right before they are written to the chunk mesh. */
@Mixin(ModelBlockRenderer.class)
public class LazyChunkTintMixin {
	@WrapMethod(method = "tesselateBlock")
	private void khoa$lazyTint(BlockQuadOutput output, float x, float y, float z, BlockAndTintGetter level, BlockPos pos, BlockState state,
		BlockStateModel model, long seed, Operation<Void> original) {
		if (LazyChunkVisualFeature.isLazy(pos)) {
			BlockQuadOutput tinted = (qx, qy, qz, quad, instance) -> {
				instance.multiplyColor(LazyChunkVisualFeature.TINT);
				output.put(qx, qy, qz, quad, instance);
			};
			original.call(tinted, x, y, z, level, pos, state, model, seed);
		} else {
			original.call(output, x, y, z, level, pos, state, model, seed);
		}
	}
}
