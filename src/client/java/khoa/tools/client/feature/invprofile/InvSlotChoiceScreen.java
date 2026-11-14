package khoa.tools.client.feature.invprofile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.equipment.Equippable;

import khoa.tools.client.gui.picker.ItemPickerScreen;
import khoa.tools.setting.Setting;

/**
 * What goes into one slot of a profile: a kind of item by name (the best Sword, Pickaxe ... you have, with preferences) or one exact item
 * from the item picker. An armor slot only offers the kinds and the items that can be worn there.
 */
final class InvSlotChoiceScreen extends Screen {
	private static final int CARD_W = 100;
	private static final int CARD_H = 24;
	private static final int COLS = 4;

	private final Screen editor;
	private final InvProfile profile;
	private final int index;
	private final String slotName;
	private final List<ProfileType> types = new ArrayList<>();
	private int gridX;
	private int gridY;

	InvSlotChoiceScreen(Screen editor, InvProfile profile, int index, String slotName) {
		super(Component.literal("Choose the slot content"));
		this.editor = editor;
		this.profile = profile;
		this.index = index;
		this.slotName = slotName;
		switch (index) {
			case 0 -> types.add(ProfileType.HELMET);
			case 1 -> types.addAll(List.of(ProfileType.CHESTPLATE, ProfileType.ELYTRA));
			case 2 -> types.add(ProfileType.LEGGINGS);
			case 3 -> types.add(ProfileType.BOOTS);
			default -> types.addAll(List.of(ProfileType.values()));
		}
	}

	/** The equipment slot of an armor slot of the profile, or null for the other slots (they take anything). */
	private EquipmentSlot wornSlot() {
		return switch (index) {
			case 0 -> EquipmentSlot.HEAD;
			case 1 -> EquipmentSlot.CHEST;
			case 2 -> EquipmentSlot.LEGS;
			case 3 -> EquipmentSlot.FEET;
			default -> null;
		};
	}

	@Override
	protected void init() {
		int gridW = COLS * (CARD_W + 4) - 4;
		gridX = (this.width - gridW) / 2;
		gridY = 50;
		int rows = (types.size() + COLS - 1) / COLS;
		int by = gridY + rows * (CARD_H + 4) + 8;
		int w = (gridW - 8) / 3;
		this.addRenderableWidget(Button.builder(Component.literal("Specific item..."), b -> openPicker())
			.bounds(gridX, by, w, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Clear slot"), b -> {
			set("");
			onClose();
		}).bounds(gridX + w + 4, by, w, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
			.bounds(gridX + (w + 4) * 2, by, gridW - (w + 4) * 2, 20).build());
	}

	private void set(String entry) {
		profile.slots[index] = entry;
		InvProfileStore.save();
	}

	private void openPicker() {
		EquipmentSlot worn = wornSlot();
		Setting setting = Setting.itemList("slot", "Item for " + slotName, "Double click the one item that goes into this slot.", List.of("item"),
			() -> profile.slots[index].startsWith("item:") ? List.of(profile.slots[index]) : List.of(),
			list -> {
				String chosen = "";
				for (String key : list) {
					if (key.startsWith("item:")) {
						chosen = key; // the last one picked wins
					}
				}
				set(chosen);
			}).maxPicks(1);
		if (worn != null) {
			setting.stackFilter(stack -> {
				Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
				return equippable != null && equippable.slot() == worn;
			});
		}
		this.minecraft.gui.setScreen(new ItemPickerScreen(editor, setting));
	}

	private int cardAt(double mx, double my) {
		for (int i = 0; i < types.size(); i++) {
			int x = gridX + (i % COLS) * (CARD_W + 4);
			int y = gridY + (i / COLS) * (CARD_H + 4);
			if (mx >= x && mx < x + CARD_W && my >= y && my < y + CARD_H) {
				return i;
			}
		}
		return -1;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.text(this.font, slotName, gridX, 14, 0xFFFFFFFF);
		g.text(this.font, "Pick a kind of item (the best one you have is used), or one exact item.", gridX, 28, 0xFFAAAAAA, false);
		int hovered = cardAt(mouseX, mouseY);
		ProfileType current = InvProfile.typeOf(profile.slots[index]);
		for (int i = 0; i < types.size(); i++) {
			int x = gridX + (i % COLS) * (CARD_W + 4);
			int y = gridY + (i / COLS) * (CARD_H + 4);
			boolean selected = types.get(i) == current;
			g.fill(x, y, x + CARD_W, y + CARD_H, selected ? 0xA02E6E9E : i == hovered ? 0xB0383838 : 0x90202020);
			g.item(types.get(i).iconStack(), x + 4, y + 4);
			g.text(this.font, types.get(i).label(), x + 24, y + 8, 0xFFFFFFFF, false);
			if (!types.get(i).options().isEmpty()) {
				g.text(this.font, "...", x + CARD_W - 14, y + 8, 0xFFAAAAAA, false); // opens preferences
			}
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		int card = cardAt(event.x(), event.y());
		if (card < 0) {
			return false;
		}
		ProfileType type = types.get(card);
		if (type.options().isEmpty()) {
			set(InvProfile.typeEntry(type, Map.of()));
			onClose();
		} else {
			this.minecraft.gui.setScreen(new InvTypeOptionsScreen(editor, profile, index, type, slotName));
		}
		return true;
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(editor);
	}
}
