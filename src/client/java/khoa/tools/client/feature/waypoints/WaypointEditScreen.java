package khoa.tools.client.feature.waypoints;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Create / edit one waypoint (same layout for both). Mode Single = one block; Mode Area = any number of boxes and blocks drawn in the area editor
 * ({@link AreaEditSession}: the screen hides, you walk around holding right click). Create starts on the block you aimed at (or stand on)
 * with the colour of that block; the colour can be changed with R/G/B sliders, a hex code or the 16 Xaero colours.
 */
final class WaypointEditScreen extends Screen {
	private static final int BAR_H = 20;
	private static final String[] MODES = { "Single", "Area" };
	private static final String[] CHANNELS = { "R", "G", "B" };
	private static final int[] CHANNEL_COLORS = { 0xFFE05050, 0xFF50D060, 0xFF5080F0 };
	private static final int PANEL_H = 204;

	private final Screen parent;
	private final Waypoint editing;

	private String name = "";
	private boolean area;
	private int px;
	private int py;
	private int pz;
	/** Regions of the area, each {minX, minY, minZ, maxX, maxY, maxZ}; drawn in the area editor. */
	private List<int[]> regions = new ArrayList<>();
	private int rgb;

	private EditBox nameBox;
	private EditBox hexBox;
	private EditBox[] cornerOne = new EditBox[3];
	private boolean syncing;
	private boolean dropdownOpen;
	private int drag = -1;

	private int left;
	private int right;
	private int leftW;
	private int rightW;
	private int top;

	WaypointEditScreen(Screen parent, Waypoint editing) {
		super(Component.literal(editing == null ? "Create waypoint" : "Edit waypoint"));
		this.parent = parent;
		this.editing = editing;
		Minecraft mc = Minecraft.getInstance();
		if (editing != null) {
			name = editing.name;
			area = editing.isArea();
			px = editing.x;
			py = editing.y;
			pz = editing.z;
			if (area) {
				regions = editing.regions();
			}
			rgb = editing.rgb;
		} else {
			BlockPos start = WaypointsFeature.aimed != null ? WaypointsFeature.aimed : WaypointsFeature.standingBlock(mc);
			px = start.getX();
			py = start.getY();
			pz = start.getZ();
			rgb = WaypointsFeature.autoColor(mc, start);
		}
	}

	private boolean isDeath() {
		return editing != null && editing.death;
	}

	// ------------------------------------------------------------------ layout

