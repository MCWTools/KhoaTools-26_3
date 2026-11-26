package khoa.tools.client.feature.waypoints;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.chat.Component;

/**
 * Address picker for the Xaero import (Shift+click on Import Xaero, or a plain click when no folder matches the current world):
 * left = every server address / world that has Xaero waypoints, right = the ones to import into the current world.
 * Same controls as the item picker: click = select, Shift+click = range, Ctrl+click = toggle, double click = move.
 * Names of saved servers (multiplayer menu) are shown for matching addresses.
 */
final class XaeroPickScreen extends Screen {
	private static final int GAP = 4;
	private static final int CARD_H = 26;
	private static final int TARGET_CARD_W = 150;
	private static final int SEARCH_Y = 22;
	private static final int SEARCH_H = 18;
	private static final int BAR_H = 20;

	private static final int COL_TEXT = 0xFFFFFFFF;
	private static final int COL_DIM = 0xFFAAAAAA;
	private static final int COL_GREEN = 0xFF55FF77;
	private static final int COL_RED = 0xFFFF6655;
	private static final int COL_BLUE = 0xFF55AAFF;
	private static final int BG_PANEL = 0xA0101010;
	private static final int BG_CARD = 0x90202020;
	private static final int BG_CARD_HOVER = 0xB0383838;
	private static final int BG_SELECTED = 0xA02E6E9E;
	private static final int BORDER = 0xFF555555;

	/** One card: a Xaero folder with the texts shown for it. */
	private record Card(XaeroImport.Folder folder, String title, String detail, String search) {
	}

	private final Screen parent;
	private final List<XaeroImport.Folder> folders;
	private final Consumer<XaeroImport.Result> done;

	private List<Card> cards;
	private final Set<Card> picked = new LinkedHashSet<>();
	private final Set<Card> selected = new LinkedHashSet<>();
	private boolean selectionInPicked;
	private int anchor = -1;
	private List<Card> availView = List.of();
	private List<Card> pickedView = List.of();
	private double scrollAvail;
	private double scrollPicked;

	private EditBox search;
	private String query = "";

	private int frameX;
	private int frameW;
	private int panelW;
	private int panelTop;
	private int panelBottom;
	private int contentTop;
	private int barY;

	XaeroPickScreen(Screen parent, List<XaeroImport.Folder> folders, Consumer<XaeroImport.Result> done) {
		super(Component.literal("Import from Xaero"));
		this.parent = parent;
		this.folders = folders;
		this.done = done;
	}

	// ------------------------------------------------------------------ layout / data

	@Override
	protected void init() {
		frameW = Math.min(this.width - 24, 640);
		frameX = (this.width - frameW) / 2;
		panelW = (frameW - 10) / 2;
		panelTop = SEARCH_Y + SEARCH_H + 8;
		contentTop = panelTop + 14;
		barY = this.height - 34;
		panelBottom = barY - 6;
		if (cards == null) {
			cards = buildCards();
		}
		search = new EditBox(this.font, frameX, SEARCH_Y, frameW, SEARCH_H, Component.literal("Search"));
		search.setHint(Component.literal("Search address, server name or world..."));
		search.setMaxLength(48);
		search.setValue(query);
		search.setResponder(value -> {
			query = value.trim().toLowerCase(Locale.ROOT);
			scrollAvail = 0;
			scrollPicked = 0;
			selected.clear(); // never move cards that are filtered out of sight
			anchor = -1;
			rebuildViews();
		});
		this.addRenderableWidget(search);
		rebuildViews();
	}

	private List<Card> buildCards() {
		ServerList servers = new ServerList(this.minecraft);
		servers.load();
		List<Card> out = new ArrayList<>();
		for (XaeroImport.Folder folder : folders) {
			String saved = null;
			if (folder.multiplayer()) {
				for (int i = 0; i < servers.size() && saved == null; i++) {
					ServerData data = servers.get(i);
					if (XaeroImport.sameAddress(folder, data.ip)) {
						saved = data.name;
					}
				}
			}
			String title = saved != null ? saved : folder.address();
			String detail = (folder.thisWorld() ? "Here - " : "") + folder.count() + " waypoint" + (folder.count() == 1 ? "" : "s")
				+ " - " + (saved != null ? folder.address() : folder.multiplayer() ? "server" : "world");
			out.add(new Card(folder, title, detail, (title + " " + folder.address()).toLowerCase(Locale.ROOT)));
		}
		return out;
	}

	private void rebuildViews() {
		availView = cards.stream().filter(c -> !picked.contains(c) && c.search().contains(query)).toList();
		pickedView = picked.stream().filter(c -> c.search().contains(query)).toList();
		scrollAvail = clampScroll(scrollAvail, availView.size());
		scrollPicked = clampScroll(scrollPicked, pickedView.size());
	}

	private int cols() {
		return Math.max(1, (panelW - 8 + GAP) / (TARGET_CARD_W + GAP));
	}

