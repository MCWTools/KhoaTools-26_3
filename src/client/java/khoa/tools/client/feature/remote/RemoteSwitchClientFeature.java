package khoa.tools.client.feature.remote;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.remote.RemoteSwitch;
import khoa.tools.remote.RemoteUsePayload;
import khoa.tools.setting.Setting;

/**
 * Client half of the server's Remote Switch (see khoa.tools.remote.RemoteSwitch).
 * <ul>
 * <li>Remote Switch (carrot on a stick): a right click with it never sends the vanilla "use" packets - the lever / button / plate you click is NOT
 *     interacted with. A {@link RemoteUsePayload} goes instead: it links the remote (first click on a switch) or toggles the linked block (any later click, anywhere).</li>
 * <li>Remote v2 (warped fungus on a stick): aim near a lever / button / pressure plate and a blue circle (radius 1.5 blocks, +0.5 per chunk beyond 5 chunks away, always visible) is drawn on it.
 *     Aim into the circle and it turns red: a right click then toggles that block. With several switches close together the one your aim is nearest to wins.</li>
 * </ul>
 * Needs the server to have KhoaTools too; without it the remotes behave like normal items.
 */
public final class RemoteSwitchClientFeature implements KhoaFeature {
	private static final String ID = "remoteswitch";
	private static final int SEGMENTS = 28;
	private static final int RESCAN_TICKS = 10;
	private static final int MAX_CANDIDATES = 4000;
	private static final int BLUE = 0x3A8CFF;
	private static final int RED = 0xFF3030;

	/** What the v2 remote is aimed at right now (updated every frame). */
	private record Target(BlockPos pos, boolean hit, double radius) {
	}

