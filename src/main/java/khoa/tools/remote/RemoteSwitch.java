package khoa.tools.remote;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Remote Switch (server side). Two items, both recognised by a {@code khoatools_remote} compound in their custom_data:
 * <ul>
 * <li><b>Remote Switch</b> (carrot on a stick, kind "switch"): "Unconnected" until you right click a lever / button / pressure plate with it
 *     (that click only links, it does not toggle). Linked, one right click anywhere toggles that block from afar. The item name shows On / Off
 *     and follows the block's real state; it glows while linked and disconnects itself when the block is broken (not while its chunk is unloaded).
 *     Sneak + right click on another switch relinks, sneak + right click in the air disconnects.</li>
 * <li><b>Remote v2</b> (warped fungus on a stick, kind "v2"): no link. The client draws a circle behind the lever / button / plate you aim near and
 *     sends the block to toggle; the server checks it.</li>
 * </ul>
 * The mod client sends {@link RemoteUsePayload} instead of the vanilla use packets, so the server never gets a click on the switch.
 * Clients without the mod fall back to the use callbacks below (works, but the lever flickers on their screen for a moment).
 */
public final class RemoteSwitch {
	public static final String TAG = "khoatools_remote";
	private static final String KIND = "kind";
	private static final String DIM = "dim";
	private static final String X = "x";
	private static final String Y = "y";
	private static final String Z = "z";

	/** Reach for linking (blocks) - a little more than vanilla reach to forgive lag. */
	private static final double LINK_REACH = 10.0;
	/** Remote v2: how far (blocks) the block may be from the line the server thinks you are aiming along. */
	private static final double V2_AIM_TOLERANCE = 6.0;

	private static final int ON_COLOR = 0x55FF55;
	private static final int OFF_COLOR = 0xFF5555;
	private static final int GRAY = 0xAAAAAA;
	private static final int V2_COLOR = 0x55FFFF;

	/** Pressure plates the remote switched on: they stay pressed until toggled off (see BasePressurePlateBlockMixin). */
	private static final Set<GlobalPos> HELD_PLATES = new HashSet<>();

	public enum Kind {
		SWITCH("switch"),
		V2("v2");

		final String id;

		Kind(String id) {
			this.id = id;
		}
	}

	public record Link(ResourceKey<Level> dimension, BlockPos pos) {
	}

	private RemoteSwitch() {
	}

	// ---------------------------------------------------------------- items

