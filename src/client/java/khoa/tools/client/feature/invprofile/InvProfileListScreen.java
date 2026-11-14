package khoa.tools.client.feature.invprofile;

import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** The list of inventory profiles: double click one to load it, select one and press Edit to change it. */
final class InvProfileListScreen extends Screen {
	private static final int ROW_H = 22;
	private static final int COL_TEXT = 0xFFFFFFFF;
	private static final int COL_DIM = 0xFFAAAAAA;

	private final Screen parent;
	private String selectedId;
	private double scroll;
	private boolean confirmDelete;
	private Button editButton;
	private Button deleteButton;
	private int frameX;
	private int frameW;
	private int listTop;
	private int listBottom;

	InvProfileListScreen(Screen parent) {
		super(Component.literal("Inventory Profiles"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		frameW = Math.min(this.width - 24, 360);
		frameX = (this.width - frameW) / 2;
		listTop = 36;
		listBottom = this.height - 34;
		int y = this.height - 26;
		int w = (frameW - 12) / 4;
		Button create = this.addRenderableWidget(Button.builder(Component.literal("Create profile"), b -> {
			InvProfile created = InvProfileStore.create();
			this.minecraft.gui.setScreen(new InvProfileEditScreen(this, created));
		}).bounds(frameX, y, w, 20).build());
		create.active = this.minecraft.level != null; // the item icons need a world
		editButton = this.addRenderableWidget(Button.builder(Component.literal("Edit"), b -> {
			InvProfile p = selected();
			if (p != null) {
				this.minecraft.gui.setScreen(new InvProfileEditScreen(this, p));
			}
		}).bounds(frameX + w + 4, y, w, 20).build());
		deleteButton = this.addRenderableWidget(Button.builder(Component.literal("Delete"), b -> {
			InvProfile p = selected();
			if (p == null) {
				return;
			}
			if (!confirmDelete) {
				confirmDelete = true;
				updateButtons();
				return;
			}
			InvProfileStore.remove(p);
			selectedId = null;
			confirmDelete = false;
			updateButtons();
		}).bounds(frameX + (w + 4) * 2, y, w, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
			.bounds(frameX + (w + 4) * 3, y, frameW - (w + 4) * 3, 20).build());
		updateButtons();
	}

	private InvProfile selected() {
		return selectedId == null ? null : InvProfileStore.find(selectedId);
	}

	private void updateButtons() {
		boolean has = selected() != null;
		editButton.active = has && this.minecraft.level != null;
		deleteButton.active = has;
		deleteButton.setMessage(Component.literal(confirmDelete && has ? "Sure?" : "Delete"));
	}

	private double maxScroll() {
		return Math.max(0, InvProfileStore.all().size() * ROW_H - (listBottom - listTop));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.text(this.font, "Inventory Profiles", frameX, 10, COL_TEXT);
		g.text(this.font, "Double click a profile to load it", frameX, 22, COL_DIM);
		g.fill(frameX, listTop, frameX + frameW, listBottom, 0x80101010);
		g.enableScissor(frameX, listTop, frameX + frameW, listBottom);
		List<InvProfile> all = InvProfileStore.all();
		for (int i = 0; i < all.size(); i++) {
			InvProfile p = all.get(i);
			int y = listTop + i * ROW_H - (int) scroll;
			if (y + ROW_H < listTop || y > listBottom) {
				continue;
			}
			boolean hover = mouseX >= frameX && mouseX < frameX + frameW && mouseY >= y && mouseY < y + ROW_H
				&& mouseY >= listTop && mouseY < listBottom;
			if (p.id.equals(selectedId)) {
				g.fill(frameX, y, frameX + frameW, y + ROW_H, 0xA02E6E9E);
			} else if (hover) {
				g.fill(frameX, y, frameX + frameW, y + ROW_H, 0x60FFFFFF);
			}
			g.text(this.font, this.font.plainSubstrByWidth(p.name, frameW - 90), frameX + 6, y + 7, COL_TEXT);
			String count = p.configured() + " slots";
			g.text(this.font, count, frameX + frameW - this.font.width(count) - 6, y + 7, COL_DIM);
		}
		if (all.isEmpty()) {
			g.text(this.font, "No profile yet. Press Create profile.", frameX + 6, listTop + 8, COL_DIM);
		}
		g.disableScissor();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		double mx = event.x();
		double my = event.y();
		if (mx < frameX || mx >= frameX + frameW || my < listTop || my >= listBottom) {
			return false;
		}
		int index = (int) ((my - listTop + scroll) / ROW_H);
		List<InvProfile> all = InvProfileStore.all();
		if (index < 0 || index >= all.size()) {
			selectedId = null;
			confirmDelete = false;
			updateButtons();
			return true;
		}
		InvProfile p = all.get(index);
		if (!p.id.equals(selectedId)) {
			confirmDelete = false;
		}
		selectedId = p.id;
		updateButtons();
		if (doubleClick) {
			this.minecraft.gui.setScreen(null);
			InvProfileLoader.start(this.minecraft, p);
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		scroll = Math.max(0, Math.min(maxScroll(), scroll - scrollY * ROW_H));
		return true;
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}
}
