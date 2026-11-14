package khoa.tools.client.feature.automation;

import java.util.List;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.feature.autoclicker.AutoClickerFeature;
import khoa.tools.setting.Setting;

/**
 * Eats when the hunger or health is at or below the limit and keeps eating until the hunger bar is full.
 * The Auto Clicker (and the other automations) stand still while it eats.
 */
public final class AutoEatFeature extends ToggleFeature {
	private static final String ID = "autoeat";
	private static final List<String> SOURCES = List.of("Offhand", "Hotbar", "Inventory");
	private static final List<String> DEFAULT_FOODS = List.of(
		"item:minecraft:cooked_beef", "item:minecraft:cooked_porkchop", "item:minecraft:cooked_chicken", "item:minecraft:cooked_mutton",
		"item:minecraft:cooked_rabbit", "item:minecraft:cooked_cod", "item:minecraft:cooked_salmon", "item:minecraft:golden_carrot",
		"item:minecraft:baked_potato");
	private static final PickedSet FOODS = new PickedSet(ID, "foods", DEFAULT_FOODS);
	private static final long TIMEOUT_NANOS = 30_000_000_000L;

	private static boolean eating;
	private static InteractionHand hand = InteractionHand.MAIN_HAND;
	private static int previousSlot = -1;
	/** Menu slot of the stack that was swapped into the selected hotbar slot (source Inventory), -1 = none. */
	private static int swappedFrom = -1;
	private static long startedAt;
	private static int idleTicks;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Auto Eat";
	}

	@Override
	public String description() {
		return "Eats the chosen foods when your hunger or health is at or below the limits, and keeps eating until the hunger bar is full. "
			+ "Take food from: Offhand = only the offhand; Hotbar = any hotbar slot (selects it, then goes back); Inventory = also the main inventory "
			+ "(swaps it into your hand and back). With Pause Auto Clicker on, the clicker waits while eating. Click the card to turn it on/off.";
	}

	@Override
	public List<Setting> settings() {
		return List.of(
			Setting.dropdown("source", "Take food from", "Where the food is taken from.", SOURCES,
				() -> KhoaConfig.getInt(ID, "source", 1), v -> KhoaConfig.put(ID, "source", v)),
			Setting.slider("hunger", "Hunger limit", "Eat when the hunger bar has this many points or fewer (20 = full).", 0, 20,
				() -> KhoaConfig.getInt(ID, "hunger", 14), v -> KhoaConfig.put(ID, "hunger", v)),
			Setting.slider("health", "Health limit", "Eat when your health has this many points or fewer (2 points = 1 heart, 20 = full). 0 = ignore health.", 0, 20,
				() -> KhoaConfig.getInt(ID, "health", 10), v -> KhoaConfig.put(ID, "health", v)),
			Setting.bool("pause_clicker", "Pause Auto Clicker", "The Auto Clicker waits while this eats and goes on afterwards.",
				() -> KhoaConfig.getBool(ID, "pause_clicker", true), v -> KhoaConfig.put(ID, "pause_clicker", v)),
			FOODS.setting("Foods", "Items that may be eaten. Default: cooked meats and fish, golden carrot, baked potato.", List.of("item")));
	}

	public static boolean isEating() {
		return eating;
	}

	@Override
	protected void onToggle(Minecraft mc, boolean now) {
		if (!now && eating) {
			stop(mc);
		}
	}

	@Override
	protected void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gui.screen() != null || !InvUtil.canClick(mc)) {
			if (eating) {
				stop(mc);
			}
			return;
		}
		int food = player.getFoodData().getFoodLevel();
		if (!eating) {
			int healthLimit = KhoaConfig.getInt(ID, "health", 10);
			boolean hungry = food <= KhoaConfig.getInt(ID, "hunger", 14);
			boolean hurt = healthLimit > 0 && player.getHealth() <= healthLimit;
			if ((hungry || hurt) && begin(mc, food)) {
				eating = true;
				startedAt = System.nanoTime();
				idleTicks = 0;
				if (KhoaConfig.getBool(ID, "pause_clicker", true)) {
					AutoClickerFeature.suspend(true);
				}
			} else {
				return;
			}
		}
		KeyMapping use = mc.options.keyUse;
		ItemStack inHand = player.getItemInHand(hand);
		boolean done = food >= 20 || System.nanoTime() - startedAt > TIMEOUT_NANOS;
		if (player.isUsingItem()) {
			use.setDown(true); // keep holding or the game cancels the eating
			idleTicks = 0;
			return;
		}
		use.setDown(false);
		if (done || !edible(inHand, food)) {
			if (!done && refill(mc, food)) { // the stack ran out: take the next one if there is any
				return;
			}
			stop(mc);
			return;
		}
		mc.gameMode.useItem(player, hand);
		if (player.isUsingItem()) {
			use.setDown(true);
			idleTicks = 0;
		} else if (++idleTicks > 5) {
			stop(mc); // the item cannot be eaten right now
		}
	}

	/** Prepares the hand with a food. Returns false when there is none. */
	private static boolean begin(Minecraft mc, int food) {
		LocalPlayer player = mc.player;
		int source = KhoaConfig.getInt(ID, "source", 1);
		previousSlot = -1;
		swappedFrom = -1;
		if (source == 0) {
			if (!edible(player.getOffhandItem(), food)) {
				return false;
			}
			hand = InteractionHand.OFF_HAND;
			return true;
		}
		hand = InteractionHand.MAIN_HAND;
		int selected = player.getInventory().getSelectedSlot();
		if (edible(player.getInventory().getItem(selected), food)) {
			return true;
		}
		for (int i = 0; i < 9; i++) {
			if (edible(player.getInventory().getItem(i), food)) {
				previousSlot = selected;
				player.getInventory().setSelectedSlot(i);
				return true;
			}
		}
		if (source == 2) {
			for (int i = InvUtil.MAIN_START; i < InvUtil.MAIN_END; i++) {
				if (edible(InvUtil.stack(mc, i), food)) {
					swappedFrom = i;
					InvUtil.swapWithHotbar(mc, i, selected);
					return true;
				}
			}
		}
		return false;
	}

	/** After a stack is used up: find another food. Returns true when one was put in the hand. */
	private static boolean refill(Minecraft mc, int food) {
		LocalPlayer player = mc.player;
		int source = KhoaConfig.getInt(ID, "source", 1);
		if (source == 0) {
			return false;
		}
		int selected = player.getInventory().getSelectedSlot();
		for (int i = 0; i < 9; i++) {
			if (edible(player.getInventory().getItem(i), food)) {
				if (previousSlot < 0) {
					previousSlot = selected;
				}
				player.getInventory().setSelectedSlot(i);
				return true;
			}
		}
		if (source == 2) {
			for (int i = InvUtil.MAIN_START; i < InvUtil.MAIN_END; i++) {
				if (edible(InvUtil.stack(mc, i), food)) {
					if (swappedFrom < 0) {
						swappedFrom = i; // remember where the item that was in the hand goes back to
					}
					InvUtil.swapWithHotbar(mc, i, selected);
					return true;
				}
			}
		}
		return false;
	}

	private static boolean edible(ItemStack stack, int food) {
		if (stack.isEmpty() || !FOODS.items().contains(stack.getItem())) {
			return false;
		}
		FoodProperties properties = stack.get(DataComponents.FOOD);
		return properties != null && (food < 20 || properties.canAlwaysEat());
	}

	private static void stop(Minecraft mc) {
		eating = false;
		AutoClickerFeature.suspend(false);
		mc.options.keyUse.setDown(false);
		LocalPlayer player = mc.player;
		if (player != null && InvUtil.canClick(mc)) {
			int selected = player.getInventory().getSelectedSlot();
			if (swappedFrom >= 0) {
				InvUtil.swapWithHotbar(mc, swappedFrom, selected); // the tool goes back where it was
			}
			if (previousSlot >= 0) {
				player.getInventory().setSelectedSlot(previousSlot);
			}
		}
		swappedFrom = -1;
		previousSlot = -1;
	}
}
