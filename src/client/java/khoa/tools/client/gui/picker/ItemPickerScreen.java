package khoa.tools.client.gui.picker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import khoa.tools.client.feature.illegal.spawnerfinder.SpawnerLook;
import khoa.tools.client.gui.picker.PickData.Entry;
import khoa.tools.setting.Setting;

/**
 * Item or block picker opened from an ITEM_LIST row of a feature's config screen.
 * Item rows list items and enchanted books, block rows list blocks (fixed by the config row). Left = everything of that type that is not picked yet, right = the picked list.
 * Click = select, Shift+click = range, Ctrl+click = toggle, double click = move, the button under the lists moves the whole selection.
 * Esc (or Back) returns to the config screen; every change is saved immediately.
 */
public final class ItemPickerScreen extends Screen {
	private static final int GAP = 4;
	private static final int CARD_H = 24;
	private static final int TARGET_CARD_W = 118;
	private static final int TAB_Y = 22;
	private static final int TAB_H = 18;
	private static final int BAR_H = 20;

	private static final int COL_TEXT = 0xFFFFFFFF;
	private static final int COL_DIM = 0xFFAAAAAA;
	private static final int COL_GREEN = 0xFF55FF77;
	private static final int COL_BLUE = 0xFF55AAFF;
	private static final int BG_PANEL = 0xA0101010;
	private static final int BG_CARD = 0x90202020;
	private static final int BG_CARD_HOVER = 0xB0383838;
	private static final int BG_SELECTED = 0xA02E6E9E;
	private static final int BORDER = 0xFF555555;

	private final Screen parent;
	private final Setting setting;
	/** ITEM (items + enchanted books) or BLOCK: fixed by the config row, the user cannot switch. */
	private final String mode;
	private List<Entry> catalogCache;

	private final Map<String, Entry> chosen = new LinkedHashMap<>();
	private List<Entry> availView = List.of();
	private List<Entry> chosenView = List.of();

	private final Set<String> selected = new LinkedHashSet<>();
	private boolean selectionInChosen;
	private int anchor = -1;
	private double scrollAvail;
	private double scrollChosen;

	private EditBox search;
	private String query = "";

	private int frameX;
	private int frameW;
	private int panelW;
	private int panelTop;
	private int panelBottom;
	private int contentTop;
	private int barY;

	public ItemPickerScreen(Screen parent, Setting setting) {
		super(Component.literal(setting.label));
		this.parent = parent;
		this.setting = setting;
		this.mode = setting.options.contains(PickData.ENTITY) ? PickData.ENTITY : setting.options.contains(PickData.SPAWNER) ? PickData.SPAWNER : setting.options.contains(PickData.BLOCK) && !setting.options.contains(PickData.ITEM) ? PickData.BLOCK : PickData.ITEM;
		for (String key : setting.getList()) {
			Entry entry = PickData.resolve(key);
			chosen.put(entry.key(), entry);
		}
	}

	// ------------------------------------------------------------------ layout / data

	@Override
	protected void init() {
		frameW = Math.min(this.width - 24, 640);
		frameX = (this.width - frameW) / 2;
		panelW = (frameW - 10) / 2;
		panelTop = TAB_Y + TAB_H + 8;
		contentTop = panelTop + 14;
		barY = this.height - 34;
		panelBottom = barY - 6;
		int searchX = frameX;
		int toggleW = setting.hasPickerToggle() ? this.font.width(setting.pickerToggleLabel) + 24 : 0;
		search = new EditBox(this.font, searchX, TAB_Y, Math.max(60, frameX + frameW - searchX - toggleW), TAB_H, Component.literal("Search"));
		search.setHint(Component.literal("Search..."));
		search.setMaxLength(48);
		search.setValue(query);
		search.setResponder(value -> {
			query = value.trim().toLowerCase(Locale.ROOT);
			scrollAvail = 0;
			scrollChosen = 0;
			rebuildViews();
		});
		this.addRenderableWidget(search);
		query = search.getValue().trim().toLowerCase(Locale.ROOT);
		rebuildViews();
	}

