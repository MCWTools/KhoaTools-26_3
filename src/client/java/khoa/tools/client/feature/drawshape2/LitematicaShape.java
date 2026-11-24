package khoa.tools.client.feature.drawshape2;

import java.util.Collection;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;

/**
 * The only class of Draw Shape V2 that touches Litematica. Load it only after checking that Litematica is installed.
 * Builds an in-memory schematic (not saved to disk) that holds the shape and adds a placement for it.
 */
final class LitematicaShape {
	private static final String REGION = "shape";

	private LitematicaShape() {
	}

	/** Adds a placement at the lowest corner of the shapes' bounding box. Returns an object to pass to {@link #remove}. */
	static Object place(String name, Collection<BlockPos> blocks, BlockState state) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (BlockPos p : blocks) {
			minX = Math.min(minX, p.getX());
			minY = Math.min(minY, p.getY());
			minZ = Math.min(minZ, p.getZ());
			maxX = Math.max(maxX, p.getX());
			maxY = Math.max(maxY, p.getY());
			maxZ = Math.max(maxZ, p.getZ());
		}
		BlockPos min = new BlockPos(minX, minY, minZ);
		BlockPos max = new BlockPos(maxX, maxY, maxZ);

		AreaSelection area = new AreaSelection();
		area.setName(name);
		area.addSubRegionBox(new Box(min, max, REGION), true);
		area.setExplicitOrigin(min);
		LitematicaSchematic schematic = LitematicaSchematic.createEmptySchematic(area, "KhoaTools");
		if (schematic == null) {
			throw new IllegalStateException("Litematica could not create an empty schematic");
		}
		LitematicaBlockStateContainer container = schematic.getSubRegionContainer(REGION);
		for (BlockPos p : blocks) {
			container.set(p.getX() - minX, p.getY() - minY, p.getZ() - minZ, state);
		}
		SchematicPlacement placement = SchematicPlacement.createFor(schematic, min, name, true, true);
		DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
		return placement;
	}

	static void remove(Object placement) {
		if (placement instanceof SchematicPlacement p) {
			DataManager.getSchematicPlacementManager().removeSchematicPlacement(p);
		}
	}
}
