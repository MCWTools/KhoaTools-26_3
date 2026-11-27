package khoa.tools.client.gui.picker;

import java.util.function.Consumer;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import khoa.api.color.KhoaBlockColor;
import khoa.tools.client.feature.illegal.spawnerfinder.SpawnerLook;
import khoa.tools.client.gui.picker.PickData.Entry;
import khoa.tools.setting.PickAdvanced;

/**
 * "Advanced Setting" of one picked entry: the Y level range it counts in (default: the whole world height of the dimension you are in)
 * and, for features with an overlay, the overlay colour. Nothing is stored until Add / Save; Esc or Cancel changes nothing.
 */
public final class AdvancedPickScreen extends Screen {
	private static final int W = 300;
	private static final int BOX_H = 16;
	private static final int BUTTON_H = 20;
	/** Vanilla limits of a dimension: -2032 .. 2031. */
	private static final int LOWEST_Y = -2032;
	private static final int HIGHEST_Y = 2031;

	private static final int COL_TEXT = 0xFFFFFFFF;
	private static final int COL_DIM = 0xFFAAAAAA;
	private static final int COL_GREEN = 0xFF55FF77;
	private static final int COL_RED = 0xFFFF6655;
	private static final int BG_PANEL = 0xE0141414;
	private static final int BORDER = 0xFF555555;

	private final Screen parent;
	private final Entry entry;
	private final boolean colorEnabled;
	private final boolean isNew;
	private final Consumer<PickAdvanced> onConfirm;

	private final int worldMinY;
	private final int worldMaxY;
	private final int autoRgb;
	private boolean customColor;

	private String minText;
	private String maxText;
	private final String[] rgbText = new String[3];

	private EditBox minBox;
	private EditBox maxBox;
	private final EditBox[] rgbBoxes = new EditBox[3];
	/** True while the boxes are filled by code, so that does not count as choosing a colour. */
	private boolean filling;

	private int left;
	private int top;
	private int height_;
	private int rangeY;
	private int colorY;
	private int buttonY;

	AdvancedPickScreen(Screen parent, Entry entry, PickAdvanced current, boolean colorEnabled, boolean isNew, Consumer<PickAdvanced> onConfirm) {
		super(Component.literal("Advanced Setting"));
		this.parent = parent;
		this.entry = entry;
		this.colorEnabled = colorEnabled;
		this.isNew = isNew;
		this.onConfirm = onConfirm;
		var level = net.minecraft.client.Minecraft.getInstance().level;
		worldMinY = level == null ? -64 : level.getMinY();
		worldMaxY = level == null ? 319 : level.getMaxY();
		autoRgb = autoRgb(entry);
		minText = String.valueOf(current.minY() == null ? worldMinY : current.minY());
		maxText = String.valueOf(current.maxY() == null ? worldMaxY : current.maxY());
		customColor = current.rgb() != null;
		fillRgb(customColor ? current.rgb() : autoRgb);
	}

	/** The colour the overlay has without an override: the mob colour of a spawner card, otherwise the average texture colour of the block. */
	private static int autoRgb(Entry entry) {
		if (entry.hasSwatch()) {
			return entry.swatch() & 0xFFFFFF;
		}
		Identifier id = Identifier.tryParse(PickData.VAULT.equals(entry.kind()) ? "minecraft:vault" : entry.id());
		if (id != null && net.minecraft.client.Minecraft.getInstance().level != null) {
			Block block = BuiltInRegistries.BLOCK.getValue(id);
			if (!block.defaultBlockState().isAir()) {
				return KhoaBlockColor.averageRgb(block.defaultBlockState()) & 0xFFFFFF;
			}
		}
		return 0xFF2020;
	}

	private void fillRgb(int rgb) {
		rgbText[0] = String.valueOf(rgb >> 16 & 255);
		rgbText[1] = String.valueOf(rgb >> 8 & 255);
		rgbText[2] = String.valueOf(rgb & 255);
	}

