package khoa.tools.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * /khoatools shield_break_cd &lt;ticks&gt; stores {@code shield_break_cd} in the custom_data of the shield the player
 * holds (main hand first, then off hand). When an axe (or anything else) disables that shield, its cooldown is
 * &lt;ticks&gt; instead of the vanilla value; 0 = no cooldown. {@code reset} removes the tag. Only that one item is changed.
 * Works on any item with the blocks_attacks component. The tag can also be given with
 * {@code shield[custom_data={shield_break_cd:10}]}.
 */
public final class ShieldBreakCdFeature implements KhoaServerFeature {
	public static final String KEY = "shield_break_cd";

	/** Cooldown in ticks from the stack's custom_data, or null if the tag is absent (vanilla behaviour). */
	public static Integer get(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return null;
		}
		return data.copyTag().getInt(KEY).map(v -> Math.max(0, v)).orElse(null);
	}

	private static ItemStack heldShield(ServerPlayer player) {
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack stack = player.getItemInHand(hand);
			if (stack.has(DataComponents.BLOCKS_ATTACKS)) {
				return stack;
			}
		}
		return ItemStack.EMPTY;
	}

	private static int apply(CommandSourceStack source, Integer ticks) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		ItemStack stack = heldShield(player);
		if (stack.isEmpty()) {
			source.sendFailure(Component.literal("Hold a shield (main or off hand) first."));
			return 0;
		}
		if (ticks == null) {
			CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.remove(KEY));
			source.sendSuccess(() -> Component.literal("Removed shield_break_cd from the held shield (vanilla cooldown)."), false);
		} else {
			CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putInt(KEY, ticks));
			source.sendSuccess(() -> Component.literal("Held shield: break cooldown = " + ticks + " ticks (" + ticks / 20.0 + " s)."), false);
		}
		return 1;
	}

	@Override
	public String id() {
		return "shield_break_cd";
	}

	@Override
	public String displayName() {
		return "Shield break cooldown";
	}

	@Override
	public String description() {
		return "Sets how long the held shield stays on cooldown after an axe disables it.";
	}

	@Override
	public void registerCommands(LiteralArgumentBuilder<CommandSourceStack> root, CommandBuildContext context) {
		root.then(Commands.literal(id())
			.then(Commands.literal("reset").executes(ctx -> apply(ctx.getSource(), null)))
			.then(Commands.argument("ticks", IntegerArgumentType.integer(0, 72000))
				.executes(ctx -> apply(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "ticks")))));
	}
}
