package khoa.tools.command;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;

import khoa.tools.KhoaTools;

/**
 * /khoatools farmobai            = toggle.
 * /khoatools farmobai &lt;distance&gt; [interval] = turns it on: mobs farther than distance (8-128 blocks) from every player run their AI step
 * (goals, targeting, pathfinding, move control) only once every interval ticks (2-20, default 4). Between those ticks they keep their last
 * movement, so a far army still walks towards you, it just thinks less often. Mobs near any player are untouched. State is saved in
 * config/khoatools-server.properties.
 */
public final class FarMobAiFeature implements KhoaServerFeature {
	private static volatile boolean enabled;
	private static volatile int distance = 16;
	private static volatile int interval = 4;
	private static boolean loaded;

	/** Called by the Mob AI mixin each tick: true = skip this AI step. */
	public static boolean shouldSkip(Mob mob) {
		load();
		if (!enabled || !(mob.level() instanceof ServerLevel level) || mob.isPassenger() || mob.isVehicle()) {
			return false;
		}
		double limit = (double) distance * distance;
		boolean any = false;
		for (ServerPlayer p : level.players()) {
			any = true;
			if (p.distanceToSqr(mob) <= limit) {
				return false;
			}
		}
		return any && (mob.tickCount + mob.getId()) % interval != 0;
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("khoatools-server.properties");
	}

	private static synchronized void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		if (!Files.exists(file())) {
			return;
		}
		try (InputStream in = Files.newInputStream(file())) {
			Properties p = new Properties();
			p.load(in);
			enabled = Boolean.parseBoolean(p.getProperty("farmobai.enabled", "false"));
			distance = clamp(Integer.parseInt(p.getProperty("farmobai.distance", "16")), 8, 128);
			interval = clamp(Integer.parseInt(p.getProperty("farmobai.interval", "4")), 2, 20);
		} catch (IOException | RuntimeException e) {
			KhoaTools.LOGGER.error("Could not read khoatools-server.properties, using defaults", e);
		}
	}

	/** Rewrites the file keeping the other features' keys. */
	private static synchronized void save() {
		Properties p = new Properties();
		if (Files.exists(file())) {
			try (InputStream in = Files.newInputStream(file())) {
				p.load(in);
			} catch (IOException e) {
				KhoaTools.LOGGER.error("Could not read khoatools-server.properties", e);
			}
		}
		p.setProperty("farmobai.enabled", Boolean.toString(enabled));
		p.setProperty("farmobai.distance", Integer.toString(distance));
		p.setProperty("farmobai.interval", Integer.toString(interval));
		try {
			Files.createDirectories(file().getParent());
			try (OutputStream out = Files.newOutputStream(file())) {
				p.store(out, "KhoaTools server features");
			}
		} catch (IOException e) {
			KhoaTools.LOGGER.error("Could not save khoatools-server.properties", e);
		}
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static String status() {
		return "Far mob AI: " + (enabled ? "on" : "off") + " (mobs more than " + distance + " blocks from every player think once per " + interval + " ticks)";
	}

	@Override
	public String id() {
		return "farmobai";
	}

	@Override
	public String displayName() {
		return "Far mob AI";
	}

	@Override
	public String description() {
		return "Mobs far from every player run their AI less often. Less lag with huge armies.";
	}

	@Override
	public void registerCommands(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext context) {
		root.then(Commands.literal(id())
			.executes(ctx -> {
				load();
				enabled = !enabled;
				save();
				ctx.getSource().sendSuccess(() -> Component.literal(status()), false);
				return 1;
			})
			.then(Commands.argument("distance", IntegerArgumentType.integer(8, 128))
				.executes(ctx -> set(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "distance"), interval))
				.then(Commands.argument("interval", IntegerArgumentType.integer(2, 20))
					.executes(ctx -> set(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "distance"), IntegerArgumentType.getInteger(ctx, "interval"))))));
	}

	private static int set(CommandSourceStack source, int d, int i) {
		load();
		distance = d;
		interval = i;
		enabled = true;
		save();
		source.sendSuccess(() -> Component.literal(status()), false);
		return 1;
	}
}
