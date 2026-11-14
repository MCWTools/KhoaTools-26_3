package khoa.tools.client.feature.blocktext;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Edit the text on a block side. Every change is applied to the text in the world at once (you see it next to the panel);
 * Done keeps it, Cancel puts back what it was (or removes a brand new text).
 */
final class BlockTextScreen extends Screen {
	private static final int PANEL_W = 232;
	private static final int MAX_CHARS = 220;
	private static final int ROW_SCALE = 24 + 86 + 24 + 24;
	private static final int ROW_COLOR = ROW_SCALE + 28;

	private final BlockText target;
	private final BlockText before;
	private final boolean created;
	private MultiLineEditBox box;
	private Button fontButton;
	private Button wrapButton;
	private Button outlineButton;
	private Button autoButton;
	private EditBox scaleBox;
	private boolean finished;

	BlockTextScreen(BlockText target, boolean created) {
		super(Component.literal("Block text"));
		this.target = target;
		this.before = target.copy();
		this.created = created;
	}

	@Override
	protected void init() {
		int x = 12;
		int w = PANEL_W - 16;
		int y = 24;
		box = MultiLineEditBox.builder().setPlaceholder(Component.literal("Type the text here...")).build(this.font, w, 78, Component.literal("Text"));
		box.setX(x);
		box.setY(y);
		box.setCharacterLimit(MAX_CHARS);
		box.setValue(target.text);
		box.setValueListener(v -> target.text = v);
		this.addRenderableWidget(box);
		this.setInitialFocus(box);

		y += 86;
		int fontW = (w - 4) / 2;
		fontButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			target.font = (target.font + 1) % PixelFont.NAMES.length;
			refreshLabels();
		}).bounds(x, y, fontW, 20).build());
		wrapButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			target.wrapWords = !target.wrapWords;
			refreshLabels();
		}).bounds(x + fontW + 4, y, w - fontW - 4, 20).build());
		y += 24;
		int half = (w - 4) / 2;
		outlineButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			target.outline = !target.outline;
			refreshLabels();
		}).bounds(x, y, half, 20).build());
		autoButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> {
			target.autoScale = !target.autoScale;
			refreshLabels();
		}).bounds(x + half + 4, y, w - half - 4, 20).build());
		y += 24;
		scaleBox = new EditBox(this.font, x + 120, y, 50, 18, Component.literal("Scale"));
		scaleBox.setMaxLength(3);
		scaleBox.setValue(Integer.toString(target.scale));
		scaleBox.setResponder(v -> {
			try {
				target.scale = Math.max(10, Math.min(100, Integer.parseInt(v.trim())));
			} catch (NumberFormatException ignored) {
				// keep the old number while the text is not valid
			}
		});
		this.addRenderableWidget(scaleBox);
		y += 28;
		rgbRow(x, y, "Color", () -> target.rgb, v -> target.rgb = v);
		y += 24;
		rgbRow(x, y, "Outline", () -> target.outlineRgb, v -> target.outlineRgb = v);
		y += 32;
		this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> done()).bounds(x, y, half, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> cancel()).bounds(x + half + 4, y, w - half - 4, 20).build());
		refreshLabels();
	}

	/** Three number boxes (0 - 255) for red, green and blue. */
	private void rgbRow(int x, int y, String label, IntSupplier get, IntConsumer set) {
		for (int i = 0; i < 3; i++) {
			final int shift = 16 - 8 * i;
			EditBox e = new EditBox(this.font, x + 56 + i * 52, y, 46, 18, Component.literal(label + " " + "RGB".charAt(i)));
			e.setMaxLength(3);
			e.setValue(Integer.toString(get.getAsInt() >> shift & 255));
			e.setResponder(v -> {
				try {
					int value = Math.max(0, Math.min(255, Integer.parseInt(v.trim())));
					set.accept((get.getAsInt() & ~(255 << shift)) | (value << shift));
				} catch (NumberFormatException ignored) {
					// keep the old number while the text is not valid
				}
			});
			this.addRenderableWidget(e);
		}
	}

	private void refreshLabels() {
		fontButton.setMessage(Component.literal("Font: " + PixelFont.NAMES[target.font]));
		wrapButton.setMessage(Component.literal("Wrap: " + (target.wrapWords ? "Words" : "Letters")));
		outlineButton.setMessage(Component.literal("Outline: " + (target.outline ? "ON" : "OFF")));
		autoButton.setMessage(Component.literal("Auto scale: " + (target.autoScale ? "ON" : "OFF")));
		scaleBox.setEditable(!target.autoScale);
		scaleBox.active = !target.autoScale;
	}

	private void done() {
		finished = true;
		if (target.text.isBlank()) {
			BlockTextFeature.remove(target);
		}
		BlockTextFeature.saved();
		this.minecraft.gui.setScreen(null);
	}

	/** Esc / closing the screen keeps the text, like Done. */
	@Override
	public void onClose() {
		if (!finished) {
			done();
		} else {
			this.minecraft.gui.setScreen(null);
		}
	}

	private void cancel() {
		if (!finished) {
			finished = true;
			if (created) {
				BlockTextFeature.remove(target);
			} else {
				target.set(before);
			}
			BlockTextFeature.saved();
		}
		this.minecraft.gui.setScreen(null);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.fill(4, 4, PANEL_W + 4, this.height - 4, 0xB0101010);
		g.text(this.font, "Block text  (" + target.text.length() + "/" + MAX_CHARS + ")", 12, 10, 0xFFFFFFFF);
		g.text(this.font, "Scale % (only if auto is off)", 12, ROW_SCALE + 5 - 4, 0xFFAAAAAA);
		g.text(this.font, "Color", 12, ROW_COLOR + 5, 0xFFAAAAAA);
		g.text(this.font, "Outline", 12, ROW_COLOR + 24 + 5, 0xFFAAAAAA);
		super.extractRenderState(g, mouseX, mouseY, partialTick);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
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
