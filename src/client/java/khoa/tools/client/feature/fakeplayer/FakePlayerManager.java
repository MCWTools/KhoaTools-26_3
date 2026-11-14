package khoa.tools.client.feature.fakeplayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import khoa.tools.client.config.KhoaConfig;
import khoa.tools.client.feature.KhoaFeature;
import khoa.tools.client.feature.camera.CameraStore;

/**
 * Owns the spawned {@link FakePlayerEntity} objects of the current world and the client-only "teleport items".
 * Everything here runs on the client thread.
 */
public final class FakePlayerManager {
	private static final String WAND_TAG = "khoatools_fakeplayer";
	/** Client-only entity ids are negative so they never collide with server ids (ghosts use -1,000,000 and up). */
	private static int nextEntityId = -2_000_000;
	private static final Map<Integer, FakePlayerEntity> ENTITIES = new HashMap<>();
	/** Teleport item of each fake player id -> inventory slot it was last seen in. */
	private static final Map<Integer, Integer> WANT_WAND = new HashMap<>();
	/** Fake players that died (physics on) -> game tick at which they come back at their saved spot. */
	private static final Map<Integer, Integer> RESPAWN_AT = new HashMap<>();
	private static ClientLevel trackedLevel;
	private static String trackedWorld;
	private static int tickCounter;
	private static long lastSwingTick = Long.MIN_VALUE;
	private static boolean enabled = KhoaConfig.getBool("fakeplayer", "visible", true);

	private FakePlayerManager() {
	}

	public static boolean isVisible() {
		return enabled;
	}

	public static void setVisible(boolean value) {
		enabled = value;
		KhoaConfig.put("fakeplayer", "visible", value);
		Minecraft mc = Minecraft.getInstance();
		if (!value) {
			removeAll();
		} else {
			spawnAll(mc);
		}
	}

	// ---------------------------------------------------------------- lifecycle