	private static boolean on = true;
	private static volatile Target target;
	private static volatile List<BlockPos> candidates = List.of();
	private static int rescanTimer;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Remote Switch";
	}

	@Override
	public String description() {
		return "Client side of the server's Remote Switch items (/khoatools remote_switch give | give_v2). Remote Switch: right click a lever / button / plate to link, "
			+ "then right click anywhere to toggle it (the block itself is never clicked). Remote v2: aim near a switch, aim into the blue circle (it turns red) and click. "
			+ "Click the card to turn it on/off.";
	}

	@Override
	public boolean isActive() {
		return on;
	}

	@Override
	public List<Setting> settings() {
		return List.of(
			Setting.slider("near", "Show circle within", "Remote v2 shows the blue circle when your aim passes within this many blocks beyond the circle's edge of a switch.", 1, 12,
				RemoteSwitchClientFeature::near, v -> KhoaConfig.put(ID, "near", v)));
	}

	/** Remote v2 works on every switch you can see: as far as your render distance. */
	private static int range() {
		return Math.max(2, Minecraft.getInstance().options.renderDistance().get()) * 16;
	}

	private static int near() {
		return Math.max(1, Math.min(12, KhoaConfig.getInt(ID, "near", 4)));
	}

	@Override
	public void activate(Minecraft mc) {
		on = !on;
		KhoaConfig.put(ID, "enabled", on);
		target = null;
		KhoaFeature.say(mc, "Remote Switch: " + (on ? "on" : "off"));
	}

	@Override
	public void onInit() {
		on = KhoaConfig.getBool(ID, "enabled", true);
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> use(player, level, hand, hit.getBlockPos()));
		UseItemCallback.EVENT.register((player, level, hand) -> use(player, level, hand, null));
		ClientTickEvents.END_CLIENT_TICK.register(RemoteSwitchClientFeature::tick);
		LevelRenderEvents.END_EXTRACTION.register(context -> {
			Minecraft mc = Minecraft.getInstance();
			if (on && mc.player != null && mc.level != null && holdingV2(mc.player)) {
				Vec3 eye = context.camera().position();
				org.joml.Vector3fc f = context.camera().forwardVector();
				updateTarget(mc.level, eye, new Vec3(f.x(), f.y(), f.z()));
				draw(eye);
			} else {
				target = null;
			}
		});
	}

	// ---------------------------------------------------------------- using

	private static boolean holdingV2(Player player) {
		return RemoteSwitch.kindOf(player.getMainHandItem()) == RemoteSwitch.Kind.V2
			|| RemoteSwitch.kindOf(player.getOffhandItem()) == RemoteSwitch.Kind.V2;
	}

	private static InteractionResult use(Player player, Level level, InteractionHand hand, BlockPos clicked) {
		if (!on || !level.isClientSide() || !ClientPlayNetworking.canSend(RemoteUsePayload.TYPE)) {
			return InteractionResult.PASS;
		}
		ItemStack stack = player.getItemInHand(hand);
		RemoteSwitch.Kind kind = RemoteSwitch.kindOf(stack);
		if (kind == null) {
			return InteractionResult.PASS;
		}
		boolean offHand = hand == InteractionHand.OFF_HAND;
		BlockPos switchPos = clicked != null && RemoteSwitch.isSwitchBlock(level.getBlockState(clicked)) ? clicked : null;

		if (kind == RemoteSwitch.Kind.SWITCH) {
			if (!RemoteSwitch.isLinked(stack)) {
				if (switchPos == null) {
					return InteractionResult.PASS; // nothing to link to: behave like a normal item
				}
				send(offHand, switchPos);
			} else if (player.isShiftKeyDown() && switchPos != null) {
				send(offHand, switchPos); // sneak + click on a switch: relink
			} else {
				send(offHand, null); // toggle (or, sneaking in the air, disconnect)
			}
			return InteractionResult.FAIL; // FAIL = Fabric sends no vanilla use packet, the block is never clicked
		}

		Target t = target;
		if (t == null || !t.hit()) {
			return InteractionResult.PASS;
		}
		send(offHand, t.pos());
		return InteractionResult.FAIL;
	}

	private static void send(boolean offHand, BlockPos pos) {
		ClientPlayNetworking.send(new RemoteUsePayload(offHand, pos != null, pos == null ? BlockPos.ZERO : pos));
	}

	// ---------------------------------------------------------------- Remote v2 aiming

	private static void tick(Minecraft mc) {
		if (!on || mc.player == null || mc.level == null || !holdingV2(mc.player)) {
			rescanTimer = 0;
			return;
		}
		if (rescanTimer-- > 0) {
			return;
		}
		rescanTimer = RESCAN_TICKS;
		candidates = scan(mc.level, mc.player.blockPosition(), range());
	}

	private static List<BlockPos> scan(ClientLevel level, BlockPos center, int range) {
		List<BlockPos> out = new ArrayList<>();
		int minY = level.getMinY();
		for (int cx = (center.getX() - range) >> 4; cx <= (center.getX() + range) >> 4; cx++) {
			for (int cz = (center.getZ() - range) >> 4; cz <= (center.getZ() + range) >> 4; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) {
					continue;
				}
				LevelChunkSection[] sections = chunk.getSections();
				for (int i = 0; i < sections.length; i++) {
					LevelChunkSection section = sections[i];
					if (section.hasOnlyAir() || !section.maybeHas(RemoteSwitch::isSwitchBlock)) {
						continue;
					}
					int baseY = minY + (i << 4);
					for (int ly = 0; ly < 16; ly++) {
						for (int lz = 0; lz < 16; lz++) {
							for (int lx = 0; lx < 16; lx++) {
								if (RemoteSwitch.isSwitchBlock(section.getBlockState(lx, ly, lz))) {
									out.add(new BlockPos((cx << 4) + lx, baseY + ly, (cz << 4) + lz));
									if (out.size() >= MAX_CANDIDATES) {
										return out;
									}
								}
							}
						}
					}
				}
			}
		}
		return out;
	}

	/** Pick the switch the aim is nearest to: distance from the aim point to the middle of the switch, measured on the plane through it facing you. */
	private static void updateTarget(ClientLevel level, Vec3 eye, Vec3 look) {
		double extra = near();
		double maxDist = range();
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		double bestScore = Double.MAX_VALUE;
		double bestRadius = RemoteSwitch.V2_BASE_RADIUS;
		for (BlockPos p : candidates) {
			Vec3 c = Vec3.atCenterOf(p);
			Vec3 v = c.subtract(eye);
			double dist = v.length();
			if (dist < 0.8 || dist > maxDist) {
				continue;
			}
			Vec3 n = v.scale(1.0 / dist);
			double denom = look.dot(n);
			if (denom < 0.2) {
				continue; // behind you / far off to the side
			}
			Vec3 hit = eye.add(look.scale(dist / denom));
			double d = hit.distanceTo(c);
			// the circle grows with distance, so compare in units of its own radius: the switch whose circle you aim nearest the middle of wins
			double radius = RemoteSwitch.v2Radius(dist);
			double score = d / radius;
			if (d <= radius + extra && score < bestScore && RemoteSwitch.isSwitchBlock(level.getBlockState(p))) {
				bestScore = score;
				best = p;
				bestRadius = radius;
				bestD = d;
			}
		}
		target = best == null ? null : new Target(best, bestD <= bestRadius, bestRadius);
	}

	private static void draw(Vec3 eye) {
		Target t = target;
		if (t == null) {
			return;
		}
		boolean hit = t.hit();
		int rgb = hit ? RED : BLUE;
		Vec3 c = Vec3.atCenterOf(t.pos());
		Vec3 n = c.subtract(eye).normalize();
		Vec3 u = n.cross(new Vec3(0, 1, 0));
		if (u.lengthSqr() < 1.0E-4) {
			u = n.cross(new Vec3(1, 0, 0));
		}
		u = u.normalize();
		Vec3 w = n.cross(u).normalize();
		Vec3 centre = c;

		GizmoStyle fill = GizmoStyle.fill(ARGB.color(hit ? 110 : 80, (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255));
		int stroke = ARGB.color(255, (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
		double radius = t.radius();
		Vec3 prev = point(centre, u, w, 0, radius);
		for (int i = 1; i <= SEGMENTS; i++) {
			Vec3 next = point(centre, u, w, i, radius);
			Gizmos.rect(centre, prev, next, centre, fill).setAlwaysOnTop();
			Gizmos.line(prev, next, stroke, 3.0F).setAlwaysOnTop();
			prev = next;
		}
	}

	private static Vec3 point(Vec3 centre, Vec3 u, Vec3 w, int i, double radius) {
		double a = 2 * Math.PI * i / SEGMENTS;
		return centre.add(u.scale(Math.cos(a) * radius)).add(w.scale(Math.sin(a) * radius));
	}
}
