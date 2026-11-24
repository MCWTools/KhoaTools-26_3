package khoa.tools.client.feature.drawshape2;

import java.util.List;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import khoa.api.render.KhoaBuildPreview;
import khoa.api.shape.KhoaShapes;
import khoa.api.shape.KhoaShapes.AngleMode;
import khoa.api.shape.KhoaShapes.Type;
import khoa.api.world.KhoaRay;
import khoa.api.world.RayHit;
import khoa.tools.KhoaTools;
import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.setting.Setting;

/**
 * Draw Shape V2: pick a shape (Line / Circle / Square), which look angles count, a distance, a block and Thick / Thin, face the
 * direction you want and press Draw. With Litematica installed the shape becomes a Litematica placement; without it,
 * KhoaAPILibs draws it as ghost blocks (red where something is in the way). Turning the feature off hides the drawing.
 */
public final class DrawShapeV2Feature implements KhoaFeature {
	private static final String ID = "drawshape2";
	private static final String PREVIEW = "khoatools.drawshape2";
	private static final String PLACEMENT_NAME = "KhoaTools Shape";
	private static final List<String> SHAPES = List.of("Line", "Circle", "Square");
	private static final List<String> ANGLES = List.of("Both", "Only Horizontal Angle", "Only Vertical Angle");
	private static final List<String> THICKNESS = List.of("Thick (connected)", "Thin (diagonals allowed)");
	private static final List<String> CENTERS = List.of("My position", "Block I look at");
	/** Refuse shapes with more blocks than this (a placement / preview that big only lags). */
	private static final int MAX_BLOCKS = 40000;

	private static boolean on;
	private static boolean litematicaFailed;
	private static List<BlockPos> blocks = List.of();
	private static BlockState state = Blocks.STONE.defaultBlockState();
	private static Object placement;
	private static ClientLevel shapeLevel;

	/** True while the drawing is a Litematica placement (self test). */
	public static boolean usesLitematica() {
		return placement != null;
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Draw Shape V2";
	}

	@Override
	public String description() {
		return "Line, Circle or Square in any direction. A circle / square lies flat when you look level and tilts up with your pitch (looking straight up makes it stand upright). Face where you want it, set distance (line length / circle radius / half the square side), pick a block and Thick or Thin, then press Draw. "
			+ "Uses Litematica (a new placement) when it is installed, otherwise draws ghost blocks (red = something is in the way). Turn the card off to hide the drawing.";
	}

	@Override
	public boolean isActive() {
		return on;
	}

	@Override
	public List<Setting> settings() {
		return List.of(
			Setting.dropdown("shape", "Shape", "Line, Circle or Square.", SHAPES,
				() -> idx("shape", SHAPES.size()), v -> KhoaConfig.put(ID, "shape", v)),
			Setting.dropdown("angle", "Angle", "Both = your exact look direction. Only Horizontal = yaw only (pitch counts as 0). Only Vertical = pitch only (yaw snaps to the nearest of the 4 compass directions).", ANGLES,
				() -> idx("angle", ANGLES.size()), v -> KhoaConfig.put(ID, "angle", v)),
			Setting.integer("distance", "Distance", "Line: length in blocks. Circle: radius. Square: half the side length (blocks between centre and an edge).", 1, 512,
				() -> KhoaConfig.getInt(ID, "distance", 10), v -> KhoaConfig.put(ID, "distance", v)),
			Setting.itemList("block", "Block", "The block to draw (pick one).", List.of("block"),
				() -> KhoaConfig.getStringList(ID, "block"), v -> KhoaConfig.putStringList(ID, "block", v)).maxPicks(1),
			Setting.dropdown("thickness", "Line style", "Thick: blocks always touch face to face. Thin: the fewest blocks, diagonal steps touch only at corners.", THICKNESS,
				() -> idx("thickness", THICKNESS.size()), v -> KhoaConfig.put(ID, "thickness", v)),
			Setting.dropdown("center", "Centre", "Where the shape starts / is centred: on your feet, or on the block your crosshair points at.", CENTERS,
				() -> idx("center", CENTERS.size()), v -> KhoaConfig.put(ID, "center", v)),
			Setting.action("draw", "Draw", "Draws the shape in the direction you are looking now (turns the feature on).", "Draw", () -> draw(Minecraft.getInstance())),
			Setting.action("clear", "Clear", "Removes the drawing.", "Clear", () -> clear()));
	}

	private static int idx(String key, int size) {
		return Math.floorMod(KhoaConfig.getInt(ID, key, 0), size);
	}

