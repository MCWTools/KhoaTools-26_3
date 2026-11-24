package khoa.tools.remote;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import khoa.tools.KhoaTools;

/**
 * Client -> server: "I used my remote". Sent INSTEAD of the vanilla use packets, so the server never sees a right click on the
 * lever / button / plate (nothing gets toggled by accident).
 *
 * @param offHand the remote is in the off hand
 * @param hasPos  {@code pos} is meaningful: the block to link to (Remote Switch, not linked yet) or the block to toggle (Remote v2)
 */
public record RemoteUsePayload(boolean offHand, boolean hasPos, BlockPos pos) implements CustomPacketPayload {
	public static final Type<RemoteUsePayload> TYPE = new Type<>(KhoaTools.id("remote_use"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RemoteUsePayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.BOOL, RemoteUsePayload::offHand,
		ByteBufCodecs.BOOL, RemoteUsePayload::hasPos,
		BlockPos.STREAM_CODEC, RemoteUsePayload::pos,
		RemoteUsePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