	private int cardW() {
		int cols = cols();
		return (panelW - 8 - (cols - 1) * GAP) / cols;
	}

	private double maxScroll(int count) {
		int rows = (count + cols() - 1) / cols();
		return Math.max(0, rows * (CARD_H + GAP) - GAP - (panelBottom - contentTop));
	}

	private double clampScroll(double value, int count) {
		return Math.max(0, Math.min(value, maxScroll(count)));
	}

	private int panelX(boolean pickedSide) {
		return pickedSide ? frameX + panelW + 10 : frameX;
	}

	private boolean inPanel(boolean pickedSide, double mx, double my) {
		int px = panelX(pickedSide);
		return mx >= px && mx < px + panelW && my >= panelTop && my < panelBottom;
	}

	private int cardAt(boolean pickedSide, double mx, double my) {
		int px = panelX(pickedSide) + 4;
		if (mx < px || mx >= panelX(pickedSide) + panelW - 4 || my < contentTop || my >= panelBottom) {
			return -1;
		}
		int cols = cols();
		int cw = cardW();
		int col = (int) ((mx - px) / (cw + GAP));
		if (col >= cols || (mx - px) - col * (cw + GAP) >= cw) {
			return -1;
		}
		double relY = my - contentTop + (pickedSide ? scrollPicked : scrollAvail);
		int row = (int) Math.floor(relY / (CARD_H + GAP));
		if (relY - row * (CARD_H + GAP) >= CARD_H) {
			return -1;
		}
		int index = row * cols + col;
		return index < (pickedSide ? pickedView : availView).size() ? index : -1;
	}

	private int actionX() {
		return this.width / 2 - 70;
	}

	private int importX() {
		return frameX + frameW - 90;
	}

	// ------------------------------------------------------------------ actions

	/** Moves the selected cards to the other list. */
	private void moveSelection() {
		if (selected.isEmpty()) {
			return;
		}
		if (selectionInPicked) {
			picked.removeAll(selected);
		} else {
			picked.addAll(selected);
		}
		selected.clear();
		anchor = -1;
		rebuildViews();
	}

