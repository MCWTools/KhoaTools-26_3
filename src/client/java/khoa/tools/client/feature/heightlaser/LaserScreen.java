package khoa.tools.client.feature.heightlaser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Settings of one laser: On/Off, the 6 ports, rotation, offset and length on the left, and a live viewport on the right (about 10
 * frames per second, only the blocks within 10 of the laser). Drag in the viewport to orbit, scroll to zoom; "Auto view" picks the
 * angle that shows the device and its beams.
 */
final class LaserScreen extends Screen {
	private static final Identifier TEXTURE_ID = Identifier.fromNamespaceAndPath("khoatools", "heightlaser_viewport");
	private static final String[] PORT_NAMES = { "Down", "Up", "North", "South", "West", "East" };
	/** Row pitch, button height and column width: shrink on small windows so the viewport keeps its room. */
	private int slot = 22;
	private int bh = 20;
	private int colW = 176;
	/** The viewport is redrawn every this many ticks (2 = 10 per second). */
	private static final int RENDER_EVERY = 2;

	private final Laser laser;
	private final List<Button> portButtons = new ArrayList<>();
	private Button onButton;
	private Button autoButton;

	private LaserViewport viewport;
	private int vx;
	private int vy;
	private int vw;
	private int vh;
	private int ticks;

	private boolean auto = true;
	private int autoRevision = -1;
	private double camYaw = 35;
	private double camPitch = 25;
	private double camDist = 5;
	private Vec3 camTarget = Vec3.ZERO;
	private boolean dragging;

	LaserScreen(Laser laser) {
		super(Component.literal("Height Laser"));
		this.laser = laser;
		this.camTarget = laser.center();
	}

	@Override
	protected void init() {
		colW = this.width < 560 ? 150 : 176;
		slot = Math.max(14, Math.min(22, (this.height - 16) / 12));
		bh = slot - 2;
		portButtons.clear();
		int x = 8;
		int y = 8;
		int bottom = this.height - 8;

		onButton = addButton(x, y, colW, onLabel(), b -> {
			laser.on = !laser.on;
			changed();
			b.setMessage(onLabel());
		});
		y += slot;
		for (int i = 0; i < 6; i++) {
			final int port = i;
			int bx = x + (i % 3) * (colW / 3);
			int by = y + (i / 3) * slot;
			Button b = addButton(bx, by, colW / 3 - 2, portLabel(port), btn -> {
				laser.ports[port] = !laser.ports[port];
				changed();
				btn.setMessage(portLabel(port));
			});
			portButtons.add(b);
		}
		y += slot * 2;

		int[] cursor = { x, y };
		slider(cursor, bottom, "Yaw", -180, 180, 1, () -> laser.yaw, v -> laser.yaw = v);
		slider(cursor, bottom, "Pitch", -90, 90, 1, () -> laser.pitch, v -> laser.pitch = v);
		slider(cursor, bottom, "Roll", -180, 180, 1, () -> laser.roll, v -> laser.roll = v);
		slider(cursor, bottom, "Offset X", -1, 1, 0.05, () -> laser.offX, v -> laser.offX = v);
		slider(cursor, bottom, "Offset Y", -1, 1, 0.05, () -> laser.offY, v -> laser.offY = v);
		slider(cursor, bottom, "Offset Z", -1, 1, 0.05, () -> laser.offZ, v -> laser.offZ = v);
		lengthSlider(cursor, bottom);
		autoButton = place(cursor, bottom, c -> addButton(c[0], c[1], colW, autoLabel(), b -> {
			auto = !auto;
			autoRevision = -1;
			b.setMessage(autoLabel());
		}));
		place(cursor, bottom, c -> {
			addButton(c[0], c[1], colW / 2 - 2, Component.literal("Remove laser"), b -> {
				LaserStore.remove(laser);
				LaserBeams.clear();
				this.minecraft.gui.setScreen(null);
			});
			return addButton(c[0] + colW / 2, c[1], colW / 2 - 2, Component.literal("Done"), b -> onClose());
		});

		int colsRight = cursor[0] + colW + 8;
		vx = colsRight;
		vy = 8;
		vw = Math.max(80, this.width - vx - 8);
		vh = Math.max(80, this.height - 16);
		int tw = Math.max(64, Math.min(420, vw / 2));
		int th = Math.max(64, Math.min(320, vh / 2));
		if (viewport == null || viewport.w != tw || viewport.h != th) {
			releaseViewport();
			viewport = new LaserViewport(tw, th, "khoatools heightlaser viewport");
			this.minecraft.getTextureManager().register(TEXTURE_ID, viewport.texture);
		}
		autoRevision = -1;
		ticks = RENDER_EVERY;
	}

