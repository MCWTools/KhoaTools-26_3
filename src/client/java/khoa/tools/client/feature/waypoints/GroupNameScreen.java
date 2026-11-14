package khoa.tools.client.feature.waypoints;

import java.util.function.Consumer;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Small screen that asks for a group name (new group or rename). */
final class GroupNameScreen extends Screen {
	private final Screen parent;
	private final String initial;
	private final String okText;
	private final Consumer<String> onOk;
	private EditBox box;

	GroupNameScreen(Screen parent, String title, String initial, String okText, Consumer<String> onOk) {
		super(Component.literal(title));
		this.parent = parent;
		this.initial = initial;
		this.okText = okText;
		this.onOk = onOk;
	}

	@Override
	protected void init() {
		int w = 240;
		int x = (this.width - w) / 2;
		int y = this.height / 2 - 20;
		String keep = box == null ? initial : box.getValue();
		box = new EditBox(this.font, x, y, w, 20, Component.literal("Group name"));
		box.setMaxLength(32);
		box.setHint(Component.literal("Group name"));
		box.setValue(keep);
		this.addRenderableWidget(box);
		this.setInitialFocus(box);
		this.addRenderableWidget(Button.builder(Component.literal(okText), b -> confirm()).bounds(x, y + 28, w / 2 - 2, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(x + w / 2 + 2, y + 28, w / 2 - 2, 20).build());
	}

	private void confirm() {
		String name = box.getValue().trim();
		this.minecraft.gui.setScreen(parent);
		onOk.accept(name.isEmpty() ? "Group" : name);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
			confirm();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.centeredText(this.font, this.title.getString(), this.width / 2, this.height / 2 - 38, 0xFFFFFFFF);
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
