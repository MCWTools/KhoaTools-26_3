package khoa.tools.client.feature.illegal.blockfinder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import khoa.api.color.KhoaBlockColor;
import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.config.KhoaKeybinds;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.client.feature.illegal.FaceRect;
import khoa.tools.client.feature.illegal.FinderMode;
import khoa.tools.client.feature.illegal.StarePath;
import khoa.tools.client.gui.picker.PickFilter;
import khoa.tools.setting.Setting;

/**
 * Block Finder: pick blocks in the block picker, turn it on and every loaded block of those types gets an overlay through walls:
 * a see-through fill on each exposed face in the average colour of that face's texture ({@link KhoaBlockColor#faceColors}) and a brighter, opaque outline. Neighbouring
 * blocks of the same type share their outline (edges between them, and flat edges inside a surface, are not drawn).
 * Only the nearest "Overlay limit" blocks are drawn, so picking stone / deepslate cannot produce a million overlays.
 * Pathfinding: look at one of the marked blocks for a few seconds and a light path search draws the way to it.
 */
public final class BlockFinderFeature implements KhoaFeature {
	private static final String ID = "blockfinder";
	private static final PickFilter PICKED = new PickFilter(ID, "blocks");
	private static final int SCAN_PERIOD = 10;
	private static final int FALLBACK_RGB = 0xFF2020;
	private static final float OUTLINE_BRIGHTEN = 0.45F;
	private static final int FILL_ALPHA = 0x48;

	private static boolean on;
	private static int timer;

	private record Draw(BlockPos pos, int rgb, int fill, int outline) {
	}

	private record Edge(Vec3 a, Vec3 b, int color) {
	}

	/** One visible block face with its own fill colour (the average colour of that face's texture). */
	private record Face(int x, int y, int z, Direction face, GizmoStyle style) {
	}

	private record Result(List<Draw> draws, List<Edge> edges, List<BlockPos> positions, List<Face> faces) {
		static final Result EMPTY = new Result(List.of(), List.of(), List.of(), List.of());
	}

	private static volatile Result result = Result.EMPTY;

	private static List<String> blocksSource;
	private static Set<Block> blockSet = Set.of();

