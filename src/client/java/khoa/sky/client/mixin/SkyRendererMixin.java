package khoa.sky.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import khoa.sky.client.SkyConfig;
import khoa.sky.client.SkyManager;
import khoa.sky.client.SkyResource;
import khoa.sky.client.SkySlot;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * Swaps textures / vertex buffers inside vanilla's own sky methods.
 * We never create a pipeline or touch a shader, so shader packs keep seeing the normal
 * END_SKY / CELESTIAL draw calls and keep working.
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
	/** Non-null only while we re-use renderEndSky() to draw the overworld skybox. */
	@Unique
	private static SkyResource skychanger$overworldCube;

	/** Non-null only while vanilla draws the sun / moon through drawCelestialBody(). */
	@Unique
	private static SkyResource skychanger$celestial;

	@Shadow
	protected abstract void renderEndSky(RenderPass pass);

	// ------------------------------------------------------------------ skybox

	// 26.3: SkyRenderer.render() opens the render pass itself, so textures must be loaded before it.
	@Inject(method = "render", at = @At("HEAD"))
	private void skychanger$prepare(GpuBufferSlice fog, SkyRenderState state, CallbackInfo ci) {
		if (state.skybox == DimensionType.Skybox.END) {
			SkyManager.prepare(SkySlot.END_SKYBOX);
		} else {
			SkyManager.prepare(SkySlot.OVERWORLD_SKYBOX);
			SkyManager.prepare(SkySlot.OVERWORLD_SUN);
			SkyManager.prepare(SkySlot.OVERWORLD_MOON);
		}
	}

	/** Overworld: right after the sky-coloured dome, draw our cube through vanilla's end-sky path. */
	@Inject(method = "renderSkyDisc", at = @At("TAIL"))
	private void skychanger$drawOverworldSkybox(RenderPass pass, org.joml.Vector3fc skyColor, CallbackInfo ci) {
		SkyResource cube = SkyManager.peek(SkySlot.OVERWORLD_SKYBOX);
		if (cube == null) {
			return;
		}
		skychanger$overworldCube = cube;
		try {
			this.renderEndSky(pass);
		} finally {
			skychanger$overworldCube = null;
		}
	}

	@Unique
	private static SkyResource skychanger$currentCube() {
		// While the override is set we are drawing the overworld; otherwise renderEndSky() was called for The End.
		return skychanger$overworldCube != null ? skychanger$overworldCube : SkyManager.peek(SkySlot.END_SKYBOX);
	}

	@WrapOperation(
		method = "renderEndSky",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/AbstractTexture;getTextureView()Lcom/mojang/renderpearl/api/textures/GpuTextureView;")
	)
	private GpuTextureView skychanger$endSkyView(AbstractTexture texture, Operation<GpuTextureView> original) {
		SkyResource cube = skychanger$currentCube();
		return cube != null ? cube.texture().getTextureView() : original.call(texture);
	}

	@WrapOperation(
		method = "renderEndSky",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/AbstractTexture;getSampler()Lcom/mojang/renderpearl/api/textures/GpuSampler;")
	)
	private GpuSampler skychanger$endSkySampler(AbstractTexture texture, Operation<GpuSampler> original) {
		SkyResource cube = skychanger$currentCube();
		return cube != null ? cube.texture().getSampler() : original.call(texture);
	}

	// 180 = Opcodes.GETFIELD
	@WrapOperation(
		method = "renderEndSky",
		at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/SkyRenderer;endSkyBuffer:Lcom/mojang/renderpearl/api/buffers/GpuBuffer;", opcode = 180)
	)
	private GpuBuffer skychanger$endSkyBuffer(SkyRenderer self, Operation<GpuBuffer> original) {
		SkyResource cube = skychanger$currentCube();
		return cube != null ? cube.buffer() : original.call(self);
	}

	// --------------------------------------------------------------------- sun

	private static final String DRAW_CELESTIAL = "Lnet/minecraft/client/renderer/SkyRenderer;drawCelestialBody(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;I)V";

	@WrapOperation(method = "renderSun", at = @At(value = "INVOKE", target = DRAW_CELESTIAL))
	private void skychanger$drawSun(SkyRenderer self, Supplier<String> label, RenderPass pass, GpuBufferSlice transform, GpuBuffer vertices, GpuBuffer indices, int count, Operation<Void> original) {
		skychanger$celestial = SkyManager.peek(SkySlot.OVERWORLD_SUN);
		try {
			original.call(self, label, pass, transform, vertices, indices, count);
		} finally {
			skychanger$celestial = null;
		}
	}

	@WrapOperation(method = "renderMoon", at = @At(value = "INVOKE", target = DRAW_CELESTIAL))
	private void skychanger$drawMoon(SkyRenderer self, Supplier<String> label, RenderPass pass, GpuBufferSlice transform, GpuBuffer vertices, GpuBuffer indices, int count, Operation<Void> original) {
		skychanger$celestial = SkyManager.peek(SkySlot.OVERWORLD_MOON);
		try {
			original.call(self, label, pass, transform, vertices, indices, count);
		} finally {
			skychanger$celestial = null;
		}
	}

	@WrapOperation(
		method = "drawCelestialBody",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureAtlas;getTextureView()Lcom/mojang/renderpearl/api/textures/GpuTextureView;")
	)
	private GpuTextureView skychanger$celestialView(TextureAtlas atlas, Operation<GpuTextureView> original) {
		SkyResource r = skychanger$celestial;
		return r != null ? r.texture().getTextureView() : original.call(atlas);
	}

	@WrapOperation(
		method = "drawCelestialBody",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureAtlas;getSampler()Lcom/mojang/renderpearl/api/textures/GpuSampler;")
	)
	private GpuSampler skychanger$celestialSampler(TextureAtlas atlas, Operation<GpuSampler> original) {
		SkyResource r = skychanger$celestial;
		return r != null ? r.texture().getSampler() : original.call(atlas);
	}

	@WrapOperation(
		method = "renderSun",
		at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/SkyRenderer;sunBuffer:Lcom/mojang/renderpearl/api/buffers/GpuBuffer;", opcode = 180)
	)
	private GpuBuffer skychanger$sunBuffer(SkyRenderer self, Operation<GpuBuffer> original) {
		SkyResource sun = SkyManager.peek(SkySlot.OVERWORLD_SUN);
		return sun != null ? sun.buffer() : original.call(self);
	}

	// -------------------------------------------------------------------- moon

	@WrapOperation(
		method = "renderMoon",
		at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/SkyRenderer;moonBuffer:Lcom/mojang/renderpearl/api/buffers/GpuBuffer;", opcode = 180)
	)
	private GpuBuffer skychanger$moonBuffer(SkyRenderer self, Operation<GpuBuffer> original) {
		SkyResource moon = SkyManager.peek(SkySlot.OVERWORLD_MOON);
		return moon != null ? moon.buffer() : original.call(self);
	}

	// ------------------------------------------------- optional overworld toggles

	@Inject(method = "renderStars", at = @At("HEAD"), cancellable = true)
	private void skychanger$toggleStars(RenderPass pass, float brightness, PoseStack poseStack, CallbackInfo ci) {
		if (!SkyConfig.overworldStars()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderSunriseAndSunset", at = @At("HEAD"), cancellable = true)
	private void skychanger$toggleSunriseGlow(RenderPass pass, PoseStack poseStack, float sunAngle, org.joml.Vector4fc color, CallbackInfo ci) {
		if (!SkyConfig.overworldSunriseGlow()) {
			ci.cancel();
		}
	}
}