	private int toggleX() {
		return frameX + frameW - (this.font.width(setting.pickerToggleLabel) + 20);
	}

	private boolean overToggle(double mx, double my) {
		return setting.hasPickerToggle() && mx >= toggleX() - 2 && mx < frameX + frameW && my >= TAB_Y && my < TAB_Y + TAB_H;
	}

	private List<Entry> catalog() {
		if (PickData.SPAWNER.equals(mode) || PickData.ENTITY.equals(mode)) {
			if (catalogCache == null) {
				catalogCache = PickData.ENTITY.equals(mode) ? PickData.entityCatalog() : PickData.spawnerCatalog(setting.getPickerToggle());
			}
			return catalogCache;
		}
		if (!PickData.available()) {
			return List.of(); // not cached: the real list is built after joining a world
		}
		if (catalogCache == null) {
			catalogCache = PickData.catalog(mode);
		}
		return catalogCache;
	}

	private void rebuildViews() {
		List<Entry> avail = new ArrayList<>();
		for (Entry e : catalog()) {
			if (setting.stackFilter != null && (e.stack() == null || !setting.stackFilter.test(e.stack()))) {
				continue;
			}
			if (!chosen.containsKey(e.key()) && (query.isEmpty() || e.search().contains(query))) {
				avail.add(e);
			}
		}
		List<Entry> picked = new ArrayList<>();
		for (Entry e : chosen.values()) {
			if (query.isEmpty() || e.search().contains(query)) {
				picked.add(e);
			}
		}
		availView = avail;
		chosenView = picked;
		scrollAvail = clampScroll(scrollAvail, avail.size());
		scrollChosen = clampScroll(scrollChosen, picked.size());
	}

	private int cols() {
		return Math.max(1, (panelW - 8 + GAP) / (TARGET_CARD_W + GAP));
	}

	private int cardW() {
		int cols = cols();
		return (panelW - 8 - (cols - 1) * GAP) / cols;
	}

	private int viewH() {
		return panelBottom - contentTop;
	}

	private double maxScroll(int count) {
		int rows = (count + cols() - 1) / cols();
		return Math.max(0, rows * (CARD_H + GAP) - GAP - viewH());
	}

	private double clampScroll(double value, int count) {
		return Math.max(0, Math.min(value, maxScroll(count)));
	}

	private int panelX(boolean chosenSide) {
		return chosenSide ? frameX + panelW + 10 : frameX;
	}

	private int cardAt(boolean chosenSide, double mx, double my) {
		int px = panelX(chosenSide) + 4;
		if (mx < px || mx >= panelX(chosenSide) + panelW - 4 || my < contentTop || my >= panelBottom) {
			return -1;
		}
		int cols = cols();
		int cw = cardW();
		int col = (int) ((mx - px) / (cw + GAP));
		if (col >= cols || (mx - px) - col * (cw + GAP) >= cw) {
			return -1;
		}
		double relY = my - contentTop + (chosenSide ? scrollChosen : scrollAvail);
		int row = (int) Math.floor(relY / (CARD_H + GAP));
		if (relY - row * (CARD_H + GAP) >= CARD_H) {
			return -1;
		}
		int index = row * cols + col;
		return index < (chosenSide ? chosenView : availView).size() ? index : -1;
	}

	private boolean inPanel(boolean chosenSide, double mx, double my) {
		int px = panelX(chosenSide);
		return mx >= px && mx < px + panelW && my >= panelTop && my < panelBottom;
	}

	// ------------------------------------------------------------------ actions

	private void save() {
		setting.setList(new ArrayList<>(chosen.keySet()));
	}

