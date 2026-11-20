package khoa.tools.client.mixin.sodium;

import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.model.light.LightPipeline;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadViewMutable;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import khoa.tools.client.feature.lazychunk.LazyChunkVisualFeature;

/** Lazy Chunk Visual with Sodium: water / lava. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer")
public class SodiumFluidRendererMixin {
	@Unique
	private boolean khoa$lazy;

	@Inject(method = "updateQuad", at = @At("HEAD"))
	private void khoa$mark(ModelQuadViewMutable quad, LevelSlice level, BlockPos pos, LightPipeline lighter, Direction dir, ModelQuadFacing facing,
		float brightness, ColorProvider<FluidState> colorProvider, FluidState fluidState, CallbackInfo ci) {
		khoa$lazy = LazyChunkVisualFeature.isLazy(pos);
	}

	@ModifyArg(method = "updateQuad", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/api/util/ColorARGB;toABGR(I)I"), index = 0)
	private int khoa$tint(int argb) {
		return khoa$lazy ? ARGB.multiply(argb, LazyChunkVisualFeature.TINT) : argb;
	}
}
