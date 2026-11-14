package khoa.tools.client.feature.biggerhitbox;

import java.util.List;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.setting.Setting;

/**
 * Bigger Hitbox: blocks whose outline is smaller than the chosen size (stems, pressure plates, buttons...) get an invisible, bigger
 * fake hitbox while you hold an item. Right clicking the fake hitbox works out the face you meant, turns the camera to the matching
 * point of the real hitbox, and sends the placement to that real point (so the server sees a normal click). Blocks that are already
 * big enough are left alone, and a real hit on a block always wins.
 */
public final class BiggerHitboxFeature implements KhoaFeature {
	private static final String ID = "biggerhitbox";
	private static final Fake NONE = new Fake(null, null, null, null);
	private static boolean on;

	/** What the crosshair is on: the block, the face of its fake box that was hit and the point of the real hitbox to click. */
	private record Fake(BlockPos pos, Direction face, Vec3 point, AABB worldBox) {
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Bigger Hitbox";
	}

	@Override
	public String description() {
		return "While you hold an item (a block, bone meal...), tiny blocks (pumpkin stems, buttons, plates...) get a bigger invisible hitbox (shown as a thin box when targeted). "
			+ "Right click it: the face is worked out for you, the camera turns to the real hitbox and the block is placed. "
			+ "Hitbox % is the minimum size (100 = a full block); only blocks smaller than that are enlarged.";
	}

	@Override
	public List<Setting> settings() {
		return List.of(
			Setting.slider("percent", "Hitbox %", "Minimum hitbox size in percent of a block (100 = 1x1x1). Bigger blocks are not changed.", 10, 100,
				BiggerHitboxFeature::percent, value -> KhoaConfig.put(ID, "percent", Math.max(10, Math.min(100, value)))),
			Setting.bool("restore", "Restore camera", "Turns the camera back to where you were looking after the placement.",
				BiggerHitboxFeature::restore, value -> KhoaConfig.put(ID, "restore", value)));
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
		KhoaFeature.say(mc, "Bigger Hitbox: " + (on ? "on (" + percent() + "%)" : "off"));
	}

	@Override
	public void onInit() {
		LevelRenderEvents.END_EXTRACTION.register(context -> drawPreview(Minecraft.getInstance()));
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(BiggerHitboxFeature::finish);
	}

	private static int percent() {
		return Math.max(10, Math.min(100, KhoaConfig.getInt(ID, "percent", 50)));
	}

	private static boolean restore() {
		return KhoaConfig.getBool(ID, "restore", false);
	}

	/** Any item that can be used on a block (bone meal, flint and steel, a block...), not just blocks. */
	private static boolean holdsItem(LocalPlayer player) {
		return !player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty();
	}

	/** The fake hitbox under the crosshair, or null when the normal target should be used. */
	private static Fake pick(Minecraft mc) {
		LocalPlayer player = mc.player;
		Level level = mc.level;
		if (!on || player == null || level == null || mc.gameMode == null || mc.gui.screen() != null || player.isSpectator() || !holdsItem(player)) {
			return null;
		}
		if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) {
			return null;
		}
		Vec3 from = player.getEyePosition();
		Vec3 to = from.add(player.getViewVector(1.0F).scale(player.blockInteractionRange()));
		double vanilla = mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK
			? mc.hitResult.getLocation().distanceTo(from) : Double.MAX_VALUE;
		double min = percent() / 100.0;

