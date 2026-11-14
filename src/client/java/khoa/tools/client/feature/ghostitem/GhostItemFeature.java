package khoa.tools.client.feature.ghostitem;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import khoa.tools.client.feature.KhoaFeature;

/**
 * Ghost Item: one-shot desync. Copies the offhand item into the selected hotbar slot, on your client only, when that slot is empty.
 * The copy stays (also while a GUI is open) until the server resends that slot; the server still has the slot empty.
 */
public final class GhostItemFeature implements KhoaFeature {
	@Override
	public String id() {
		return "ghostitem";
	}

	@Override
	public String displayName() {
		return "Ghost Item";
	}

	@Override
	public String description() {
		return "Click the card (or use its keybind / command): copies the item in your offhand into the selected hotbar slot, client-side only. "
			+ "The slot must be empty. The real item stays in the offhand; the server thinks the slot is empty, so the copy is a desync and "
			+ "disappears when the server resends that slot (inventory click, relog...).";
	}

	@Override
	public void activate(Minecraft mc) {
		if (mc.player == null) {
			return;
		}
		Inventory inv = mc.player.getInventory();
		ItemStack offhand = mc.player.getOffhandItem();
		int selected = inv.getSelectedSlot();
		if (offhand.isEmpty()) {
			KhoaFeature.say(mc, "Ghost Item: your offhand is empty.");
		} else if (!inv.getItem(selected).isEmpty()) {
			KhoaFeature.say(mc, "Ghost Item: the selected hotbar slot is not empty.");
		} else {
			inv.setItem(selected, offhand.copy());
			KhoaFeature.say(mc, "Ghost Item: copied to hotbar slot " + (selected + 1) + ".");
		}
	}
}
