package khoa.tools.client.feature.automation;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.item.ItemStack;

import khoa.tools.client.config.KhoaConfig;
import khoa.tools.setting.Setting;

/** Throws away items by the list. All matching stacks go in the same tick, as container packets. */
public final class AutoDropFeature extends ToggleFeature {
	private static final String ID = "autodrop";
	private static final List<String> MODES = List.of("Blacklist", "Whitelist");
	private static final PickedSet ITEMS = new PickedSet(ID, "items", List.of());
	private int cooldown;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Auto Drop";
	}

	@Override
	public String description() {
		return "Blacklist = throws away every stack of the listed items. Whitelist = keeps the listed items and throws away everything else "
			+ "(armor you wear is never touched). All stacks are thrown in one go. With 'Open inventory to throw' on, it only works while your own inventory "
			+ "screen is open. Thrown items lie at your feet and a player can pick them up again after about 2 seconds. Click the card to turn it on/off.";
	}

	@Override
	public List<Setting> settings() {
		return List.of(
			Setting.dropdown("mode", "List mode", "Blacklist: throw the listed items. Whitelist: throw everything that is not listed.", MODES,
				() -> KhoaConfig.getInt(ID, "mode", 0), v -> KhoaConfig.put(ID, "mode", v)),
			ITEMS.setting("Items", "The items of the list.", List.of("item")),
			Setting.bool("open_inventory", "Open inventory to throw", "Only throws while your inventory screen is open. Off = throws all the time (a few times a second).",
				() -> KhoaConfig.getBool(ID, "open_inventory", true), v -> KhoaConfig.put(ID, "open_inventory", v)));
	}

	@Override
	protected void tick(Minecraft mc) {
		if (mc.player == null || mc.gameMode == null) {
			return;
		}
		boolean needScreen = KhoaConfig.getBool(ID, "open_inventory", true);
		boolean inventoryOpen = mc.gui.screen() instanceof InventoryScreen;
		if (needScreen ? !inventoryOpen : (mc.gui.screen() != null && !inventoryOpen)) {
			return;
		}
		if (!InvUtil.canClick(mc) || --cooldown > 0) {
			return;
		}
		cooldown = 4;
		boolean whitelist = KhoaConfig.getInt(ID, "mode", 0) == 1;
		if (ITEMS.items().isEmpty() && whitelist) {
			return; // an empty whitelist would throw everything
		}
		for (int slot = InvUtil.MAIN_START; slot < InvUtil.HOTBAR_START + 9; slot++) { // main inventory and hotbar
			ItemStack stack = InvUtil.stack(mc, slot);
			if (stack.isEmpty()) {
				continue;
			}
			boolean listed = ITEMS.items().contains(stack.getItem());
			if (whitelist ? !listed : listed) { // blacklist: throw when listed; whitelist: throw when not listed
				InvUtil.throwStack(mc, slot);
			}
		}
	}
}
