package khoa.tools.client.feature.inventorytweak;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;

import khoa.tools.client.mixin.CreativeModeInventoryScreenAccessor;

/**
 * Small vanilla-looking box over the creative inventory: type a hotbar number (1 and up), then Save or Load.
 * 1-9 are the vanilla saved hotbars, 10 and up are the extra ones. Esc goes back to the creative inventory.
 */
public final class SaveLoadHotbarScreen extends Screen {
	private static final int W = 156, H = 66;
	private static String lastNumber = "10";

	private final Screen parent;
	private EditBox number;
	private Component message = Component.empty();
	private long messageUntil;

	public SaveLoadHotbarScreen(Screen parent) {
		super(Component.literal("Save / Load hotbar"));
		this.parent = parent;
	}

	@Override public boolean isPauseScreen() { return false; }
	@Override public void onClose() { minecraft.setScreenAndShow(parent); }

	private int px() { return (width - W) / 2; }
	private int py() { return (height - H) / 2; }

	@Override
	protected void init() {
		int x = px(), y = py();
		number = new EditBox(font, x + 8, y + 22, 40, 18, Component.literal("Hotbar number"));
		number.setMaxLength(6);
		number.setValue(lastNumber);
		number.setResponder(v -> {
			String digits = v.replaceAll("[^0-9]", "");
			if (!digits.equals(v)) {
				number.setValue(digits);
			} else {
				lastNumber = digits;
			}
		});
		addRenderableWidget(number);
		setInitialFocus(number);
		addRenderableWidget(Button.builder(Component.literal("Save"), b -> act(true)).bounds(x + 54, y + 22, 44, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Load"), b -> act(false)).bounds(x + 104, y + 22, 44, 18).build());
	}

	private int parsed() {
		try {
			return Integer.parseInt(number.getValue());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private void say(String s) {
		message = Component.literal(s);
		messageUntil = System.currentTimeMillis() + 3000;
	}

	private void act(boolean save) {
		int n = parsed();
		if (n < 1) {
			say("Type a number, 1 or more");
			return;
		}
		if (save) {
			HotbarRows.saveCurrent(n);
			say("Saved your hotbar as " + n);
		} else if (HotbarRows.isEmpty(n)) {
			say("Hotbar " + n + " is empty");
		} else if (HotbarRows.loadInto(n)) {
			say("Loaded hotbar " + n);
		} else {
			say("Load needs creative mode");
		}
		if (parent instanceof CreativeModeInventoryScreen creative) {
			// refresh the Saved Hotbars rows behind this box
			CreativeModeTab tab = CreativeModeInventoryScreenAccessor.khoa$getSelectedTab();
			((CreativeModeInventoryScreenAccessor) creative).khoa$selectTab(tab);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
		parent.extractRenderState(g, -1, -1, dt);
		int x = px(), y = py();
		// vanilla panel: black outline, light grey body, white top/left, dark grey bottom/right
		g.fill(x, y, x + W, y + H, 0xFF000000);
		g.fill(x + 1, y + 1, x + W - 1, y + H - 1, 0xFFC6C6C6);
		g.fill(x + 1, y + 1, x + W - 1, y + 2, 0xFFFFFFFF);
		g.fill(x + 1, y + 1, x + 2, y + H - 1, 0xFFFFFFFF);
		g.fill(x + 1, y + H - 2, x + W - 1, y + H - 1, 0xFF555555);
		g.fill(x + W - 2, y + 1, x + W - 1, y + H - 1, 0xFF555555);
		g.text(font, "Hotbar number", x + 8, y + 8, 0xFF404040, false);
		super.extractRenderState(g, mx, my, dt); // widgets
		String hint = System.currentTimeMillis() < messageUntil ? message.getString() : "1-9 vanilla, 10+ extra";
		g.text(font, hint, x + 8, y + 48, System.currentTimeMillis() < messageUntil ? 0xFF1E6B1E : 0xFF555555, false);
	}
}
