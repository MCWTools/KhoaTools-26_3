package khoa.tools.client.feature.blocktext;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import khoa.tools.client.feature.KhoaFeature;

/**
 * Block Text (client side): look at the side of a block and click the card (or run the command) to write a text on it.
 * The text is drawn flat on that side (black outline, white letters by default), is saved per world / server, and disappears when its block is broken.
 * Shift + right click on a text opens the editor again.
 */
public final class BlockTextFeature implements KhoaFeature {
	private static final String ID = "blocktext";
	private static boolean openNext;
	private static BlockText pending;
	private static boolean pendingCreated;
	private static int tick;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "Block Text";
	}

	@Override
	public String description() {
		return "Look at the side of a block and click the card: a text editor opens and the text is written on that side "
			+ "(auto sized, up to about 20 letters per line and 10 lines). Breaking the block removes the text. "
			+ "Shift + right click a text to edit it.";
	}

	@Override
	public void activate(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			KhoaFeature.say(mc, "Block Text: join a world first.");
			return;
		}
		if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			KhoaFeature.say(mc, "Block Text: look at the side of a block, then use this again.");
			return;
		}
		String dim = dimension(mc);
		BlockPos p = hit.getBlockPos();
		String face = hit.getDirection().getName();
		BlockText existing = BlockTextStore.find(mc, dim, p.getX(), p.getY(), p.getZ(), face);
		if (existing != null) {
			pending = existing;
			pendingCreated = false;
		} else {
			BlockText t = new BlockText();
			t.dim = dim;
			t.x = p.getX();
			t.y = p.getY();
			t.z = p.getZ();
			t.face = face;
			BlockTextStore.all(mc).add(t);
			pending = t;
			pendingCreated = true;
		}
		openNext = true; // the screen opens one tick later so the command / click that started this does not close it again
	}

	@Override
	public void onInit() {
		ClientTickEvents.END_CLIENT_TICK.register(BlockTextFeature::tick);
		LevelRenderEvents.END_EXTRACTION.register(context -> drawAll());
	}

	static String dimension(Minecraft mc) {
		return mc.level.dimension().identifier().toString();
	}

	static void remove(BlockText t) {
		BlockTextStore.all(Minecraft.getInstance()).remove(t);
	}

	static void saved() {
		BlockTextStore.save(Minecraft.getInstance());
	}

	private static BlockText looked(Minecraft mc) {
		if (mc.level == null || mc.gui.screen() != null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			return null;
		}
		BlockPos p = hit.getBlockPos();
		return BlockTextStore.find(mc, dimension(mc), p.getX(), p.getY(), p.getZ(), hit.getDirection().getName());
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			pending = null;
			openNext = false;
			return;
		}
		if (openNext) {
			openNext = false;
			if (pending != null) {
				mc.gui.setScreen(new BlockTextScreen(pending, pendingCreated));
				pending = null;
			}
			return;
		}
		if (looked(mc) != null) {
			mc.gui.hud.setOverlayMessage(Component.literal("Shift + Right click to edit text"), false);
		}
		if (++tick % 10 == 0) {
			removeBroken(mc);
		}
	}

	/** Texts whose block is gone (mined, exploded...) are deleted; unloaded chunks are left alone. */
	private static void removeBroken(Minecraft mc) {
		List<BlockText> gone = new ArrayList<>();
		String dim = dimension(mc);
		for (BlockText t : BlockTextStore.all(mc)) {
			BlockPos p = new BlockPos(t.x, t.y, t.z);
			if (t.dim.equals(dim) && mc.level.hasChunkAt(p) && mc.level.getBlockState(p).isAir()) {
				gone.add(t);
			}
		}
		if (!gone.isEmpty()) {
			BlockTextStore.all(mc).removeAll(gone);
			BlockTextStore.save(mc);
		}
	}

	/** Called by the mixin on right click: Shift + right click on a text opens its editor. True = handled. */
	public static boolean onUse(Minecraft mc) {
		if (mc.player == null || !mc.player.isShiftKeyDown()) {
			return false;
		}
		BlockText t = looked(mc);
		if (t == null) {
			return false;
		}
		pending = t;
		pendingCreated = false;
		openNext = true;
		return true;
	}

	private static void drawAll() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return;
		}
		var eye = mc.gameRenderer.mainCamera().position();
		String dim = dimension(mc);
		for (BlockText t : BlockTextStore.all(mc)) {
			if (t.dim.equals(dim)) {
				BlockTextRender.draw(mc, t, eye);
			}
		}
	}
}
