package khoa.tools.client.feature.invrefill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import khoa.tools.KhoaTools;

/**
 * {@code /autorefill <inventory name>}: restores the STACK SIZES of an inventory saved with the optional mod
 * "Save and Load Inventories" by Serilum (modid {@value #MOD_ID}, {@code /saveinventory <name>}).
 *
 * The other mod is NOT a compile or run-time dependency: it is only detected with {@link FabricLoader#isModLoaded} and its
 * saved {@code .txt} files are read here. Without it, the command only prints how to install it.
 *
 * Only a change in the number of a layout item triggers it. Then, for every saved slot whose item you carry (or carried since
 * the command started), the count is topped up to the saved count with the vanilla command {@code /item replace entity @s <slot>
 * with <item>[<saved components>] <count>}. NBT/components are the saved ones (a stack is only touched when its components are
 * identical to the saved ones), and a slot that now holds something else is never replaced. If you sorted the inventory, a stack
 * is topped up where it is now; an item that ran out completely comes back into its saved slot if that slot is empty.
 * Armor is not handled. Needs permission to run /item (operator / cheats), because the client cannot create items itself.
 */
public final class SavedInventoryRefill {
	static final String MOD_ID = "saveandloadinventories";
	private static final String INSTALL_MESSAGE =
		"Install Save and load inventories by Serilium to use https://github.com/Serilum/Save-and-Load-Inventories.git";

	/** Logical slots: 0..8 hotbar, 9..35 main inventory, 36 offhand. */
	private static final int SLOT_COUNT = 37;
	private static final int OFFHAND = 36;
	private static final int MAX_COMMANDS_PER_TICK = 4;
	/** Ticks to wait for the server to apply a command before sending it again. */
	private static final int RETRY_TICKS = 20;
	private static final int MAX_ATTEMPTS = 3;

	/** What the saved file says about one slot: the stack (for matching, count = saved count) and its /item argument. */
	private record Want(ItemStack stack, String spec) {
	}

	private static String activeName;
	private static Want[] layout;
	private static Map<Item, Integer> lastTotals;
	/** Layout items that were in the inventory at some point since the command started. */
	private static final Set<Item> seen = new HashSet<>();
	private static boolean pending;
	private static long tickCounter;
	private static final long[] sentAt = new long[SLOT_COUNT];
	private static final int[] attempts = new int[SLOT_COUNT];

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
		Want[] parsed;
		try {
			parsed = parse(mc, Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException e) {
			KhoaTools.LOGGER.error("Auto Refill could not read {}", file, e);
			source.sendError(Component.literal("Auto Refill: could not read " + file.getFileName() + " (" + e.getMessage() + ")."));
			return 0;
		}
		int wanted = 0;
		for (Want w : parsed) {
			if (w != null) {
				wanted++;
			}
		}
		if (wanted == 0) {
			source.sendError(Component.literal("Auto Refill: '" + name + "' has no readable item in the 36 inventory slots or the offhand."));
			return 0;
		}
		reset();
		layout = parsed;
		activeName = name;
		pending = true;
		source.sendFeedback(Component.literal("Auto Refill on: '" + name + "' (" + wanted + " slots). When the count of a saved item changes, its stack size is restored. /autorefill off to stop."));
		return 1;
	}

	private static void reset() {
		activeName = null;
		layout = null;
		lastTotals = null;
		pending = false;
		seen.clear();
		java.util.Arrays.fill(sentAt, 0);
		java.util.Arrays.fill(attempts, 0);
	}

	// ---- parsing the saved file ----