	private void importPicked() {
		if (picked.isEmpty()) {
			return;
		}
		int imported = 0;
		int skipped = 0;
		for (Card card : picked) {
			XaeroImport.Result r = XaeroImport.importFolder(this.minecraft, card.folder());
			imported += r.imported();
			skipped += r.skipped();
		}
		this.minecraft.gui.setScreen(parent);
		done.accept(new XaeroImport.Result(imported, skipped));
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) { // the search box
			return true;
		}
		this.setFocused((GuiEventListener) null);
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		double mx = event.x();
		double my = event.y();
		if (inside(mx, my, frameX, barY, 70, BAR_H)) {
			onClose();
			return true;
		}
		if (inside(mx, my, actionX(), barY, 140, BAR_H)) {
			moveSelection();
			return true;
		}
		if (inside(mx, my, importX(), barY, 90, BAR_H)) {
			importPicked();
			return true;
		}
		for (boolean side : new boolean[] { false, true }) {
			if (!inPanel(side, mx, my)) {
				continue;
			}
			int index = cardAt(side, mx, my);
			if (index < 0) {
				selected.clear();
				anchor = -1;
				return true;
			}
			clickCard(side, index, event.hasControlDown(), event.hasShiftDown(), doubleClick);
			return true;
		}
		return false;
	}

	private void clickCard(boolean side, int index, boolean ctrl, boolean shift, boolean doubleClick) {
		List<Card> view = side ? pickedView : availView;
		Card card = view.get(index);
		if (selectionInPicked != side) { // selection always lives in one list
			selected.clear();
			anchor = -1;
			selectionInPicked = side;
		}
		if (doubleClick && !ctrl && !shift) {
			if (!selected.contains(card)) {
				selected.clear();
				selected.add(card);
			}
			moveSelection();
			return;
		}
		if (shift && anchor >= 0 && anchor < view.size()) {
			if (!ctrl) {
				selected.clear();
			}
			for (int i = Math.min(anchor, index); i <= Math.max(anchor, index); i++) {
				selected.add(view.get(i));
			}
		} else if (ctrl) {
			if (!selected.remove(card)) {
				selected.add(card);
			}
			anchor = index;
		} else {
			selected.clear();
			selected.add(card);
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
			selectionInPicked = selectionInPicked && !selected.isEmpty();
			List<Card> view = selectionInPicked ? pickedView : availView;
			selected.clear();
			selected.addAll(view);
			anchor = view.isEmpty() ? -1 : 0;
			return true;
		}
		return super.keyPressed(event); // Esc -> onClose()
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		for (boolean side : new boolean[] { false, true }) {
			if (inPanel(side, x, y)) {
				if (side) {
					scrollPicked = clampScroll(scrollPicked - scrollY * 24, pickedView.size());
				} else {
					scrollAvail = clampScroll(scrollAvail - scrollY * 24, availView.size());
				}
				return true;
			}
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	/** Esc / Back: nothing is imported. */
	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	// ------------------------------------------------------------------ render

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.text(this.font, "Import from Xaero", frameX, 8, COL_TEXT);
		String count = "Picked: " + picked.size();
		g.text(this.font, count, frameX + frameW - this.font.width(count), 8, COL_DIM);

		Card hovered = null;
		for (boolean side : new boolean[] { false, true }) {
			Card card = renderPanel(g, side, mouseX, mouseY);
			if (card != null) {
				hovered = card;
			}
		}

		button(g, mouseX, mouseY, frameX, 70, "< Back", true, 0xFF777777);
		String action = selected.isEmpty() ? "Select cards first" : selectionInPicked ? "<  Remove (" + selected.size() + ")" : "Add (" + selected.size() + ")  >";
		button(g, mouseX, mouseY, actionX(), 140, action, !selected.isEmpty(), selectionInPicked ? COL_RED : COL_GREEN);
		int total = picked.stream().mapToInt(c -> c.folder().count()).sum();
		button(g, mouseX, mouseY, importX(), 90, picked.isEmpty() ? "Import" : "Import (" + total + ")", !picked.isEmpty(), COL_GREEN);
		g.text(this.font, "Click: select   Shift: range   Ctrl: toggle   Double click: add / remove   Ctrl+A: all   Esc: back",
			frameX, this.height - 10, COL_DIM);

		super.extractRenderState(g, mouseX, mouseY, partialTick); // the search box
		if (hovered != null) {
			g.setComponentTooltipForNextFrame(this.font, List.of(Component.literal(hovered.title()),
				Component.literal(hovered.folder().address()), Component.literal(hovered.detail())), mouseX, mouseY);
		}
	}

	/** Draws one list; returns the card under the mouse (for the tooltip). */
	private Card renderPanel(GuiGraphicsExtractor g, boolean pickedSide, int mouseX, int mouseY) {
		int px = panelX(pickedSide);
		List<Card> view = pickedSide ? pickedView : availView;
		double scroll = pickedSide ? scrollPicked : scrollAvail;
		g.fill(px, panelTop, px + panelW, panelBottom, BG_PANEL);
		border(g, px, panelTop, px + panelW, panelBottom, BORDER);
		g.text(this.font, (pickedSide ? "Import into this world (" : "Servers & worlds (") + view.size() + ")", px + 5, panelTop + 3, COL_TEXT);
		if (view.isEmpty()) {
			g.centeredText(this.font, pickedSide && picked.isEmpty() ? "Nothing picked yet." : "No match.", px + panelW / 2, contentTop + 20, COL_DIM);
			return null;
		}
		Card hovered = null;
		int cols = cols();
		int cw = cardW();
		g.enableScissor(px, contentTop, px + panelW, panelBottom);
		for (int i = 0; i < view.size(); i++) {
			int x = px + 4 + (i % cols) * (cw + GAP);
			int y = contentTop - (int) scroll + (i / cols) * (CARD_H + GAP);
			if (y + CARD_H < contentTop || y > panelBottom) {
				continue;
			}
			Card card = view.get(i);
			boolean sel = selectionInPicked == pickedSide && selected.contains(card);
			boolean hover = inside(mouseX, mouseY, x, y, cw, CARD_H) && mouseY >= contentTop && mouseY < panelBottom;
			g.fill(x, y, x + cw, y + CARD_H, sel ? BG_SELECTED : hover ? BG_CARD_HOVER : BG_CARD);
			border(g, x, y, x + cw, y + CARD_H, sel ? COL_BLUE : BORDER);
			g.text(this.font, this.font.plainSubstrByWidth(card.title(), cw - 10), x + 5, y + 4, card.folder().thisWorld() ? COL_GREEN : COL_TEXT);
			g.text(this.font, this.font.plainSubstrByWidth(card.detail(), cw - 10), x + 5, y + 15, COL_DIM);
			if (hover) {
				hovered = card;
			}
		}
		g.disableScissor();
		double max = maxScroll(view.size());
		if (max > 0) {
			int trackH = panelBottom - contentTop;
			int barH = Math.max(12, (int) (trackH * (trackH / (trackH + max))));
			int barTop = contentTop + (int) ((trackH - barH) * (scroll / max));
			g.fill(px + panelW - 4, barTop, px + panelW - 2, barTop + barH, 0xFF888888);
		}
		return hovered;
	}

	private void button(GuiGraphicsExtractor g, int mouseX, int mouseY, int x, int w, String text, boolean enabled, int accent) {
		boolean hover = enabled && inside(mouseX, mouseY, x, barY, w, BAR_H);
		g.fill(x, barY, x + w, barY + BAR_H, enabled ? hover ? 0xC0505050 : 0xA0262626 : 0x70101010);
		border(g, x, barY, x + w, barY + BAR_H, enabled ? accent : BORDER);
		g.centeredText(this.font, text, x + w / 2, barY + 6, enabled ? COL_TEXT : COL_DIM);
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
}
