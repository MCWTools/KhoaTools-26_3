package khoa.tools.client.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;

import khoa.tools.client.feature.customitemtab.CustomTabs;
import khoa.tools.client.feature.inventorytweak.HotbarRows;
import khoa.tools.client.feature.inventorytweak.InventoryTweakFeature;
import khoa.tools.client.feature.inventorytweak.SaveLoadHotbarScreen;

@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeModeInventoryScreenMixin {
	@Shadow
	protected abstract void selectTab(CreativeModeTab tab);

	@Shadow
	private float scrollOffs;

	private int khoa$left() { return (((Screen) (Object) this).width - 195) / 2; }
	private int khoa$top() { return (((Screen) (Object) this).height - 136) / 2; }

	private static final int KHOA_BTN_W = 58;
	private static final int KHOA_BTN_H = 12;

	private boolean khoa$onHotbarTab() {
		return InventoryTweakFeature.moreHotbars()
			&& CreativeModeInventoryScreenAccessor.khoa$getSelectedTab().getType() == CreativeModeTab.Type.HOTBAR;
	}

	private boolean khoa$overSaveLoad(double mx, double my) {
		int x = khoa$left() + 195 - KHOA_BTN_W - 7, y = khoa$top() + 4;
		return mx >= x && mx < x + KHOA_BTN_W && my >= y && my < y + KHOA_BTN_H;
	}

	/** Small light-grey "Save/Load" button in the Saved Hotbars tab (Inventory Tweak, unlimited saved hotbars). */
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void khoa$saveLoadButton(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (!khoa$onHotbarTab()) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		int x = khoa$left() + 195 - KHOA_BTN_W - 7, y = khoa$top() + 4;
		boolean hover = khoa$overSaveLoad(mouseX, mouseY);
		g.fill(x, y, x + KHOA_BTN_W, y + KHOA_BTN_H, 0xFF000000);
		g.fill(x + 1, y + 1, x + KHOA_BTN_W - 1, y + KHOA_BTN_H - 1, hover ? 0xFFDADADA : 0xFFC6C6C6);
		g.fill(x + 1, y + 1, x + KHOA_BTN_W - 1, y + 2, 0xFFFFFFFF);
		g.fill(x + 1, y + 1, x + 2, y + KHOA_BTN_H - 1, 0xFFFFFFFF);
		g.fill(x + 1, y + KHOA_BTN_H - 2, x + KHOA_BTN_W - 1, y + KHOA_BTN_H - 1, 0xFF555555);
		g.fill(x + KHOA_BTN_W - 2, y + 1, x + KHOA_BTN_W - 1, y + KHOA_BTN_H - 1, 0xFF555555);
		String label = "Save/Load";
		g.text(font, label, x + (KHOA_BTN_W - font.width(label)) / 2, y + 2, 0xFF404040, false);
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void khoa$saveLoadClick(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
		if (khoa$onHotbarTab() && event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT && khoa$overSaveLoad(event.x(), event.y())) {
			Minecraft.getInstance().setScreenAndShow(new SaveLoadHotbarScreen((Screen) (Object) this));
			cir.setReturnValue(true);
		}
	}

	/** Extra hotbar rows (number 10 and up) after the 9 vanilla ones; more are added while you scroll down. */
	@Inject(method = "selectTab", at = @At("TAIL"))
	private void khoa$extraRows(CreativeModeTab tab, CallbackInfo ci) {
		if (InventoryTweakFeature.moreHotbars() && tab.getType() == CreativeModeTab.Type.HOTBAR) {
			CreativeModeInventoryScreen screen = (CreativeModeInventoryScreen) (Object) this;
			CreativeModeInventoryScreen.ItemPickerMenu menu = (CreativeModeInventoryScreen.ItemPickerMenu) screen.getMenu();
			HotbarRows.append(menu.items, 0, HotbarRows.loadedExtra());
			menu.scrollTo(0.0F);
		}
	}

	@Inject(method = "mouseScrolled", at = @At("TAIL"))
	private void khoa$moreOnScroll(double x, double y, double sx, double sy, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
		khoa$loadMore();
	}

	@Inject(method = "mouseDragged", at = @At("TAIL"))
	private void khoa$moreOnDrag(net.minecraft.client.input.MouseButtonEvent e, double dx, double dy, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
		khoa$loadMore();
	}

	/** Near the bottom of the list: load the next 5 hotbars and keep the same rows on screen. */
	private void khoa$loadMore() {
		if (!khoa$onHotbarTab() || scrollOffs < 0.97F) {
			return;
		}
		CreativeModeInventoryScreen screen = (CreativeModeInventoryScreen) (Object) this;
		CreativeModeInventoryScreen.ItemPickerMenu menu = (CreativeModeInventoryScreen.ItemPickerMenu) screen.getMenu();
		int rowsBefore = menu.items.size() / 9;
		int maxBefore = Math.max(1, rowsBefore - 5);
		int topRow = Math.round(scrollOffs * maxBefore);
		int already = HotbarRows.loadedExtra();
		HotbarRows.append(menu.items, already, already + HotbarRows.PAGE);
		HotbarRows.setLoadedExtra(already + HotbarRows.PAGE);
		int maxAfter = Math.max(1, menu.items.size() / 9 - 5);
		scrollOffs = Math.min(1.0F, (float) topRow / maxAfter);
		menu.scrollTo(scrollOffs);
	}

	/**
	 * Edit mode: hold the key and click an item. In one of our tabs it removes the item from that tab, in any other tab it adds
	 * the item to the tab being edited. The click itself is swallowed.
	 */
	@Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
	private void khoa$altClick(Slot slot, int slotId, int button, ContainerInput input, CallbackInfo ci) {
		if (!CustomTabs.isEditing() || !CustomTabs.addKeyDown() || slot == null || !slot.hasItem()) {
			return;
		}
		CreativeModeTab shown = CreativeModeInventoryScreenAccessor.khoa$getSelectedTab();
		if (CustomTabs.isCustomTab(shown)) {
			CustomTabs.removeItem(shown, slot.getItem());
			selectTab(shown); // redraw the grid without the removed item
		} else {
			CustomTabs.addItem(slot.getItem());
		}
		ci.cancel();
	}

	/** Fill one of our tabs with its saved items right before it is shown. */
	@Inject(method = "selectTab", at = @At("HEAD"))
	private void khoa$prepareCustomTab(CreativeModeTab tab, CallbackInfo ci) {
		CustomTabs.prepare(tab);
	}

	/** Reminder above the creative inventory while edit mode is on. It follows the GUI position and scale. */
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void khoa$editReminder(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (!CustomTabs.isEditing()) {
			return;
		}
		Screen screen = (Screen) (Object) this;
		Font font = Minecraft.getInstance().font;
		CreativeModeTab shown = CreativeModeInventoryScreenAccessor.khoa$getSelectedTab();
		String key = CustomTabs.addKeyName();
		String text = CustomTabs.isCustomTab(shown)
			? "EDIT MODE: HOLD " + key + " AND CLICK ON AN ITEM TO REMOVE FROM TAB " + CustomTabs.customTabTitle(shown).toUpperCase(java.util.Locale.ROOT)
			: "EDIT MODE: HOLD " + key + " AND CLICK ON AN ITEM TO ADD TO TAB " + CustomTabs.customTabTitle(CustomTabs.editingTab()).toUpperCase(java.util.Locale.ROOT);
		List<FormattedCharSequence> lines = new ArrayList<>(font.split(Component.literal(text), Math.max(120, screen.width - 8)));
		List<FormattedCharSequence> warn = font.split(Component.literal("You may need to rejoin to apply changes"), Math.max(120, screen.width - 8));
		int top = (screen.height - 136) / 2 - 28; // creative GUI is 136 high; its tab row is 28 above it
		int y = Math.max(2, top - 4 - (lines.size() + warn.size()) * 10);
		for (FormattedCharSequence line : lines) {
			g.text(font, line, (screen.width - font.width(line)) / 2, y, 0xFFFFFFFF);
			y += 10;
		}
		for (FormattedCharSequence line : warn) {
			g.text(font, line, (screen.width - font.width(line)) / 2, y, 0xFFFFC857);
			y += 10;
		}
	}
}
