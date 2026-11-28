package khoa.tools.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;

import khoa.tools.client.feature.lightlevel.LightLevelOverlayFeature;

/**
 * Light Level Overlay: the client light engine reports every section whose light it has just changed. Block updates and
 * light packets arrive earlier than that, so without this the overlay could keep the light of before.
 */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheLightMixin {
	@Inject(method = "onLightUpdate", at = @At("HEAD"))
	private void khoatools$lightChanged(LightLayer layer, SectionPos pos, CallbackInfo ci) {
		if (layer == LightLayer.BLOCK) {
			LightLevelOverlayFeature.markDirty();
		}
	}
}
