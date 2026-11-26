package khoa.tools.client.feature.waypoints;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.mojang.blaze3d.platform.InputConstants;

import com.google.gson.Gson;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import khoa.tools.client.config.KhoaConfig;

/**
 * The waypoint manager: sortable, multi-select list of waypoints and groups.
 * Group = merge the selection into one folder (double click opens it, drag items onto it to move them in, groups can hold groups).
 * Death points are always on top (Latest death -> Old death).
 */
public final class WaypointScreen extends Screen {
	private static final String[] SORTS = { "Distance", "Name", "Created", "Modified", "Color" };
	private static final int ROW_H = 26;
	private static final int TOP_BAR_Y = 22;
	private static final int BAR_H = 20;
	private static final int GAP = 4;

	private static final int COL_TEXT = 0xFFFFFFFF;
	private static final int COL_DIM = 0xFFAAAAAA;
	private static final int COL_FOLDER = 0xFFE0B040;
	private static final int BG_PANEL = 0xA0101010;
	private static final int BG_ROW = 0x90202020;
	private static final int BG_HOVER = 0xB0383838;
	private static final int BG_SELECTED = 0xA02E6E9E;
	private static final int BORDER = 0xFF555555;

	private static final String[] EYE = {
		"..#####..",
		".#.....#.",
		"#..###..#",
		"#..###..#",
		".#.....#.",
		"..#####.." };

	/** A row: a waypoint, a group, or the ".." row that goes one level up. */
	private record Entry(Waypoint wp, Group group, boolean up) {
		String id() {
			return up ? "" : wp != null ? wp.id : group.id;
		}

		boolean isGroup() {
			return group != null;
		}
	}

	private final Screen parent;
	private WorldData data;
	private String openGroup;
	private final Set<String> selected = new LinkedHashSet<>();
	private List<Entry> view = List.of();
	private String anchor;
	private double scroll;
	private int sortTick;

	// dragging items onto a group
	private String pressedId;
	private String collapseTo;
	private double pressX;
	private double pressY;
	private boolean dragging;

	private String status = "";
	private int statusColor = COL_DIM;
	private long statusUntil;
	private long confirmDeleteUntil;

	private Button edit;
	private Button teleport;
	private Button groupButton;
	private Button delete;
	private Button toggle;
	private Button sort;
	private Button back;
	private Button dimButton;

	/** Dimension whose waypoints are listed (starts as the one you are in). */
	private String viewDim;
	private boolean dimOpen;
	private static final int DIM_W = 170;
	private static final int DIM_ITEM_H = 16;
	private static final Gson GSON = new Gson();
	/** Ctrl+C / Ctrl+X: copies of the waypoints, kept between openings of this screen. */
	private static final List<Waypoint> CLIPBOARD = new ArrayList<>();
	private static boolean clipCut;

	private int frameX;
	private int frameW;
	private int listTop;
	private int listBottom;

	public WaypointScreen(Screen parent) {
		super(Component.literal("Waypoints"));
		this.parent = parent;
	}

	// ------------------------------------------------------------------ layout

	@Override
	protected void init() {
		data = WaypointStore.world(Minecraft.getInstance());
		frameW = Math.min(this.width - 24, 640);
		frameX = (this.width - frameW) / 2;
		listTop = TOP_BAR_Y + BAR_H + 6;
		listBottom = this.height - 30;

		int n = 8;
		int bw = (frameW - GAP * (n - 1)) / n;
		int x = frameX;
		this.addRenderableWidget(Button.builder(Component.literal("Create"), b -> this.minecraft.gui.setScreen(new WaypointEditScreen(this, null))).bounds(x, TOP_BAR_Y, bw, BAR_H).build());
		x += bw + GAP;
		edit = this.addRenderableWidget(Button.builder(Component.literal("Edit"), b -> editSelected()).bounds(x, TOP_BAR_Y, bw, BAR_H).build());
		x += bw + GAP;
		groupButton = this.addRenderableWidget(Button.builder(Component.literal("Group"), b -> groupOrUnpack()).bounds(x, TOP_BAR_Y, bw, BAR_H).build());
		x += bw + GAP;
		teleport = this.addRenderableWidget(Button.builder(Component.literal("Teleport"), b -> teleportSelected()).bounds(x, TOP_BAR_Y, bw, BAR_H).build());
		x += bw + GAP;
		toggle = this.addRenderableWidget(Button.builder(Component.literal("Show / Hide"), b -> toggleSelected()).bounds(x, TOP_BAR_Y, bw, BAR_H).build());
		x += bw + GAP;
		delete = this.addRenderableWidget(Button.builder(Component.literal("Delete"), b -> deleteSelected()).bounds(x, TOP_BAR_Y, bw, BAR_H).build());
		x += bw + GAP;
		this.addRenderableWidget(Button.builder(Component.literal("Import Xaero"), b -> importXaero(this.minecraft.hasShiftDown())).bounds(x, TOP_BAR_Y, bw, BAR_H)
			.tooltip(Tooltip.create(Component.literal("Import the Xaero waypoints of this server / world.\nShift+click: pick other servers / worlds to import."))).build());
		x += bw + GAP;
		sort = this.addRenderableWidget(Button.builder(Component.literal("Sort"), b -> cycleSort()).bounds(x, TOP_BAR_Y, frameX + frameW - x, BAR_H).build());
		back = this.addRenderableWidget(Button.builder(Component.literal("< Back"), b -> onClose()).bounds(frameX, this.height - 26, 70, BAR_H).build());
		if (viewDim == null) {
			viewDim = this.minecraft.level == null ? "minecraft:overworld" : WaypointsFeature.dimension(this.minecraft);
		}
		dimButton = this.addRenderableWidget(Button.builder(Component.empty(), b -> dimOpen = !dimOpen)
			.bounds(frameX + 76, this.height - 26, DIM_W, BAR_H).build());
		dimButton.setMessage(Component.literal("Dimension: " + dimName(viewDim) + " v"));
		rebuild();
		updateButtons();
	}

