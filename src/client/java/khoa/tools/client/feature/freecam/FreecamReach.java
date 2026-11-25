package khoa.tools.client.feature.freecam;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Ray casts of the two "through wall" options. They start at the player's eyes and keep the vanilla reach. */
public final class FreecamReach {
	private static final Map<Class<?>, Boolean> USABLE = new ConcurrentHashMap<>();

	private FreecamReach() {
	}

	/** The nearest entity on the player's aim inside entity reach, ignoring blocks. Null when the option is off or nothing is there. */
	public static EntityHitResult entity(Minecraft mc, float partialTicks) {
		LocalPlayer player = mc.player;
		if (!FreecamFeature.entityThroughWall() || player == null || mc.level == null || player.isSpectator()) return null;
		double range = player.entityInteractionRange();
		Vec3 from = player.getEyePosition(partialTicks);
		Vec3 direction = player.getViewVector(partialTicks);
		Vec3 to = from.add(direction.scale(range));
		AABB box = player.getBoundingBox().expandTowards(direction.scale(range)).inflate(1.0);
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, from, to, box, EntitySelector.CAN_BE_PICKED, range * range);
		return hit != null && hit.getLocation().closerThan(from, range) ? hit : null;
	}

	/**
	 * The first block on the player's aim inside block reach that reacts to a right click (chest, door, lever, button,
	 * crafting table...), skipping every plain block in front of it. Null when the option is off, when you sneak with an item
	 * (vanilla then places / uses the item instead) or when there is no such block.
	 */
	public static BlockHitResult block(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (!FreecamFeature.blockThroughWall() || player == null || mc.level == null || player.isSpectator()) return null;
		if (player.isSecondaryUseActive() && (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty())) return null;
		double range = player.blockInteractionRange();
		Vec3 from = player.getEyePosition();
		Vec3 to = from.add(player.getViewVector(1.0F).scale(range));
		CollisionContext context = CollisionContext.of(player);
		return BlockGetter.<BlockHitResult, Minecraft>traverseBlocks(from, to, mc, (client, pos) -> hitUsable(client, pos, from, to, context), client -> null);
	}

	private static BlockHitResult hitUsable(Minecraft mc, BlockPos pos, Vec3 from, Vec3 to, CollisionContext context) {
		BlockState state = mc.level.getBlockState(pos);
		if (state.isAir() || !usable(state)) return null;
		return state.getShape(mc.level, pos, context).clip(from, to, pos.immutable());
	}

	/** Has a block entity, or its class (not the base block) overrides one of the right click methods. */
	private static boolean usable(BlockState state) {
		return state.hasBlockEntity() || USABLE.computeIfAbsent(state.getBlock().getClass(), FreecamReach::overridesUse);
	}

	private static boolean overridesUse(Class<?> type) {
		for (Class<?> c = type; c != null && c != Block.class && c != BlockBehaviour.class; c = c.getSuperclass()) {
			for (java.lang.reflect.Method method : c.getDeclaredMethods()) {
				if (method.getName().equals("useWithoutItem") || method.getName().equals("useItemOn")) return true;
			}
		}
		return false;
	}
}
