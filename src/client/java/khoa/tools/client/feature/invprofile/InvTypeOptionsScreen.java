package khoa.tools.client.feature.invprofile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** The dropdowns of one kind of item (for example the Sword: Knockback, damage enchantment, Fire Aspect). They are preferences, not requirements. */
final class InvTypeOptionsScreen extends Screen {
	private static final int PANEL_W = 320;
	private static final int ROW_H = 28;
	private static final int DROP_W = 180;
	private static final int ITEM_H = 16;

	private final Screen editor;
	private final InvProfile profile;
	private final int index;
	private final ProfileType type;
	private final String slotName;
	private final Map<String, Integer> values = new HashMap<>();
	private Button[] buttons;
	private int open = -1;
	private int left;
	private int top;

	InvTypeOptionsScreen(Screen editor, InvProfile profile, int index, ProfileType type, String slotName) {
		super(Component.literal(type.label() + " options"));
		this.editor = editor;
		this.profile = profile;
		this.index = index;
		this.type = type;
		this.slotName = slotName;
		if (InvProfile.typeOf(profile.slots[index]) == type) {
			values.putAll(InvProfile.prefsOf(profile.slots[index]));
		}
	}

	@Override
	protected void init() {
		left = (this.width - PANEL_W) / 2;
		top = 56;
		List<ProfileType.Option> options = type.options();
		buttons = new Button[options.size()];
		for (int i = 0; i < options.size(); i++) {
			int row = i;
			buttons[i] = this.addRenderableWidget(Button.builder(Component.empty(), b -> open = open == row ? -1 : row)
				.bounds(left + PANEL_W - DROP_W, top + i * ROW_H, DROP_W, 20).build());
		}
		refreshLabels();
		int by = top + options.size() * ROW_H + 10;
		int half = (PANEL_W - 4) / 2;
		this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> {
			profile.slots[index] = InvProfile.typeEntry(type, values);
			InvProfileStore.save();
			onClose();
		}).bounds(left, by, half, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
			.bounds(left + half + 4, by, PANEL_W - half - 4, 20).build());
	}

	private void refreshLabels() {
		List<ProfileType.Option> options = type.options();
		for (int i = 0; i < options.size(); i++) {
			ProfileType.Option option = options.get(i);
			int value = Math.min(values.getOrDefault(option.key(), 0), option.values().size() - 1);
			buttons[i].setMessage(Component.literal(option.values().get(value) + "  v"));
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.item(type.iconStack(), left, 14);
		g.text(this.font, type.label() + " for " + slotName, left + 22, 18, 0xFFFFFFFF);
		g.text(this.font, "These are preferences: an item that has them is chosen first, otherwise the best one.", left, 38, 0xFFAAAAAA, false);
		List<ProfileType.Option> options = type.options();
		for (int i = 0; i < options.size(); i++) {
			g.text(this.font, options.get(i).label(), left, top + i * ROW_H + 6, 0xFFFFFFFF);
		}
		if (open >= 0) {
			ProfileType.Option option = options.get(open);
			int x = left + PANEL_W - DROP_W;
			int y = top + open * ROW_H + 21;
			int h = option.values().size() * ITEM_H + 2;
			g.fill(x, y, x + DROP_W, y + h, 0xF0101010);
			g.fill(x, y, x + DROP_W, y + 1, 0xFFAAAAAA);
			g.fill(x, y + h - 1, x + DROP_W, y + h, 0xFFAAAAAA);
			g.fill(x, y, x + 1, y + h, 0xFFAAAAAA);
			g.fill(x + DROP_W - 1, y, x + DROP_W, y + h, 0xFFAAAAAA);
			int current = values.getOrDefault(option.key(), 0);
			for (int k = 0; k < option.values().size(); k++) {
				int iy = y + 1 + k * ITEM_H;
				boolean hover = mouseX >= x + 1 && mouseX < x + DROP_W - 1 && mouseY >= iy && mouseY < iy + ITEM_H;
				if (hover) {
					g.fill(x + 1, iy, x + DROP_W - 1, iy + ITEM_H, 0xFF404040);
				}
				g.text(this.font, option.values().get(k), x + 6, iy + 4, k == current ? 0xFF77DD77 : 0xFFFFFFFF, false);
			}
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (open >= 0) {
			ProfileType.Option option = type.options().get(open);
			int x = left + PANEL_W - DROP_W;
			int y = top + open * ROW_H + 21;
			double mx = event.x();
			double my = event.y();
			int chosen = -1;
			if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && mx >= x && mx < x + DROP_W && my >= y + 1 && my < y + 1 + option.values().size() * ITEM_H) {
				chosen = (int) ((my - (y + 1)) / ITEM_H);
			}
			if (chosen >= 0) {
				values.put(option.key(), chosen);
				refreshLabels();
			}
			open = -1;
			return true; // the click only closes the list (or picks a value)
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(editor);
	}
}
