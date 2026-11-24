package khoa.tools.client.feature.heightlaser;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Where each beam ends. A beam is a straight line of {@code length} blocks that stops at the first solid block of a LOADED chunk;
 * unloaded chunks count as empty, so it simply carries on through them. Results are cached and refreshed every few ticks
 * (blocks may change) or when the laser is edited.
 */
final class LaserBeams {
	/** A computed beam: start, end, and whether a block stopped it. */
	record Beam(Vec3 start, Vec3 end, boolean blocked) {
	}

	private static final int REFRESH_TICKS = 5;

	private record Entry(int revision, long tick, Beam[] beams) {
	}

	private static final Map<Laser, Entry> CACHE = new IdentityHashMap<>();
	private static long ticks;

	private LaserBeams() {
	}

	static void tick() {
		ticks++;
	}

	static void clear() {
		CACHE.clear();
	}

	/** Beam of a port, or null when the port is off or the laser is off. */
	static Beam of(Laser laser, int port) {
		if (!laser.on || !laser.ports[port]) {
			return null;
		}
		Entry e = CACHE.get(laser);
		if (e == null || e.revision != laser.revision || ticks - e.tick >= REFRESH_TICKS) {
			Beam[] beams = new Beam[6];
			for (int p = 0; p < 6; p++) {
				beams[p] = laser.ports[p] ? compute(laser, p) : null;
			}
			e = new Entry(laser.revision, ticks, beams);
			CACHE.put(laser, e);
		}
		return e.beams[port];
	}

	static Beam compute(Laser laser, int port) {
		Vec3 start = laser.beamStart(port);
		Vec3 dir = laser.beamDirection(port);
		Vec3 end = start.add(dir.scale(laser.length));
		boolean blocked = false;
		ClientLevel level = Minecraft.getInstance().level;
		if (level != null && Minecraft.getInstance().player != null) {
			BlockHitResult hit = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
				Minecraft.getInstance().player));
			if (hit.getType() == HitResult.Type.BLOCK) {
				end = hit.getLocation();
				blocked = true;
			}
		}
		return new Beam(start, end, blocked);
	}
}
