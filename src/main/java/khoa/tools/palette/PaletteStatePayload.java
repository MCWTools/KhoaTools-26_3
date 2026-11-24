package khoa.tools.palette;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import khoa.tools.KhoaTools;

/**
 * Server -> client, after every apply / undo: how many applies of the current selection can still be undone.
 * The client shows the Undo button while this is above 0 and refreshes the block list.
 */
public record PaletteStatePayload(int undoDepth) implements CustomPacketPayload {
	public static final Type<PaletteStatePayload> TYPE = new Type<>(KhoaTools.id("palette_state"));
	public static final StreamCodec<RegistryFriendlyByteBuf, PaletteStatePayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, PaletteStatePayload::undoDepth,
		PaletteStatePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