	private Button addButton(int x, int y, int w, Component text, Button.OnPress press) {
		return addRenderableWidget(Button.builder(text, press).bounds(x, y, w, bh).build());
	}

	/** Places one control slot, moving to the next column when the current one is full. */
	private <T> T place(int[] cursor, int bottom, java.util.function.Function<int[], T> make) {
		if (cursor[1] + bh > bottom) {
			cursor[0] += colW + 8;
			cursor[1] = 8;
		}
		T t = make.apply(cursor);
		cursor[1] += slot;
		return t;
	}

	private void slider(int[] cursor, int bottom, String label, double min, double max, double step, DoubleSupplier get, DoubleConsumer set) {
		place(cursor, bottom, c -> addRenderableWidget(new ValueSlider(c[0], c[1], colW, label, min, max, step, get, set)));
	}

	private void lengthSlider(int[] cursor, int bottom) {
		place(cursor, bottom, c -> addRenderableWidget(new LengthSlider(c[0], c[1], colW)));
	}

	private Component onLabel() {
		return Component.literal("Laser: " + (laser.on ? "ON" : "OFF")).withStyle(laser.on ? ChatFormatting.GREEN : ChatFormatting.RED);
	}

	private Component portLabel(int port) {
		return Component.literal(PORT_NAMES[port]).withStyle(laser.ports[port] ? ChatFormatting.GREEN : ChatFormatting.GRAY);
	}

	private Component autoLabel() {
		return Component.literal("Auto view: " + (auto ? "on" : "off (drag to orbit)"));
	}

	private void changed() {
		laser.changed();
		LaserStore.save();
	}

	// ------------------------------------------------------------------ viewport

	@Override
	public void tick() {
		super.tick();
		if (viewport == null || this.minecraft.level == null) {
			return;
		}
		if (++ticks < RENDER_EVERY) {
			return;
		}
		ticks = 0;
		ClientLevel level = this.minecraft.level;
		if (auto && autoRevision != laser.revision) {
			autoRevision = laser.revision;
			autoView(level);
		}
		Vec3 dir = new Vec3(Math.cos(Math.toRadians(camPitch)) * Math.sin(Math.toRadians(camYaw)), Math.sin(Math.toRadians(camPitch)),
			Math.cos(Math.toRadians(camPitch)) * Math.cos(Math.toRadians(camYaw)));
		viewport.setCamera(camTarget.add(dir.scale(camDist)), camTarget);
		viewport.render(level, laser);
	}

