package khoa.tools.client.feature.fakeplayer;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.item.ItemStack;

/**
 * "Copy player's data": name, skin, model type (slim / normal), active effects, hands, armor (with trims and every other component) and,
 * for yourself, the whole hotbar and inventory. Position, pose and animation are never touched.
 */
public final class FakePlayerCopy {
	private FakePlayerCopy() {
	}

	/**
	 * Fills {@code out} from a player (blank name = you). Returns an error text, or null on success.
	 * Other players only expose their hands and armor to the client; their hotbar / inventory stay empty.
	 */
	public static String copy(Minecraft mc, String playerName, FakePlayerData out) {
		if (mc.player == null || mc.level == null) return "Join a world first.";
		AbstractClientPlayer source = mc.player;
		String wanted = playerName == null ? "" : playerName.trim();
		if (!wanted.isEmpty()) {
			source = null;
			for (AbstractClientPlayer p : mc.level.players()) {
				if (p.getGameProfile().name().equalsIgnoreCase(wanted) && !(p instanceof FakePlayerEntity)) {
					source = p;
					break;
				}
			}
			if (source == null) return "Player '" + wanted + "' is not loaded near you.";
		}
		out.name = source.getGameProfile().name();
		out.skin = out.name;
		out.slim = source.getSkin().model() == PlayerModelType.SLIM;
		out.head = text(source.getItemBySlot(EquipmentSlot.HEAD));
		out.chest = text(source.getItemBySlot(EquipmentSlot.CHEST));
		out.legs = text(source.getItemBySlot(EquipmentSlot.LEGS));
		out.feet = text(source.getItemBySlot(EquipmentSlot.FEET));
		out.offHand = text(source.getOffhandItem());
		out.mainHand = text(source.getMainHandItem());
		out.effects = effectsText(source);
		out.inventory = new ArrayList<>();
		out.inventoryCounts = new int[0];
		if (source == mc.player) {
			List<String> items = new ArrayList<>();
			int[] counts = new int[36];
			for (int i = 0; i < 36; i++) {
				ItemStack stack = source.getInventory().getItem(i);
				items.add(text(stack));
				counts[i] = stack.isEmpty() || FakePlayerManager.wandId(stack) >= 0 ? 0 : stack.getCount();
			}
			out.inventory = items;
			out.inventoryCounts = counts;
			out.selectedSlot = source.getInventory().getSelectedSlot();
			out.mainHand = items.get(out.selectedSlot);
		}
		return null;
	}

	/** Active effects of the player as {@code minecraft:speed:1, minecraft:glowing} (the format {@link FakePlayerEffects} parses). */
	private static String effectsText(AbstractClientPlayer source) {
		List<String> parts = new ArrayList<>();
		for (MobEffectInstance instance : source.getActiveEffects()) {
			String id = instance.getEffect().unwrapKey().map(k -> k.identifier().toString()).orElse(null);
			if (id == null) continue;
			parts.add(instance.getAmplifier() > 0 ? id + ":" + instance.getAmplifier() : id);
		}
		return String.join(", ", parts);
	}

	/** Item syntax of a stack; our own teleport items are never copied. */
	private static String text(ItemStack stack) {
		if (stack.isEmpty() || FakePlayerManager.wandId(stack) >= 0) return "";
		return FakePlayerItems.toText(stack);
	}

	/** Armor and off hand of the player (used by the teleport item when 'copy items' is on). */
	public static void copyWorn(Minecraft mc, FakePlayerData d) {
		if (mc.player == null) return;
		d.head = text(mc.player.getItemBySlot(EquipmentSlot.HEAD));
		d.chest = text(mc.player.getItemBySlot(EquipmentSlot.CHEST));
		d.legs = text(mc.player.getItemBySlot(EquipmentSlot.LEGS));
		d.feet = text(mc.player.getItemBySlot(EquipmentSlot.FEET));
		d.offHand = text(mc.player.getOffhandItem());
	}
}
