package khoa.tools.client.feature.fakeplayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;

/** Editor of one fake player: name, skin, pose, items, armor, effects, copy-from-player and the teleport item. */
public final class FakePlayerScreen extends Screen {
	private static final String[] POSES = {"STANDING", "CROUCHING", "SWIMMING", "FALL_FLYING"};
	private static final int OK_COLOR = 0xFFE0E0E0;
	private static final int BAD_COLOR = 0xFFFF5555;
	private static final String NAME = "Name";
	private static final String SKIN = "Skin (player name or .png path; empty = Name)";
	private static final String MAIN = "Main hand";
	private static final String OFF = "Off hand";
	private static final String EFFECTS = "Effects (speed, regeneration:1, glowing)";
	private static final String COPY = "Copy from player (empty = you)";
	private static final String HEAD = "Head";
	private static final String CHEST = "Chest";
	private static final String LEGS = "Legs";
	private static final String FEET = "Feet";

	private final Screen parent;
	private final FakePlayerData data;
	private final Map<String, EditBox> boxes = new LinkedHashMap<>();
	private final Map<String, String> errors = new LinkedHashMap<>();
	private final Map<String, String> typed = new LinkedHashMap<>();
	private boolean slim;
	private boolean tpCopiesItems;
	private boolean physics;
	private int poseIndex;
	private boolean confirmDelete;
	private String status = "";
	// inventory that Save will store (copied from a player, or what is already saved)
	private List<String> inventory;
	private int[] inventoryCounts;
	private int selectedSlot;

	public FakePlayerScreen(Screen parent, FakePlayerData data) {
		super(Component.literal("Fake Player #" + data.id));
		this.parent = parent;
		this.data = data;
		this.slim = data.slim;
		this.tpCopiesItems = data.tpCopiesItems;
		this.physics = data.physics;
		this.poseIndex = Math.max(0, Arrays.asList(POSES).indexOf(data.pose));
		this.inventory = new ArrayList<>(data.inventory);
		this.inventoryCounts = data.inventoryCounts.clone();
		this.selectedSlot = data.selectedSlot;
		typed.put(NAME, data.name);
		typed.put(SKIN, data.skin);
		typed.put(MAIN, data.mainHand);
		typed.put(OFF, data.offHand);
		typed.put(EFFECTS, data.effects);
		typed.put(COPY, "");
		typed.put(HEAD, data.head);
		typed.put(CHEST, data.chest);
		typed.put(LEGS, data.legs);
		typed.put(FEET, data.feet);
	}