	/** Moves the selected cards to the other list (Add from the left, Remove from the right) and saves. */
	private void moveSelection() {
		if (selected.isEmpty()) {
			return;
		}
		List<Entry> source = selectionInChosen ? chosenView : availView;
		List<Entry> moving = new ArrayList<>();
		for (Entry e : source) {
			if (selected.contains(e.key())) {
				moving.add(e);
			}
		}
		for (Entry e : moving) {
			if (selectionInChosen) {
				chosen.remove(e.key());
			} else {
				chosen.put(e.key(), e);
			}
		}
		if (setting.maxPicks > 0) { // keep only the newest picks
			while (chosen.size() > setting.maxPicks) {
				chosen.remove(chosen.keySet().iterator().next());
			}
		}
		selected.clear();
		anchor = -1;
		rebuildViews();
		save();
	}

	private void removeAll() {
		if (chosen.isEmpty()) {
			return;
		}
		chosen.clear();
		selected.clear();
		anchor = -1;
		scrollChosen = 0;
		rebuildViews();
		save();
	}

	private String actionText() {
		if (selected.isEmpty()) {
			return "Select cards first";
		}
		return selectionInChosen ? "<  Remove (" + selected.size() + ")" : "Add (" + selected.size() + ")  >";
	}

	private int removeAllX() {
		return frameX + frameW - 90;
	}

	private int actionX() {
		return this.width / 2 - 80;
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && overToggle(event.x(), event.y())) {
			setting.setPickerToggle(!setting.getPickerToggle());
			catalogCache = null;
			scrollAvail = 0;
			selected.clear();
			anchor = -1;
			rebuildViews();
			return true;
		}
		if (super.mouseClicked(event, doubleClick)) { // the search box
			return true;
		}
		this.setFocused((GuiEventListener) null);
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		double mx = event.x();
		double my = event.y();