	public static ItemStack createSwitch() {
		ItemStack stack = new ItemStack(Items.CARROT_ON_A_STICK);
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			CompoundTag t = new CompoundTag();
			t.putString(KIND, Kind.SWITCH.id);
			tag.put(TAG, t);
		});
		applyUnlinked(stack);
		return stack;
	}

	public static ItemStack createV2() {
		ItemStack stack = new ItemStack(Items.WARPED_FUNGUS_ON_A_STICK);
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			CompoundTag t = new CompoundTag();
			t.putString(KIND, Kind.V2.id);
			tag.put(TAG, t);
		});
		stack.set(DataComponents.CUSTOM_NAME, bold("Remote v2", V2_COLOR));
		stack.set(DataComponents.LORE, new ItemLore(List.of(plain("Aim near a lever, button or pressure plate and click.", GRAY))));
		return stack;
	}

	private static CompoundTag data(ItemStack stack) {
		CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
		if (custom == null) {
			return null;
		}
		return custom.copyTag().getCompound(TAG).orElse(null);
	}

	/** Which remote this stack is, or null for any other item. */
	public static Kind kindOf(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		CompoundTag t = data(stack);
		if (t == null) {
			return null;
		}
		String kind = t.getString(KIND).orElse("");
		for (Kind k : Kind.values()) {
			if (k.id.equals(kind)) {
				return k;
			}
		}
		return null;
	}

	/** The block a Remote Switch is linked to, or null. */
	public static Link linkOf(ItemStack stack) {
		CompoundTag t = data(stack);
		if (t == null || t.getString(DIM).isEmpty() || t.getInt(X).isEmpty() || t.getInt(Y).isEmpty() || t.getInt(Z).isEmpty()) {
			return null;
		}
		Identifier id = Identifier.tryParse(t.getString(DIM).get());
		if (id == null) {
			return null;
		}
		return new Link(ResourceKey.create(Registries.DIMENSION, id), new BlockPos(t.getInt(X).get(), t.getInt(Y).get(), t.getInt(Z).get()));
	}

	public static boolean isLinked(ItemStack stack) {
		return linkOf(stack) != null;
	}

	private static void writeLink(ItemStack stack, Link link) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			CompoundTag t = tag.getCompound(TAG).orElseGet(CompoundTag::new);
			t.putString(DIM, link.dimension().identifier().toString());
			t.putInt(X, link.pos().getX());
			t.putInt(Y, link.pos().getY());
			t.putInt(Z, link.pos().getZ());
			tag.put(TAG, t);
		});
	}

	private static void clearLink(ItemStack stack) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			CompoundTag t = tag.getCompound(TAG).orElseGet(CompoundTag::new);
			t.remove(DIM);
			t.remove(X);
			t.remove(Y);
			t.remove(Z);
			tag.put(TAG, t);
		});
	}

	private static Component bold(String text, int rgb) {
		return Component.literal(text).withStyle(Style.EMPTY.withBold(true).withItalic(false).withColor(TextColor.fromRgb(rgb)));
	}

	private static Component plain(String text, int rgb) {
		return Component.literal(text).withStyle(Style.EMPTY.withItalic(false).withColor(TextColor.fromRgb(rgb)));
	}

	private static void applyUnlinked(ItemStack stack) {
		setIfChanged(stack, bold("Unconnected", GRAY));
		stack.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
		if (stack.has(DataComponents.LORE)) {
			stack.remove(DataComponents.LORE);
		}
	}

	private static void applyLinked(ItemStack stack, Link link, BlockState state) {
		boolean on = isOn(state);
		setIfChanged(stack, on ? bold("On", ON_COLOR) : bold("Off", OFF_COLOR));
		if (!Boolean.TRUE.equals(stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE))) {
			stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		}
		BlockPos p = link.pos();
		ItemLore lore = new ItemLore(List.of(plain(state.getBlock().getName().getString() + " at " + p.getX() + " " + p.getY() + " " + p.getZ(), GRAY)));
		if (!lore.equals(stack.get(DataComponents.LORE))) {
			stack.set(DataComponents.LORE, lore);
		}
	}

	private static void setIfChanged(ItemStack stack, Component name) {
		if (!name.equals(stack.get(DataComponents.CUSTOM_NAME))) {
			stack.set(DataComponents.CUSTOM_NAME, name);
		}
	}

	// ---------------------------------------------------------------- blocks

	public static boolean isSwitchBlock(BlockState state) {
		return state.is(Blocks.LEVER) || state.is(BlockTags.BUTTONS) || state.is(BlockTags.PRESSURE_PLATES);
	}

	public static boolean isOn(BlockState state) {
		if (state.hasProperty(BlockStateProperties.POWERED)) {
			return state.getValue(BlockStateProperties.POWERED);
		}
		if (state.hasProperty(BlockStateProperties.POWER)) {
			return state.getValue(BlockStateProperties.POWER) > 0;
		}
		return false;
	}

	/** True while the remote holds this pressure plate down. Called from BasePressurePlateBlockMixin. */
	public static boolean isHeldPlate(ServerLevel level, BlockPos pos) {
		return !HELD_PLATES.isEmpty() && HELD_PLATES.contains(GlobalPos.of(level.dimension(), pos));
	}

	private static void toggleBlock(ServerLevel level, BlockPos pos, BlockState state) {
		Block block = state.getBlock();
		if (block instanceof LeverBlock lever) {
			lever.pull(state, level, pos, null);
		} else if (block instanceof ButtonBlock button) {
			if (isOn(state)) {
				setPowered(level, pos, state, false);
				level.playSound(null, pos, SoundEvents.STONE_BUTTON_CLICK_OFF, SoundSource.BLOCKS, 0.3F, 0.5F);
			} else {
				button.press(state, level, pos, null);
			}
		} else if (block instanceof BasePressurePlateBlock) {
			GlobalPos key = GlobalPos.of(level.dimension(), pos);
			if (isOn(state) && HELD_PLATES.contains(key)) {
				HELD_PLATES.remove(key);
				setPowered(level, pos, state, false);
			} else if (isOn(state)) {
				// pressed by something else right now: just hold it so it cannot pop back up
				HELD_PLATES.add(key);
				level.scheduleTick(pos, block, 20);
			} else {
				HELD_PLATES.add(key);
				setPowered(level, pos, state, true);
				level.scheduleTick(pos, block, 20);
			}
		}
	}

	private static void setPowered(ServerLevel level, BlockPos pos, BlockState state, boolean on) {
		BlockState next = state;
		if (state.hasProperty(BlockStateProperties.POWERED)) {
			next = state.setValue(BlockStateProperties.POWERED, on);
		} else if (state.hasProperty(BlockStateProperties.POWER)) {
			next = state.setValue(BlockStateProperties.POWER, on ? 15 : 0);
		}
		if (next == state) {
			return;
		}
		level.setBlock(pos, next, 3);
		Block block = state.getBlock();
		level.updateNeighborsAt(pos, block);
		for (Direction d : Direction.values()) {
			level.updateNeighborsAt(pos.relative(d), block);
		}
		if (block instanceof BasePressurePlateBlock) {
			level.playSound(null, pos, state.is(BlockTags.WOODEN_PRESSURE_PLATES)
				? (on ? SoundEvents.WOODEN_PRESSURE_PLATE_CLICK_ON : SoundEvents.WOODEN_PRESSURE_PLATE_CLICK_OFF)
				: (on ? SoundEvents.STONE_PRESSURE_PLATE_CLICK_ON : SoundEvents.STONE_PRESSURE_PLATE_CLICK_OFF),
				SoundSource.BLOCKS, 0.3F, on ? 0.6F : 0.5F);
		}
	}

	private static boolean chunkLoaded(ServerLevel level, BlockPos pos) {
		return level.hasChunkAt(pos);
	}

	private static boolean inSight(ServerPlayer player, ServerLevel level, BlockPos pos) {
		if (player.level() != level) {
			return false;
		}
		int view = level.getServer().getPlayerList().getViewDistance();
		int dx = Math.abs((player.blockPosition().getX() >> 4) - (pos.getX() >> 4));
		int dz = Math.abs((player.blockPosition().getZ() >> 4) - (pos.getZ() >> 4));
		return Math.max(dx, dz) <= view && chunkLoaded(level, pos);
	}

	// ---------------------------------------------------------------- using

	/**
	 * Remote Switch used. {@code clicked} = the switch block that was right clicked, or null (air / no usable block).
	 *
	 * @return true if the click was consumed (vanilla must not also run)
	 */
	public static boolean useSwitch(ServerPlayer player, InteractionHand hand, BlockPos clicked) {
		ItemStack stack = player.getItemInHand(hand);
		if (kindOf(stack) != Kind.SWITCH || !(player.level() instanceof ServerLevel level)) {
			return false;
		}
		Link link = linkOf(stack);
		boolean sneaking = player.isShiftKeyDown();

		if (link != null && sneaking && clicked == null) {
			clearLink(stack);
			applyUnlinked(stack);
			message(player, plain("Remote disconnected.", GRAY));
			return true;
		}

		if (link == null || sneaking) {
			if (clicked == null) {
				return link != null;
			}
			BlockState state = level.getBlockState(clicked);
			if (!isSwitchBlock(state) || player.getEyePosition().distanceTo(Vec3.atCenterOf(clicked)) > LINK_REACH) {
				return link != null;
			}
			Link fresh = new Link(level.dimension(), clicked.immutable());
			writeLink(stack, fresh);
			applyLinked(stack, fresh, state);
			level.playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.4F, 1.6F);
			message(player, plain("Remote connected to " + state.getBlock().getName().getString() + ".", ON_COLOR));
			return true;
		}

		ServerLevel target = level.getServer().getLevel(link.dimension());
		if (target == null || target != level) {
			message(player, plain("That block is in another dimension.", OFF_COLOR));
			return true;
		}
		if (!inSight(player, target, link.pos())) {
			message(player, plain("Out of sight.", OFF_COLOR));
			return true;
		}
		BlockState state = target.getBlockState(link.pos());
		if (!isSwitchBlock(state)) {
			clearLink(stack);
			applyUnlinked(stack);
			return true;
		}
		toggleBlock(target, link.pos(), state);
		applyLinked(stack, link, target.getBlockState(link.pos()));
		player.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
		return true;
	}

	/** Remote v2 used on {@code pos} (the block the client was aiming at). */
	public static boolean useV2(ServerPlayer player, InteractionHand hand, BlockPos pos) {
		ItemStack stack = player.getItemInHand(hand);
		if (kindOf(stack) != Kind.V2 || !(player.level() instanceof ServerLevel level) || !inSight(player, level, pos)) {
			return false;
		}
		BlockState state = level.getBlockState(pos);
		if (!isSwitchBlock(state)) {
			return false;
		}
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		Vec3 toBlock = Vec3.atCenterOf(pos).subtract(eye);
		double along = toBlock.dot(look);
		if (along < 0 || toBlock.subtract(look.scale(along)).length() > V2_AIM_TOLERANCE) {
			return false;
		}
		toggleBlock(level, pos, state);
		player.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
		return true;
	}

	private static void message(ServerPlayer player, Component text) {
		player.sendSystemMessage(text, true);
	}

	// ---------------------------------------------------------------- upkeep

	/** Every 2 ticks: keep the name of every Remote Switch in a player's inventory in step with its block, disconnect broken ones. */
	private static void tickPlayers(MinecraftServer server) {
		int tick = server.getTickCount();
		if (tick % 2 != 0) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Inventory inventory = player.getInventory();
			for (int i = 0; i < inventory.getContainerSize(); i++) {
				ItemStack stack = inventory.getItem(i);
				if (kindOf(stack) == Kind.SWITCH) {
					refresh(server, stack);
				}
			}
		}
		if (tick % 40 == 0 && !HELD_PLATES.isEmpty()) {
			Iterator<GlobalPos> it = HELD_PLATES.iterator();
			while (it.hasNext()) {
				GlobalPos key = it.next();
				ServerLevel level = server.getLevel(key.dimension());
				if (level == null) {
					continue;
				}
				if (!chunkLoaded(level, key.pos())) {
					continue; // unloaded is not broken
				}
				BlockState state = level.getBlockState(key.pos());
				if (!(state.getBlock() instanceof BasePressurePlateBlock) || !isOn(state)) {
					it.remove();
				}
			}
		}
	}

	private static void refresh(MinecraftServer server, ItemStack stack) {
		Link link = linkOf(stack);
		if (link == null) {
			applyUnlinked(stack);
			return;
		}
		ServerLevel level = server.getLevel(link.dimension());
		if (level == null || !chunkLoaded(level, link.pos())) {
			return; // not loaded: keep what it shows, do not disconnect
		}
		BlockState state = level.getBlockState(link.pos());
		if (!isSwitchBlock(state)) {
			clearLink(stack);
			applyUnlinked(stack);
			HELD_PLATES.remove(GlobalPos.of(link.dimension(), link.pos()));
			return;
		}
		applyLinked(stack, link, state);
	}

	// ---------------------------------------------------------------- setup

	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(RemoteUsePayload.TYPE, RemoteUsePayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(RemoteUsePayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			InteractionHand hand = payload.offHand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
			Kind kind = kindOf(player.getItemInHand(hand));
			if (kind == Kind.SWITCH) {
				useSwitch(player, hand, payload.hasPos() ? payload.pos() : null);
			} else if (kind == Kind.V2 && payload.hasPos()) {
				useV2(player, hand, payload.pos());
			}
		});

		// Fallback for players without the KhoaTools client (the mod client never sends these packets for a remote).
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer sp) || kindOf(player.getItemInHand(hand)) != Kind.SWITCH) {
				return InteractionResult.PASS;
			}
			return useSwitch(sp, hand, hit.getBlockPos()) ? InteractionResult.SUCCESS : InteractionResult.PASS;
		});
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer sp) || kindOf(player.getItemInHand(hand)) != Kind.SWITCH) {
				return InteractionResult.PASS;
			}
			return useSwitch(sp, hand, null) ? InteractionResult.SUCCESS : InteractionResult.PASS;
		});

		ServerTickEvents.END_SERVER_TICK.register(RemoteSwitch::tickPlayers);
	}
}
