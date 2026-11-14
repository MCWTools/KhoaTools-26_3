package khoa.tools.client.feature.waypoints;

import java.util.List;
import java.util.function.Consumer;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Shown when no Xaero folder matches the current world: pick the Xaero world to import from. */
final class XaeroPickScreen extends Screen {
	private static final int ROW_H = 24;

	private final Screen parent;
	private final List<XaeroImport.Folder> folders;
	private final Consumer<XaeroImport.Result> done;
	private double scroll;
	private int frameX;
	private int frameW;
	private int listTop;
	private int listBottom;

	XaeroPickScreen(Screen parent, List<XaeroImport.Folder> folders, Consumer<XaeroImport.Result> done) {
		super(Component.literal("Import from Xaero"));
		this.parent = parent;
		this.folders = folders;
		this.done = done;
	}

	@Override
	protected void init() {
		frameW = Math.min(this.width - 24, 460);
		frameX = (this.width - frameW) / 2;
		listTop = 40;
		listBottom = this.height - 34;
		this.addRenderableWidget(Button.builder(Component.literal("< Back"), b -> onClose()).bounds(frameX, this.height - 26, 70, 20).build());
	}

	private double maxScroll() {
		return Math.max(0, folders.size() * ROW_H - (listBottom - listTop));
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		scroll = Math.max(0, Math.min(maxScroll(), scroll - scrollY * ROW_H));
		return true;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || event.x() < frameX || event.x() >= frameX + frameW || event.y() < listTop || event.y() >= listBottom) {
			return false;
		}
		int index = (int) ((event.y() - listTop + scroll) / ROW_H);
		if (index >= 0 && index < folders.size()) {
			XaeroImport.Result result = XaeroImport.importFolder(this.minecraft, folders.get(index));
			this.minecraft.gui.setScreen(parent);
			done.accept(result);
			return true;
		}
		return false;
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.text(this.font, "Import from Xaero", frameX, 8, 0xFFFFFFFF);
		g.text(this.font, "No Xaero folder matches this world. Click the one to import (only new waypoints are added):", frameX, 24, 0xFFAAAAAA);
		g.fill(frameX, listTop, frameX + frameW, listBottom, 0xA0101010);
		g.enableScissor(frameX, listTop, frameX + frameW, listBottom);
		for (int i = 0; i < folders.size(); i++) {
			int y = listTop - (int) scroll + i * ROW_H;
			if (y + ROW_H < listTop || y > listBottom) {
				continue;
			}
			boolean hover = mouseX >= frameX && mouseX < frameX + frameW && mouseY >= Math.max(y, listTop) && mouseY < Math.min(y + ROW_H, listBottom);
			g.fill(frameX + 2, y + 1, frameX + frameW - 2, y + ROW_H - 1, hover ? 0xB0383838 : 0x90202020);
			XaeroImport.Folder folder = folders.get(i);
			g.text(this.font, this.font.plainSubstrByWidth(folder.label(), frameW - 90), frameX + 8, y + 8, 0xFFFFFFFF);
			String count = folder.count() + " waypoints";
			g.text(this.font, count, frameX + frameW - 8 - this.font.width(count), y + 8, 0xFFAAAAAA);
		}
		g.disableScissor();
		super.extractRenderState(g, mouseX, mouseY, partialTick);
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
