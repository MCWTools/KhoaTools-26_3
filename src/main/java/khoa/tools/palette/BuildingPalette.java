package khoa.tools.palette;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import khoa.tools.KhoaTools;

/**
 * Building Palette (server side). The item is a carrot on a stick with a {@code khoatools_palette} compound in its custom_data and an enchantment glint.
 * The mod client selects a box and sends {@link PaletteApplyPayload}; this class checks it and does the replacing.
 */
public final class BuildingPalette {
	public static final String TAG = "khoatools_palette";
	/** Biggest box (in blocks) one apply may touch. */
	public static final long MAX_VOLUME = 4_000_000L;
	/** Biggest side length of the box. */
	public static final int MAX_SIDE = 512;

	private BuildingPalette() {
	}

	public static ItemStack create() {
		ItemStack stack = new ItemStack(Items.CARROT_ON_A_STICK);
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(TAG, new CompoundTag()));
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Building Palette")
			.withStyle(Style.EMPTY.withBold(true).withItalic(false).withColor(TextColor.fromRgb(0x55FFAA))));
		return stack;
	}

	public static boolean isPalette(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		return custom != null && custom.copyTag().contains(TAG);
	}

	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(PaletteApplyPayload.TYPE, PaletteApplyPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(PaletteApplyPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> apply(player, payload));
		});
	}

	private static Block block(String id) {
		Identifier identifier = Identifier.tryParse(id);
		if (identifier == null) {
			return null;
		}
		Block b = BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null);
		return b == null || b == Blocks.AIR ? null : b;
	}

	private static void apply(ServerPlayer player, PaletteApplyPayload payload) {
		if (!Commands.LEVEL_GAMEMASTERS.check(player.permissions())) {
			player.sendSystemMessage(Component.literal("Building Palette: operators only."));
			return;
		}
		if (!isPalette(player.getMainHandItem()) && !isPalette(player.getOffhandItem())) {
			player.sendSystemMessage(Component.literal("Building Palette: hold the palette to apply."));
			return;
		}
		BlockPos a = payload.min();
		BlockPos b = payload.max();
		BlockPos lo = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
		BlockPos hi = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
		long sx = (long) hi.getX() - lo.getX() + 1;
		long sy = (long) hi.getY() - lo.getY() + 1;
		long sz = (long) hi.getZ() - lo.getZ() + 1;
		if (sx > MAX_SIDE || sy > MAX_SIDE || sz > MAX_SIDE || sx * sy * sz > MAX_VOLUME) {
			player.sendSystemMessage(Component.literal("Building Palette: the selection is too big (max " + MAX_SIDE + " per side, " + MAX_VOLUME + " blocks)."));
			return;
		}
		Map<Block, List<BlockState>> palettes = new HashMap<>();
		for (PaletteApplyPayload.Entry e : payload.entries()) {
			Block source = block(e.source());
			if (source == null) {
				continue;
			}
			List<BlockState> states = new ArrayList<>();
			for (String id : e.palette()) {
				Block p = block(id);
				if (p != null) {
					states.add(p.defaultBlockState());
				}
			}
			if (!states.isEmpty()) {
				palettes.put(source, states);
			}
		}
		if (palettes.isEmpty()) {
			player.sendSystemMessage(Component.literal("Building Palette: no palette chosen."));
			return;
		}
		ServerLevel level = player.level();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int changed = 0;
		int skipped = 0;
		for (int x = lo.getX(); x <= hi.getX(); x++) {
			for (int z = lo.getZ(); z <= hi.getZ(); z++) {
				if (!level.hasChunkAt(pos.set(x, level.getMinY(), z))) {
					skipped++;
					continue;
				}
				for (int y = Math.max(lo.getY(), level.getMinY()); y <= Math.min(hi.getY(), level.getMaxY()); y++) {
					pos.set(x, y, z);
					BlockState current = level.getBlockState(pos);
					List<BlockState> palette = palettes.get(current.getBlock());
					if (palette == null || level.getBlockEntity(pos) != null) {
						continue; // not picked, or a block with data (chest...) that a replace would wipe
					}
					BlockState next = palette.get(palette.size() == 1 ? 0 : level.getRandom().nextInt(palette.size()));
					if (next != current) {
						level.setBlock(pos, next, 2);
						changed++;
					}
				}
			}
		}
		KhoaTools.LOGGER.info("Building Palette: {} replaced {} blocks in {} -> {}", player.getName().getString(), changed, lo.toShortString(), hi.toShortString());
		player.sendSystemMessage(Component.literal("Building Palette: replaced " + changed + " blocks" + (skipped > 0 ? " (" + skipped + " unloaded columns skipped)" : "") + "."));
	}
}
