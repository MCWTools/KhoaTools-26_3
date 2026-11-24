package khoa.tools.command;

import java.util.Collection;
import java.util.List;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import khoa.tools.palette.BuildingPalette;

/** /khoatools palette give [targets]: hands out the Building Palette (see {@link BuildingPalette}). */
public final class PaletteFeature implements KhoaServerFeature {
	@Override
	public String id() {
		return "palette";
	}

	@Override
	public String displayName() {
		return "Building Palette";
	}

	@Override
	public String description() {
		return "Gives the Building Palette: hold right click and drag over blocks to select a box, then randomise block types with palettes.";
	}

	private static int give(CommandSourceStack source, Collection<ServerPlayer> targets) {
		for (ServerPlayer player : targets) {
			ItemStack stack = BuildingPalette.create();
			if (!player.getInventory().add(stack)) {
				player.spawnAtLocation(player.level(), stack);
			}
		}
		source.sendSuccess(() -> Component.literal("Gave a Building Palette to " + targets.size() + " player(s)."), true);
		return targets.size();
	}

	@Override
	public void registerCommands(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext context) {
		root.then(Commands.literal(id())
			.then(Commands.literal("give")
				.executes(ctx -> give(ctx.getSource(), List.of(ctx.getSource().getPlayerOrException())))
				.then(Commands.argument("targets", EntityArgument.players())
					.executes(ctx -> give(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets"))))));
	}
}
