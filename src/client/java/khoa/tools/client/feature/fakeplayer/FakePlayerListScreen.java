package khoa.tools.client.feature.fakeplayer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** All fake players of this server + dimension. */
public final class FakePlayerListScreen extends Screen {
	private final Screen parent;
	private FakeList list;
	private boolean confirmDelete;

	public FakePlayerListScreen(Screen parent) {
		super(Component.literal("Fake Players"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.clearWidgets();
		int listW = Math.min(440, this.width - 32);
		this.list = new FakeList(this.minecraft, listW, this.height - 90, 30, 18);
		this.list.setX(this.width / 2 - listW / 2);
		for (FakePlayerData d : FakePlayerStore.current(this.minecraft)) this.list.addRow(d);
		this.addRenderableWidget(this.list);
		int cx = this.width / 2;
		int by = this.height - 28;
		this.addRenderableWidget(Button.builder(Component.literal("Edit"), b -> {
			FakeRow row = list.getSelected();
			if (row != null) this.minecraft.gui.setScreen(new FakePlayerScreen(this, row.data));
		}).bounds(cx - 262, by, 80, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("New at my position"), b -> {
			if (this.minecraft.player == null) return;
			FakePlayerData d = FakePlayerStore.create(this.minecraft);
			FakePlayerManager.captureFrom(this.minecraft, d, true);
			FakePlayerStore.save();
			FakePlayerManager.spawn(this.minecraft, d);
			this.minecraft.gui.setScreen(new FakePlayerScreen(this, d));
		}).bounds(cx - 178, by, 130, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal(confirmDelete ? "Sure?" : "Delete"), b -> {
			FakeRow row = list.getSelected();
			if (row == null) return;
			if (!confirmDelete) {
				confirmDelete = true;
				b.setMessage(Component.literal("Sure?"));
				return;
			}
			confirmDelete = false;
			FakePlayerManager.delete(this.minecraft, row.data);
			this.rebuildWidgets();
		}).bounds(cx - 44, by, 80, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(cx + 40, by, 80, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);
		g.centeredText(this.font, "Client-side only, saved per server and dimension.", this.width / 2, 20, 0xFFAAAAAA);
		if (list != null && list.children().isEmpty()) {
			g.centeredText(this.font, "No fake players here yet. Press 'New at my position'.", this.width / 2, 60, 0xFFAAAAAA);
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}

	private final class FakeList extends ObjectSelectionList<FakeRow> {
		FakeList(Minecraft mc, int w, int h, int y, int rowH) {
			super(mc, w, h, y, rowH);
		}

		void addRow(FakePlayerData d) {
			this.addEntry(new FakeRow(d));
		}
	}

	private final class FakeRow extends ObjectSelectionList.Entry<FakeRow> {
		final FakePlayerData data;

		FakeRow(FakePlayerData data) {
			this.data = data;
		}

		@Override
		public Component getNarration() {
			return Component.literal(data.name);
		}

		@Override
		public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float partialTick) {
			String text = "#" + data.id + "  " + data.name + "   " + String.format("%.0f %.0f %.0f", data.x, data.y, data.z) + "   " + data.pose;
			g.text(FakePlayerListScreen.this.font, text, getContentX() + 4, getContentYMiddle() - 4, 0xFFFFFFFF);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			list.setSelected(this);
			confirmDelete = false;
			if (doubleClick) FakePlayerListScreen.this.minecraft.gui.setScreen(new FakePlayerScreen(FakePlayerListScreen.this, data));
			return true;
		}
	}
}
