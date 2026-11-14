package khoa.tools.client.feature.fakeplayer;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.world.item.ItemStack;

/** Item text (vanilla /give syntax) -> ItemStack, with a readable error when the syntax is wrong. */
public final class FakePlayerItems {
	private FakePlayerItems() {
	}

	public record Result(ItemStack stack, String error) {
		public boolean ok() {
			return error == null;
		}
	}

	public static Result parse(String text) {
		if (text == null || text.isBlank()) return new Result(ItemStack.EMPTY, null);
		var level = Minecraft.getInstance().level;
		if (level == null) return new Result(ItemStack.EMPTY, "Join a world first.");
		String trimmed = text.trim();
		Result raw = tryParse(level, trimmed);
		if (raw.ok()) return raw;
		String fixed = normalize(trimmed);
		if (fixed.equals(trimmed)) return raw;
		Result second = tryParse(level, fixed);
		return second.ok() ? second : raw;
	}

	private static Result tryParse(net.minecraft.client.multiplayer.ClientLevel level, String text) {
		try {
			StringReader reader = new StringReader(text);
			ItemInput input = new ItemParser(level.registryAccess()).parse(reader);
			if (reader.canRead()) return new Result(ItemStack.EMPTY, "Unexpected text: " + reader.getRemaining());
			return new Result(input.createItemStack(1), null);
		} catch (CommandSyntaxException e) {
			return new Result(ItemStack.EMPTY, e.getMessage());
		} catch (RuntimeException e) {
			return new Result(ItemStack.EMPTY, String.valueOf(e.getMessage()));
		}
	}

	/** ItemStack -> item syntax ({@code id[component=value,...]}) that {@link #parse} reads back. Empty stack -> "". */
	public static String toText(ItemStack stack) {
		var level = Minecraft.getInstance().level;
		if (stack.isEmpty() || level == null) return "";
		var ops = net.minecraft.resources.RegistryOps.create(net.minecraft.nbt.NbtOps.INSTANCE, level.registryAccess());
		net.minecraft.nbt.Tag tag = ItemStack.CODEC.encodeStart(ops, stack.copyWithCount(1)).result().orElse(null);
		if (!(tag instanceof net.minecraft.nbt.CompoundTag compound)) return "";
		String id = compound.getStringOr("id", "");
		if (id.isEmpty()) return "";
		net.minecraft.nbt.CompoundTag components = compound.getCompound("components").orElse(null);
		if (components == null || components.isEmpty()) return id;
		StringBuilder sb = new StringBuilder(id).append('[');
		boolean first = true;
		for (String key : components.keySet()) {
			if (!first) sb.append(',');
			first = false;
			if (key.startsWith("!")) sb.append(key);
			else sb.append(key).append('=').append(components.get(key));
		}
		return sb.append(']').toString();
	}

	/**
	 * Vanilla writes components as {@code item[component=value]}. People often type {@code item[component:value]};
	 * this turns a "name:" that starts a component into "name=" (namespaced ids like minecraft:enchantments= stay as they are).
	 */
	static String normalize(String text) {
		int bracket = text.indexOf('[');
		if (bracket < 0) return text;
		StringBuilder out = new StringBuilder(text.substring(0, bracket + 1));
		int depth = 1;
		boolean atStart = true;
		char quote = 0;
		int i = bracket + 1;
		while (i < text.length()) {
			char c = text.charAt(i);
			if (quote != 0) {
				out.append(c);
				if (c == '\\' && i + 1 < text.length()) out.append(text.charAt(++i));
				else if (c == quote) quote = 0;
				i++;
				continue;
			}
			if (atStart && depth == 1 && (Character.isLetterOrDigit(c) || c == '_' || c == '!')) {
				int start = i;
				while (i < text.length() && isIdChar(text.charAt(i))) i++;
				out.append(text, start, i);
				if (i < text.length() && text.charAt(i) == ':') {
					int j = i + 1;
					while (j < text.length() && isIdChar(text.charAt(j))) j++;
					boolean namespaced = j > i + 1 && j < text.length() && text.charAt(j) == '=';
					if (namespaced) {
						out.append(text, i, j);
						i = j;
					} else {
						out.append('=');
						i++;
					}
				}
				atStart = false;
				continue;
			}
			if (c == '"' || c == '\'') quote = c;
			if (c == '[' || c == '{') depth++;
			if (c == ']' || c == '}') depth--;
			atStart = depth == 1 && c == ',';
			out.append(c);
			i++;
		}
		return out.toString();
	}

	private static boolean isIdChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-' || c == '/';
	}
}