	/**
	 * Reads the gear text of Save and Load Inventories (one line per slot: {@code 'head' : '<snbt>',} or {@code 12 : '<snbt>',}).
	 * Returns the wanted stack for logical slots 0..35 and the offhand; unreadable or empty lines are null.
	 */
	private static Want[] parse(Minecraft mc, String text) {
		Want[] out = new Want[SLOT_COUNT];
		RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, mc.level.registryAccess());
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
			out[slot] = readWant(ops, data);
		}
		return out;
	}

	private static Want readWant(RegistryOps<Tag> ops, String snbt) {
		CompoundTag tag;
		try {
			tag = TagParser.parseCompoundFully(snbt);
		} catch (CommandSyntaxException e) {
			KhoaTools.LOGGER.warn("Auto Refill: unreadable item data: {}", e.getMessage());
			return null;
		}
		String idText = tag.getStringOr("id", "");
		Identifier id = Identifier.tryParse(idText);
		if (id == null) {
			return null;
		}
		ItemStack stack = ItemStack.CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY);
		if (stack.isEmpty()) {
			// Components the current version can't decode: match and give the plain item.
			Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
			if (item == null) {
				return null;
			}
			stack = new ItemStack(item, Math.max(1, tag.getIntOr("count", 1)));
			return new Want(stack, id + "");
		}
		StringBuilder spec = new StringBuilder(id.toString());
		CompoundTag components = tag.getCompound("components").orElse(null);
		if (components != null && !components.keySet().isEmpty()) {
			spec.append('[');
			boolean first = true;
			for (String k : components.keySet()) {
				if (!first) {
					spec.append(',');
				}
				first = false;
				spec.append(k).append('=').append(components.get(k));
			}
			spec.append(']');
		}
		return new Want(stack, spec.toString());
	}

	// ---- per tick ----

	private static void tick(Minecraft mc) {
		if (activeName == null) {
			return;
		}
		tickCounter++;
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.getConnection() == null) {
			lastTotals = null; // after a reconnect everything is checked once again
			pending = true;
			return;
		}
		if (mc.gui.screen() != null || player.containerMenu != player.inventoryMenu || player.isSpectator()) {
			return; // keep `pending`; checked again once this is over
		}
		Map<Item, Integer> totals = totals(player);
		for (Map.Entry<Item, Integer> e : totals.entrySet()) {
			if (e.getValue() > 0) {
				seen.add(e.getKey());
			}
		}
		if (lastTotals == null || !totals.equals(lastTotals)) {
			lastTotals = totals;
			pending = true;
		}
		if (pending && refill(mc, player) == 0) {
			pending = false;
		}
	}

	/** Total count of every layout item in the 36 slots + offhand (0 for items that are not carried). */
	private static Map<Item, Integer> totals(LocalPlayer player) {
		Map<Item, Integer> totals = new HashMap<>();
		for (Want w : layout) {
			if (w != null) {
				totals.putIfAbsent(w.stack().getItem(), 0);
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

	/** Sends the /item commands that restore stack sizes; returns how many fixes are still sent or waiting (0 = all done). */
	private static int refill(Minecraft mc, LocalPlayer player) {
		boolean[] claimed = new boolean[SLOT_COUNT];
		for (int slot = 0; slot < SLOT_COUNT; slot++) {
			if (layout[slot] != null && sameStack(stackAt(player, slot), layout[slot])) {
				claimed[slot] = true;
			}
		}
		int busy = 0;
		int sent = 0;
		for (int slot = 0; slot < SLOT_COUNT; slot++) {
			Want want = layout[slot];
			if (want == null) {
				continue;
			}
			int target = -1;
			int holder = slot;
			if (!claimed[slot]) {
				holder = -1;
				for (int other = 0; other < SLOT_COUNT; other++) {
					if (!claimed[other] && sameStack(stackAt(player, other), want)) {
						holder = other;
						claimed[other] = true;
						break;
					}
				}
			}
			if (holder >= 0) {
				if (stackAt(player, holder).getCount() < want.stack().getCount()) {
					target = holder;
				}
			} else if (seen.contains(want.stack().getItem()) && stackAt(player, slot).isEmpty()) {
				target = slot; // ran out completely: back into its saved slot (never over another item)
			}
			if (target < 0) {
				attempts[slot] = 0;
				continue;
			}
			busy++;
			if (tickCounter - sentAt[target] < RETRY_TICKS || sent >= MAX_COMMANDS_PER_TICK) {
				continue;
			}
			if (attempts[target] >= MAX_ATTEMPTS) {
				player.sendSystemMessage(Component.literal("Auto Refill stopped: the server did not apply /item replace (operator permission needed)."));
				reset();
				return 0;
			}
			attempts[target]++;
			sentAt[target] = tickCounter;
			sent++;
			mc.getConnection().sendCommand("item replace entity @s " + slotName(target) + " with " + want.spec() + " " + want.stack().getCount());
		}
		return busy;
	}

	/** Same item and same components as the saved stack (the count is ignored). */
	private static boolean sameStack(ItemStack have, Want want) {
		return !have.isEmpty() && ItemStack.isSameItemSameComponents(have, want.stack());
	}

	private static ItemStack stackAt(LocalPlayer player, int logical) {
		return logical == OFFHAND ? player.getOffhandItem() : player.getInventory().getItem(logical);
	}

	/** Slot argument of /item: hotbar.0-8, inventory.0-26, weapon.offhand. */
	private static String slotName(int logical) {
		if (logical == OFFHAND) {
			return "weapon.offhand";
		}
		return logical < 9 ? "hotbar." + logical : "inventory." + (logical - 9);
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
