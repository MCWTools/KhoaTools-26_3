package khoa.tools.client.mixin.sodium;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import khoa.tools.client.feature.lazychunk.LazyChunkVisualFeature;

/** Lazy Chunk Visual with Sodium: Sodium meshes blocks itself, so the vanilla ModelBlockRenderer is never used. Tints the vertex colours it writes. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer")
public class SodiumBlockRendererMixin {
	@Unique
	private boolean khoa$lazy;

	@Inject(method = "renderModel", at = @At("HEAD"))
	private void khoa$mark(BlockStateModel model, BlockState state, BlockPos pos, BlockPos modelOffset, CallbackInfo ci) {
		khoa$lazy = LazyChunkVisualFeature.isLazy(pos);
	}

	@ModifyArg(method = "bufferQuad", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/api/util/ColorARGB;toABGR(I)I"), index = 0)
	private int khoa$tint(int argb) {
		return khoa$lazy ? ARGB.multiply(argb, LazyChunkVisualFeature.TINT) : argb;
	}
}
