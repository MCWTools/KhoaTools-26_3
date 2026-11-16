package khoa.tools.client.feature.invrefill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import khoa.tools.KhoaTools;

/**
 * {@code /autorefill <inventory name>}: keeps your inventory arranged like an inventory saved with the optional mod
 * "Save and Load Inventories" by Serilum (modid {@value #MOD_ID}, {@code /saveinventory <name>}).
 *
 * The other mod is NOT a compile or run-time dependency: it is only detected with {@link FabricLoader#isModLoaded} and its
 * saved {@code .txt} files are read here. Without it, the command only prints how to install it.
 *
 * Each time the number of one of the layout's items changes (picked up, used, dropped...), the items you already carry are
 * swapped into the slots of the saved layout. Nothing is ever created: items that are not in your inventory are skipped, and
 * a slot is only changed when an item of the wanted type exists in another, not-yet-correct slot. The layout is always the
 * saved one, so after you sort the inventory by hand the next change puts everything back in the saved order.
 * Armor slots are not touched. Only the survival-style inventory menu is used (no creative mode, no open container or screen).
 */
public final class SavedInventoryRefill {
	static final String MOD_ID = "saveandloadinventories";
	private static final String INSTALL_MESSAGE =
		"Install Save and load inventories by Serilium to use https://github.com/Serilum/Save-and-Load-Inventories.git";

	/** Logical slots: 0..8 hotbar, 9..35 main inventory, 36 offhand. */
	private static final int SLOT_COUNT = 37;
	private static final int OFFHAND = 36;
	/** Swaps per tick; the rest continues on the next tick. */
	private static final int MAX_SWAPS_PER_TICK = 6;

	private static String activeName;
	/** Wanted stack per logical slot (null = the layout does not care about that slot). */
	private static ItemStack[] layout;
	private static Map<Item, Integer> lastTotals;
	private static boolean pending;

	private SavedInventoryRefill() {
	}

