package khoa.tools.client.feature.camlock;

import java.util.List;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.util.Mth;

import khoa.tools.client.feature.KhoaFeature;

/**
 * Cam Lock: freezes your look direction so the mouse cannot turn the camera.
 * Click the card (or /khoatoolsclient camlock) to lock at the angle you are looking at now, click again to unlock.
 * /khoatoolsclient camlock <yaw> <pitch> locks at exactly those numbers.
 * Ported from the standalone "camera-angle-lock" mod (CC0) to Minecraft 26.3.
 */
public final class CamLockFeature implements KhoaFeature {
	private static boolean locked;
	private static float yaw;
	private static float pitch;

	@Override
	public String id() {
		return "camlock";
	}

	@Override
	public String displayName() {
		return "Cam Lock";
	}

	@Override
	public String description() {
		return "Locks your camera so the mouse cannot turn it. Click the card to lock at the angle you look at now, click again to unlock. "
			+ "Command: /khoatoolsclient camlock <yaw> <pitch> locks at exact numbers (yaw -180..180, pitch -90..90).";
	}

	@Override
	public boolean isActive() {
		return locked;
	}

	@Override
	public void activate(Minecraft mc) {
		if (locked) {
			locked = false;
			KhoaFeature.say(mc, "Cam Lock: off");
			return;
		}
		LocalPlayer player = mc.player;
		if (player == null) {
			return;
		}
		lock(player.getYRot(), player.getXRot());
		KhoaFeature.say(mc, "Cam Lock: on (yaw " + fmt(yaw) + ", pitch " + fmt(pitch) + ")");
	}

	@Override
	public void registerCommands(LiteralArgumentBuilder<FabricClientCommandSource> root, CommandBuildContext context) {
		root.then(ClientCommands.literal(id())
			.executes(ctx -> {
				activate(ctx.getSource().getClient());
				return 1;
			})
			.then(ClientCommands.argument("yaw", FloatArgumentType.floatArg(-180F, 180F))
				.then(ClientCommands.argument("pitch", FloatArgumentType.floatArg(-90F, 90F)).executes(ctx -> {
					lock(FloatArgumentType.getFloat(ctx, "yaw"), FloatArgumentType.getFloat(ctx, "pitch"));
					KhoaFeature.say(ctx.getSource().getClient(), "Cam Lock: on (yaw " + fmt(yaw) + ", pitch " + fmt(pitch) + ")");
					return 1;
				}))));
	}

	/** Locks at the angle the local player looks at now (used by the Auto Clicker). Returns false when there is no player. */
	public static boolean lockHere(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null) {
			return false;
		}
		lock(player.getYRot(), player.getXRot());
		return true;
	}

	public static void unlock() {
		locked = false;
	}

	private static void lock(float newYaw, float newPitch) {
		yaw = Mth.wrapDegrees(newYaw);
		pitch = Mth.clamp(newPitch, -90F, 90F);
		locked = true;
	}

	private static String fmt(float v) {
		return String.format("%.1f", v);
	}

	public static boolean isLocked() {
		return locked;
	}

	public static float yaw() {
		return yaw;
	}

	public static float pitch() {
		return pitch;
	}
}