	private int sortMode() {
		return Math.floorMod(KhoaConfig.getInt(WaypointsFeature.ID, "sort", 0), SORTS.length);
	}

	private void cycleSort() {
		KhoaConfig.put(WaypointsFeature.ID, "sort", (sortMode() + 1) % SORTS.length);
		KhoaConfig.save();
		rebuild();
		updateButtons();
	}

	private double distance(Waypoint w) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || !w.dim.equals(WaypointsFeature.dimension(mc))) {
			return Double.MAX_VALUE;
		}
		return w.distanceTo(mc.player.getX(), mc.player.getY(), mc.player.getZ());
	}

	/** Nearest waypoint inside a group (all levels) in your dimension. */
	private double distance(Group g) {
		double best = Double.MAX_VALUE;
		for (Waypoint w : data.descendantWaypoints(g)) {
			best = Math.min(best, distance(w));
		}
		return best;
	}

	private static float[] hsb(Waypoint w) {
		return Color.RGBtoHSB((w.rgb >> 16) & 0xFF, (w.rgb >> 8) & 0xFF, w.rgb & 0xFF, null);
	}

	/** Death points first (Latest death -> Old death), then groups, then waypoints, each sorted by the chosen sort. */
	private void rebuild() {
		if (openGroup != null && data.group(openGroup) == null) {
			openGroup = null;
		}
		List<Waypoint> wps = new ArrayList<>();
		List<Group> groups = new ArrayList<>();
		if (openGroup == null) {
			for (Waypoint w : data.waypoints) {
				if (data.parentOf(w.id) == null) {
					wps.add(w);
				}
			}
			for (Group g : data.groups) {
				if (data.parentOf(g.id) == null) {
					groups.add(g);
				}
			}
		} else {
			Group open = data.group(openGroup);
			for (String id : open.waypoints) {
				if (data.waypoint(id) != null) {
					wps.add(data.waypoint(id));
				}
			}
			for (String id : open.groups) {
				if (data.group(id) != null) {
					groups.add(data.group(id));
				}
			}
		}
		// only this dimension is listed; a group shows when it is empty or has something here
		String here = viewDim;
		if (here != null) {
			wps.removeIf(w -> !w.dim.equals(here));
			groups.removeIf(g -> {
				List<Waypoint> inside = data.descendantWaypoints(g);
				return !inside.isEmpty() && inside.stream().noneMatch(w -> w.dim.equals(here));
			});
		}
		Comparator<Waypoint> byName = Comparator.comparing((Waypoint w) -> w.title().toLowerCase(Locale.ROOT));
		Comparator<Waypoint> base = switch (sortMode()) {
			case 1 -> byName;
			case 2 -> Comparator.comparingLong((Waypoint w) -> w.created).reversed().thenComparing(byName);
			case 3 -> Comparator.comparingLong((Waypoint w) -> w.modified).reversed().thenComparing(byName);
			case 4 -> Comparator.comparingDouble((Waypoint w) -> hsb(w)[0]).thenComparingDouble(w -> hsb(w)[2]).thenComparing(byName);
			default -> Comparator.comparingDouble((Waypoint w) -> distance(w)).thenComparing(byName);
		};
		Comparator<Group> groupName = Comparator.comparing((Group g) -> g.name.toLowerCase(Locale.ROOT));
		Comparator<Group> groupBase = switch (sortMode()) {
			case 2 -> Comparator.comparingLong((Group g) -> g.created).reversed().thenComparing(groupName);
			case 3 -> Comparator.comparingLong((Group g) -> g.modified).reversed().thenComparing(groupName);
			case 0 -> Comparator.comparingDouble((Group g) -> distance(g)).thenComparing(groupName);
			default -> groupName;
		};
		List<Waypoint> deaths = new ArrayList<>();
		List<Waypoint> others = new ArrayList<>();
		for (Waypoint w : wps) {
			(w.death ? deaths : others).add(w);
		}
		deaths.sort(Comparator.comparingInt(w -> w.deathRank));
		others.sort(base);
		groups.sort(groupBase);

		List<Entry> out = new ArrayList<>();
		if (openGroup != null) {
			out.add(new Entry(null, null, true));
		}
		for (Waypoint w : deaths) {
			out.add(new Entry(w, null, false));
		}
		for (Group g : groups) {
			out.add(new Entry(null, g, false));
		}
		for (Waypoint w : others) {
			out.add(new Entry(w, null, false));
		}
		view = out;
		Set<String> ids = new LinkedHashSet<>();
		for (Entry e : view) {
			if (!e.up()) {
				ids.add(e.id());
			}
		}
		selected.retainAll(ids);
		scroll = clampScroll(scroll);
	}

	private Entry selectedSingle() {
		if (selected.size() != 1) {
			return null;
		}
		String id = selected.iterator().next();
		for (Entry e : view) {
			if (!e.up() && e.id().equals(id)) {
				return e;
			}
		}
		return null;
	}

	private void updateButtons() {
		int n = selected.size();
		Entry single = selectedSingle();
		boolean oneGroup = single != null && single.isGroup();
		edit.setMessage(Component.literal(oneGroup ? "Rename" : "Edit"));
		edit.active = single != null;
		teleport.active = single != null && !single.isGroup();
		groupButton.setMessage(Component.literal(oneGroup ? "Unpack" : "Group"));
		groupButton.active = n >= 1;
		toggle.active = n >= 1;
		toggle.setMessage(Component.literal(n > 1 ? "Show / Hide (" + n + ")" : "Show / Hide"));
		boolean confirming = System.currentTimeMillis() < confirmDeleteUntil && n >= 1;
		delete.active = n >= 1;
		delete.setMessage(confirming ? Component.literal("§cConfirm (" + n + ")") : Component.literal(n > 1 ? "Delete (" + n + ")" : "Delete"));
		sort.setMessage(Component.literal("Sort: " + SORTS[sortMode()]));
		back.setMessage(Component.literal(openGroup != null ? "< Up" : "< Back"));
	}

	/** /tp to the top of the selected waypoint's block (middle of the box for an area). Needs permission to use /tp. */
	private void teleportSelected() {
		Entry single = selectedSingle();
		Minecraft mc = Minecraft.getInstance();
		if (single == null || single.isGroup() || mc.getConnection() == null || mc.level == null) {
			return;
		}
		Waypoint w = single.wp();
		double x = (w.minX() + w.maxX() + 1) / 2.0;
		double y = w.maxY() + 1;
		double z = (w.minZ() + w.maxZ() + 1) / 2.0;
		String pos = String.format(Locale.ROOT, "%.1f %d %.1f", x, (int) y, z);
		boolean sameDim = w.dim.equals(WaypointsFeature.dimension(mc));
		mc.getConnection().sendCommand(sameDim ? "tp @s " + pos : "execute in " + w.dim + " run tp @s " + pos);
		this.minecraft.gui.setScreen(null);
	}

	// ------------------------------------------------------------------ dimensions

	/** "minecraft:the_nether" -> "The Nether". */
	private static String dimName(String id) {
		String name = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		StringBuilder sb = new StringBuilder();
		for (String part : name.split("[_/]")) {
			if (!part.isEmpty()) {
				sb.append(sb.length() == 0 ? "" : " ").append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
			}
		}
		return sb.length() == 0 ? id : sb.toString();
	}

	/** The three vanilla dimensions, the one you are in and every dimension a waypoint already uses. */
	private List<String> dimChoices() {
		Set<String> all = new LinkedHashSet<>(List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"));
		if (this.minecraft.level != null) {
			all.add(WaypointsFeature.dimension(this.minecraft));
		}
		for (Waypoint w : data.waypoints) {
			all.add(w.dim);
		}
		all.add(viewDim);
		return new ArrayList<>(all);
	}

	private int[] dimRect(int count) {
		int h = count * DIM_ITEM_H + 2;
		return new int[] { dimButton.getX(), dimButton.getY() - h - 2, DIM_W, h };
	}

	private void renderDimList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		List<String> choices = dimChoices();
		int[] r = dimRect(choices.size());
		g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0xF0101010);
		border(g, r[0], r[1], r[0] + r[2], r[1] + r[3], COL_TEXT);
		for (int i = 0; i < choices.size(); i++) {
			int iy = r[1] + 1 + i * DIM_ITEM_H;
			boolean hover = mouseX >= r[0] + 1 && mouseX < r[0] + r[2] - 1 && mouseY >= iy && mouseY < iy + DIM_ITEM_H;
			if (hover) {
				g.fill(r[0] + 1, iy, r[0] + r[2] - 1, iy + DIM_ITEM_H, 0xFF404040);
			}
			g.text(this.font, this.font.plainSubstrByWidth(dimName(choices.get(i)), r[2] - 12), r[0] + 6, iy + 4, choices.get(i).equals(viewDim) ? 0xFF77DD77 : COL_TEXT);
		}
	}

	private void clickDimList(double mx, double my, int button) {
		dimOpen = false;
		if (button != InputConstants.MOUSE_BUTTON_LEFT) {
			return;
		}
		List<String> choices = dimChoices();
		int[] r = dimRect(choices.size());
		if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] + 1 && my < r[1] + r[3] - 1) {
			int index = (int) ((my - (r[1] + 1)) / DIM_ITEM_H);
			if (index >= 0 && index < choices.size() && !choices.get(index).equals(viewDim)) {
				viewDim = choices.get(index);
				openGroup = null;
				selected.clear();
				scroll = 0;
				dimButton.setMessage(Component.literal("Dimension: " + dimName(viewDim) + " v"));
				rebuild();
				updateButtons();
			}
		}
	}

	/** Ctrl+C / Ctrl+X: remember the selected waypoints (a selected group gives all the waypoints inside it). */
	private void copySelected(boolean cut) {
		List<Waypoint> list = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (Entry e : selectedEntries()) {
			List<Waypoint> found = e.isGroup() ? data.descendantWaypoints(e.group()) : List.of(e.wp());
			for (Waypoint w : found) {
				if (seen.add(w.id)) {
					list.add(w);
				}
			}
		}
		if (list.isEmpty()) {
			say("Select waypoints first.", 0xFFFFAA55);
			return;
		}
		CLIPBOARD.clear();
		for (Waypoint w : list) {
			CLIPBOARD.add(GSON.fromJson(GSON.toJson(w), Waypoint.class));
		}
		clipCut = cut;
		say((cut ? "Cut " : "Copied ") + list.size() + " waypoint" + (list.size() == 1 ? "" : "s") + ". Ctrl+V pastes into " + dimName(viewDim) + ".", 0xFF77DD77);
	}

	/** Ctrl+V: puts the copies into the dimension on view. Overworld <-> Nether coordinates are converted (x and z times / divided by 8). */
	private void paste() {
		if (CLIPBOARD.isEmpty()) {
			say("Nothing copied yet (Ctrl+C or Ctrl+X).", 0xFFFFAA55);
			return;
		}
		boolean convert = KhoaConfig.getBool(WaypointsFeature.ID, "convert", true);
		long now = System.currentTimeMillis();
		List<Waypoint> made = new ArrayList<>();
		boolean converted = false;
		for (Waypoint source : CLIPBOARD) {
			Waypoint w = GSON.fromJson(GSON.toJson(source), Waypoint.class);
			w.migrate();
			w.id = UUID.randomUUID().toString();
			if (source.death) {
				w.name = source.title();
				w.death = false;
				w.ticksLeft = -1;
			}
			int factor = convert ? coordFactor(source.dim, viewDim) : 1;
			if (factor != 1) {
				convertCoords(w, factor);
				converted = true;
			}
			w.dim = viewDim;
			w.created = now;
			w.modified = now;
			made.add(w);
		}
		Minecraft mc = Minecraft.getInstance();
		if (clipCut) {
			List<Waypoint> originals = new ArrayList<>();
			for (Waypoint source : CLIPBOARD) {
				Waypoint original = data.waypoint(source.id);
				if (original != null) {
					originals.add(original);
				}
			}
			WaypointStore.remove(mc, originals);
			CLIPBOARD.clear();
			clipCut = false;
		}
		WaypointStore.addAll(mc, made);
		if (openGroup != null) {
			data.move(made.stream().map(w -> w.id).toList(), openGroup);
			WaypointStore.saveNow();
		}
		selected.clear();
		for (Waypoint w : made) {
			selected.add(w.id);
		}
		rebuild();
		updateButtons();
		say("Pasted " + made.size() + " waypoint" + (made.size() == 1 ? "" : "s") + " into " + dimName(viewDim) + (converted ? " (coordinates converted)" : "") + ".", 0xFF77DD77);
	}

	/** 8 = nether to overworld (times 8), -8 = overworld to nether (divide by 8), 1 = no change (the End and other dimensions are not scaled). */
	private static int coordFactor(String from, String to) {
		if (from.equals("minecraft:the_nether") && to.equals("minecraft:overworld")) {
			return 8;
		}
		if (from.equals("minecraft:overworld") && to.equals("minecraft:the_nether")) {
			return -8;
		}
		return 1;
	}

	/** factor 8: x and z times 8 (a box covers the whole 8 x 8 columns); factor -8: x and z divided by 8, rounded down. */
	private static void convertCoords(Waypoint w, int factor) {
		boolean up = factor > 0;
		w.x = up ? w.x * 8 : Math.floorDiv(w.x, 8);
		w.z = up ? w.z * 8 : Math.floorDiv(w.z, 8);
		for (int[] b : w.blocks) {
			b[0] = up ? b[0] * 8 : Math.floorDiv(b[0], 8);
			b[2] = up ? b[2] * 8 : Math.floorDiv(b[2], 8);
		}
		for (int[] b : w.boxes) {
			b[0] = up ? b[0] * 8 : Math.floorDiv(b[0], 8);
			b[2] = up ? b[2] * 8 : Math.floorDiv(b[2], 8);
			b[3] = up ? b[3] * 8 + 7 : Math.floorDiv(b[3], 8);
			b[5] = up ? b[5] * 8 + 7 : Math.floorDiv(b[5], 8);
		}
		w.bounds = null;
	}

	private void say(String text, int color) {
		status = text;
		statusColor = color;
		statusUntil = System.currentTimeMillis() + 5000;
	}

	private String path() {
		StringBuilder sb = new StringBuilder();
		String id = openGroup;
		for (int guard = 0; id != null && guard < 100; guard++) {
			Group g = data.group(id);
			sb.insert(0, " > " + (g == null ? "?" : g.name));
			id = data.parentOf(id);
		}
		return "Waypoints" + sb;
	}

	// ------------------------------------------------------------------ actions

	private List<Entry> selectedEntries() {
		List<Entry> out = new ArrayList<>();
		for (Entry e : view) {
			if (!e.up() && selected.contains(e.id())) {
				out.add(e);
			}
		}
		return out;
	}

	private void editSelected() {
		Entry e = selectedSingle();
		if (e == null) {
			return;
		}
		if (e.isGroup()) {
			Group g = e.group();
			this.minecraft.gui.setScreen(new GroupNameScreen(this, "Rename group", g.name, "Rename", name -> {
				g.name = name;
				g.modified = System.currentTimeMillis();
				WaypointStore.saveNow();
				rebuild();
			}));
		} else {
			this.minecraft.gui.setScreen(new WaypointEditScreen(this, e.wp()));
		}
	}

	private void groupOrUnpack() {
		Entry single = selectedSingle();
		if (single != null && single.isGroup()) {
			String id = single.id();
			WaypointStore.backupNow("unpack");
			data.unpack(id);
			WaypointStore.saveNow();
			selected.clear();
			say("Group unpacked.", COL_DIM);
			rebuild();
			updateButtons();
			return;
		}
		if (selected.isEmpty()) {
			return;
		}
		List<String> ids = new ArrayList<>(selected);
		this.minecraft.gui.setScreen(new GroupNameScreen(this, "New group", "Group " + (data.groups.size() + 1), "Create", name -> {
			Group g = data.createGroup(name, ids, openGroup);
			WaypointStore.saveNow();
			selected.clear();
			selected.add(g.id);
			anchor = g.id;
			say("Grouped " + ids.size() + " into '" + g.name + "'. Double click it to open.", 0xFF77FF99);
			rebuild();
			updateButtons();
		}));
	}

	private void deleteSelected() {
		List<Entry> sel = selectedEntries();
		if (sel.isEmpty()) {
			return;
		}
		if (System.currentTimeMillis() >= confirmDeleteUntil) {
			confirmDeleteUntil = System.currentTimeMillis() + 3000; // press again to confirm
			boolean hasGroup = sel.stream().anyMatch(Entry::isGroup);
			say(hasGroup ? "Press Delete again: a group is deleted together with everything inside it (a backup is made)." : "Press Delete again to confirm.", 0xFFFFCC66);
			updateButtons();
			return;
		}
		confirmDeleteUntil = 0;
		WaypointStore.backupNow("delete");
		List<String> waypointIds = new ArrayList<>();
		for (Entry e : sel) {
			if (e.isGroup()) {
				data.deleteGroup(e.id());
			} else {
				waypointIds.add(e.id());
			}
		}
		data.deleteWaypoints(waypointIds);
		WaypointStore.saveNow();
		selected.clear();
		say("Deleted " + sel.size() + " item" + (sel.size() == 1 ? "" : "s") + ".", 0xFFFF8877);
		rebuild();
		updateButtons();
	}

	private void setVisible(Entry e, boolean visible) {
		long now = System.currentTimeMillis();
		if (e.isGroup()) {
			e.group().visible = visible;
			e.group().modified = now;
		} else if (e.wp() != null) {
			e.wp().visible = visible;
			e.wp().modified = now;
		}
	}

	private static boolean isOn(Entry e) {
		return e.isGroup() ? e.group().visible : e.wp().visible;
	}

	private void toggleSelected() {
		List<Entry> sel = selectedEntries();
		if (sel.isEmpty()) {
			return;
		}
		boolean show = sel.stream().anyMatch(e -> !isOn(e)); // any off -> turn all on, otherwise turn all off
		for (Entry e : sel) {
			setVisible(e, show);
		}
		WaypointStore.saveNow();
		say((show ? "Shown " : "Hidden ") + sel.size() + ".", COL_DIM);
	}

	/** {@code choose}: always open the address picker instead of importing the folders of this world. */
	private void importXaero(boolean choose) {
		List<XaeroImport.Folder> folders = XaeroImport.folders(this.minecraft);
		if (folders.isEmpty()) {
			say("No Xaero waypoints found in .minecraft/xaero/minimap.", 0xFFFF8877);
			return;
		}
		List<XaeroImport.Folder> mine = folders.stream().filter(XaeroImport.Folder::thisWorld).toList();
		if (choose || mine.isEmpty()) {
			this.minecraft.gui.setScreen(new XaeroPickScreen(this, folders, this::imported));
			return;
		}
		int imported = 0;
		int skipped = 0;
		for (XaeroImport.Folder folder : mine) {
			XaeroImport.Result r = XaeroImport.importFolder(this.minecraft, folder);
			imported += r.imported();
			skipped += r.skipped();
		}
		imported(new XaeroImport.Result(imported, skipped));
	}

	void imported(XaeroImport.Result r) {
		say("Imported " + r.imported() + " Xaero waypoint" + (r.imported() == 1 ? "" : "s") + (r.skipped() > 0 ? " (" + r.skipped() + " already here)" : "") + ".", 0xFF77FF99);
		rebuild();
		updateButtons();
	}

	/** Called by the editor after Create: the new waypoint goes into the group you are looking at and is selected. */
	void created(Waypoint w) {
		if (openGroup != null) {
			data.move(List.of(w.id), openGroup);
			WaypointStore.saveNow();
		}
		selected.clear();
		selected.add(w.id);
		anchor = w.id;
	}

	private void open(Group g) {
		openGroup = g.id;
		selected.clear();
		anchor = null;
		scroll = 0;
		rebuild();
		updateButtons();
	}

	private void goUp() {
		if (openGroup == null) {
			return;
		}
		String came = openGroup;
		openGroup = data.parentOf(openGroup);
		selected.clear();
		selected.add(came);
		anchor = came;
		scroll = 0;
		rebuild();
		updateButtons();
	}

	private void moveSelectedInto(String targetGroup, String name) {
		List<String> ids = new ArrayList<>(selected);
		int moved = data.move(ids, targetGroup);
		WaypointStore.saveNow();
		selected.clear();
		say(moved == 0 ? "Nothing moved." : "Moved " + moved + " into " + name + ".", moved == 0 ? COL_DIM : 0xFF77FF99);
		rebuild();
		updateButtons();
	}

	// ------------------------------------------------------------------ input

	private int rowAt(double mx, double my) {
		if (mx < frameX || mx >= frameX + frameW - 6 || my < listTop || my >= listBottom) {
			return -1;
		}
		int index = (int) ((my - listTop + scroll) / ROW_H);
		return index >= 0 && index < view.size() ? index : -1;
	}

	private int eyeX() {
		return frameX + frameW - 6 - 16;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (dimOpen) {
			clickDimList(event.x(), event.y(), event.button());
			return true;
		}
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		int index = rowAt(event.x(), event.y());
		if (index < 0) {
			if (event.x() >= frameX && event.x() < frameX + frameW && event.y() >= listTop && event.y() < listBottom) {
				selected.clear();
				updateButtons();
				return true;
			}
			return false;
		}
		Entry e = view.get(index);
		pressedId = null;
		collapseTo = null;
		dragging = false;
		if (e.up()) {
			if (doubleClick) {
				goUp();
			}
			return true;
		}
		String id = e.id();
		if (event.x() >= eyeX() && event.x() < eyeX() + 14) { // eye: turn just this one on / off
			setVisible(e, !isOn(e));
			WaypointStore.saveNow();
			return true;
		}
		boolean box = event.x() < frameX + 20;
		if (event.hasShiftDown() && anchor != null) {
			int from = indexOf(anchor);
			if (from >= 0) {
				if (!event.hasControlDown()) {
					selected.clear();
				}
				for (int i = Math.min(from, index); i <= Math.max(from, index); i++) {
					if (!view.get(i).up()) {
						selected.add(view.get(i).id());
					}
				}
			}
		} else if (event.hasControlDown() || box) {
			if (!selected.remove(id)) {
				selected.add(id);
			}
			anchor = id;
		} else {
			if (doubleClick && selected.size() == 1 && selected.contains(id)) {
				if (e.isGroup()) {
					open(e.group());
				} else {
					editSelected();
				}
				return true;
			}
			if (selected.contains(id) && selected.size() > 1) {
				collapseTo = id; // keep the whole selection for a drag; a plain click selects just this one on release
			} else {
				selected.clear();
				selected.add(id);
			}
			anchor = id;
			pressedId = id;
			pressX = event.x();
			pressY = event.y();
		}
		updateButtons();
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (pressedId != null && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			if (!dragging && Math.hypot(event.x() - pressX, event.y() - pressY) > 4) {
				dragging = true;
				collapseTo = null;
				updateButtons();
			}
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (pressedId != null || collapseTo != null) {
			if (dragging) {
				int index = rowAt(event.x(), event.y());
				if (index >= 0) {
					Entry target = view.get(index);
					if (target.up()) {
						moveSelectedInto(data.parentOf(openGroup), data.parentOf(openGroup) == null ? "the top level" : data.group(data.parentOf(openGroup)).name);
					} else if (target.isGroup() && !selected.contains(target.id())) {
						moveSelectedInto(target.id(), "'" + target.group().name + "'");
					}
				}
			} else if (collapseTo != null) {
				selected.clear();
				selected.add(collapseTo);
				updateButtons();
			}
			dragging = false;
			pressedId = null;
			collapseTo = null;
			return true;
		}
		return super.mouseReleased(event);
	}

	private int indexOf(String id) {
		for (int i = 0; i < view.size(); i++) {
			if (!view.get(i).up() && view.get(i).id().equals(id)) {
				return i;
			}
		}
		return -1;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.hasControlDown() && event.key() == InputConstants.KEY_A) {
			for (Entry e : view) {
				if (!e.up()) {
					selected.add(e.id());
				}
			}
			updateButtons();
			return true;
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_C) {
			copySelected(false);
			return true;
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_X) {
			copySelected(true);
			return true;
		}
		if (event.hasControlDown() && event.key() == InputConstants.KEY_V) {
			paste();
			return true;
		}
		if (event.key() == InputConstants.KEY_DELETE) {
			deleteSelected();
			return true;
		}
		if (event.key() == InputConstants.KEY_BACKSPACE && openGroup != null) {
			goUp();
			return true;
		}
		return super.keyPressed(event);
	}

	private double maxScroll() {
		return Math.max(0, view.size() * ROW_H - (listBottom - listTop));
	}

	private double clampScroll(double value) {
		return Math.max(0, Math.min(maxScroll(), value));
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (y >= listTop && y < listBottom) {
			scroll = clampScroll(scroll - scrollY * ROW_H);
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public void tick() {
		super.tick();
		if (++sortTick >= 10) {
			sortTick = 0;
			if (sortMode() == 0 && !dragging) {
				rebuild(); // distances change while you move
			}
		}
		if (confirmDeleteUntil != 0 && System.currentTimeMillis() >= confirmDeleteUntil) {
			confirmDeleteUntil = 0;
		}
		updateButtons();
	}

	/** Esc / Back: leave the open group first, then the screen. */
	@Override
	public void onClose() {
		if (openGroup != null) {
			goUp();
			return;
		}
		WaypointStore.saveIfDirty();
		this.minecraft.gui.setScreen(parent);
	}

	// ------------------------------------------------------------------ render

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.text(this.font, this.font.plainSubstrByWidth(path(), frameW - 160), frameX, 8, COL_TEXT);
		String count = view.size() - (openGroup != null ? 1 : 0) + " items  |  " + selected.size() + " selected";
		g.text(this.font, count, frameX + frameW - this.font.width(count), 8, COL_DIM);

		g.fill(frameX, listTop, frameX + frameW, listBottom, BG_PANEL);
		border(g, frameX, listTop, frameX + frameW, listBottom, BORDER);
		Minecraft mc = Minecraft.getInstance();
		if (view.isEmpty() || (view.size() == 1 && view.get(0).up())) {
			g.centeredText(this.font, openGroup == null ? "No waypoints yet. Press Create, or Import Xaero." : "This group is empty. Drag waypoints onto it from above.", frameX + frameW / 2, listTop + 40, COL_DIM);
		}
		int hoverRow = dragging ? rowAt(mouseX, mouseY) : -1;
		g.enableScissor(frameX, listTop, frameX + frameW, listBottom);
		for (int i = 0; i < view.size(); i++) {
			int y = listTop - (int) scroll + i * ROW_H;
			if (y + ROW_H < listTop || y > listBottom) {
				continue;
			}
			drawRow(g, mc, view.get(i), y, mouseX, mouseY, i == hoverRow);
		}
		g.disableScissor();
		double max = maxScroll();
		if (max > 0) {
			int trackH = listBottom - listTop;
			int barH = Math.max(14, (int) (trackH * (trackH / (trackH + max))));
			int barY = listTop + (int) ((trackH - barH) * (scroll / max));
			g.fill(frameX + frameW - 4, barY, frameX + frameW - 2, barY + barH, 0xFF888888);
		}

		boolean showStatus = System.currentTimeMillis() < statusUntil;
		String hint = showStatus ? status : "Click select  Shift range  Ctrl toggle  Double click open/edit  Drag onto a group to move  Del delete";
		g.text(this.font, this.font.plainSubstrByWidth(hint, frameW - 80 - DIM_W - 8), frameX + 76 + DIM_W + 8, this.height - 20, showStatus ? statusColor : COL_DIM);
		super.extractRenderState(g, mouseX, mouseY, partialTick); // buttons
		if (dimOpen) {
			renderDimList(g, mouseX, mouseY);
		}

		if (dragging) {
			String label = selected.size() == 1 ? "1 item" : selected.size() + " items";
			int w = this.font.width(label) + 10;
			g.fill(mouseX + 8, mouseY + 6, mouseX + 8 + w, mouseY + 20, 0xE0102030);
			border(g, mouseX + 8, mouseY + 6, mouseX + 8 + w, mouseY + 20, 0xFF55AAFF);
			g.text(this.font, label, mouseX + 13, mouseY + 10, COL_TEXT);
		}
	}

	private boolean validDrop(Entry e) {
		return e.up() || (e.isGroup() && !selected.contains(e.id()));
	}

	private void drawRow(GuiGraphicsExtractor g, Minecraft mc, Entry e, int y, int mouseX, int mouseY, boolean dropHover) {
		int x1 = frameX + 2;
		int x2 = frameX + frameW - 6;
		boolean sel = !e.up() && selected.contains(e.id());
		boolean hover = mouseX >= x1 && mouseX < x2 && mouseY >= Math.max(y, listTop) && mouseY < Math.min(y + ROW_H - 2, listBottom);
		int bg = sel ? BG_SELECTED : (hover && !dragging ? BG_HOVER : BG_ROW);
		g.fill(x1, y + 1, x2, y + ROW_H - 1, bg);
		boolean target = dragging && validDrop(e);
		int edge = dropHover && target ? 0xFF55FF77 : (target ? 0xFF3C8C55 : (sel ? 0xFF55AAFF : BORDER));
		border(g, x1, y + 1, x2, y + ROW_H - 1, edge);

		if (e.up()) {
			g.text(this.font, "..  (up one level)", x1 + 30, y + 9, COL_DIM);
			return;
		}

		int bx = x1 + 5;
		int by = y + (ROW_H - 10) / 2;
		g.fill(bx, by, bx + 10, by + 10, 0xA0202020);
		border(g, bx, by, bx + 10, by + 10, sel ? 0xFF55FF77 : 0xFF777777);
		if (sel) {
			g.fill(bx + 3, by + 3, bx + 7, by + 7, 0xFF55FF77);
		}

		int tx = x1 + 44;
		boolean on;
		String title;
		String second;
		String right;
		int rightColor = COL_TEXT;
		String timer = null;
		if (e.isGroup()) {
			Group grp = e.group();
			on = data.visible(grp);
			drawFolder(g, x1 + 24, y + 6, on ? COL_FOLDER : 0xFF665522);
			int wps = data.descendantWaypoints(grp).size();
			title = grp.name + "  (" + wps + ")";
			second = grp.waypoints.size() + " waypoint" + (grp.waypoints.size() == 1 ? "" : "s") + ", " + grp.groups.size() + " group" + (grp.groups.size() == 1 ? "" : "s");
			double d = distance(grp);
			right = d == Double.MAX_VALUE ? "" : "nearest " + WaypointHud.formatDistance(d);
		} else {
			Waypoint w = e.wp();
			on = data.visible(w);
			WaypointBadge.draw(g, this.font, x1 + 30, y + ROW_H - 3, 0.95F, w.rgb, w.initial());
			title = w.title();
			second = w.isArea()
				? "Area: " + w.regionText() + ", " + w.sizeText() + " box   " + w.minX() + ", " + w.minY() + ", " + w.minZ() + "  to  " + w.maxX() + ", " + w.maxY() + ", " + w.maxZ() + "   " + shortDimension(w.dim)
				: w.x + ", " + w.y + ", " + w.z + "   " + shortDimension(w.dim);
			double d = distance(w);
			right = d == Double.MAX_VALUE ? "other dimension" : WaypointHud.formatDistance(d);
			rightColor = d == Double.MAX_VALUE ? 0xFF777777 : COL_TEXT;
			timer = WaypointHud.timerText(mc, w);
		}
		int rightEdge = eyeX() - 6;
		g.text(this.font, this.font.plainSubstrByWidth(title + (on ? "" : "  (hidden)"), rightEdge - tx - 80), tx, y + 5, on ? COL_TEXT : 0xFF777777);
		g.text(this.font, this.font.plainSubstrByWidth(second, rightEdge - tx - 4), tx, y + 15, COL_DIM);
		if (!right.isEmpty()) {
			g.text(this.font, right, rightEdge - this.font.width(right), y + 5, rightColor);
		}
		if (timer != null) {
			boolean counting = timer.startsWith("Items despawn in");
			g.text(this.font, timer, rightEdge - this.font.width(timer), y + 15, e.wp().ticksLeft == 0 ? 0xFF777777 : (counting ? 0xFFFFDD66 : COL_DIM));
		}
		drawEye(g, eyeX() + 2, y + (ROW_H - 6) / 2, isOn(e));
	}

	private static void drawFolder(GuiGraphicsExtractor g, int x, int y, int color) {
		g.fill(x, y, x + 8, y + 3, color);
		g.fill(x, y + 2, x + 18, y + 14, color);
		g.fill(x + 1, y + 4, x + 17, y + 5, 0x40FFFFFF);
	}

	private static void drawEye(GuiGraphicsExtractor g, int x, int y, boolean on) {
		int color = on ? 0xFFFFFFFF : 0xFF777777;
		for (int row = 0; row < EYE.length; row++) {
			for (int col = 0; col < 9; col++) {
				if (EYE[row].charAt(col) == '#') {
					g.fill(x + col, y + row, x + col + 1, y + row + 1, color);
				}
			}
		}
		if (!on) {
			for (int i = 0; i < 6; i++) { // slash, bottom left to top right
				g.fill(x + 1 + i, y + 5 - i, x + 2 + i, y + 6 - i, 0xFFFF5555);
			}
		}
	}

	static String shortDimension(String dim) {
		return switch (dim) {
			case "minecraft:overworld" -> "Overworld";
			case "minecraft:the_nether" -> "Nether";
			case "minecraft:the_end" -> "End";
			default -> dim;
		};
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
