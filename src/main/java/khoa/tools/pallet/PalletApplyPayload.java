package khoa.tools.pallet;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import khoa.tools.KhoaTools;

/**
 * Client -> server: "replace these block types inside this box with random blocks of their palettes".
 *
 * @param min     lowest corner (inclusive)
 * @param max     highest corner (inclusive)
 * @param entries one per source block type that has a palette
 */
public record PalletApplyPayload(BlockPos min, BlockPos max, List<Entry> entries) implements CustomPacketPayload {
	/** @param source block id of the type to replace; @param palette block ids to pick from at random */
	public record Entry(String source, List<String> palette) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, Entry::source,
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), Entry::palette,
			Entry::new);
	}

	public static final Type<PalletApplyPayload> TYPE = new Type<>(KhoaTools.id("pallet_apply"));
	public static final StreamCodec<RegistryFriendlyByteBuf, PalletApplyPayload> CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, PalletApplyPayload::min,
		BlockPos.STREAM_CODEC, PalletApplyPayload::max,
		Entry.CODEC.apply(ByteBufCodecs.list()), PalletApplyPayload::entries,
		PalletApplyPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
