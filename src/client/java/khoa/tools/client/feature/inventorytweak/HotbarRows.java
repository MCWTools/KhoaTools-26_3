package khoa.tools.client.feature.inventorytweak;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Hotbar numbers: 1-9 are the vanilla saved hotbars (read and written through the game's own HotbarManager, same file, same
 * format), 10 and up are extra hotbars kept in config/khoatools-hotbars.json. The Saved Hotbars tab shows the 9 vanilla rows
 * followed by the extra ones, 5 more each time you scroll to the bottom.
 */
public final class HotbarRows {
	public static final int PAGE = 5;
	public static final int FIRST_EXTRA = 10;
	private static int loadedExtra = PAGE;

	private HotbarRows() {}

	public static int loadedExtra() { return loadedExtra; }

	public static void setLoadedExtra(int n) { loadedExtra = n; }

	/** The 9 items of hotbar {@code number} (all empty if it was never saved). */
	public static ItemStack[] get(int number) {
		ItemStack[] out = new ItemStack[9];
		java.util.Arrays.fill(out, ItemStack.EMPTY);
		if (number < FIRST_EXTRA) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null) {
				return out;
			}
			List<ItemStack> list = mc.getHotbarManager().get(number - 1).load(mc.level.registryAccess());
			for (int i = 0; i < 9 && i < list.size(); i++) {
				out[i] = list.get(i);
			}
			return out;
		}
		return SavedHotbarStore.get(number);
	}

	public static boolean isEmpty(int number) {
		for (ItemStack s : get(number)) {
			if (!s.isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/** Stores the player's current hotbar as hotbar {@code number}. */
	public static void saveCurrent(int number) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		if (number < FIRST_EXTRA) {
			mc.getHotbarManager().get(number - 1).storeFrom(mc.player.getInventory(), mc.level.registryAccess());
			mc.getHotbarManager().save();
			return;
		}
		ItemStack[] hb = new ItemStack[9];
		for (int i = 0; i < 9; i++) {
			hb[i] = mc.player.getInventory().getItem(i);
		}
		SavedHotbarStore.put(number, hb);
	}

	/** Puts hotbar {@code number} into the player's hotbar (creative). @return false if not possible. */
	public static boolean loadInto(int number) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.gameMode == null || !mc.player.getAbilities().instabuild) {
			return false;
		}
		ItemStack[] items = get(number);
		for (int i = 0; i < 9; i++) {
			ItemStack s = items[i].copy();
			mc.player.getInventory().setItem(i, s);
			mc.gameMode.handleCreativeModeItemAdd(s, 36 + i);
		}
		return true;
	}

	/** Adds the rows of extra hotbars [fromExtra, toExtra) to the creative item list (9 slots per row). */
	public static void append(NonNullList<ItemStack> items, int fromExtra, int toExtra) {
		for (int k = fromExtra; k < toExtra; k++) {
			int number = FIRST_EXTRA + k;
			ItemStack[] row = SavedHotbarStore.get(number);
			boolean empty = true;
			for (ItemStack s : row) {
				empty &= s.isEmpty();
			}
			if (empty) {
				ItemStack paper = new ItemStack(Items.PAPER);
				paper.set(DataComponents.CREATIVE_SLOT_LOCK, Unit.INSTANCE);
				paper.set(DataComponents.ITEM_NAME, Component.literal("Saved hotbar " + number + " (empty)"));
				items.add(paper);
				for (int i = 1; i < 9; i++) {
					items.add(ItemStack.EMPTY);
				}
			} else {
				for (int i = 0; i < 9; i++) {
					items.add(row[i]);
				}
			}
		}
	}
}
