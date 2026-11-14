package khoa.tools.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;

@Mixin(OptionsSubScreen.class)
public interface OptionsSubScreenAccessor {
	@Accessor("list")
	OptionsList khoatools$list();
}
