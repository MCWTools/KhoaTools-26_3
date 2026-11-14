package khoa.tools.client.feature.automation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** When the item in your hand (or offhand) runs out or breaks, moves the same item from the inventory into that slot. */
public final class AutoRefillFeature extends ToggleFeature {
	private Item lastMain;
	private int lastSlot = -1;
	private Item lastOff;

	@Override
	public String id() {
		return "autorefill";
	}

	@Override
	public String displayName() {
		return "Auto Refill";
	}

	@Override
	public String description() {
		return "When the stack in your selected hotbar slot (or in the offhand) is used up or the tool breaks, takes the same item from your main inventory "
			+ "and puts it in that slot. Does nothing while a menu is open. Click the card to turn it on/off.";
	}

	@Override
	protected void onToggle(Minecraft mc, boolean now) {
		forget();
	}

	private void forget() {
		lastMain = null;
		lastOff = null;
		lastSlot = -1;
	}

	@Override
	protected void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.gui.screen() != null || !InvUtil.canClick(mc) || AutoEatFeature.isEating() || AutoReplaceFeature.isBusy()) {
			forget();
			return;
		}
		int slot = player.getInventory().getSelectedSlot();
		ItemStack main = player.getInventory().getItem(slot);
		ItemStack off = player.getOffhandItem();
		if (slot == lastSlot && main.isEmpty() && lastMain != null) {
			int from = find(mc, lastMain);
			if (from >= 0) {
				InvUtil.swapWithHotbar(mc, from, slot);
				main = player.getInventory().getItem(slot);
			}
		}
		if (off.isEmpty() && lastOff != null) {
			int from = find(mc, lastOff);
			if (from >= 0) {
				InvUtil.swapWithOffhand(mc, from);
				off = player.getOffhandItem();
			}
		}
		lastSlot = slot;
		lastMain = main.isEmpty() ? null : main.getItem();
		lastOff = off.isEmpty() ? null : off.getItem();
	}

	/** Main inventory menu slot holding {@code item}, or -1. */
	private static int find(Minecraft mc, Item item) {
		for (int i = InvUtil.MAIN_START; i < InvUtil.MAIN_END; i++) {
			ItemStack s = InvUtil.stack(mc, i);
			if (!s.isEmpty() && s.getItem() == item) {
				return i;
			}
		}
		return -1;
	}
}