	@Override
	public void activate(Minecraft mc) {
		on = !on;
		applyVisibility();
		KhoaFeature.say(mc, "Draw Shape V2: " + (on ? "on" : "off (drawing hidden)"));
	}

	@Override
	public void onInit() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (shapeLevel != null && mc.level != shapeLevel) {
				clear(); // joined another world: the drawing belongs to the old one
			}
		});
	}

	private static BlockState wantedState() {
		for (String key : KhoaConfig.getStringList(ID, "block")) {
			String id = key.startsWith("block:") ? key.substring(6) : key;
			Identifier identifier = Identifier.tryParse(id);
			if (identifier != null) {
				Block b = BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null);
				if (b != null && b != Blocks.AIR) {
					return b.defaultBlockState();
				}
			}
		}
		return Blocks.STONE.defaultBlockState();
	}

	private static void draw(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		on = true;
		BlockPos center = mc.player.blockPosition();
		if (idx("center", CENTERS.size()) == 1) {
			RayHit hit = KhoaRay.blockFromEyes(128, false);
			if (hit.hit() && hit.pos() != null) {
				center = hit.pos().relative(hit.face());
			}
		}
		Type type = Type.values()[idx("shape", SHAPES.size())];
		AngleMode mode = switch (idx("angle", ANGLES.size())) {
			case 1 -> AngleMode.HORIZONTAL;
			case 2 -> AngleMode.VERTICAL;
			default -> AngleMode.BOTH;
		};
		boolean thick = idx("thickness", THICKNESS.size()) == 0;
		float yaw = mc.player.getYRot();
		float pitch = mc.player.getXRot();
		AngleMode generateMode = mode;
		if (type != Type.LINE) {
			// Circle / Square lie IN the look direction (flat when you look level, tilting up with your pitch), not across it like a
			// hoop. KhoaShapes puts the plane perpendicular to the direction it is given, so give it the direction 90 degrees above
			// the (mode-adjusted) look direction.
			if (mode == AngleMode.HORIZONTAL) {
				pitch = 0;
			} else if (mode == AngleMode.VERTICAL) {
				yaw = Math.round(yaw / 90f) * 90f;
			}
			pitch -= 90f;
			generateMode = AngleMode.BOTH;
		}
		List<BlockPos> shape = KhoaShapes.generate(type, center, yaw, pitch, generateMode,
			KhoaConfig.getInt(ID, "distance", 10), thick);
		if (shape.size() > MAX_BLOCKS) {
			KhoaFeature.say(mc, "Draw Shape V2: that shape has " + shape.size() + " blocks (max " + MAX_BLOCKS + "), lower the distance");
			return;
		}
		removeDrawing();
		blocks = shape;
		state = wantedState();
		shapeLevel = mc.level;
		applyVisibility();
		KhoaFeature.say(mc, "Draw Shape V2: " + SHAPES.get(type.ordinal()) + ", " + shape.size() + " blocks of "
			+ state.getBlock().getName().getString() + (placement != null ? " (Litematica placement)" : " (ghost blocks)"));
	}

	/** Shows the drawing while the feature is on, hides it otherwise. */
	private static void applyVisibility() {
		KhoaBuildPreview.Preview preview = KhoaBuildPreview.get(PREVIEW);
		if (!on || blocks.isEmpty()) {
			if (preview != null) {
				preview.setVisible(false);
			}
			removePlacement();
			return;
		}
		if (FabricLoader.getInstance().isModLoaded("litematica") && !litematicaFailed) {
			if (placement == null) {
				try {
					placement = LitematicaShape.place(PLACEMENT_NAME, blocks, state);
				} catch (Throwable t) {
					litematicaFailed = true;
					KhoaTools.LOGGER.error("Draw Shape V2: Litematica placement failed, using ghost blocks", t);
				}
			}
			if (placement != null) {
				if (preview != null) {
					preview.setVisible(false);
				}
				return;
			}
		}
		if (preview == null || preview.size() != blocks.size()) {
			preview = KhoaBuildPreview.create(PREVIEW);
			preview.setBlocks(blocks, state);
		}
		preview.setVisible(true);
	}

	private static void removePlacement() {
		if (placement != null) {
			try {
				LitematicaShape.remove(placement);
			} catch (Throwable t) {
				KhoaTools.LOGGER.error("Draw Shape V2: could not remove the Litematica placement", t);
			}
			placement = null;
		}
	}

	/** Removes the placement / preview but keeps nothing of the old shape. */
	private static void removeDrawing() {
		removePlacement();
		KhoaBuildPreview.remove(PREVIEW);
	}

	private static void clear() {
		removeDrawing();
		blocks = List.of();
		shapeLevel = null;
	}
}
