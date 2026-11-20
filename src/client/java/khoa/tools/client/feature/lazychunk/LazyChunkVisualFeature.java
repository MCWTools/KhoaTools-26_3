package khoa.tools.client.feature.lazychunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.ARGB;

import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.setting.Setting;

/**
 * Lazy Chunk Visual: every block (and fluid) in the "lazy chunk" ring around you is tinted yellow, so you can see where it is.
 * <p>
 * Lazy chunk = a chunk at level 32 ("block ticking"): redstone / scheduled ticks run there, entities do not. With simulation distance S the
 * server gives every chunk with Chebyshev distance &lt;= S from your chunk level 31 (entity ticking), the single ring at distance S + 1 level 32
 * (lazy) and the ring at S + 2 level 33 (border, not shown). S comes from the server's simulation distance packet. Chunks kept loaded by other
 * players, forceload or portals cannot be seen by the client, so only the ring from YOUR own chunk is shown.
 * <p>
 * The tint is applied while the chunk sections are meshed (see LazyChunkTintMixin / LazyChunkFluidMixin), so a block that changes is meshed with the tint
 * again. When the ring moves (you cross a chunk border, S changes, on / off, lock / unlock) the columns that entered or left the ring are re-meshed.
 * Lock freezes the ring where it is; turning the feature off and on again unlocks it.
 */
public final class LazyChunkVisualFeature implements KhoaFeature {
	/** Multiplied into every vertex colour: keeps red, drops a little green and a lot of blue = yellower than normal. */
	public static final int TINT = ARGB.color(255, 255, 238, 80);

	/** The ring being shown: all chunks with max(|x - cx|, |z - cz|) == ring. */
	private record Area(int cx, int cz, int ring) {
		boolean contains(int x, int z) {
			return Math.max(Math.abs(x - cx), Math.abs(z - cz)) == ring;
		}
	}

	/** Read by the mesh threads. */
	private static volatile Area area;
	private static boolean on;
	private static boolean locked;
	private static Area lockedArea;
	private static ClientLevel lastLevel;

	/** Is the block at this position inside a lazy chunk that is currently tinted? Called from the chunk mesh threads. */
	public static boolean isLazy(net.minecraft.core.BlockPos pos) {
		Area a = area;
		return a != null && a.contains(pos.getX() >> 4, pos.getZ() >> 4);
	}

	@Override
	public String id() {
		return "lazychunk";
	}

	@Override
	public String displayName() {
		return "Lazy Chunk Visual";
	}

	@Override
	public String description() {
		return "Tints every block in your lazy chunks (the ring just outside your simulation distance, where redstone runs but entities do not) yellow. "
			+ "Unloaded chunks are not shown. Click the card to turn it on/off. Turning it off and on again unlocks it.";
	}

	@Override
	public boolean isActive() {
		return on;
	}

	@Override
	public List<Setting> settings() {
		return List.of(Setting.action("lock", "Lock", "Locked: the yellow ring stays where it is while you walk (and ignores simulation distance changes). Unlocked: it follows you.",
			() -> locked ? "Unlock" : "Lock", LazyChunkVisualFeature::toggleLock));
	}

	@Override
	public void activate(Minecraft mc) {
		on = !on;
		locked = false; // re-enabling always starts unlocked
		lockedArea = null;
		lastLevel = null;
		KhoaFeature.say(mc, "Lazy Chunk Visual: " + (on ? "on" : "off"));
		if (!on) {
			setArea(mc.level, null);
		} else {
			update(mc);
		}
	}

	private static void toggleLock() {
		Minecraft mc = Minecraft.getInstance();
		if (!on) {
			KhoaFeature.say(mc, "Lazy Chunk Visual is off: turn it on first.");
			return;
		}
		if (locked) {
			locked = false;
			lockedArea = null;
		} else if (area != null) {
			locked = true;
			lockedArea = area;
		} else {
			KhoaFeature.say(mc, "Lazy Chunk Visual: nothing to lock yet.");
			return;
		}
		KhoaFeature.say(mc, "Lazy Chunk Visual: " + (locked ? "locked" : "unlocked"));
		update(mc);
	}

	@Override
	public void onInit() {
		ClientTickEvents.END_CLIENT_TICK.register(LazyChunkVisualFeature::update);
	}

	private static void update(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level != lastLevel) {
			// new world / dimension: nothing of the old ring exists any more, and a lock does not carry over
			lastLevel = level;
			area = null;
			if (locked) {
				locked = false;
				lockedArea = null;
			}
		}
		if (!on || level == null || mc.player == null) {
			if (area != null) {
				setArea(level, null);
			}
			return;
		}
		Area wanted = locked && lockedArea != null ? lockedArea
			: new Area(mc.player.blockPosition().getX() >> 4, mc.player.blockPosition().getZ() >> 4, lazyRing(level.getServerSimulationDistance()));
		if (!wanted.equals(area)) {
			setArea(level, wanted);
		}
	}

	/** The player ticket has level max(0, 31 - S) in your chunk and +1 per chunk of distance; level 32 = block ticking = lazy. */
	private static int lazyRing(int simulationDistance) {
		return 32 - Math.max(0, 31 - simulationDistance);
	}

	/** Switch to a new ring and re-mesh every chunk column that entered or left it. */
	private static void setArea(ClientLevel level, Area next) {
		Area old = area;
		area = next;
		if (level == null) {
			return;
		}
		Set<Long> before = columns(old);
		Set<Long> after = columns(next);
		Set<Long> changed = new HashSet<>(before);
		for (long c : after) {
			if (!changed.remove(c)) {
				changed.add(c);
			}
		}
		int minY = level.getMinSectionY();
		int maxY = level.getMaxSectionY();
		for (long c : changed) {
			int x = (int) (c >> 32);
			int z = (int) c;
			level.setSectionRangeDirty(x, minY, z, x, maxY, z);
		}
	}

	private static Set<Long> columns(Area a) {
		Set<Long> out = new HashSet<>();
		if (a == null || a.ring() < 0) {
			return out;
		}
		int r = a.ring();
		List<int[]> cells = new ArrayList<>();
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) == r) {
					cells.add(new int[] {a.cx() + dx, a.cz() + dz});
				}
			}
		}
		for (int[] c : cells) {
			out.add(((long) c[0] << 32) | (c[1] & 0xFFFFFFFFL));
		}
		return out;
	}
}