	@Override
	protected void init() {
		this.clearWidgets();
		boxes.clear();
		int colW = Math.min(210, (this.width - 36) / 2);
		int leftX = this.width / 2 - colW - 4;
		int rightX = this.width / 2 + 4;
		int pitch = this.height >= 330 ? 28 : 25;
		int top = 24;
		String[] left = {NAME, SKIN, MAIN, OFF, EFFECTS, COPY};
		String[] right = {HEAD, CHEST, LEGS, FEET};
		for (int i = 0; i < left.length; i++) addBox(left[i], leftX, top + i * pitch, colW);
		for (int i = 0; i < right.length; i++) addBox(right[i], rightX, top + i * pitch, colW);
		int half = (colW - 4) / 2;
		int row4 = top + 4 * pitch + 7;
		int row5 = top + 5 * pitch + 7;
		int row6 = top + 6 * pitch + 7;
		this.addRenderableWidget(Button.builder(Component.literal("Slim arms: " + (slim ? "on" : "off")), b -> {
			slim = !slim;
			b.setMessage(Component.literal("Slim arms: " + (slim ? "on" : "off")));
		}).bounds(rightX, row4, half, 18).build());
		this.addRenderableWidget(Button.builder(Component.literal("Pose: " + POSES[poseIndex]), b -> {
			poseIndex = (poseIndex + 1) % POSES.length;
			b.setMessage(Component.literal("Pose: " + POSES[poseIndex]));
		}).bounds(rightX + half + 4, row4, half, 18).build());
		this.addRenderableWidget(Button.builder(Component.literal("Copy player's data"), b -> copyPlayer()).bounds(rightX, row5, half, 18).build());
		this.addRenderableWidget(Button.builder(Component.literal("Inventory"), b -> openInventory()).bounds(rightX + half + 4, row5, half, 18).build());
		this.addRenderableWidget(Button.builder(Component.literal("TP copies armor/offhand: " + (tpCopiesItems ? "on" : "off")), b -> {
			tpCopiesItems = !tpCopiesItems;
			b.setMessage(Component.literal("TP copies armor/offhand: " + (tpCopiesItems ? "on" : "off")));
		}).bounds(leftX, row6, colW, 18).build());
		this.addRenderableWidget(Button.builder(Component.literal("Physics: " + (physics ? "on" : "off")), b -> {
			physics = !physics;
			b.setMessage(Component.literal("Physics: " + (physics ? "on" : "off")));
		}).bounds(rightX, row6, colW, 18).build());

		int by = this.height - 26;
		int bw = Math.min(96, (this.width - 24) / 5 - 4);
		int bx = this.width / 2 - (bw * 5 + 16) / 2;
		this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).bounds(bx, by, bw, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Give TP item"), b -> giveItem()).bounds(bx + (bw + 4), by, bw, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Tp to me now"), b -> tpNow()).bounds(bx + 2 * (bw + 4), by, bw, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal(confirmDelete ? "Sure?" : "Delete"), b -> {
			if (!confirmDelete) {
				confirmDelete = true;
				b.setMessage(Component.literal("Sure?"));
				return;
			}
			FakePlayerManager.delete(this.minecraft, data);
			this.minecraft.gui.setScreen(parent);
		}).bounds(bx + 3 * (bw + 4), by, bw, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(bx + 4 * (bw + 4), by, bw, 20).build());
		validateAll();
	}

	private void addBox(String label, int x, int y, int w) {
		EditBox box = new EditBox(this.font, x, y + 9, w, 16, Component.literal(label));
		box.setMaxLength(label.equals(NAME) ? 16 : 100000);
		box.setValue(typed.getOrDefault(label, ""));
		box.setResponder(value -> {
			typed.put(label, value);
			confirmDelete = false;
			validate(label);
		});
		this.addRenderableWidget(box);
		boxes.put(label, box);
	}

	private String value(String label) {
		return typed.getOrDefault(label, "");
	}

	private void validateAll() {
		for (String label : boxes.keySet()) validate(label);
	}

	/** Sets the box colour and remembers the error text of one field. */
	private void validate(String label) {
		String err = check(label, value(label));
		if (err == null) errors.remove(label);
		else errors.put(label, err);
		EditBox box = boxes.get(label);
		if (box != null) box.setTextColor(err == null ? OK_COLOR : BAD_COLOR);
	}

	private static String check(String label, String text) {
		String t = text.trim();
		switch (label) {
			case COPY:
				return t.isEmpty() || StringUtil.isValidPlayerName(t) ? null : "Player name has no spaces.";
			case NAME:
				return t.isEmpty() || !StringUtil.isValidPlayerName(t) ? "Name must be 1-16 characters with no spaces." : null;
			case SKIN:
				if (t.isEmpty()) return null;
				if (FakePlayerSkins.isFile(t)) return Files.isRegularFile(Path.of(t)) ? null : "Skin file not found.";
				return StringUtil.isValidPlayerName(t) ? null : "Skin must be a player name (no spaces) or a .png file path.";
			case EFFECTS:
				FakePlayerEffects.Result e = FakePlayerEffects.parse(t);
				return e.ok() ? null : e.error();
			default:
				FakePlayerItems.Result r = FakePlayerItems.parse(t);
				return r.ok() ? null : r.error();
		}
	}

	private static String shortName(String label) {
		return label.split(" ")[0];
	}

	/** Copies the screen into the data object. Returns false (and shows the error) when something is invalid. */
	private boolean apply() {
		validateAll();
		if (!errors.isEmpty()) {
			Map.Entry<String, String> first = errors.entrySet().iterator().next();
			status = shortName(first.getKey()) + ": " + first.getValue();
			return false;
		}
		data.name = value(NAME).trim();
		data.skin = value(SKIN).trim();
		data.mainHand = value(MAIN).trim();
		data.offHand = value(OFF).trim();
		data.effects = value(EFFECTS).trim();
		data.head = value(HEAD).trim();
		data.chest = value(CHEST).trim();
		data.legs = value(LEGS).trim();
		data.feet = value(FEET).trim();
		data.slim = slim;
		data.pose = POSES[poseIndex];
		data.tpCopiesItems = tpCopiesItems;
		data.physics = physics;
		data.inventory = new ArrayList<>(inventory);
		data.inventoryCounts = inventoryCounts.clone();
		data.selectedSlot = selectedSlot;
		return true;
	}

	private void copyPlayer() {
		if (errors.containsKey(COPY)) return;
		FakePlayerData tmp = new FakePlayerData();
		String err = FakePlayerCopy.copy(this.minecraft, value(COPY), tmp);
		if (err != null) {
			status = err;
			return;
		}
		typed.put(NAME, tmp.name);
		typed.put(SKIN, tmp.skin);
		typed.put(EFFECTS, tmp.effects);
		typed.put(MAIN, tmp.mainHand);
		typed.put(OFF, tmp.offHand);
		typed.put(HEAD, tmp.head);
		typed.put(CHEST, tmp.chest);
		typed.put(LEGS, tmp.legs);
		typed.put(FEET, tmp.feet);
		slim = tmp.slim;
		inventory = new ArrayList<>(tmp.inventory);
		inventoryCounts = tmp.inventoryCounts.clone();
		selectedSlot = tmp.selectedSlot;
		status = "Copied " + tmp.name + (tmp.inventory.isEmpty() ? " (hands + armor only; other players' inventory is not visible to you)." : " (hands, armor, hotbar and inventory).")
			+ " Press Save to keep it.";
		this.rebuildWidgets();
	}

	private void openInventory() {
		this.minecraft.gui.setScreen(new FakePlayerInventoryScreen(this, inventory, inventoryCounts, selectedSlot, text -> {
			typed.put(MAIN, text);
			status = "Main hand set from the inventory. Press Save to keep it.";
		}));
	}

	private void save() {
		if (!apply()) return;
		FakePlayerStore.save();
		FakePlayerManager.spawn(this.minecraft, data);
		FakePlayerManager.refreshWand(this.minecraft, data);
		status = "Saved.";
	}

	private void giveItem() {
		if (!apply()) return;
		FakePlayerStore.save();
		FakePlayerManager.spawn(this.minecraft, data);
		FakePlayerManager.giveWand(this.minecraft, data);
		status = "Teleport item given. Close this screen, walk to the spot you want and right click with it.";
	}

	private void tpNow() {
		if (!apply()) return;
		FakePlayerManager.teleportToPlayer(this.minecraft, data, false);
		status = "Teleported to your position (pose kept as chosen).";
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
		for (Map.Entry<String, EditBox> e : boxes.entrySet()) {
			int color = errors.containsKey(e.getKey()) ? BAD_COLOR : 0xFFAAAAAA;
			g.text(this.font, e.getKey(), e.getValue().getX(), e.getValue().getY() - 9, color);
		}
		int y = this.height - 52;
		if (!errors.isEmpty()) {
			Map.Entry<String, String> first = errors.entrySet().iterator().next();
			g.text(this.font, shortName(first.getKey()) + ": " + first.getValue(), 14, y, BAD_COLOR);
		} else if (!status.isEmpty()) {
			g.text(this.font, status, 14, y, 0xFF55FF77);
		}
		g.text(this.font, String.format("Position %.1f %.1f %.1f", data.x, data.y, data.z), 14, this.height - 40, 0xFF888888);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}
}