	public static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) {
			ENTITIES.clear();
			RESPAWN_AT.clear();
			WANT_WAND.clear();
			trackedLevel = null;
			trackedWorld = null;
			return;
		}
		String world = CameraStore.worldKey(mc);
		if (level != trackedLevel || !world.equals(trackedWorld)) {
			ENTITIES.clear();
			RESPAWN_AT.clear();
			WANT_WAND.clear();
			trackedLevel = level;
			trackedWorld = world;
			spawnAll(mc);
		}
		if (mc.isPaused()) return;
		tickCounter++;
		if (mc.player.isSwinging()) lastSwingTick = tickCounter;
		tickDeaths(mc);
		if (tickCounter % 20 == 0) verifyEntities(mc);
		if (tickCounter % 5 == 0 && mc.gui.screen() == null) keepWands(mc);
	}

	private static void spawnAll(Minecraft mc) {
		if (!enabled || mc.level == null) return;
		for (FakePlayerData d : FakePlayerStore.current(mc)) spawn(mc, d);
	}

	private static void removeAll() {
		ClientLevel level = trackedLevel;
		if (level != null) {
			for (FakePlayerEntity e : ENTITIES.values()) level.removeEntity(e.getId(), Entity.RemovalReason.DISCARDED);
		}
		ENTITIES.clear();
	}

	/** (Re)creates the entity of {@code d} from its saved data. */
	public static void spawn(Minecraft mc, FakePlayerData d) {
		ClientLevel level = mc.level;
		if (level == null || !enabled) return;
		despawn(d.id);
		try {
			FakePlayerEntity entity = new FakePlayerEntity(level, d);
			entity.setId(nextEntityId--);
			level.addEntity(entity);
			ENTITIES.put(d.id, entity);
			FakePlayerSkins.resolve(d, entity::setSkin);
		} catch (RuntimeException e) {
			khoa.tools.KhoaTools.LOGGER.error("Could not create fake player #{}", d.id, e);
			KhoaFeature.say(mc, "[Fake Player] Could not create '" + d.name + "': " + e.getMessage());
		}
	}

	/** Dead fake players (death animation finished) are removed and come back after 3 seconds with full health. */
	private static void tickDeaths(Minecraft mc) {
		for (FakePlayerEntity e : new ArrayList<>(ENTITIES.values())) {
			if (!e.isFinishedDying()) continue;
			for (int i = 0; i < 20; i++) {
				mc.level.addParticle(ParticleTypes.POOF, e.getRandomX(1.0), e.getRandomY(), e.getRandomZ(1.0), 0.0, 0.02, 0.0);
			}
			despawn(e.npcId);
			RESPAWN_AT.put(e.npcId, tickCounter + 60);
		}
		if (RESPAWN_AT.isEmpty()) return;
		for (Map.Entry<Integer, Integer> entry : new ArrayList<>(RESPAWN_AT.entrySet())) {
			if (tickCounter < entry.getValue()) continue;
			RESPAWN_AT.remove(entry.getKey());
			FakePlayerData d = FakePlayerStore.find(mc, entry.getKey());
			if (d != null) spawn(mc, d);
		}
	}

	public static void despawn(int npcId) {
		RESPAWN_AT.remove(npcId);
		FakePlayerEntity old = ENTITIES.remove(npcId);
		if (old != null && trackedLevel != null) trackedLevel.removeEntity(old.getId(), Entity.RemovalReason.DISCARDED);
	}

	/** A chunk that unloads and loads again drops client-only entities: put them back. */
	private static void verifyEntities(Minecraft mc) {
		if (!enabled || mc.level == null) return;
		for (FakePlayerData d : FakePlayerStore.current(mc)) {
			if (RESPAWN_AT.containsKey(d.id)) continue;
			FakePlayerEntity e = ENTITIES.get(d.id);
			boolean chunkLoaded = mc.level.hasChunk(((int) Math.floor(d.x)) >> 4, ((int) Math.floor(d.z)) >> 4);
			if (chunkLoaded && (e == null || e.isRemoved() || mc.level.getEntity(e.getId()) != e)) spawn(mc, d);
		}
	}

	public static void delete(Minecraft mc, FakePlayerData d) {
		despawn(d.id);
		WANT_WAND.remove(d.id);
		removeWands(mc, d.id);
		FakePlayerStore.delete(mc, d);
	}

	// ---------------------------------------------------------------- capture / teleport

	/** Copies position, facing and the current animation frame of the player into {@code d}. {@code withPose} also copies crouch / swim / elytra. */
	public static void captureFrom(Minecraft mc, FakePlayerData d, boolean withPose) {
		LocalPlayer p = mc.player;
		if (p == null) return;
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		d.x = p.getX();
		d.y = p.getY();
		d.z = p.getZ();
		d.yRot = p.getYRot();
		d.xRot = p.getXRot();
		d.headRot = p.yHeadRot;
		d.bodyRot = p.yBodyRot;
		d.sprinting = p.isSprinting();
		d.walkPos = p.walkAnimation.position(partial);
		d.walkSpeed = p.walkAnimation.speed(partial);
		d.age = p.tickCount + partial;
		if (p.isSwinging()) {
			d.swing = p.getSwingAnimation(partial);
		} else {
			// right-click shortly after a punch keeps the arm in mid swing
			d.swing = tickCounter - lastSwingTick <= 10 ? 0.4F : 0.0F;
		}
		if (withPose) {
			Pose pose = p.getPose();
			d.pose = pose == Pose.CROUCHING || pose == Pose.SWIMMING || pose == Pose.FALL_FLYING ? pose.name() : "STANDING";
		}
	}

	/** Teleports the fake player to where the player stands, with the player's current pose, facing and animation. */
	public static void teleportToPlayer(Minecraft mc, FakePlayerData d, boolean withPose) {
		captureFrom(mc, d, withPose);
		FakePlayerStore.save();
		spawn(mc, d);
	}

	/** Called at the start of every right click. Returns true when the held item was a teleport item (the click is then cancelled). */
	public static boolean handleUse(Minecraft mc) {
		if (mc.player == null || mc.level == null) return false;
		if (mc.player.isShiftKeyDown() && mc.gui.screen() == null) {
			FakePlayerEntity target = lookedAtFakePlayer(mc, 4.5);
			if (target != null) {
				FakePlayerData data = FakePlayerStore.find(mc, target.npcId);
				if (data != null) {
					mc.gui.setScreen(new FakePlayerScreen(null, data));
					return true;
				}
			}
		}
		int id = wandId(mc.player.getMainHandItem());
		if (id < 0) id = wandId(mc.player.getOffhandItem());
		if (id < 0) return false;
		FakePlayerData d = FakePlayerStore.find(mc, id);
		if (d == null) {
			KhoaFeature.say(mc, "[Fake Player] That teleport item belongs to a fake player that no longer exists here.");
			return true;
		}
		if (d.tpCopiesItems) FakePlayerCopy.copyWorn(mc, d);
		teleportToPlayer(mc, d, true);
		mc.player.sendOverlayMessage(Component.literal("Teleported " + d.name + " to you"));
		return true;
	}

	/** Fake players are not pickable, so the crosshair never targets them: ray test their boxes ourselves (blocks in front block the click). */
	private static FakePlayerEntity lookedAtFakePlayer(Minecraft mc, double reach) {
		LocalPlayer p = mc.player;
		Vec3 from = p.getEyePosition();
		Vec3 to = from.add(p.getViewVector(1.0F).scale(reach));
		double blockDist = Double.MAX_VALUE;
		if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) blockDist = from.distanceTo(mc.hitResult.getLocation());
		FakePlayerEntity best = null;
		double bestDist = Double.MAX_VALUE;
		for (FakePlayerEntity e : ENTITIES.values()) {
			if (e.isRemoved() || e.isDeadOrDying()) continue;
			Optional<Vec3> hit = e.getBoundingBox().inflate(0.1).clip(from, to);
			if (hit.isEmpty()) continue;
			double dist = from.distanceTo(hit.get());
			if (dist < bestDist && dist < blockDist) {
				bestDist = dist;
				best = e;
			}
		}
		return best;
	}

	/**
	 * Left click on a fake player with physics on. Damage = attack damage attribute (weapon included) scaled by the attack
	 * cooldown, x1.5 for a critical hit; knockback = 0.4 + (attack knockback + 1 when sprinting) / 2, away from you.
	 * Enchantments (sharpness, knockback, fire aspect...) are not applied. Returns true when the click was consumed.
	 */
	public static boolean handleAttack(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null || mc.gui.screen() != null) return false;
		FakePlayerEntity target = lookedAtFakePlayer(mc, 3.0);
		if (target == null || !target.physics()) return false;
		float scale = p.getAttackStrengthScale(0.5F);
		boolean strong = scale > 0.9F;
		ItemStack weapon = p.getMainHandItem();
		int sharpness = 0;
		int fireAspect = 0;
		int knockbackLevel = 0;
		ItemEnchantments enchants = weapon.get(DataComponents.ENCHANTMENTS);
		if (enchants != null) {
			for (var entry : enchants.entrySet()) {
				if (entry.getKey().is(Enchantments.SHARPNESS)) sharpness = entry.getIntValue();
				else if (entry.getKey().is(Enchantments.FIRE_ASPECT)) fireAspect = entry.getIntValue();
				else if (entry.getKey().is(Enchantments.KNOCKBACK)) knockbackLevel = entry.getIntValue();
			}
		}
		float base = (float) p.getAttributeValue(Attributes.ATTACK_DAMAGE);
		float enchantBonus = sharpness > 0 ? 1.0F + 0.5F * (sharpness - 1) : 0.0F;
		float damage = base * (0.2F + scale * scale * 0.8F) + enchantBonus * scale;
		boolean crit = strong && p.fallDistance > 0.0 && !p.onGround() && !p.onClimbable() && !p.isInWater()
			&& !p.hasEffect(MobEffects.BLINDNESS) && !p.isPassenger() && !p.isSprinting();
		if (crit) damage *= 1.5F;
		boolean sprintHit = strong && p.isSprinting();
		double extraKnockback = (p.getAttributeValue(Attributes.ATTACK_KNOCKBACK) + knockbackLevel + (sprintHit ? 1.0 : 0.0)) * 0.5;
		p.swing(InteractionHand.MAIN_HAND, p.getMainHandItem().getAttackAnimation(), true);
		p.resetAttackStrengthTicker();
		double dx = p.getX() - target.getX();
		double dz = p.getZ() - target.getZ();
		float healthBefore = target.getHealth();
		if (target.applyDamage(damage, false)) {
			// same as vanilla: the hit itself pushes 0.4, then the sprint / knockback bonus is a second push
			target.knockback(0.4, dx, dz);
			if (extraKnockback > 0.0) target.knockback(extraKnockback, dx, dz);
			if (fireAspect > 0) target.ignite(fireAspect * 4);
			p.sendOverlayMessage(Component.literal(String.format(java.util.Locale.ROOT, "Hit %.1f (weapon %.1f, sharpness +%.1f, cooldown %.0f%%) -> HP %.1f/%.0f",
				damage, base, enchantBonus, scale * 100.0F, target.getHealth(), target.getMaxHealth())));
			if (crit) {
				for (int i = 0; i < 8; i++) {
					mc.level.addParticle(ParticleTypes.CRIT, target.getRandomX(0.5), target.getRandomY(), target.getRandomZ(0.5), 0.0, 0.1, 0.0);
				}
			}
			mc.level.playLocalSound(target.getX(), target.getY(), target.getZ(),
				crit ? SoundEvents.PLAYER_ATTACK_CRIT : sprintHit ? SoundEvents.PLAYER_ATTACK_KNOCKBACK : strong ? SoundEvents.PLAYER_ATTACK_STRONG : SoundEvents.PLAYER_ATTACK_WEAK,
				SoundSource.PLAYERS, 1.0F, 1.0F, false);
		} else {
			mc.level.playLocalSound(target.getX(), target.getY(), target.getZ(), SoundEvents.PLAYER_ATTACK_WEAK, SoundSource.PLAYERS, 1.0F, 1.0F, false);
		}
		return true;
	}

	// ---------------------------------------------------------------- teleport item

	public static int wandId(ItemStack stack) {
		if (stack.isEmpty()) return -1;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return -1;
		return data.copyTag().getIntOr(WAND_TAG, -1);
	}

	private static ItemStack makeWand(FakePlayerData d) {
		ItemStack stack = new ItemStack(Items.STICK);
		CompoundTag tag = new CompoundTag();
		tag.putInt(WAND_TAG, d.id);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("TP " + d.name).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.AQUA)));
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		return stack;
	}

	/** Puts the teleport item of {@code d} in the inventory (client side only). If it is already there it is just selected. */
	public static void giveWand(Minecraft mc, FakePlayerData d) {
		LocalPlayer p = mc.player;
		if (p == null) return;
		Inventory inv = p.getInventory();
		int existing = findWand(inv, d.id);
		if (existing >= 0) {
			WANT_WAND.put(d.id, existing);
			if (existing < 9) inv.setSelectedSlot(existing);
			refreshWand(mc, d);
			KhoaFeature.say(mc, "[Fake Player] You already have the teleport item of " + d.name + ".");
			return;
		}
		int slot = -1;
		for (int i = 0; i < 9; i++) {
			if (inv.getItem(i).isEmpty()) {
				slot = i;
				break;
			}
		}
		if (slot < 0) slot = inv.getSelectedSlot();
		inv.setItem(slot, makeWand(d));
		inv.setSelectedSlot(slot);
		WANT_WAND.put(d.id, slot);
		KhoaFeature.say(mc, "[Fake Player] Teleport item for " + d.name + " is in hotbar slot " + (slot + 1)
			+ ". Stand where you want it, then right click. (Client only: the server does not know about it.)");
	}

	/** After renaming: update the name of the teleport item that is already in the inventory. */
	public static void refreshWand(Minecraft mc, FakePlayerData d) {
		if (mc.player == null) return;
		Inventory inv = mc.player.getInventory();
		int slot = findWand(inv, d.id);
		if (slot >= 0) inv.setItem(slot, makeWand(d));
	}

	private static void removeWands(Minecraft mc, int npcId) {
		if (mc.player == null) return;
		Inventory inv = mc.player.getInventory();
		int slot = findWand(inv, npcId);
		if (slot >= 0) inv.setItem(slot, ItemStack.EMPTY);
	}

	private static int findWand(Inventory inv, int npcId) {
		for (int i = 0; i < 36; i++) {
			if (wandId(inv.getItem(i)) == npcId) return i;
		}
		return -1;
	}

	/** The server overwrites the inventory now and then (it does not know the item): put the item back into its empty slot. */
	private static void keepWands(Minecraft mc) {
		if (WANT_WAND.isEmpty() || mc.player == null) return;
		Inventory inv = mc.player.getInventory();
		for (Map.Entry<Integer, Integer> entry : new HashMap<>(WANT_WAND).entrySet()) {
			int id = entry.getKey();
			FakePlayerData d = FakePlayerStore.find(mc, id);
			if (d == null) {
				WANT_WAND.remove(id);
				continue;
			}
			int found = findWand(inv, id);
			if (found >= 0) {
				WANT_WAND.put(id, found);
				continue;
			}
			int slot = entry.getValue();
			if (!inv.getItem(slot).isEmpty()) {
				slot = -1;
				for (int i = 0; i < 9; i++) {
					if (inv.getItem(i).isEmpty()) {
						slot = i;
						break;
					}
				}
			}
			if (slot < 0) {
				WANT_WAND.remove(id);
				continue;
			}
			inv.setItem(slot, makeWand(d));
			WANT_WAND.put(id, slot);
		}
	}
}