	/** Picks the camera angle that sees the device from outside the walls with its beams leaving into the scene. */
	private void autoView(ClientLevel level) {
		Vec3 c = laser.center();
		Vec3 b = Vec3.ZERO;
		for (int p = 0; p < 6; p++) {
			if (laser.ports[p]) {
				b = b.add(laser.beamDirection(p));
			}
		}
		if (b.lengthSqr() < 1e-6) {
			b = laser.anyPort() ? laser.beamDirection(firstPort()) : new Vec3(0, 0, 1);
		}
		b = b.normalize();
		Vec3 target = c.add(b.scale(2.5));
		double bestScore = -1e9;
		Vec3 bestEye = c.add(0, 2, 4);
		int n = 48;
		for (int i = 0; i < n; i++) {
			// Fibonacci sphere
			double y = 1 - 2 * (i + 0.5) / n;
			double r = Math.sqrt(1 - y * y);
			double a = i * Math.PI * (3 - Math.sqrt(5));
			Vec3 d = new Vec3(Math.cos(a) * r, y, Math.sin(a) * r);
			if (d.y < -0.1) {
				continue;
			}
			for (double dist : new double[] { 6, 4.5, 3 }) {
				Vec3 eye = c.add(d.scale(dist));
				BlockHitResult hit = level.clip(new ClipContext(eye, c, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.minecraft.player));
				boolean blocked = hit.getType() == HitResult.Type.BLOCK && hit.getLocation().distanceTo(c) > 0.4;
				Vec3 f = target.subtract(eye).normalize();
				double s = dist * 1.5 - Math.abs(Math.abs(d.dot(b)) - 0.45) * 18 + 8 * Math.max(0, b.dot(f)) + 5 * Math.min(0.6, Math.max(0, d.y));
				if (blocked) {
					s -= 100;
				}
				if (s > bestScore) {
					bestScore = s;
					bestEye = eye;
				}
			}
		}
		camTarget = target;
		Vec3 off = bestEye.subtract(target);
		camDist = Math.max(2.5, off.length());
		camPitch = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, off.y / off.length()))));
		camYaw = Math.toDegrees(Math.atan2(off.x, off.z));
	}

	private int firstPort() {
		for (int p = 0; p < 6; p++) {
			if (laser.ports[p]) {
				return p;
			}
		}
		return 0;
	}

	private boolean inViewport(double x, double y) {
		return x >= vx && x < vx + vw && y >= vy && y < vy + vh;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && inViewport(event.x(), event.y())) {
			dragging = true;
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging) {
			auto = false;
			autoButton.setMessage(autoLabel());
			camYaw -= dx * 0.6;
			camPitch = Math.max(-85, Math.min(85, camPitch + dy * 0.6));
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double sx, double sy) {
		if (inViewport(x, y)) {
			camDist = Math.max(1.5, Math.min(40, camDist * (sy > 0 ? 0.88 : 1.14)));
			return true;
		}
		return super.mouseScrolled(x, y, sx, sy);
	}

	// ------------------------------------------------------------------ drawing / closing

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float partial) {
		g.fill(0, 0, this.width, this.height, 0xC0101014);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
		super.extractRenderState(g, mx, my, partial);
		if (viewport != null) {
			g.blit(TEXTURE_ID, vx, vy, vx + vw, vy + vh, 0f, 1f, 0f, 1f);
		}
		g.outline(vx - 1, vy - 1, vw + 2, vh + 2, 0xFF55AA77);
		g.text(this.font, "Viewport: drag = orbit, scroll = zoom (10 fps, blocks within " + LaserViewport.WALL_RADIUS + ")", vx + 4, vy + 4, 0xFFE0FFE8);
		g.text(this.font, String.format(Locale.ROOT, "Block %d %d %d", laser.cell.getX(), laser.cell.getY(), laser.cell.getZ()), vx + 4, vy + vh - 12, 0xFFB0C0B8);
	}

	/** Self test: writes the current viewport image as a PNG. */
	void dumpViewport(java.nio.file.Path file) throws java.io.IOException {
		if (viewport != null && viewport.texture.getPixels() != null) {
			viewport.texture.getPixels().writeToFile(file);
		}
	}

	java.util.List<net.minecraft.client.gui.components.events.GuiEventListener> widgets() {
		return new java.util.ArrayList<>(children());
	}

	/** Self test: centre of the viewport. */
	double[] viewportCenter() {
		return new double[] { vx + vw / 2.0, vy + vh / 2.0 };
	}

	boolean autoView() {
		return auto;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		LaserStore.save();
		this.minecraft.gui.setScreen(null);
	}

	@Override
	public void removed() {
		LaserStore.save();
		releaseViewport();
	}

	private void releaseViewport() {
		if (viewport != null) {
			this.minecraft.getTextureManager().release(TEXTURE_ID);
			viewport = null;
		}
	}

	// ------------------------------------------------------------------ sliders

	private abstract class BaseSlider extends AbstractSliderButton {
		BaseSlider(int x, int y, int w, double initial) {
			super(x, y, w, bh, Component.empty(), initial);
		}

		@Override
		public void onRelease(MouseButtonEvent event) {
			super.onRelease(event);
			LaserStore.save();
		}
	}

	private final class ValueSlider extends BaseSlider {
		private final String label;
		private final double min;
		private final double max;
		private final double step;
		private final DoubleSupplier get;
		private final DoubleConsumer set;

		ValueSlider(int x, int y, int w, String label, double min, double max, double step, DoubleSupplier get, DoubleConsumer set) {
			super(x, y, w, (get.getAsDouble() - min) / (max - min));
			this.label = label;
			this.min = min;
			this.max = max;
			this.step = step;
			this.get = get;
			this.set = set;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			if (get != null) {
				setMessage(Component.literal(label + ": " + String.format(Locale.ROOT, step < 1 ? "%.2f" : "%.0f", get.getAsDouble())));
			}
		}

		@Override
		protected void applyValue() {
			double v = min + this.value * (max - min);
			v = Math.round(v / step) * step;
			set.accept(Math.max(min, Math.min(max, v)));
			laser.changed();
		}
	}

	/** 1 .. 1024 blocks, finer at the short end. */
	private final class LengthSlider extends BaseSlider {
		LengthSlider(int x, int y, int w) {
			super(x, y, w, Math.sqrt((laser.length - 1) / (Laser.MAX_LENGTH - 1)));
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal("Length: " + (int) Math.round(laser.length) + " blocks"));
		}

		@Override
		protected void applyValue() {
			laser.length = Math.max(1, Math.min(Laser.MAX_LENGTH, Math.round(1 + this.value * this.value * (Laser.MAX_LENGTH - 1))));
			laser.changed();
		}
	}
}