		Fake found = BlockGetter.traverseBlocks(from, to, level, (lvl, pos) -> {
			BlockState state = lvl.getBlockState(pos);
			if (state.isAir()) {
				return null;
			}
			VoxelShape shape = state.getShape(lvl, pos);
			if (shape.isEmpty()) {
				return null;
			}
			if (shape.clip(from, to, pos) != null) {
				return NONE; // the real hitbox is hit first: normal behaviour
			}
			AABB real = shape.bounds();
			if (real.getXsize() >= min && real.getYsize() >= min && real.getZsize() >= min) {
				return null; // big enough, not enlarged
			}
			AABB fake = enlarge(real, min);
			BlockHitResult hit = AABB.clip(List.of(fake), from, to, pos);
			if (hit == null) {
				return null;
			}
			if (hit.getLocation().distanceTo(from) >= vanilla) {
				return NONE;
			}
			Direction face = hit.getDirection();
			Vec3 center = real.getCenter();
			double x = face.getAxis() == Direction.Axis.X ? (face.getStepX() > 0 ? real.maxX : real.minX) : center.x;
			double y = face.getAxis() == Direction.Axis.Y ? (face.getStepY() > 0 ? real.maxY : real.minY) : center.y;
			double z = face.getAxis() == Direction.Axis.Z ? (face.getStepZ() > 0 ? real.maxZ : real.minZ) : center.z;
			return new Fake(pos, face, new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z), fake.move(pos));
		}, lvl -> null);
		return found == null || found == NONE ? null : found;
	}

	/** Grows every side that is shorter than {@code min} to {@code min}, around its centre, kept inside the block cell. */
	private static AABB enlarge(AABB real, double min) {
		double[] lo = {real.minX, real.minY, real.minZ};
		double[] hi = {real.maxX, real.maxY, real.maxZ};
		double[] outLo = new double[3];
		double[] outHi = new double[3];
		for (int i = 0; i < 3; i++) {
			double a = lo[i];
			double b = hi[i];
			if (b - a < min) {
				double c = (a + b) / 2;
				a = c - min / 2;
				b = c + min / 2;
				if (a < 0) {
					b -= a;
					a = 0;
				}
				if (b > 1) {
					a -= b - 1;
					b = 1;
				}
			}
			outLo[i] = Math.min(a, lo[i]);
			outHi[i] = Math.max(b, hi[i]);
		}
		return new AABB(outLo[0], outLo[1], outLo[2], outHi[0], outHi[1], outHi[2]);
	}

	/** A click waiting for this tick's movement packet, see {@link #onUse}. */
	private static Pending pending;

	private record Pending(BlockHitResult hit, float oldYaw, float oldPitch) {
	}

	/**
	 * Called by the mixin on right click. True = handled (the normal use is skipped).
	 * Only the client camera is turned here. The server must see the new rotation BEFORE the placement, but an extra rotation packet
	 * would be a second "flying" packet in one tick and anticheats set the player back for it. The normal movement packet of this
	 * tick (sent while the player ticks, after the keys are handled) already carries the new rotation, so the placement is sent at the
	 * end of the same tick, right after it: one movement packet, then the use packet.
	 */
	public static boolean onUse(Minecraft mc) {
		if (pending != null) {
			return true;
		}
		Fake fake = pick(mc);
		if (fake == null) {
			return false;
		}
		LocalPlayer player = mc.player;
		Vec3 d = fake.point().subtract(player.getEyePosition());
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
		pending = new Pending(new BlockHitResult(fake.point(), fake.face(), fake.pos(), false), player.getYRot(), player.getXRot());
		turn(player, yaw, pitch);
		return true;
	}

	private static void finish(Minecraft mc) {
		Pending job = pending;
		if (job == null) {
			return;
		}
		pending = null;
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gameMode == null) {
			return;
		}
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack stack = player.getItemInHand(hand);
			if (stack.isEmpty() || !stack.isItemEnabled(mc.level.enabledFeatures())) {
				continue;
			}
			InteractionResult result = mc.gameMode.useItemOn(player, hand, job.hit());
			if (result.consumesAction()) {
				player.swing(hand, SwingAnimation.DEFAULT, true);
				break;
			}
			if (result == InteractionResult.FAIL) {
				break;
			}
		}
		if (restore()) {
			turn(player, job.oldYaw(), job.oldPitch()); // sent with the next movement packet
		}
	}

	private static void turn(LocalPlayer player, float yaw, float pitch) {
		player.setYRot(yaw);
		player.setXRot(pitch);
		player.yRotO = yaw;
		player.xRotO = pitch;
	}

	private static void drawPreview(Minecraft mc) {
		Fake fake = pick(mc);
		if (fake != null) {
			Gizmos.cuboid(fake.worldBox().inflate(0.002), GizmoStyle.strokeAndFill(0xFFFFFFFF, 1.5F, 0x18FFFFFF));
		}
	}
}