	@Override
	protected void init() {
		int avail = this.width - 24;
		leftW = Math.min(300, Math.max(240, avail * 55 / 100));
		rightW = Math.max(180, Math.min(230, avail - leftW - 16));
		left = (this.width - (leftW + 16 + rightW)) / 2;
		right = left + leftW + 16;
		top = Math.max(6, (this.height - PANEL_H) / 2);

		nameBox = new EditBox(this.font, left, top + 30, leftW, BAR_H, Component.literal("Name"));
		nameBox.setMaxLength(32);
		nameBox.setHint(Component.literal("Waypoint name"));
		if (isDeath()) {
			nameBox.setValue(editing.title());
			nameBox.setEditable(false);
		} else {
			nameBox.setValue(name);
			nameBox.setResponder(v -> name = v);
		}
		this.addRenderableWidget(nameBox);

		if (area) {
			this.addRenderableWidget(Button.builder(Component.literal("Edit area  (" + regions.size() + (regions.size() == 1 ? " region)" : " regions)")), b -> startAreaEditor())
				.bounds(left, top + 100, leftW, BAR_H).build());
		} else {
			cornerOne = coordRow(top + 100);
			int half = (leftW - 4) / 2;
			this.addRenderableWidget(Button.builder(Component.literal("Aimed block"), b -> {
				BlockPos p = WaypointsFeature.aimed;
				if (p != null) {
					setPosition(p);
				}
			}).bounds(left, top + 128, half, BAR_H).build()).active = WaypointsFeature.aimed != null;
			this.addRenderableWidget(Button.builder(Component.literal("Standing block"), b -> setPosition(WaypointsFeature.standingBlock(Minecraft.getInstance())))
				.bounds(left + half + 4, top + 128, leftW - half - 4, BAR_H).build());
		}

		hexBox = new EditBox(this.font, right + 14, top + 76, 62, BAR_H, Component.literal("Hex"));
		hexBox.setMaxLength(6);
		hexBox.setValue(String.format("%06X", rgb));
		hexBox.setResponder(v -> {
			if (syncing || v.length() != 6) {
				return;
			}
			try {
				rgb = Integer.parseInt(v, 16);
			} catch (NumberFormatException ignored) {
				// keep the old colour while the text is not a valid hex code
			}
		});
		this.addRenderableWidget(hexBox);
		this.addRenderableWidget(Button.builder(Component.literal("Block colour"), b -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level != null) {
				setRgb(WaypointsFeature.autoColor(mc, new BlockPos(px, py, pz)));
			}
		}).bounds(right + 82, top + 76, 84, BAR_H).build());

		int half = (leftW + 16 + rightW - 4) / 2;
		this.addRenderableWidget(Button.builder(Component.literal(editing == null ? "Create" : "Save"), b -> confirm())
			.bounds(left, top + 176, half, BAR_H).build());
		this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
			.bounds(left + half + 4, top + 176, leftW + 16 + rightW - half - 4, BAR_H).build());
	}

	/** X Y Z boxes of the single position. */
	private EditBox[] coordRow(int y) {
		int boxW = (leftW - 3 * 12 - 2 * 4) / 3;
		int[] values = { px, py, pz };
		String[] axes = { "X", "Y", "Z" };
		EditBox[] boxes = new EditBox[3];
		int x = left;
		for (int i = 0; i < 3; i++) {
			final int axis = i;
			// Pasting "123 12 52" (3 numbers) into any of the boxes fills X, Y and Z; "123 52" (2 numbers) sets X and Z only.
			EditBox box = new EditBox(this.font, x + 12, y, boxW, BAR_H, Component.literal(axes[i])) {
				@Override
				public void insertText(String text) {
					if (!pasteCoordinates(text)) {
						super.insertText(text);
					}
				}
			};
			box.setMaxLength(9);
			box.setValue(Integer.toString(values[i]));
			box.setResponder(v -> {
				if (syncing) {
					return;
				}
				try {
					int value = Integer.parseInt(v.trim());
					if (axis == 0) {
						px = value;
					} else if (axis == 1) {
						py = value;
					} else {
						pz = value;
					}
				} catch (NumberFormatException ignored) {
					// keep the old number while the text is not valid
				}
			});
			boxes[i] = this.addRenderableWidget(box);
			x += 12 + boxW + 4;
		}
		return boxes;
	}

	/** True when the text was 2 or 3 numbers and went into the X Y Z boxes. */
	private boolean pasteCoordinates(String text) {
		int[] v = khoa.api.chat.ChatCoordinates.parse(text);
		if (v == null || cornerOne[0] == null) {
			return false;
		}
		if (v.length == 3) {
			px = v[0];
			py = v[1];
			pz = v[2];
		} else {
			px = v[0];
			pz = v[1];
		}
		syncing = true;
		cornerOne[0].setValue(Integer.toString(px));
		cornerOne[1].setValue(Integer.toString(py));
		cornerOne[2].setValue(Integer.toString(pz));
		syncing = false;
		return true;
	}

	private void setPosition(BlockPos p) {
		px = p.getX();
		py = p.getY();
		pz = p.getZ();
		syncing = true;
		if (cornerOne[0] != null) {
			cornerOne[0].setValue(Integer.toString(px));
			cornerOne[1].setValue(Integer.toString(py));
			cornerOne[2].setValue(Integer.toString(pz));
		}
		syncing = false;
	}

	private void startAreaEditor() {
		AreaEditSession.begin(this, editing == null ? null : editing.id, name.isBlank() ? "W" : name, rgb, regions);
	}

	/** The regions drawn in the area editor (Enter pressed). */
	void applyRegions(List<int[]> result) {
		regions = new ArrayList<>(result);
	}

	private void setArea(boolean value) {
		if (area == value || isDeath()) {
			return;
		}
		area = value;
		if (area && regions.isEmpty()) {
			regions.add(new int[] { px, py, pz, px, py, pz }); // start from the block of the single position
		}
		this.rebuildWidgets();
	}

	private void setRgb(int value) {
		rgb = value & 0xFFFFFF;
		syncing = true;
		hexBox.setValue(String.format("%06X", rgb));
		syncing = false;
	}

	private void confirm() {
		Minecraft mc = this.minecraft;
		long now = System.currentTimeMillis();
		Waypoint w = editing;
		if (w == null) {
			w = new Waypoint();
			w.dim = WaypointsFeature.dimension(mc);
			w.created = now;
		} else if (!w.death) {
			w.name = cleanName();
		}
		if (editing == null) {
			w.name = cleanName();
		}
		if (area && !w.death && !regions.isEmpty()) {
			w.setRegions(regions);
		} else {
			w.setRegions(List.of(new int[] { px, py, pz, px, py, pz }));
		}
		w.rgb = rgb;
		w.modified = now;
		if (editing == null) {
			WaypointStore.add(mc, w);
			if (parent instanceof WaypointScreen list) {
				list.created(w);
			}
		} else {
			WaypointStore.saveNow();
		}
		onClose();
	}

	private String cleanName() {
		String n = name.trim();
		return n.isEmpty() ? "Waypoint" : n;
	}

	// ------------------------------------------------------------------ sliders / palette

	private int channelValue(int channel) {
		return (rgb >> (16 - 8 * channel)) & 0xFF;
	}

	private void setChannel(int channel, int value) {
		int shift = 16 - 8 * channel;
		setRgb((rgb & ~(0xFF << shift)) | (Math.max(0, Math.min(255, value)) << shift));
	}

	private int sliderX() {
		return right + 14;
	}

	private int sliderW() {
		return rightW - 14 - 30;
	}

	private int sliderY(int channel) {
		return top + 24 + channel * 16;
	}

	private int paletteCell() {
		return rightW / 16;
	}

	private int paletteX(int index) {
		return right + index * paletteCell();
	}

	private int paletteY() {
		return top + 108;
	}

	private int sliderAt(double mx, double my) {
		for (int c = 0; c < 3; c++) {
			if (mx >= sliderX() - 2 && mx <= sliderX() + sliderW() + 2 && my >= sliderY(c) - 2 && my <= sliderY(c) + 12) {
				return c;
			}
		}
		return -1;
	}

	private void dragTo(int channel, double mx) {
		double t = (mx - sliderX()) / sliderW();
		setChannel(channel, (int) Math.round(Math.max(0, Math.min(1, t)) * 255));
	}

	private int dropdownX() {
		return left;
	}

	private int dropdownY() {
		return top + 68;
	}

	private static final int DROPDOWN_W = 140;

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && !isDeath()) {
			if (dropdownOpen) {
				dropdownOpen = false;
				for (int i = 0; i < MODES.length; i++) {
					int oy = dropdownY() + BAR_H + i * BAR_H;
					if (mx >= dropdownX() && mx < dropdownX() + DROPDOWN_W && my >= oy && my < oy + BAR_H) {
						setArea(i == 1);
					}
				}
				return true; // the click only closes the list / picks a mode
			}
			if (mx >= dropdownX() && mx < dropdownX() + DROPDOWN_W && my >= dropdownY() && my < dropdownY() + BAR_H) {
				dropdownOpen = true;
				return true;
			}
		}
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		int channel = sliderAt(mx, my);
		if (channel >= 0) {
			drag = channel;
			dragTo(channel, mx);
			return true;
		}
		if (my >= paletteY() && my < paletteY() + 14 && mx >= right) {
			int index = (int) ((mx - right) / paletteCell());
			if (index >= 0 && index < 16) {
				setRgb(WaypointBadge.XAERO[index]);
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (drag >= 0) {
			dragTo(drag, event.x());
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		drag = -1;
		return super.mouseReleased(event);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	// ------------------------------------------------------------------ render

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.fill(left - 8, top - 4, right + rightW + 8, top + PANEL_H, 0xA0101010);
		g.text(this.font, this.title.getString(), left, top + 6, 0xFFFFFFFF);
		g.text(this.font, "Name", left, top + 19, 0xFFAAAAAA);
		if (isDeath()) {
			g.text(this.font, "Death point: skull icon, name is automatic", left + 36, top + 19, 0xFF777777);
		}
		g.text(this.font, "Mode", left, top + 57, 0xFFAAAAAA);
		if (area) {
			g.text(this.font, "Area (drawn in the area editor)", left, top + 89, 0xFFAAAAAA);
			drawAreaSummary(g, top + 126);
		} else {
			g.text(this.font, "Position (block)", left, top + 89, 0xFFAAAAAA);
			drawAxisLabels(g, top + 106);
		}

		g.text(this.font, "Color", right, top + 8, 0xFFAAAAAA);
		for (int c = 0; c < 3; c++) {
			int y = sliderY(c);
			int value = channelValue(c);
			g.text(this.font, CHANNELS[c], right + 2, y + 1, CHANNEL_COLORS[c]);
			g.fill(sliderX(), y + 3, sliderX() + sliderW(), y + 7, 0xFF202020);
			int knob = sliderX() + (int) Math.round(sliderW() * value / 255.0);
			g.fill(sliderX(), y + 3, knob, y + 7, CHANNEL_COLORS[c]);
			g.fill(knob - 2, y, knob + 2, y + 10, 0xFFFFFFFF);
			String text = Integer.toString(value);
			g.text(this.font, text, right + rightW - this.font.width(text), y + 1, 0xFFFFFFFF);
		}
		g.text(this.font, "#", right + 4, top + 82, 0xFFAAAAAA);

		// preview: the board arrow and the filled block
		String letter = isDeath() ? null : letterOf(name);
		WaypointBadge.draw(g, this.font, right + rightW - 20, top + 70, 1.3F, rgb, letter);
		g.fill(right + rightW - 62, top + 46, right + rightW - 40, top + 68, 0xFF000000);
		g.fill(right + rightW - 61, top + 47, right + rightW - 41, top + 67, 0xFF000000 | rgb);

		int hoverIndex = -1;
		for (int i = 0; i < 16; i++) {
			int x = paletteX(i);
			int cell = paletteCell();
			g.fill(x, paletteY(), x + cell - 2, paletteY() + 14, 0xFF000000 | WaypointBadge.XAERO[i]);
			boolean current = (rgb & 0xFFFFFF) == WaypointBadge.XAERO[i];
			boolean hover = mouseX >= x && mouseX < x + cell - 2 && mouseY >= paletteY() && mouseY < paletteY() + 14;
			if (hover) {
				hoverIndex = i;
			}
			if (current || hover) {
				g.fill(x, paletteY() - 1, x + cell - 2, paletteY(), 0xFFFFFFFF);
				g.fill(x, paletteY() + 14, x + cell - 2, paletteY() + 15, 0xFFFFFFFF);
			}
		}
		g.text(this.font, hoverIndex >= 0 ? "Xaero colour " + hoverIndex : "Xaero colours", right, top + 126, 0xFF777777);

		// the Mode dropdown (its open list is drawn last, on top of everything)
		boolean disabled = isDeath();
		int dx0 = dropdownX();
		int dy0 = dropdownY();
		boolean hoverBox = !disabled && mouseX >= dx0 && mouseX < dx0 + DROPDOWN_W && mouseY >= dy0 && mouseY < dy0 + BAR_H;
		g.fill(dx0, dy0, dx0 + DROPDOWN_W, dy0 + BAR_H, hoverBox ? 0xC0404040 : 0xA0202020);
		border(g, dx0, dy0, dx0 + DROPDOWN_W, dy0 + BAR_H, dropdownOpen ? 0xFF55AAFF : (hoverBox ? 0xFFFFFFFF : 0xFF777777));
		g.text(this.font, disabled ? "Single" : MODES[area ? 1 : 0], dx0 + 6, dy0 + 6, disabled ? 0xFF777777 : 0xFFFFFFFF);
		g.text(this.font, "v", dx0 + DROPDOWN_W - 12, dy0 + 6, 0xFFAAAAAA);

		super.extractRenderState(g, mouseX, mouseY, partialTick);

		if (dropdownOpen) {
			for (int i = 0; i < MODES.length; i++) {
				int oy = dy0 + BAR_H + i * BAR_H;
				boolean hover = mouseX >= dx0 && mouseX < dx0 + DROPDOWN_W && mouseY >= oy && mouseY < oy + BAR_H;
				g.fill(dx0, oy, dx0 + DROPDOWN_W, oy + BAR_H, hover ? 0xF0404060 : 0xF0181818);
				border(g, dx0, oy, dx0 + DROPDOWN_W, oy + BAR_H, 0xFF55AAFF);
				g.text(this.font, MODES[i], dx0 + 6, oy + 6, (i == 1) == area ? 0xFF55FF77 : 0xFFFFFFFF);
			}
		}
	}

	private void drawAxisLabels(GuiGraphicsExtractor g, int y) {
		int boxW = (leftW - 3 * 12 - 2 * 4) / 3;
		String[] axes = { "X", "Y", "Z" };
		int x = left;
		for (String axis : axes) {
			g.text(this.font, axis, x + 3, y, 0xFFFFFFFF);
			x += 12 + boxW + 4;
		}
	}

	private void drawAreaSummary(GuiGraphicsExtractor g, int y) {
		if (regions.isEmpty()) {
			g.text(this.font, "Nothing drawn yet: press Edit area.", left, y, 0xFF777777);
			return;
		}
		int blocks = 0;
		int[] b = regions.get(0).clone();
		for (int[] r : regions) {
			blocks += Waypoint.isBlock(r) ? 1 : 0;
			for (int k = 0; k < 3; k++) {
				b[k] = Math.min(b[k], r[k]);
				b[k + 3] = Math.max(b[k + 3], r[k + 3]);
			}
		}
		int boxes = regions.size() - blocks;
		g.text(this.font, regions.size() + " region" + (regions.size() == 1 ? "" : "s") + "  (" + boxes + " box" + (boxes == 1 ? "" : "es") + ", " + blocks + " block" + (blocks == 1 ? "" : "s") + ")", left, y, 0xFFFFFFFF);
		g.text(this.font, "Bounds " + b[0] + ", " + b[1] + ", " + b[2] + "  to  " + b[3] + ", " + b[4] + ", " + b[5], left, y + 11, 0xFFAAAAAA);
		g.text(this.font, "Size " + (b[3] - b[0] + 1) + " x " + (b[4] - b[1] + 1) + " x " + (b[5] - b[2] + 1) + "   (board arrow sits above the middle)", left, y + 22, 0xFFAAAAAA);
	}

	private static String letterOf(String text) {
		for (int i = 0; i < text.length(); i++) {
			if (!Character.isWhitespace(text.charAt(i))) {
				return String.valueOf(text.charAt(i)).toUpperCase(java.util.Locale.ROOT);
			}
		}
		return "W";
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
}
