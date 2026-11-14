package khoa.tools.client.feature.invprofile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.feature.KhoaFeature;

/**
 * Arranges the inventory like a profile. It works one exchange at a time: every step looks at the real inventory, finds the first slot of the
 * profile that is not right yet and fixes it, so it still works when something changes while it runs. The speed is limited (steps per second,
 * one step = one container click). Menu slots: 5..8 armor, 9..35 main, 36..44 hotbar, 45 offhand.
 */
final class InvProfileLoader {
	private static final int MAX_EXCHANGES = 120;

	private record Exchange(int a, int b) {
	}

	private static InvProfile profile;
	private static long nextAt;
	private static int exchanges;

	private InvProfileLoader() {
	}

	static boolean isLoading() {
		return profile != null;
	}

	static void start(Minecraft mc, InvProfile p) {
		if (mc.player == null || mc.gameMode == null) {
			KhoaFeature.say(mc, "Inventory Profile: join a world first.");
			return;
		}
		if (profile != null) {
			KhoaFeature.say(mc, "Inventory Profile: already loading '" + profile.name + "'.");
			return;
		}
		profile = p;
		exchanges = 0;
		nextAt = System.nanoTime();
		KhoaFeature.say(mc, "Inventory Profile: loading '" + p.name + "' ...");
	}

	static void stop(Minecraft mc, String why) {
		if (profile == null) {
			return;
		}
		String name = profile.name;
		profile = null;
		KhoaFeature.say(mc, "Inventory Profile '" + name + "': " + why);
	}

	static void tick(Minecraft mc) {
		if (profile == null) {
			return;
		}
		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null) {
			profile = null;
			return;
		}
		if (player.containerMenu != player.inventoryMenu) {
			stop(mc, "stopped, another container is open.");
			return;
		}
		if (!player.containerMenu.getCarried().isEmpty()) {
			stop(mc, "stopped, there is an item on your cursor. Put it down and load again.");
			return;
		}
		long interval = 1_000_000_000L / Math.max(1, KhoaConfig.getInt("invprofile", "steps_per_second", 9));
		long now = System.nanoTime();
		for (int guard = 0; guard < 4 && now - nextAt >= 0; guard++) {
			List<String> unmet = new ArrayList<>();
			Exchange next = plan(mc, profile, unmet);
			if (next == null) {
				stop(mc, unmet.isEmpty() ? "done." : "done, but missing: " + String.join(", ", unmet) + ".");
				return;
			}
			if (++exchanges > MAX_EXCHANGES) {
				stop(mc, "stopped, too many steps (the inventory keeps changing).");
				return;
			}
			int clicks = execute(mc, next.a(), next.b());
			nextAt = Math.max(nextAt, now) + interval * clicks;
		}
	}

	// ------------------------------------------------------------------ planning

	/** The next exchange of two inventory-menu slots, or null when every slot is right. {@code unmet} gets what could not be found. */
	private static Exchange plan(Minecraft mc, InvProfile p, List<String> unmet) {
		Set<Integer> claimed = new HashSet<>();
		Set<Integer> reserved = new HashSet<>();
		for (int i = 0; i < InvProfile.SIZE; i++) {
			if (InvProfile.parse(p.slots[i]) != null) {
				reserved.add(InvProfile.menuSlot(i));
			}
		}
		for (int i = 0; i < InvProfile.SIZE; i++) {
			InvProfile.Request request = InvProfile.parse(p.slots[i]);
			if (request == null) {
				continue;
			}
			int target = InvProfile.menuSlot(i);
			if (target == 45 && !stack(mc, 45).isEmpty()) {
				continue; // the offhand is left alone when something is in it
			}
			int best = pick(mc, request, target, claimed);
			if (best < 0) {
				unmet.add(request.label() + " (slot " + (i + 1) + ")");
				continue;
			}
			claimed.add(best);
			if (best == target) {
				continue;
			}
			Exchange move = place(mc, best, target, reserved);
			if (move == null) {
				unmet.add("room for " + request.label() + " (slot " + (i + 1) + ")");
				continue;
			}
			return move;
		}
		return null;
	}

	/** The menu slot of the best stack for {@code request} that may go into {@code target}, or -1. */
	private static int pick(Minecraft mc, InvProfile.Request request, int target, Set<Integer> claimed) {
		int best = -1;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (int pos = 5; pos < 45; pos++) {
			if (claimed.contains(pos)) {
				continue;
			}
			ItemStack s = stack(mc, pos);
			if (s.isEmpty() || !request.matches(s) || !mc.player.inventoryMenu.getSlot(target).mayPlace(s)) {
				continue;
			}
			double score = request.score(s);
			if (score > bestScore || (score == bestScore && pos == target)) {
				best = pos;
				bestScore = score;
			}
		}
		return best;
	}

	/** Moves the stack from {@code from} to {@code target}; whatever was in the way is pushed into the main inventory first. */
	private static Exchange place(Minecraft mc, int from, int target, Set<Integer> reserved) {
		ItemStack in = stack(mc, target);
		if (in.isEmpty()) {
			return new Exchange(from, target);
		}
		boolean fromMain = from >= 9 && from < 36;
		if (fromMain && mc.player.inventoryMenu.getSlot(from).mayPlace(in)) {
			return new Exchange(from, target); // a plain swap: the old stack lands in the inventory
		}
		int free = freeMainSlot(mc, reserved, target);
		if (free >= 0) {
			return new Exchange(target, free);
		}
		if (mc.player.inventoryMenu.getSlot(from).mayPlace(in)) {
			return new Exchange(from, target);
		}
		return null;
	}

	private static int freeMainSlot(Minecraft mc, Set<Integer> reserved, int except) {
		int fallback = -1;
		for (int pos = 9; pos < 36; pos++) {
			if (pos == except || !stack(mc, pos).isEmpty()) {
				continue;
			}
			if (!reserved.contains(pos)) {
				return pos;
			}
			if (fallback < 0) {
				fallback = pos;
			}
		}
		return fallback;
	}

	private static ItemStack stack(Minecraft mc, int menuSlot) {
		return mc.player.inventoryMenu.getSlot(menuSlot).getItem();
	}

	// ------------------------------------------------------------------ clicks

	/** Swaps the contents of two menu slots. Returns how many clicks it took. */
	private static int execute(Minecraft mc, int a, int b) {
		if (b >= 36 && b <= 44) {
			swap(mc, a, b - 36);
			return 1;
		}
		if (a >= 36 && a <= 44) {
			swap(mc, b, a - 36);
			return 1;
		}
		if (a == 45) {
			swap(mc, b, 40);
			return 1;
		}
		if (b == 45) {
			swap(mc, a, 40);
			return 1;
		}
		int clicks = 2;
		pickup(mc, a);
		pickup(mc, b);
		if (!mc.player.containerMenu.getCarried().isEmpty()) {
			pickup(mc, a); // b had something: it comes back to a
			clicks = 3;
		}
		return clicks;
	}

	private static void swap(Minecraft mc, int slot, int button) {
		mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, slot, button, ContainerInput.SWAP, mc.player);
	}

	private static void pickup(Minecraft mc, int slot) {
		mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, slot, 0, ContainerInput.PICKUP, mc.player);
	}
}
