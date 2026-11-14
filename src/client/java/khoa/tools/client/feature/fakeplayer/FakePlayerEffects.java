package khoa.tools.client.feature.fakeplayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;

/** Parses the effect text: {@code speed, regeneration:1, minecraft:glowing}. The number is the amplifier (0 = level I). */
public final class FakePlayerEffects {
	private FakePlayerEffects() {
	}

	public record Entry(Holder<MobEffect> effect, int amplifier) {
	}

	public record Result(List<Entry> entries, String error) {
		public boolean ok() {
			return error == null;
		}
	}

	public static Result parse(String text) {
		List<Entry> entries = new ArrayList<>();
		if (text == null || text.isBlank()) return new Result(entries, null);
		for (String raw : text.split(",")) {
			String part = raw.trim();
			if (part.isEmpty()) continue;
			int amplifier = 0;
			int colon = part.lastIndexOf(':');
			if (colon >= 0) {
				String tail = part.substring(colon + 1).trim();
				if (tail.matches("\\d{1,3}")) {
					amplifier = Integer.parseInt(tail);
					part = part.substring(0, colon).trim();
				}
			}
			Identifier id = Identifier.tryParse(part.toLowerCase());
			if (id == null) return new Result(entries, "Bad effect id '" + part + "'.");
			Optional<Holder.Reference<MobEffect>> holder = BuiltInRegistries.MOB_EFFECT.get(id);
			if (holder.isEmpty()) return new Result(entries, "Unknown effect '" + part + "'.");
			entries.add(new Entry(holder.get(), amplifier));
		}
		return new Result(entries, null);
	}

	public static boolean isGlowing(Entry e) {
		return e.effect().is(MobEffects.GLOWING);
	}

	public static boolean isInvisibility(Entry e) {
		return e.effect().is(MobEffects.INVISIBILITY);
	}
}
