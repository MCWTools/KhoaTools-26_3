package khoa.tools.client.feature.schematicguard;

import net.minecraft.core.BlockPos;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;

/**
 * The only class that touches Litematica classes. Load it only after checking that Litematica is installed
 * (checked against Litematica 0.29.1 for Minecraft 26.3).
 */
final class LitematicaBridge {
	private LitematicaBridge() {
	}

	/**
	 * True = the position holds a schematic block inside the current render layer range, false = it is inside an enabled, visible
	 * placement but not such a block (outside the layer, or air in the schematic), null = nothing to guard here: Litematica rendering
	 * is switched off, no placement is enabled and visible, or the position is outside every visible placement.
	 */
	static Boolean isSchematicBlock(BlockPos pos) {
		if (!Configs.Visuals.ENABLE_RENDERING.getBooleanValue() || !Configs.Visuals.ENABLE_SCHEMATIC_RENDERING.getBooleanValue()
			|| !Configs.Visuals.ENABLE_SCHEMATIC_BLOCKS.getBooleanValue()) {
			return null;
		}
		boolean insidePlacement = false;
		for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
			if (!placement.isEnabled() || !placement.isRenderingEnabled()) {
				continue;
			}
			for (Box box : placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.RENDERING_ENABLED).values()) {
				if (contains(box, pos)) {
					insidePlacement = true;
					break;
				}
			}
			if (insidePlacement) {
				break;
			}
		}
		if (!insidePlacement) {
			return null;
		}
		WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
		if (world == null) {
			return null;
		}
		if (!DataManager.getRenderLayerRange().isPositionWithinRange(pos)) {
			return false;
		}
		return !world.getBlockState(pos).isAir();
	}

	private static boolean contains(Box box, BlockPos pos) {
		BlockPos a = box.getPos1();
		BlockPos b = box.getPos2();
		if (a == null || b == null) {
			return false;
		}
		return pos.getX() >= Math.min(a.getX(), b.getX()) && pos.getX() <= Math.max(a.getX(), b.getX())
			&& pos.getY() >= Math.min(a.getY(), b.getY()) && pos.getY() <= Math.max(a.getY(), b.getY())
			&& pos.getZ() >= Math.min(a.getZ(), b.getZ()) && pos.getZ() <= Math.max(a.getZ(), b.getZ());
	}
}
