package khoa.tools.command;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import khoa.tools.remote.RemoteSwitch;

/**
 * /khoatools remote_switch give [targets] and /khoatools remote_switch give_v2 [targets].
 * See {@link RemoteSwitch} for what the two items do.
 */
public final class RemoteSwitchFeature implements KhoaServerFeature {
	@Override
	public String id() {
		return "remote_switch";
	}

	@Override
	public String displayName() {
		return "Remote Switch";
	}

	@Override
	public String description() {
		return "Gives a remote that links to a lever / button / pressure plate and toggles it from afar, or the link-free Remote v2.";
	}

	private static int give(CommandSourceStack source, Collection<ServerPlayer> targets, Supplier<ItemStack> factory, String what) {
		for (ServerPlayer player : targets) {
			ItemStack stack = factory.get();
			if (!player.getInventory().add(stack)) {
				player.spawnAtLocation(player.level(), stack);
			}
		}
		source.sendSuccess(() -> Component.literal("Gave " + what + " to " + targets.size() + " player(s)."), true);
		return targets.size();
	}

	@Override
	public void registerCommands(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext context) {
		root.then(Commands.literal(id())
			.then(Commands.literal("give")
				.executes(ctx -> give(ctx.getSource(), List.of(ctx.getSource().getPlayerOrException()), RemoteSwitch::createSwitch, "a Remote Switch"))
				.then(Commands.argument("targets", EntityArgument.players())
					.executes(ctx -> give(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets"), RemoteSwitch::createSwitch, "a Remote Switch"))))
			.then(Commands.literal("give_v2")
				.executes(ctx -> give(ctx.getSource(), List.of(ctx.getSource().getPlayerOrException()), RemoteSwitch::createV2, "a Remote v2"))
				.then(Commands.argument("targets", EntityArgument.players())
					.executes(ctx -> give(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets"), RemoteSwitch::createV2, "a Remote v2")))));
	}
}