	@Override
	protected void init() {
		height_ = colorEnabled ? 152 : 112;
		left = (this.width - W) / 2;
		top = Math.max(2, (this.height - height_) / 2);
		rangeY = top + 48;
		colorY = rangeY + 40;
		buttonY = top + height_ - BUTTON_H - 8;

		int x = left + 10 + this.font.width("Y level range:") + 6;
		minBox = box(x, rangeY, 46, 5, true, "Minimum Y", minText, value -> minText = value);
		maxBox = box(x + 46 + this.font.width("to") + 12, rangeY, 46, 5, true, "Maximum Y", maxText, value -> maxText = value);
		if (colorEnabled) {
			int cx = left + 10 + this.font.width("Color:") + 4;
			String[] labels = { "R:", "G:", "B:" };
			for (int i = 0; i < 3; i++) {
				int index = i;
				cx += this.font.width(labels[i]) + 4;
				rgbBoxes[i] = box(cx, colorY, 32, 3, false, labels[i], rgbText[i], value -> {
					rgbText[index] = value;
					if (!filling) {
						customColor = true;
					}
				});
				cx += 32 + 6;
			}
		}
		this.setInitialFocus(minBox);
	}

	/** A number box: digits only (and a leading minus when {@code signed}). */
	private EditBox box(int x, int y, int w, int maxLength, boolean signed, String label, String value, Consumer<String> onChange) {
		EditBox box = new EditBox(this.font, x, y, w, BOX_H, Component.literal(label));
		box.setMaxLength(maxLength);
		box.setValue(value);
		box.setResponder(text -> {
			String clean = text.replaceAll(signed ? "(?!^-)[^0-9]" : "[^0-9]", "");
			if (!clean.equals(text)) {
				box.setValue(clean); // the responder runs again with the clean text
				return;
			}
			onChange.accept(clean);
		});
		return this.addRenderableWidget(box);
	}

	// ------------------------------------------------------------------ values

