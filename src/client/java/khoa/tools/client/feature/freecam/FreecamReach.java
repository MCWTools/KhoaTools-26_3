package khoa.tools.client.feature.freecam;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import khoa.tools.client.feature.camera.CameraWatch;

/**
 * The target of the freecam's aim, used instead of the player's aim while a "through wall" option is on.
 * The ray starts at the camera; the target still has to be inside the player's normal reach (the server checks that).
 * A target the player cannot see from its eyes counts as "through wall" and needs the option of its type.
 */
public final class FreecamReach {
	private FreecamReach() {
	}

	/** What the camera aims at. A miss when there is nothing, it is out of reach, or it is hidden and its option is off. */
	public static HitResult cameraAim(Minecraft mc, float partialTicks) {
		LocalPlayer player = mc.player;
		Vec3 eye = player.getEyePosition(partialTicks);
		Vec3 from = CameraWatch.pos();
		Vec3 direction = Vec3.directionFromRotation(CameraWatch.pitch(), CameraWatch.yaw());
		double reach = Math.max(player.blockInteractionRange(), player.entityInteractionRange());
		// far enough to cover everything the player can reach, however far away the camera is
		Vec3 to = from.add(direction.scale(from.distanceTo(eye) + reach + 8.0));

		BlockHitResult block = firstBlock(mc, from, to);
		Vec3 end = block == null ? to : block.getLocation();
		EntityHitResult entity = ProjectileUtil.getEntityHitResult(player, from, end, new AABB(from, end).inflate(1.0),
			EntitySelector.CAN_BE_PICKED, from.distanceToSqr(end));

		if (entity != null) {
			Entity target = entity.getEntity();
			boolean allowed = player.isWithinEntityInteractionRange(target, 0.0)
				&& (FreecamFeature.entityThroughWall() || playerSees(mc, eye, entity.getLocation(), null)
					|| playerSees(mc, eye, target.getBoundingBox().getCenter(), null));
			return allowed ? entity : miss(from, entity.getLocation());
		}
		if (block != null) {
			boolean allowed = player.isWithinBlockInteractionRange(block.getBlockPos(), 0.0)
				&& (FreecamFeature.blockThroughWall() || playerSees(mc, eye, block.getLocation(), block));
			return allowed ? block : miss(from, block.getLocation());
		}
		return miss(from, to);
	}

	/**
	 * First block outline on the camera ray. A block the camera is inside of is skipped, so a camera flying through a wall
	 * targets what is in front of it.
	 */
	private static BlockHitResult firstBlock(Minecraft mc, Vec3 from, Vec3 to) {
		CollisionContext context = CollisionContext.of(mc.player);
		BlockPos cameraBlock = BlockPos.containing(from);
		return BlockGetter.<BlockHitResult, Minecraft>traverseBlocks(from, to, mc, (client, pos) -> {
			BlockState state = client.level.getBlockState(pos);
			if (state.isAir()) return null;
			BlockPos at = pos.immutable();
			BlockHitResult hit = client.level.clipWithInteractionOverride(from, to, at, state.getShape(client.level, at, context), state);
			return hit != null && hit.isInside() && at.equals(cameraBlock) ? null : hit;
		}, client -> null);
	}

	/** True when nothing is between the player's eyes and the point ({@code block}: or the first thing there is that same block face). */
	private static boolean playerSees(Minecraft mc, Vec3 eye, Vec3 point, BlockHitResult block) {
		BlockHitResult seen = mc.level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
		if (seen.getType() == HitResult.Type.MISS) return true;
		return block != null && seen.getBlockPos().equals(block.getBlockPos()) && seen.getDirection() == block.getDirection();
	}

	private static BlockHitResult miss(Vec3 from, Vec3 location) {
		Direction direction = Direction.getApproximateNearest(location.x - from.x, location.y - from.y, location.z - from.z);
		return BlockHitResult.miss(location, direction, BlockPos.containing(location));
	}
}