	private static final StarePath PATH = new StarePath("Block", () -> on && pathfinding(), BlockFinderFeature::stareSeconds,
		() -> result.positions(), pos -> {
			Minecraft mc = Minecraft.getInstance();
			return mc.level == null ? "block" : mc.level.getBlockState(pos).getBlock().getName().getString();
		}, false);

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Block Finder";
	}

	@Override
	public String description() {
		return "Pick blocks in Blocks to find, turn the card on and they get an overlay through walls: see-through fill in the block's average texture colour, brighter outline. "
			+ "Only the nearest Overlay limit blocks are drawn. Look at a marked block for a few seconds to see a path to it.";
	}

	@Override
	public boolean persistEnabled() {
		return true;
	}

	@Override
	public boolean isActive() {
		return on;
	}

	@Override
	public void activate(Minecraft mc) {
		on = !on;
		timer = 0;
		result = Result.EMPTY;
		PATH.clear();
		KhoaBlockColor.clearCache(); // pick up resource pack changes
		KhoaFeature.say(mc, "Block Finder: " + (on ? "on" : "off"));
	}

	@Override
	public List<Setting> settings() {
		return List.of(
			PICKED.setting("Blocks to find", "Blocks that get an overlay. Empty = nothing is shown.", List.of("block")),
			FinderMode.setting(ID),
			KhoaKeybinds.setting(ID, "mode_key", "Mode key", "Press to switch Box / Lines / Box and Lines.", () -> {
				FinderMode.cycle(ID);
				KhoaFeature.say(Minecraft.getInstance(), "Block Finder mode: " + FinderMode.name(ID));
			}),
			Setting.bool("texture_color", "Use block colour", "On: overlay colour = average colour of the block's texture. Off: red for every block.",
				BlockFinderFeature::textureColor, v -> KhoaConfig.put(ID, "texture_color", v)),
			Setting.slider("limit", "Overlay limit", "Most overlays drawn at once. When there are more blocks, the farthest ones are hidden.", 10, 5000,
				BlockFinderFeature::limit, v -> KhoaConfig.put(ID, "limit", v)),
			Setting.slider("radius", "Scan radius (blocks)", "How far around you blocks are searched. Bigger = more work per scan.", 8, 96,
				BlockFinderFeature::radius, v -> KhoaConfig.put(ID, "radius", v)),
			Setting.bool("pathfinding", "Pathfinding", "Look at a marked block for a few seconds to draw a path to it (green = walkable). If there is no walkable path a straight orange line is shown.",
				BlockFinderFeature::pathfinding, v -> {
					KhoaConfig.put(ID, "pathfinding", v);
					if (!v) {
						PATH.clear();
					}
				}),
			Setting.integer("stare_seconds", "Path after looking (seconds)", "How long you must look at a marked block before its path is calculated.", 1, 60,
				BlockFinderFeature::stareSeconds, v -> KhoaConfig.put(ID, "stare_seconds", v)),
			KhoaKeybinds.setting(ID, "path_key", "Pathfinding key", "Press to turn Pathfinding on/off.", () -> {
				boolean now = !pathfinding();
				KhoaConfig.put(ID, "pathfinding", now);
				if (!now) {
					PATH.clear();
				}
				KhoaFeature.say(Minecraft.getInstance(), "Block Finder pathfinding: " + (now ? "on" : "off"));
			}));
	}

	static int limit() {
		return KhoaConfig.getInt(ID, "limit", 500);
	}

	static int radius() {
		return KhoaConfig.getInt(ID, "radius", 32);
	}

	static boolean textureColor() {
		return KhoaConfig.getBool(ID, "texture_color", true);
	}

	static boolean pathfinding() {
		return KhoaConfig.getBool(ID, "pathfinding", true);
	}

	static int stareSeconds() {
		return KhoaConfig.getInt(ID, "stare_seconds", 3);
	}

	@Override
	public void onInit() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (!on || mc.level == null || mc.player == null) {
				PATH.onClientTick(mc);
				return;
			}
			if (!mc.isPaused() && timer-- <= 0) {
				timer = SCAN_PERIOD;
				result = scan(mc.level, mc.player.blockPosition());
			}
			PATH.onClientTick(mc);
		});
		LevelRenderEvents.END_EXTRACTION.register(context -> draw());
	}

	private static void draw() {
		Minecraft mc = Minecraft.getInstance();
		if (!on || mc.player == null || mc.level == null) {
			return;
		}
		if (pathfinding()) {
			PATH.render(mc);
		}
		Result r = result;
		int mode = FinderMode.get(ID);
		if (FinderMode.box(mode)) {
			Vec3 eye = mc.player.getEyePosition();
			for (Face f : r.faces()) {
				if (FaceRect.facesEye(f.x(), f.y(), f.z(), f.face(), eye)) {
					FaceRect.draw(f.x(), f.y(), f.z(), f.face(), f.style());
				}
			}
			for (Edge e : r.edges()) {
				Gizmos.line(e.a(), e.b(), e.color(), 2.0F).setAlwaysOnTop();
			}
		}
		if (FinderMode.lines(mode)) {
			Vec3 from = FinderMode.lineStart(mc);
			for (Draw d : r.draws()) {
				FinderMode.line(from, Vec3.atCenterOf(d.pos()), d.outline());
			}
		}
	}

	private static Set<Block> blocks() {
		List<String> keys = PICKED.get();
		if (keys != blocksSource) {
			blocksSource = keys;
			Set<Block> set = new HashSet<>();
			for (String key : keys) {
				int colon = key.indexOf(':');
				Identifier id = Identifier.tryParse(colon < 0 ? key : key.substring(colon + 1));
				if (id != null) {
					BuiltInRegistries.BLOCK.getOptional(id).ifPresent(set::add);
				}
			}
			blockSet = set;
		}
		return blockSet;
	}

	private record Hit(long pos, BlockState state, double distSq) {
	}

	/** Nearest {@link #limit()} matching blocks within the scan radius (bounded max-heap), then their colours and merged outline edges. */
	private static Result scan(ClientLevel level, BlockPos center) {
		Set<Block> wanted = blocks();
		if (wanted.isEmpty()) {
			return Result.EMPTY;
		}
		Predicate<BlockState> test = s -> wanted.contains(s.getBlock());
		int r = radius();
		int limit = limit();
		double rSq = (double) r * r;
		PriorityQueue<Hit> heap = new PriorityQueue<>((a, b) -> Double.compare(b.distSq(), a.distSq())); // farthest on top
		int minY = level.getMinY();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int cx = (center.getX() - r) >> 4; cx <= (center.getX() + r) >> 4; cx++) {
			for (int cz = (center.getZ() - r) >> 4; cz <= (center.getZ() + r) >> 4; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) {
					continue;
				}
				LevelChunkSection[] sections = chunk.getSections();
				for (int i = 0; i < sections.length; i++) {
					LevelChunkSection section = sections[i];
					int baseY = minY + (i << 4);
					if (baseY + 15 < center.getY() - r || baseY > center.getY() + r || section.hasOnlyAir() || !section.maybeHas(test)) {
						continue;
					}
					for (int ly = 0; ly < 16; ly++) {
						for (int lz = 0; lz < 16; lz++) {
							for (int lx = 0; lx < 16; lx++) {
								BlockState state = section.getBlockState(lx, ly, lz);
								if (!wanted.contains(state.getBlock())) {
									continue;
								}
								int x = (cx << 4) + lx;
								int y = baseY + ly;
								int z = (cz << 4) + lz;
								double dx = x + 0.5 - center.getX();
								double dy = y + 0.5 - center.getY();
								double dz = z + 0.5 - center.getZ();
								double d = dx * dx + dy * dy + dz * dz;
								if (d > rSq) {
									continue;
								}
								if (heap.size() < limit) {
									heap.add(new Hit(m.set(x, y, z).asLong(), state, d));
								} else if (d < heap.peek().distSq()) {
									heap.poll();
									heap.add(new Hit(m.set(x, y, z).asLong(), state, d));
								}
							}
						}
					}
				}
			}
		}
		if (heap.isEmpty()) {
			return Result.EMPTY;
		}
		boolean texture = textureColor();
		Map<BlockState, Integer> rgbOf = new HashMap<>();
		Map<Long, Block> typeAt = new HashMap<>();
		List<Draw> draws = new ArrayList<>(heap.size());
		List<BlockPos> positions = new ArrayList<>(heap.size());
		for (Hit h : heap) {
			int rgb = rgbOf.computeIfAbsent(h.state(), st -> texture ? KhoaBlockColor.averageRgb(st) : FALLBACK_RGB);
			BlockPos pos = BlockPos.of(h.pos());
			draws.add(new Draw(pos, rgb, (FILL_ALPHA << 24) | rgb, 0xFF000000 | KhoaBlockColor.brighten(rgb, OUTLINE_BRIGHTEN)));
			positions.add(pos);
			typeAt.put(h.pos(), h.state().getBlock());
		}
		List<Face> faces = new ArrayList<>();
		BlockPos.MutableBlockPos nb = new BlockPos.MutableBlockPos();
		for (Hit h : heap) {
			BlockPos pos = BlockPos.of(h.pos());
			int[] faceRgb = texture ? KhoaBlockColor.faceColors(h.state()) : null;
			for (Direction d : Direction.values()) {
				if (typeAt.get(nb.setWithOffset(pos, d).asLong()) == h.state().getBlock()) {
					continue; // covered by a neighbour of the same type
				}
				int rgb = faceRgb == null ? FALLBACK_RGB : faceRgb[d.get3DDataValue()];
				faces.add(new Face(pos.getX(), pos.getY(), pos.getZ(), d, GizmoStyle.fill((FILL_ALPHA << 24) | rgb)));
			}
		}
		return new Result(draws, edgesOf(draws, typeAt), positions, List.copyOf(faces));
	}

	/**
	 * Outline of the union of same-type blocks. Each cube edge is touched by 4 cells (a 2x2 square around it). Drawn only when it is a real
	 * outer edge: one cell filled (convex), three filled (concave) or two filled diagonally. Not drawn when none / all four are filled
	 * (inside), or two next to each other are filled (flat surface). The lowest filled cell of the four owns the edge so it is drawn once.
	 */
	private static List<Edge> edgesOf(List<Draw> draws, Map<Long, Block> typeAt) {
		List<Edge> edges = new ArrayList<>();
		BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
		for (Draw d : draws) {
			Block block = typeAt.get(d.pos().asLong());
			int[] p = { d.pos().getX(), d.pos().getY(), d.pos().getZ() };
			for (int ax = 0; ax < 3; ax++) {
				int u = (ax + 1) % 3;
				int v = (ax + 2) % 3;
				for (int s = 0; s <= 1; s++) {
					for (int t = 0; t <= 1; t++) {
						boolean[] filled = new boolean[4];
						int count = 0;
						int own = (1 - s) * 2 + (1 - t); // our cell's slot among the four
						int owner = -1;
						for (int i = 0; i < 2; i++) {
							for (int j = 0; j < 2; j++) {
								int[] c = p.clone();
								c[u] = p[u] + s - 1 + i;
								c[v] = p[v] + t - 1 + j;
								boolean f = typeAt.get(q.set(c[0], c[1], c[2]).asLong()) == block;
								filled[i * 2 + j] = f;
								if (f) {
									count++;
									if (owner < 0) {
										owner = i * 2 + j;
									}
								}
							}
						}
						if (owner != own) {
							continue; // another cell draws this edge
						}
						boolean diagonal = count == 2 && ((filled[0] && filled[3]) || (filled[1] && filled[2]));
						if (!(count == 1 || count == 3 || diagonal)) {
							continue;
						}
						double[] a = { p[0], p[1], p[2] };
						a[u] += s;
						a[v] += t;
						double[] b = a.clone();
						b[ax] += 1;
						edges.add(new Edge(new Vec3(a[0], a[1], a[2]), new Vec3(b[0], b[1], b[2]), d.outline()));
					}
				}
			}
		}
		return List.copyOf(edges);
	}
}
