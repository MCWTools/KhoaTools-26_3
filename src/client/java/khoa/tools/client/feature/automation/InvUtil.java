package khoa.tools.client.feature.automation;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/**
 * Container clicks on the player's own inventory menu (id 0). Menu slots: 9..35 main inventory, 36..44 hotbar, 45 offhand.
 * Only valid while no other container (chest, furnace...) is open, because the server ignores clicks for a menu that is not open.
 */
final class InvUtil {
	static final int MAIN_START = 9;
	static final int MAIN_END = 36; // exclusive
	static final int HOTBAR_START = 36;
	static final int OFFHAND = 45;

	private InvUtil() {
	}

	static boolean canClick(Minecraft mc) {
		return mc.player != null && mc.gameMode != null && mc.player.containerMenu == mc.player.inventoryMenu;
	}

	static ItemStack stack(Minecraft mc, int menuSlot) {
		return mc.player.inventoryMenu.getSlot(menuSlot).getItem();
	}

	/** Swaps the stack in {@code menuSlot} with a hotbar slot (0..8). */
	static void swapWithHotbar(Minecraft mc, int menuSlot, int hotbar) {
		mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, menuSlot, hotbar, ContainerInput.SWAP, mc.player);
	}

	/** Swaps the stack in {@code menuSlot} with the offhand. */
	static void swapWithOffhand(Minecraft mc, int menuSlot) {
		mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, menuSlot, 40, ContainerInput.SWAP, mc.player);
	}

	/** Drops the whole stack of {@code menuSlot}. */
	static void throwStack(Minecraft mc, int menuSlot) {
		mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId, menuSlot, 1, ContainerInput.THROW, mc.player);
	}
}
