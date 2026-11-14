package khoa.tools.client.feature.fakeplayer;

import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Read-only view of the copied hotbar + inventory. Clicking a slot makes that item the fake player's main hand. */
public final class FakePlayerInventoryScreen extends Screen {
	private static final int SLOT = 20;

	private final Screen parent;
	private final List<String> items;
	private final int[] counts;
	private final int selected;
	private final Consumer<String> pickMainHand;
	private final ItemStack[] stacks = new ItemStack[36];

	public FakePlayerInventoryScreen(Screen parent, List<String> items, int[] counts, int selected, Consumer<String> pickMainHand) {
		super(Component.literal("Copied inventory"));
		this.parent = parent;
		this.items = items;
		this.counts = counts;
		this.selected = selected;
		this.pickMainHand = pickMainHand;
		for (int i = 0; i < 36; i++) {
			ItemStack stack = ItemStack.EMPTY;
			if (i < items.size() && !items.get(i).isEmpty()) {
				stack = FakePlayerItems.parse(items.get(i)).stack();
				if (!stack.isEmpty() && i < counts.length && counts[i] > 1) stack.setCount(counts[i]);
			}
			stacks[i] = stack;
		}
	}

	@Override
	protected void init() {
		this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(this.width / 2 - 50, this.height - 28, 100, 20).build());
	}

	private int gridX() {
		return this.width / 2 - SLOT * 9 / 2;
	}

	private int slotY(int slot) {
		// hotbar on the bottom row like the real inventory
		int row = slot < 9 ? 3 : (slot - 9) / 9;
		return 50 + row * SLOT + (slot < 9 ? 6 : 0);
	}

	private int slotX(int slot) {
		return gridX() + (slot % 9) * SLOT;
	}

	private int slotAt(double mx, double my) {
		for (int i = 0; i < 36; i++) {
			if (mx >= slotX(i) && mx < slotX(i) + SLOT && my >= slotY(i) && my < slotY(i) + SLOT) return i;
		}
		return -1;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
		if (items.isEmpty()) {
			g.centeredText(this.font, "Nothing stored. Copy from yourself (leave the player box empty) to store your hotbar and inventory.", this.width / 2, 60, 0xFFAAAAAA);
			return;
		}
		g.centeredText(this.font, "Click a slot to set it as the main hand.", this.width / 2, 26, 0xFFAAAAAA);
		int hover = slotAt(mouseX, mouseY);
		for (int i = 0; i < 36; i++) {
			int x = slotX(i);
			int y = slotY(i);
			g.fill(x, y, x + SLOT - 1, y + SLOT - 1, i == selected ? 0xFF6A6A2A : 0xFF3A3A3A);
			if (i == hover) g.fill(x, y, x + SLOT - 1, y + SLOT - 1, 0x60FFFFFF);
			if (!stacks[i].isEmpty()) {
				g.item(stacks[i], x + 1, y + 1);
				g.itemDecorations(this.font, stacks[i], x + 1, y + 1);
			}
		}
		if (hover >= 0 && !stacks[hover].isEmpty()) g.setTooltipForNextFrame(this.font, stacks[hover], mouseX, mouseY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int slot = slotAt(event.x(), event.y());
		if (slot >= 0 && slot < items.size()) {
			pickMainHand.accept(items.get(slot));
			this.minecraft.gui.setScreen(parent);
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}
}
