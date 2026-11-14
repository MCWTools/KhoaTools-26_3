package khoa.tools.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.projectile.FishingHook;

/** Auto Fish: true while a fish is biting (the client copy of the synced DATA_BITING). */
@Mixin(FishingHook.class)
public interface FishingHookAccessor {
	@Accessor("biting")
	boolean khoa$isBiting();
}