	/** A Y box: empty = the default of that end. Returns null for text that is not a usable number. */
	private static Integer parseY(String text, int fallback) {
		if (text.isEmpty()) {
			return fallback;
		}
		try {
			int v = Integer.parseInt(text);
			return v < LOWEST_Y || v > HIGHEST_Y ? null : v;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static int channel(String text) {
		try {
			return text.isEmpty() ? 0 : Math.max(0, Math.min(255, Integer.parseInt(text)));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private int shownRgb() {
		return channel(rgbText[0]) << 16 | channel(rgbText[1]) << 8 | channel(rgbText[2]);
	}

	/** Why the values cannot be saved, or null when they can. */
	private String problem() {
		Integer min = parseY(minText, worldMinY);
		Integer max = parseY(maxText, worldMaxY);
		if (min == null || max == null) {
			return "Y must be a number from " + LOWEST_Y + " to " + HIGHEST_Y;
		}
		if (min > max) {
			return "The first Y must not be above the second";
		}
		if (colorEnabled) {
			for (String text : rgbText) {
				if (!text.isEmpty() && Integer.parseInt(text) > 255) {
					return "R, G and B go from 0 to 255";
				}
			}
		}
		return null;
	}

	/** An end left at the world's own limit is stored as "default", so it also covers the whole height in other dimensions. */
	private PickAdvanced value() {
		int min = parseY(minText, worldMinY);
		int max = parseY(maxText, worldMaxY);
		return new PickAdvanced(min == worldMinY ? null : min, max == worldMaxY ? null : max,
			colorEnabled && customColor ? shownRgb() : null);
	}

	private void confirm() {
		if (problem() != null) {
			return;
		}
		onConfirm.accept(value());
		onClose();
	}

	private void reset() {
		filling = true;
		minBox.setValue(String.valueOf(worldMinY));
		maxBox.setValue(String.valueOf(worldMaxY));
		if (colorEnabled) {
			customColor = false;
			rgbBoxes[0].setValue(String.valueOf(autoRgb >> 16 & 255));
			rgbBoxes[1].setValue(String.valueOf(autoRgb >> 8 & 255));
			rgbBoxes[2].setValue(String.valueOf(autoRgb & 255));
		}
		filling = false;
	}

	// ------------------------------------------------------------------ input

	private int buttonX(int index) {
		return left + 10 + index * 96;
	}

	/** Centre of a button (0 = Cancel, 1 = Reset, 2 = Add / Save), for the in-game self test. */
	public double[] buttonCenter(int index) {
		return new double[] { buttonX(index) + 44, buttonY + BUTTON_H / 2.0 };
	}

	/** Types into the boxes as a user would (self test). A null colour part leaves the colour alone. */
	public void type(String min, String max, String r, String g, String b) {
		minBox.setValue(min);
		maxBox.setValue(max);
		if (colorEnabled && r != null) {
			rgbBoxes[0].setValue(r);
			rgbBoxes[1].setValue(g);
			rgbBoxes[2].setValue(b);
		}
	}

	public String values() {
		return minText + " " + maxText + " " + String.join(" ", colorEnabled ? rgbText : new String[0]);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) { // a box took it
			return true;
		}
		this.setFocused((GuiEventListener) null);
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		for (int i = 0; i < 3; i++) {
			if (event.x() >= buttonX(i) && event.x() < buttonX(i) + 88 && event.y() >= buttonY && event.y() < buttonY + BUTTON_H) {
				if (i == 0) {
					onClose();
				} else if (i == 1) {
					reset();
				} else {
					confirm();
				}
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
			confirm();
			return true;
		}
		return super.keyPressed(event); // Esc -> onClose()
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	// ------------------------------------------------------------------ render

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.fill(left, top, left + W, top + height_, BG_PANEL);
		border(g, left, top, left + W, top + height_, BORDER);
		g.centeredText(this.font, this.title.getString(), left + W / 2, top + 8, COL_TEXT);
		if (entry.hasSwatch()) {
			SpawnerLook.drawIcon(g, left + 10, top + 22, 16, PickData.TRIAL.equals(entry.kind()), entry.swatch());
		} else {
			g.item(entry.stack(), left + 10, top + 22);
		}
		g.text(this.font, this.font.plainSubstrByWidth(entry.name(), W - 44), left + 32, top + 26, COL_DIM);

		g.text(this.font, "Y level range:", left + 10, rangeY + 4, COL_TEXT);
		g.text(this.font, "to", minBox.getX() + minBox.getWidth() + 6, rangeY + 4, COL_TEXT);
		g.text(this.font, "World: " + worldMinY + " to " + worldMaxY + " (" + (worldMaxY - worldMinY + 1) + " blocks)", left + 10, rangeY + 22, COL_DIM);

		if (colorEnabled) {
			g.text(this.font, "Color:", left + 10, colorY + 4, COL_TEXT);
			String[] labels = { "R:", "G:", "B:" };
			for (int i = 0; i < 3; i++) {
				g.text(this.font, labels[i], rgbBoxes[i].getX() - this.font.width(labels[i]) - 4, colorY + 4, COL_TEXT);
			}
			int sx = rgbBoxes[2].getX() + rgbBoxes[2].getWidth() + 6;
			g.text(this.font, "|", sx, colorY + 4, COL_DIM);
			sx += this.font.width("|") + 6;
			g.fill(sx, colorY - 1, sx + 18, colorY + 17, COL_TEXT);
			g.fill(sx + 1, colorY, sx + 17, colorY + 16, 0xFF000000 | shownRgb());
			g.text(this.font, customColor ? "Custom colour: replaces the overlay colour" : "Automatic colour (type to override)", left + 10, colorY + 22,
				customColor ? COL_GREEN : COL_DIM);
		}

		String problem = problem();
		if (problem != null) {
			g.centeredText(this.font, problem, left + W / 2, top + height_ + 4, COL_RED);
		}
		String[] texts = { "Cancel", "Reset", isNew ? "Add" : "Save" };
		for (int i = 0; i < 3; i++) {
			int x = buttonX(i);
			boolean enabled = i != 2 || problem == null;
			boolean hover = enabled && mouseX >= x && mouseX < x + 88 && mouseY >= buttonY && mouseY < buttonY + BUTTON_H;
			g.fill(x, buttonY, x + 88, buttonY + BUTTON_H, hover ? 0xC0505050 : 0xA0262626);
			border(g, x, buttonY, x + 88, buttonY + BUTTON_H, i == 2 && enabled ? COL_GREEN : (hover ? COL_TEXT : BORDER));
			g.centeredText(this.font, texts[i], x + 44, buttonY + 6, enabled ? COL_TEXT : COL_DIM);
		}
		super.extractRenderState(g, mouseX, mouseY, partialTick); // the boxes
	}

	private static void border(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
		g.fill(x1, y1, x2, y1 + 1, color);
		g.fill(x1, y2 - 1, x2, y2, color);
		g.fill(x1, y1, x1 + 1, y2, color);
		g.fill(x2 - 1, y1, x2, y2, color);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, this.width, this.height, 0xC0101010);
	}

	@Override
	public void extractTransparentBackground(GuiGraphicsExtractor graphics) {
	}

	@Override
	protected void extractBlurredBackground(GuiGraphicsExtractor graphics) {
	}

	@Override
	protected void extractMenuBackground(GuiGraphicsExtractor graphics) {
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return true;
	}
}
