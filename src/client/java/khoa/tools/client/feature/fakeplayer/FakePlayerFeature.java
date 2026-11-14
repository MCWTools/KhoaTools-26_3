package khoa.tools.client.feature.fakeplayer;

import java.util.List;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;

import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.setting.Setting;

/**
 * Client-side fake players. Clicking the card creates one where you stand and opens its editor;
 * the editor can hand you a client-only "teleport item" that moves the fake player to wherever you
 * stand (with your pose, facing and animation frame) when you right click with it.
 */
public final class FakePlayerFeature implements KhoaFeature {
	@Override public String id() { return "fakeplayer"; }
	@Override public String displayName() { return "Fake Player"; }
	@Override public String description() {
		return "Client-side players only you can see. Clicking the card creates one at your position and opens its editor "
			+ "(name, skin, pose, hands, armor, effects). Armor and hands use item syntax like netherite_chestplate[enchantments={sharpness:5}]; "
			+ "wrong syntax is shown in red. 'Give TP item' puts a named stick in your hotbar (client only, it comes back by itself if the server clears it): "
			+ "walk somewhere, crouch / punch / walk / jump / look anywhere, then right click with it and that fake player freezes in exactly that spot and pose. "
			+ "Commands: /khoatoolsclient fakeplayer [list].";
	}

	@Override public List<Setting> settings() {
		return List.of(
			Setting.bool("visible", "Show fake players", "Turns every fake player of this feature on or off (they stay saved).",
				FakePlayerManager::isVisible, FakePlayerManager::setVisible),
			Setting.action("manage", "Fake players", "All fake players of this server and dimension.", "Open list",
				() -> {
					Minecraft mc = Minecraft.getInstance();
					mc.gui.setScreen(new FakePlayerListScreen(mc.gui.screen()));
				}));
	}

	@Override public void onInit() {
		ClientTickEvents.END_CLIENT_TICK.register(FakePlayerManager::tick);
	}

	@Override public void activate(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			KhoaFeature.say(mc, "[Fake Player] Join a world first.");
			return;
		}
		FakePlayerData d = FakePlayerStore.create(mc);
		FakePlayerManager.captureFrom(mc, d, true);
		FakePlayerStore.save();
		FakePlayerManager.spawn(mc, d);
		mc.gui.setScreen(new FakePlayerScreen(mc.gui.screen() instanceof khoa.tools.client.gui.KhoaToolsScreen ? mc.gui.screen() : null, d));
	}

	@Override public void registerCommands(LiteralArgumentBuilder<FabricClientCommandSource> root, CommandBuildContext context) {
		root.then(ClientCommands.literal(id())
			.executes(ctx -> {
				Minecraft mc = ctx.getSource().getClient();
				mc.execute(() -> activate(mc));
				return 1;
			})
			.then(ClientCommands.literal("list").executes(ctx -> {
				Minecraft mc = ctx.getSource().getClient();
				mc.execute(() -> mc.gui.setScreen(new FakePlayerListScreen(null)));
				return 1;
			})));
	}
}
