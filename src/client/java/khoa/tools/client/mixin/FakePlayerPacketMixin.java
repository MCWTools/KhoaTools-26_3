package khoa.tools.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;

import khoa.tools.client.feature.fakeplayer.FakePlayerManager;

/** The client-only teleport item must never be written to the server (creative inventory packets would save it for real). */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class FakePlayerPacketMixin {
	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
	private void khoatools$noFakeItemToServer(Packet<?> packet, CallbackInfo ci) {
		if (packet instanceof ServerboundSetCreativeModeSlotPacket creative && FakePlayerManager.wandId(creative.itemStack()) >= 0) ci.cancel();
	}
}
