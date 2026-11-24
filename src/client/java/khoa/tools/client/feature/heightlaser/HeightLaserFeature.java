package khoa.tools.client.feature.heightlaser;

import java.util.List;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.ARGB;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import khoa.api.world.KhoaRay;
import khoa.api.world.RayHit;
import khoa.tools.client.config.KhoaKeybinds;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.setting.Setting;

/**
 * Height Laser (client side only): put a laser device next to the block you aim at, right click it to set rotation, offset, length
 * and On/Off in a config screen with a live viewport. Shift + right click a port toggles that port, shift + left click breaks
 * the device. Only enabled ports of a switched-on laser fire a neon green beam, straight along any angle, through chunks that are
 * not loaded, stopping at the first solid block. Nothing is ever sent to the server.
 */
public final class HeightLaserFeature implements KhoaFeature {
	private static final String ID = "heightlaser";
	/** How far you can reach a device. */
	private static final double REACH = 6.0;
	/** Devices further than this are not drawn (their beams still are). */
	private static final double MODEL_RANGE = 96;

	private static final int HALO = ARGB.color(55, 0, 255, 90);
	private static final int MID = ARGB.color(130, 60, 255, 120);
	private static final int CORE = ARGB.color(255, 215, 255, 225);

	private static boolean on;
	private static Laser pendingOpen;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Height Laser";
	}

	@Override
	public String description() {
		return "Press the Place key while aiming at a block: a laser device appears next to the face you look at. Right click it to open its settings (rotation, offset, length, On/Off, viewport). "
			+ "Shift + right click a port to switch that port on / off, shift + left click to break it. Only enabled ports shoot a beam. Client side only, nothing is sent to the server. Turn the card off to hide everything.";
	}

	@Override
	public boolean isActive() {
		return on;
	}

	@Override
	public boolean persistEnabled() {
		return true;
	}

	@Override
	public void activate(Minecraft mc) {
		on = !on;
		KhoaFeature.say(mc, "Height Laser: " + (on ? "on" : "off (lasers hidden)"));
	}

	@Override
	public List<Setting> settings() {
		return List.of(
			KhoaKeybinds.setting(ID, "place_key", "Place key", "Aim at a block and press this key: a laser device is put next to the face you look at.", () -> place(Minecraft.getInstance())),
			Setting.action("place", "Place now", "Same as the Place key (aim first: this only works while no menu is open, so prefer the key or /khoatoolsclient heightlaser place).", "Place", () -> place(Minecraft.getInstance())),
			Setting.action("removeall", "Remove all lasers", "Deletes every laser of this world.", "Remove all", HeightLaserFeature::removeAll));
	}

	@Override
	public void registerCommands(LiteralArgumentBuilder<FabricClientCommandSource> root, CommandBuildContext context) {
		root.then(ClientCommands.literal(ID)
			.executes(ctx -> {
				activate(ctx.getSource().getClient());
				return 1;
			})
			.then(ClientCommands.literal("place").executes(ctx -> {
				place(ctx.getSource().getClient());
				return 1;
			})));
	}

	@Override
	public void onInit() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			FeatureSelfTest.tick(mc);
			LaserBeams.tick();
			LaserStore.sync(mc);
			if (pendingOpen != null) {
				Laser l = pendingOpen;
				pendingOpen = null;
				if (mc.gui.screen() == null && LaserStore.all().contains(l)) {
					mc.gui.setScreen(new LaserScreen(l));
				}
			}
		});
		LevelRenderEvents.END_EXTRACTION.register(ctx -> draw());

		// right click on a device: handled here, never reaches the server (FAIL = no packet)
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> use(world instanceof ClientLevel, hand));
		UseItemCallback.EVENT.register((player, world, hand) -> use(world instanceof ClientLevel, hand));
		// shift + left click: break the device
		ClientPreAttackCallback.EVENT.register((client, player, clickCount) -> {
			if (!on || !player.isShiftKeyDown()) {
				return false;
			}
			Aim aim = aim(client);
			if (aim == null) {
				return false;
			}
			LaserStore.remove(aim.laser);
			LaserBeams.clear();
			KhoaFeature.say(client, "Laser removed");
			return true;
		});
	}

	// ------------------------------------------------------------ interaction

	record Aim(Laser laser, LaserModel.Hit hit) {
	}

	/** The device under the crosshair, if it is closer than whatever block vanilla would hit. */
	static Aim aim(Minecraft mc) {
		if (!on || mc.player == null || mc.level == null || mc.gui.screen() != null) {
			return null;
		}
		Vec3 eye = mc.player.getEyePosition();
		Vec3 look = mc.player.getLookAngle();
		double blockDist = Double.MAX_VALUE;
		if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) {
			blockDist = mc.hitResult.getLocation().distanceTo(eye);
		}
		Aim best = null;
		double bestDist = Math.min(REACH, blockDist);
		for (Laser l : LaserStore.all()) {
			LaserModel.Hit h = LaserModel.raycast(l, eye, look, bestDist);
			if (h != null && h.distance() < bestDist) {
				bestDist = h.distance();
				best = new Aim(l, h);
			}
		}
		return best;
	}

	static InteractionResult use(boolean client, InteractionHand hand) {
		if (!client || !on) {
			return InteractionResult.PASS;
		}
		Minecraft mc = Minecraft.getInstance();
		Aim aim = aim(mc);
		if (aim == null) {
			return InteractionResult.PASS;
		}
		if (hand == InteractionHand.MAIN_HAND) {
			if (mc.player.isShiftKeyDown()) {
				aim.laser.ports[aim.hit.port()] = !aim.laser.ports[aim.hit.port()];
				aim.laser.changed();
				LaserStore.save();
			} else {
				pendingOpen = aim.laser;
			}
		}
		return InteractionResult.FAIL;
	}

	static void place(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		if (!on) {
			on = true;
		}
		RayHit hit = KhoaRay.blockFromEyes(8, false);
		if (!hit.hit() || hit.pos() == null || hit.face() == null) {
			KhoaFeature.say(mc, "Height Laser: aim at a block first");
			return;
		}
		BlockPos cell = hit.pos().relative(hit.face());
		for (Laser l : LaserStore.all()) {
			if (l.cell.equals(cell)) {
				KhoaFeature.say(mc, "Height Laser: there is already a laser there");
				return;
			}
		}
		Laser laser = new Laser(cell);
		Direction f = hit.face();
		// sit against the block (the body is about 0.3 wide, the ports reach 0.23 from the centre)
		laser.offX = -f.getStepX() * 0.29;
		laser.offY = -f.getStepY() * 0.29;
		laser.offZ = -f.getStepZ() * 0.29;
		laser.ports[f.get3DDataValue()] = true; // the port that points away from the block
		LaserStore.add(laser);
		KhoaFeature.say(mc, "Laser placed. Right click it to configure; it is Off until you press On.");
	}

	private static void removeAll() {
		for (Laser l : List.copyOf(LaserStore.all())) {
			LaserStore.remove(l);
		}
		LaserBeams.clear();
	}

	// ------------------------------------------------------------ drawing

	private static void draw() {
		if (!on) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		Vec3 cam = mc.gameRenderer.mainCamera().position();
		for (Laser l : LaserStore.all()) {
			if (l.center().distanceTo(cam) <= MODEL_RANGE) {
				for (LaserModel.Quad q : LaserModel.quads(l)) {
					Gizmos.rect(q.a(), q.b(), q.c(), q.d(), GizmoStyle.fill(q.argb()));
				}
			}
			for (int p = 0; p < 6; p++) {
				LaserBeams.Beam beam = LaserBeams.of(l, p);
				if (beam == null) {
					continue;
				}
				Gizmos.line(beam.start(), beam.end(), HALO, 14f);
				Gizmos.line(beam.start(), beam.end(), MID, 6f);
				Gizmos.line(beam.start(), beam.end(), CORE, 2.2f);
				if (beam.blocked()) {
					Gizmos.point(beam.end(), ARGB.color(220, 90, 255, 140), 16f);
				}
			}
		}
	}
}