	public static boolean modInstalled() {
		return FabricLoader.getInstance().isModLoaded(MOD_ID);
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(SavedInventoryRefill::tick);
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommands.literal("autorefill")
			.executes(ctx -> stopOrUsage(ctx.getSource()))
			.then(ClientCommands.literal("off").executes(ctx -> stopOrUsage(ctx.getSource())))
			.then(ClientCommands.argument("inventory-name", StringArgumentType.word())
				.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(modInstalled() ? SavedInventoryFiles.names() : List.of(), builder))
				.executes(ctx -> start(ctx.getSource(), StringArgumentType.getString(ctx, "inventory-name")))));
	}

	private static int stopOrUsage(FabricClientCommandSource source) {
		if (!modInstalled()) {
			source.sendError(Component.literal(INSTALL_MESSAGE));
			return 0;
		}
		if (activeName != null) {
			source.sendFeedback(Component.literal("Auto Refill stopped (was: " + activeName + ")."));
			reset();
			return 1;
		}
		source.sendFeedback(Component.literal("Usage: /autorefill <inventory name>   (saved with /saveinventory <name>). Stop: /autorefill off"));
		return 1;
	}

	private static int start(FabricClientCommandSource source, String rawName) {
		if (!modInstalled()) {
			source.sendError(Component.literal(INSTALL_MESSAGE));
			return 0;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			source.sendError(Component.literal("Auto Refill: not connected to a world."));
			return 0;
		}
		String name = rawName.toLowerCase(Locale.ROOT);
		Path file = SavedInventoryFiles.find(name);
		if (file == null) {
			source.sendError(Component.literal("Auto Refill: no saved inventory named '" + name + "'. Saved: " + SavedInventoryFiles.names()));
			return 0;
		}
		ItemStack[] parsed;
		try {
			parsed = parse(mc, Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException e) {
			KhoaTools.LOGGER.error("Auto Refill could not read {}", file, e);
			source.sendError(Component.literal("Auto Refill: could not read " + file.getFileName() + " (" + e.getMessage() + ")."));
			return 0;
		}
		int wanted = 0;
		for (ItemStack s : parsed) {
			if (s != null) {
				wanted++;
			}
		}
		if (wanted == 0) {
			source.sendError(Component.literal("Auto Refill: '" + name + "' has no readable item in the 36 inventory slots or the offhand."));
			return 0;
		}
		layout = parsed;
		activeName = name;
		lastTotals = null;
		pending = true;
		source.sendFeedback(Component.literal("Auto Refill on: '" + name + "' (" + wanted + " slots). Items you carry are put back into that layout whenever their count changes. /autorefill off to stop."));
		return 1;
	}

	private static void reset() {
		activeName = null;
		layout = null;
		lastTotals = null;
		pending = false;
	}

	// ---- parsing the saved file ----

	/**
	 * Reads the gear text of Save and Load Inventories (one line per slot: {@code 'head' : '<snbt>',} or {@code 12 : '<snbt>',}).
	 * Returns the wanted stack for logical slots 0..35 and the offhand; unreadable or empty lines are null.
	 */
	private static ItemStack[] parse(Minecraft mc, String text) {
		ItemStack[] out = new ItemStack[SLOT_COUNT];
		RegistryOps<net.minecraft.nbt.Tag> ops = RegistryOps.create(NbtOps.INSTANCE, mc.level.registryAccess());
		for (String line : text.split("',[\\r\\n]+")) {
			String trimmed = line.trim();
			int sep = trimmed.indexOf(" : ");
			if (sep < 0) {
				continue;
			}
			String key = trimmed.substring(0, sep).replace("'", "").strip();
			int slot;
			if (key.equalsIgnoreCase("offhand")) {
				slot = OFFHAND;
			} else {
				try {
					slot = Integer.parseInt(key);
				} catch (NumberFormatException e) {
					continue; // head/chest/legs/feet/effects: not handled
				}
				if (slot < 0 || slot >= OFFHAND) {
					continue;
				}
			}
			String data = trimmed.substring(sep + 3).strip();
			if (data.endsWith(",")) {
				data = data.substring(0, data.length() - 1);
			}
			if (data.startsWith("'")) {
				data = data.substring(1);
			}
			if (data.endsWith("'")) {
				data = data.substring(0, data.length() - 1);
			}
			if (data.isBlank()) {
				continue;
			}
			ItemStack stack = readStack(ops, data);
			if (stack != null) {
				out[slot] = stack;
			}
		}
		return out;
	}

	private static ItemStack readStack(RegistryOps<net.minecraft.nbt.Tag> ops, String snbt) {
		CompoundTag tag;
		try {
			tag = TagParser.parseCompoundFully(snbt);
		} catch (CommandSyntaxException e) {
			KhoaTools.LOGGER.warn("Auto Refill: unreadable item data: {}", e.getMessage());
			return null;
		}
		ItemStack stack = ItemStack.CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY);
		if (!stack.isEmpty()) {
			return stack;
		}
		// Components the current version can't decode: fall back to the plain item.
		Identifier id = Identifier.tryParse(tag.getStringOr("id", ""));
		if (id == null) {
			return null;
		}
		Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
		return item == null ? null : new ItemStack(item);
	}

	// ---- per tick ----

	private static void tick(Minecraft mc) {
		if (activeName == null) {
			return;
		}
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			lastTotals = null; // after a reconnect the layout is applied once again
			pending = true;
			return;
		}
		if (mc.gui.screen() != null || mc.gameMode == null || player.containerMenu != player.inventoryMenu
			|| player.hasInfiniteMaterials() || !player.inventoryMenu.getCarried().isEmpty() || player.isSpectator()) {
			return; // keep `pending`; the totals are compared again once this is over
		}
		Map<Item, Integer> totals = totals(player);
		if (lastTotals == null || !totals.equals(lastTotals)) {
			lastTotals = totals;
			pending = true;
		}
		if (pending && arrange(mc, player) == 0) {
			pending = false;
		}
	}

	private static Map<Item, Integer> totals(LocalPlayer player) {
		Map<Item, Integer> totals = new HashMap<>();
		for (ItemStack wanted : layout) {
			if (wanted != null) {
				totals.putIfAbsent(wanted.getItem(), 0);
			}
		}
		for (int slot = 0; slot < SLOT_COUNT; slot++) {
			ItemStack have = stackAt(player, slot);
			if (!have.isEmpty() && totals.containsKey(have.getItem())) {
				totals.merge(have.getItem(), have.getCount(), Integer::sum);
			}
		}
		return totals;
	}

	/** Does the stack in logical {@code slot} already satisfy the layout? (Layout without an entry = nothing to satisfy.) */
	private static boolean satisfied(LocalPlayer player, int slot) {
		ItemStack wanted = layout[slot];
		return wanted == null || stackAt(player, slot).is(wanted.getItem());
	}

	/** Performs up to {@link #MAX_SWAPS_PER_TICK} swaps toward the layout; returns how many it did (0 = nothing left to do). */
	private static int arrange(Minecraft mc, LocalPlayer player) {
		int swaps = 0;
		for (int target = 0; target < SLOT_COUNT && swaps < MAX_SWAPS_PER_TICK; target++) {
			ItemStack wanted = layout[target];
			if (wanted == null || satisfied(player, target)) {
				continue;
			}
			int source = findSource(player, target, wanted);
			if (source < 0) {
				continue; // none of that item in the inventory: skipped on purpose
			}
			swap(mc, player, source, target);
			swaps++;
		}
		return swaps;
	}

	/** A slot other than {@code target} holding the wanted item that is not itself correctly placed; exact component match first. */
	private static int findSource(LocalPlayer player, int target, ItemStack wanted) {
		int sameItem = -1;
		for (int slot = 0; slot < SLOT_COUNT; slot++) {
			if (slot == target) {
				continue;
			}
			ItemStack have = stackAt(player, slot);
			if (have.isEmpty() || !have.is(wanted.getItem())) {
				continue;
			}
			if (layout[slot] != null && have.is(layout[slot].getItem())) {
				continue; // already where the layout wants it
			}
			if (ItemStack.isSameItemSameComponents(have, wanted)) {
				return slot;
			}
			if (sameItem < 0) {
				sameItem = slot;
			}
		}
		return sameItem;
	}

	private static ItemStack stackAt(LocalPlayer player, int logical) {
		return logical == OFFHAND ? player.getOffhandItem() : player.getInventory().getItem(logical);
	}

	/** Menu slot of the player's inventory menu: 36..44 hotbar, 9..35 main, 45 offhand. */
	private static int menuSlot(int logical) {
		if (logical == OFFHAND) {
			return 45;
		}
		return logical < 9 ? 36 + logical : logical;
	}

	/** Swaps the stacks of two logical slots using only SWAP clicks (hotbar number 0..8, or 40 = offhand). */
	private static void swap(Minecraft mc, LocalPlayer player, int a, int b) {
		if (a == OFFHAND || b == OFFHAND) {
			click(mc, player, menuSlot(a == OFFHAND ? b : a), 40);
		} else if (a < 9) {
			click(mc, player, menuSlot(b), a);
		} else if (b < 9) {
			click(mc, player, menuSlot(a), b);
		} else {
			// main <-> main: go through hotbar slot 0 and put its own stack back.
			click(mc, player, menuSlot(a), 0);
			click(mc, player, menuSlot(b), 0);
			click(mc, player, menuSlot(a), 0);
		}
	}

	private static void click(Minecraft mc, LocalPlayer player, int menuSlot, int button) {
		mc.gameMode.handleContainerInput(player.inventoryMenu.containerId, menuSlot, button, ContainerInput.SWAP, player);
	}

	/** Names of the saved inventories (for tab completion). */
	static List<String> listNames(List<Path> dirs) {
		List<String> names = new ArrayList<>();
		for (Path dir : dirs) {
			if (!Files.isDirectory(dir)) {
				continue;
			}
			try (Stream<Path> files = Files.list(dir)) {
				files.filter(p -> p.getFileName().toString().endsWith(".txt")).forEach(p -> {
					String n = p.getFileName().toString();
					n = n.substring(0, n.length() - 4);
					if (!names.contains(n)) {
						names.add(n);
					}
				});
			} catch (IOException e) {
				KhoaTools.LOGGER.warn("Auto Refill could not list {}: {}", dir, e.getMessage());
			}
		}
		names.sort(String::compareTo);
		return names;
	}
}
