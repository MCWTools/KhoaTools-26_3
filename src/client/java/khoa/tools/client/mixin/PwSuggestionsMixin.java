package khoa.tools.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;

import khoa.tools.client.feature.pw.PwScanFeature;

/** Hands the suggestions the server sends back to the PW scanner (the vanilla chat ignores ids it did not ask for). */
@Mixin(ClientPacketListener.class)
public class PwSuggestionsMixin {
	@Inject(method = "handleCommandSuggestions", at = @At("HEAD"))
	private void khoatools$pwSuggestions(ClientboundCommandSuggestionsPacket packet, CallbackInfo ci) {
		if (Minecraft.getInstance().isSameThread()) { // packet handlers run on the network thread first, then again on the main thread
			PwScanFeature.onSuggestions(packet.id(), packet.toSuggestions());
		}
	}
}
