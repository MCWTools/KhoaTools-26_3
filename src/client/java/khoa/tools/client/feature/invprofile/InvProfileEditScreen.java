package khoa.tools.client.feature.invprofile;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Edits one profile on a chest-like grid of 9 x 5: row 1 = helmet, chestplate, leggings, boots and (in the middle) the offhand,
 * row 2 = hotbar, rows 3 to 5 = the 27 inventory slots. Left click a slot to choose what goes there, right click to clear it.
 */
final class InvProfileEditScreen extends Screen {
	private static final int CELL = 22;
	private static final int GAP = 8;
	private static final int COL_TEXT = 0xFFFFFFFF;
	private static final int COL_DIM = 0xFFAAAAAA;
	private static final ProfileType[] PLACEHOLDERS = { ProfileType.HELMET, ProfileType.CHESTPLATE, ProfileType.LEGGINGS, ProfileType.BOOTS, ProfileType.SHIELD };

	private final Screen parent;
	private final InvProfile profile;
	private EditBox nameBox;
	private int gridX;
	private int gridY;

	InvProfileEditScreen(Screen parent, InvProfile profile) {
		super(Component.literal("Edit inventory profile"));
		this.parent = parent;
		this.profile = profile;
	}

	@Override
	protected void init() {
		int gridW = 9 * CELL;
		gridX = (this.width - gridW) / 2;
		gridY = 62;
		nameBox = new EditBox(this.font, gridX, 24, gridW, 18, Component.literal("Name"));
		nameBox.setMaxLength(32);
		nameBox.setValue(profile.name);
		nameBox.setResponder(v -> {
			profile.name = v.isBlank() ? "Profile" : v;
			InvProfileStore.save();
		});
		this.addRenderableWidget(nameBox);
		int by = gridY + 5 * CELL + 2 * GAP + 8;
		int half = (gridW - 4) / 2;
		this.addRenderableWidget(Button.builder(Component.literal("Load this profile"), b -> {
			this.minecraft.gui.setScreen(null);
			InvProfileLoader.start(this.minecraft, profile);
		}).bounds(gridX, by, half, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
			.bounds(gridX + half + 4, by, gridW - half - 4, 20).build());
	}

	/** Position of grid cell (row, col) -> profile slot index, or -1 for the unused cells of the first row. */
	private static int indexAt(int row, int col) {
		return switch (row) {
			case 0 -> col <= 4 ? col : -1; // 0..3 armor, 4 offhand
			case 1 -> 5 + col; // hotbar
			default -> 14 + (row - 2) * 9 + col; // main inventory
		};
	}

	private int cellX(int col) {
		return gridX + col * CELL;
	}

	private int cellY(int row) {
		return gridY + row * CELL + (row >= 1 ? GAP : 0) + (row >= 2 ? GAP : 0);
	}

	private int slotAt(double mx, double my) {
		for (int row = 0; row < 5; row++) {
			for (int col = 0; col < 9; col++) {
				int index = indexAt(row, col);
				if (index >= 0 && mx >= cellX(col) && mx < cellX(col) + CELL - 2 && my >= cellY(row) && my < cellY(row) + CELL - 2) {
					return index;
				}
			}
		}
		return -1;
	}

	private static String slotName(int index) {
		if (index < 4) {
			return new String[] { "Helmet", "Chestplate", "Leggings", "Boots" }[index] + " slot";
		}
		if (index == InvProfile.OFFHAND) {
			return "Offhand";
		}
		if (index < 14) {
			return "Hotbar " + (index - 4);
		}
		return "Inventory slot " + (index - 13);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.text(this.font, "Name", gridX, 12, COL_DIM);
		int hovered = slotAt(mouseX, mouseY);
		List<Component> tooltip = null;
		for (int row = 0; row < 5; row++) {
			for (int col = 0; col < 9; col++) {
				int index = indexAt(row, col);
				if (index < 0) {
					continue;
				}
				int x = cellX(col);
				int y = cellY(row);
				boolean over = index == hovered;
				g.fill(x, y, x + CELL - 2, y + CELL - 2, over ? 0xFF6A6A6A : 0xFF3A3A3A);
				g.fill(x + 1, y + 1, x + CELL - 3, y + CELL - 3, 0xFF1E1E1E);
				InvProfile.Request request = InvProfile.parse(profile.slots[index]);
				if (request == null) {
					if (row == 0) { // a faded picture of what this slot holds
						g.item(PLACEHOLDERS[col].iconStack(), x + 1, y + 1);
						g.fill(x + 1, y + 1, x + CELL - 3, y + CELL - 3, 0xB01E1E1E);
					}
				} else {
					ItemStack icon = request.type() != null ? request.type().iconStack() : new ItemStack(request.item());
					g.item(icon, x + 1, y + 1);
					if (request.type() != null) {
						g.text(this.font, "*", x + CELL - 9, y + 1, 0xFFFFD755, true); // a kind of item, not one item
					}
				}
				if (row == 1) { // hotbar slot number, the same as the number keys
					g.text(this.font, Integer.toString(col + 1), x + 2, y + 2, 0xFFFFFFFF, true);
				}
				if (over) {
					List<Component> lines = new ArrayList<>();
					lines.add(Component.literal(slotName(index)));
					lines.add(Component.literal(request == null ? "Empty: left alone when loading"
						: request.type() != null ? "Best " + request.describe() + " you have" : request.label()));
					lines.add(Component.literal("Left click: choose, right click: clear"));
					tooltip = lines;
				}
			}
		}
		g.text(this.font, "Armor and offhand", gridX + 5 * CELL + 4, cellY(0) + 6, COL_DIM, false);
		g.text(this.font, "Hotbar", gridX, cellY(1) - 9, COL_DIM, false);
		g.text(this.font, "Inventory", gridX, cellY(2) - 9, COL_DIM, false);
		if (tooltip != null) {
			g.setComponentTooltipForNextFrame(this.font, tooltip, mouseX, mouseY);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		int index = slotAt(event.x(), event.y());
		if (index < 0) {
			return false;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			this.minecraft.gui.setScreen(new InvSlotChoiceScreen(this, profile, index, slotName(index)));
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
			profile.slots[index] = "";
			InvProfileStore.save();
			return true;
		}
		return false;
	}

	@Override
	public void onClose() {
		InvProfileStore.save();
		this.minecraft.gui.setScreen(parent);
	}
}
