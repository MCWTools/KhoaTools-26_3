package khoa.tools.palette;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import khoa.tools.KhoaTools;

/** Client -> server: "undo my last Building Palette apply" (the server keeps the history, see {@link BuildingPalette}). */
public record PaletteUndoPayload() implements CustomPacketPayload {
	public static final Type<PaletteUndoPayload> TYPE = new Type<>(KhoaTools.id("palette_undo"));
	public static final StreamCodec<RegistryFriendlyByteBuf, PaletteUndoPayload> CODEC = StreamCodec.unit(new PaletteUndoPayload());

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