		if (inside(mx, my, removeAllX(), barY, 90, BAR_H)) {
			removeAll();
			return true;
		}
		if (inside(mx, my, frameX, barY, 70, BAR_H)) {
			onClose();
			return true;
		}
		if (inside(mx, my, actionX(), barY, 160, BAR_H)) {
			moveSelection();
			return true;
		}
		for (boolean side : new boolean[] { false, true }) {
			if (!inPanel(side, mx, my)) {
				continue;
			}
			int index = cardAt(side, mx, my);
			if (index < 0) {
				selected.clear(); // click on the empty part of a list
				anchor = -1;
				return true;
			}
			clickCard(side, index, event.hasControlDown(), event.hasShiftDown(), doubleClick);
			return true;
		}
		return false;
	}

	private void clickCard(boolean side, int index, boolean ctrl, boolean shift, boolean doubleClick) {
		List<Entry> view = side ? chosenView : availView;
		Entry entry = view.get(index);
		if (selectionInChosen != side) { // selection always lives in one list
			selected.clear();
			anchor = -1;
			selectionInChosen = side;
		}
		if (doubleClick && !ctrl && !shift) {
			if (!selected.contains(entry.key())) {
				selected.clear();
				selected.add(entry.key());
			}
			boolean pickedNow = !selectionInChosen;
			moveSelection();
			if (pickedNow && setting.maxPicks == 1) {
				onClose(); // a single-item picker: double click chooses and leaves
			}
			return;
		}
		if (shift && anchor >= 0 && anchor < view.size()) {
			if (!ctrl) {
				selected.clear();
			}
			for (int i = Math.min(anchor, index); i <= Math.max(anchor, index); i++) {
				selected.add(view.get(i).key());
			}
		} else if (ctrl) {
			if (!selected.remove(entry.key())) {
				selected.add(entry.key());
			}
			anchor = index;
		} else {
			selected.clear();
			selected.add(entry.key());
			anchor = index;
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		boolean typing = search != null && search.isFocused();
		if (!typing && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)) {
			moveSelection();
			return true;
		}
		if (!typing && event.hasControlDown() && event.key() == InputConstants.KEY_A) {
			List<Entry> view = selectionInChosen && !selected.isEmpty() ? chosenView : availView;
			selectionInChosen = view == chosenView;
			selected.clear();
			for (Entry e : view) {
				selected.add(e.key());
			}
			anchor = view.isEmpty() ? -1 : 0;
			return true;
		}
		return super.keyPressed(event); // Esc -> onClose()
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		for (boolean side : new boolean[] { false, true }) {
			if (inPanel(side, x, y)) {
				int count = (side ? chosenView : availView).size();
				if (side) {
					scrollChosen = clampScroll(scrollChosen - scrollY * 24, count);
				} else {
					scrollAvail = clampScroll(scrollAvail - scrollY * 24, count);
				}
				return true;
			}
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	/** Esc / Back: return to the config screen, not to the game. */
	@Override
	public void onClose() {
		save();
		this.minecraft.gui.setScreen(parent);
	}

	// ------------------------------------------------------------------ render

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.text(this.font, this.title.getString(), frameX, 8, COL_TEXT);
		g.text(this.font, "Picked: " + chosen.size(), frameX + frameW - this.font.width("Picked: " + chosen.size()), 8, COL_DIM);

		Entry hovered = null;
		for (boolean side : new boolean[] { false, true }) {
			Entry e = renderPanel(g, side, mouseX, mouseY);
			if (e != null) {
				hovered = e;
			}
		}
		renderBar(g, mouseX, mouseY);
		g.text(this.font, "Click: select   Shift: range   Ctrl: toggle   Double click: add / remove   Ctrl+A: all   Esc: back",
			frameX, this.height - 10, COL_DIM);

		super.extractRenderState(g, mouseX, mouseY, partialTick); // the search box
		if (setting.hasPickerToggle()) {
			renderToggle(g, mouseX, mouseY);
		}
		if (hovered != null) {
			g.setComponentTooltipForNextFrame(this.font, hovered.tooltip(), mouseX, mouseY);
		}
	}

	/** Draws one list; returns the card under the mouse (for the tooltip). */
	private Entry renderPanel(GuiGraphicsExtractor g, boolean chosenSide, int mouseX, int mouseY) {
		int px = panelX(chosenSide);
		List<Entry> view = chosenSide ? chosenView : availView;
		double scroll = chosenSide ? scrollChosen : scrollAvail;
		g.fill(px, panelTop, px + panelW, panelBottom, BG_PANEL);
		border(g, px, panelTop, px + panelW, panelBottom, BORDER);
		g.text(this.font, (chosenSide ? "Picked (" : PickData.modeLabel(mode) + " (") + view.size() + ")", px + 5, panelTop + 3, COL_TEXT);

		if (view.isEmpty()) {
			String message = chosenSide ? (chosen.isEmpty() ? "Nothing picked yet." : "No match.")
				: (!PickData.available() && !PickData.SPAWNER.equals(mode) && !PickData.ENTITY.equals(mode) ? "Join a world to list items." : "No match.");
			g.centeredText(this.font, message, px + panelW / 2, contentTop + 20, COL_DIM);
			return null;
		}
		Entry hovered = null;
		int cols = cols();
		int cw = cardW();
		boolean mouseInList = mouseY >= contentTop && mouseY < panelBottom;
		g.enableScissor(px, contentTop, px + panelW, panelBottom);
		for (int i = 0; i < view.size(); i++) {
			int x = px + 4 + (i % cols) * (cw + GAP);
			int y = contentTop - (int) scroll + (i / cols) * (CARD_H + GAP);
			if (y + CARD_H < contentTop || y > panelBottom) {
				continue;
			}
			Entry e = view.get(i);
			boolean sel = selectionInChosen == chosenSide && selected.contains(e.key());
			boolean hover = mouseInList && inside(mouseX, mouseY, x, y, cw, CARD_H) && inPanel(chosenSide, mouseX, mouseY);
			g.fill(x, y, x + cw, y + CARD_H, sel ? BG_SELECTED : (hover ? BG_CARD_HOVER : BG_CARD));
			border(g, x, y, x + cw, y + CARD_H, sel ? COL_BLUE : BORDER);
			if (e.hasSwatch()) {
				SpawnerLook.drawIcon(g, x + 4, y + (CARD_H - 16) / 2, 16, PickData.TRIAL.equals(e.kind()), e.swatch());
			} else {
				g.item(e.stack(), x + 4, y + (CARD_H - 16) / 2);
			}
			int textW = cw - 28 - (chosenSide ? 10 : 0);
			g.text(this.font, this.font.plainSubstrByWidth(e.name(), textW), x + 24, y + (CARD_H - 8) / 2, COL_TEXT);
			if (chosenSide && e.kind().equals(PickData.BOOK)) {
				g.text(this.font, "E", x + cw - 9, y + 2, COL_DIM);
			}
			if (hover) {
				hovered = e;
			}
		}
		g.disableScissor();

		double max = maxScroll(view.size());
		if (max > 0) {
			int trackH = viewH();
			int barH = Math.max(12, (int) (trackH * (trackH / (trackH + max))));
			int barYpos = contentTop + (int) ((trackH - barH) * (scroll / max));
			g.fill(px + panelW - 4, barYpos, px + panelW - 2, barYpos + barH, 0xFF888888);
		}
		return hovered;
	}

	/** The tick box next to the search box (e.g. "Vanilla" in the Spawner picker). */
	private void renderToggle(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		boolean on = setting.getPickerToggle();
		boolean hover = overToggle(mouseX, mouseY);
		int bx = toggleX();
		int by = TAB_Y + (TAB_H - 12) / 2;
		g.fill(bx, by, bx + 12, by + 12, hover ? 0xC0404040 : 0xA0202020);
		border(g, bx, by, bx + 12, by + 12, on ? COL_GREEN : (hover ? COL_TEXT : 0xFF777777));
		if (on) {
			g.fill(bx + 3, by + 3, bx + 9, by + 9, COL_GREEN);
		}
		g.text(this.font, setting.pickerToggleLabel, bx + 17, by + 2, COL_TEXT);
		if (hover && !setting.pickerToggleHint.isEmpty()) {
			g.setComponentTooltipForNextFrame(this.font, List.of(Component.literal(setting.pickerToggleHint)), mouseX, mouseY);
		}
	}

	private void renderBar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		boolean backHover = inside(mouseX, mouseY, frameX, barY, 70, BAR_H);
		g.fill(frameX, barY, frameX + 70, barY + BAR_H, backHover ? 0xC0505050 : 0xA0202020);
		border(g, frameX, barY, frameX + 70, barY + BAR_H, backHover ? COL_TEXT : 0xFF777777);
		g.centeredText(this.font, "< Back", frameX + 35, barY + 6, COL_TEXT);

		boolean raHover = !chosen.isEmpty() && inside(mouseX, mouseY, removeAllX(), barY, 90, BAR_H);
		g.fill(removeAllX(), barY, removeAllX() + 90, barY + BAR_H, chosen.isEmpty() ? 0x70101010 : (raHover ? 0xC0603030 : 0xA0402020));
		border(g, removeAllX(), barY, removeAllX() + 90, barY + BAR_H, chosen.isEmpty() ? BORDER : 0xFFFF6655);
		g.centeredText(this.font, "Remove all", removeAllX() + 45, barY + 6, chosen.isEmpty() ? COL_DIM : COL_TEXT);

		boolean enabled = !selected.isEmpty();
		int ax = actionX();
		boolean hover = enabled && inside(mouseX, mouseY, ax, barY, 160, BAR_H);
		int accent = selectionInChosen ? 0xFFFF6655 : COL_GREEN;
		g.fill(ax, barY, ax + 160, barY + BAR_H, enabled ? (hover ? 0xC0404040 : 0xA0262626) : 0x70101010);
		border(g, ax, barY, ax + 160, barY + BAR_H, enabled ? accent : BORDER);
		g.centeredText(this.font, actionText(), ax + 80, barY + 6, enabled ? COL_TEXT : COL_DIM);
	}

	private static boolean inside(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
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
