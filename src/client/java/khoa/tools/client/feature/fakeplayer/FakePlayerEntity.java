package khoa.tools.client.feature.fakeplayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.phys.Vec3;

/**
 * Client-only player. Without physics it never moves: it keeps the pose and animation frame stored in its
 * {@link FakePlayerData} and only spawns effect particles. With physics on it falls, collides, can be hit,
 * knocked back, hurt by falls / lava / fire / drowning / void, dies at 0 health and is respawned by the manager.
 */
public final class FakePlayerEntity extends RemotePlayer {
	public final int npcId;
	public final float frozenWalkPos;
	public final float frozenWalkSpeed;
	public final float frozenSwing;
	public final float frozenAge;
	private volatile PlayerSkin skin;
	private final List<FakePlayerEffects.Entry> particleEffects = new ArrayList<>();
	private final boolean physics;
	/** Vanilla invulnerability frames: 20 ticks after a hit; during the first 10 only a stronger hit counts (for the difference). */
	private int invulnTicks;
	private float lastDamage;
	private int deadTicks;
	private int fireTicks;
	private int air = 300;

	public FakePlayerEntity(ClientLevel level, FakePlayerData d) {
		super(level, new GameProfile(UUID.nameUUIDFromBytes(("khoatools-fakeplayer-" + d.id).getBytes()), d.name));
		this.npcId = d.id;
		this.frozenWalkPos = d.walkPos;
		this.frozenWalkSpeed = d.walkSpeed;
		this.frozenSwing = d.swing;
		this.frozenAge = d.age;
		this.physics = d.physics;
		this.noPhysics = !physics;
		this.setNoGravity(true);
		// every skin layer visible
		this.getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7F);
		this.snapTo(d.x, d.y, d.z, d.yRot, d.xRot);
		this.yBodyRot = d.bodyRot;
		this.yBodyRotO = d.bodyRot;
		this.yHeadRot = d.headRot;
		this.yHeadRotO = d.headRot;
		Pose pose;
		try {
			pose = Pose.valueOf(d.pose);
		} catch (IllegalArgumentException e) {
			pose = Pose.STANDING;
		}
		this.setPose(pose);
		this.setSwimming(pose == Pose.SWIMMING);
		this.setSharedFlag(7, pose == Pose.FALL_FLYING);
		this.setSprinting(d.sprinting);
		this.setItemSlot(EquipmentSlot.MAINHAND, FakePlayerItems.parse(d.mainHand).stack());
		this.setItemSlot(EquipmentSlot.OFFHAND, FakePlayerItems.parse(d.offHand).stack());
		this.setItemSlot(EquipmentSlot.HEAD, FakePlayerItems.parse(d.head).stack());
		this.setItemSlot(EquipmentSlot.CHEST, FakePlayerItems.parse(d.chest).stack());
		this.setItemSlot(EquipmentSlot.LEGS, FakePlayerItems.parse(d.legs).stack());
		this.setItemSlot(EquipmentSlot.FEET, FakePlayerItems.parse(d.feet).stack());
		for (FakePlayerEffects.Entry e : FakePlayerEffects.parse(d.effects).entries()) {
			if (FakePlayerEffects.isGlowing(e)) {
				this.setGlowingTag(true);
			} else if (FakePlayerEffects.isInvisibility(e)) {
				this.setInvisible(true);
			}
			particleEffects.add(e);
		}
	}

	/** True when this fake player simulates gravity, health, damage and death on the client. */
	public boolean physics() {
		return physics;
	}

	/** Death animation is over: the manager removes the entity and respawns it later. */
	public boolean isFinishedDying() {
		return physics && deadTicks >= 20;
	}

	public void setSkin(PlayerSkin skin) {
		this.skin = skin;
	}

	@Override
	public PlayerSkin getSkin() {
		PlayerSkin s = skin;
		return s != null ? s : super.getSkin();
	}

	@Override
	public void tick() {
		this.tickCount++;
		for (FakePlayerEffects.Entry e : particleEffects) {
			MobEffectInstance instance = new MobEffectInstance(e.effect(), -1, e.amplifier(), false, true);
			ParticleOptions particle = instance.getParticleOptions();
			if (particle != null && this.random.nextInt(this.isInvisible() ? 15 : 4) == 0) {
				this.level().addParticle(particle, this.getRandomX(0.5), this.getRandomY(), this.getRandomZ(0.5), 1.0, 1.0, 1.0);
			}
		}
		if (physics) physicsTick();
	}

	// ---------------------------------------------------------------- physics

	private void physicsTick() {
		if (this.hurtTime > 0) this.hurtTime--;
		if (this.invulnTicks > 0) this.invulnTicks--;
		super.baseTick(); // water / lava state and fluid pushing
		boolean dead = this.isDeadOrDying();
		Vec3 v = this.getDeltaMovement();
		double vx = Math.abs(v.x) < 0.003 ? 0.0 : v.x;
		double vz = Math.abs(v.z) < 0.003 ? 0.0 : v.z;
		this.setDeltaMovement(vx, v.y, vz);
		// same order as vanilla LivingEntity.travel: the friction is chosen from the ground state BEFORE moving,
		// then the entity moves by its velocity, then drag and gravity are applied to the velocity for the next tick
		boolean wasOnGround = this.onGround();
		float groundFriction = wasOnGround
			? this.level().getBlockState(this.getBlockPosBelowThatAffectsMyMovement()).getBlock().getFriction()
			: 1.0F;
		boolean lava = this.isInLava();
		boolean water = !lava && this.isInWater();
		this.move(MoverType.SELF, this.getDeltaMovement());
		Vec3 after = this.getDeltaMovement();
		if (lava) {
			this.setDeltaMovement(after.x * 0.5, after.y * 0.5 - 0.02, after.z * 0.5);
		} else if (water) {
			this.setDeltaMovement(after.x * 0.8, after.y * 0.8 - 0.02, after.z * 0.8);
		} else {
			this.setDeltaMovement(after.x * groundFriction * 0.91, (after.y - 0.08) * 0.98, after.z * groundFriction * 0.91);
		}
		this.calculateEntityAnimation(false);
		if (dead) {
			this.deadTicks++;
			this.deathTime = this.deadTicks;
			return;
		}
		environment();
	}

	/** Void, lava, burning and drowning (the server never reports damage for a client-only entity). */
	private void environment() {
		if (this.getY() < this.level().getMinY() - 64) applyDamage(4.0F, true);
		if (this.isInLava()) {
			applyDamage(4.0F, false);
			this.fireTicks = 300;
		} else if (this.isInWater()) {
			this.fireTicks = 0;
		}
		if (this.fireTicks > 0) {
			this.fireTicks--;
			if (this.fireTicks % 20 == 0) applyDamage(1.0F, false);
		}
		this.setSharedFlagOnFire(this.fireTicks > 0);
		if (this.isEyeInFluid(FluidTags.WATER)) {
			this.air--;
			if (this.air <= -20) {
				this.air = 0;
				applyDamage(2.0F, true);
			}
		} else {
			this.air = Math.min(300, this.air + 4);
		}
	}

	/**
	 * Damage with vanilla-like invulnerability frames. {@code bypassArmor} is used for fall, void and drowning.
	 * Returns false when ignored (dead, physics off, or still invulnerable to a hit this weak).
	 */
	public boolean applyDamage(float amount, boolean bypassArmor) {
		if (!physics || this.isDeadOrDying() || amount <= 0.0F) return false;
		if (!bypassArmor) amount = afterArmor(amount);
		float dealt;
		if (this.invulnTicks > 10) {
			if (amount <= this.lastDamage) return false;
			dealt = amount - this.lastDamage;
			this.lastDamage = amount;
		} else {
			this.lastDamage = amount;
			this.invulnTicks = 20;
			dealt = amount;
		}
		float health = Math.max(0.0F, this.getHealth() - dealt);
		this.setHealth(health);
		this.hurtDuration = 10;
		this.hurtTime = 10;
		this.level().playLocalSound(this.getX(), this.getY(), this.getZ(),
			health <= 0.0F ? SoundEvents.PLAYER_DEATH : SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1.0F, 1.0F, false);
		return true;
	}

	/** Vanilla armor formula; armor is read from the worn items because the server never sent attributes for this entity. */
	private float afterArmor(float damage) {
		double[] sum = new double[2];
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			this.getItemBySlot(slot).forEachModifier(slot, (attribute, modifier) -> {
				if (modifier.operation() != AttributeModifier.Operation.ADD_VALUE) return;
				if (attribute.equals(Attributes.ARMOR)) sum[0] += modifier.amount();
				else if (attribute.equals(Attributes.ARMOR_TOUGHNESS)) sum[1] += modifier.amount();
			});
		}
		double f = 2.0 + sum[1] / 4.0;
		double g = Mth.clamp(sum[0] - damage / f, sum[0] * 0.2, 20.0);
		return (float) (damage * (1.0 - g / 25.0));
	}

	/** Sets the fake player on fire (fire aspect, lava). Burning does 1 damage per second until it ends or water puts it out. */
	public void ignite(int seconds) {
		if (physics) this.fireTicks = Math.max(this.fireTicks, seconds * 20);
	}

	/** Pushes away from the attacker: (dx, dz) = attacker position - this position. */
	public void knockback(double strength, double dx, double dz) {
		if (!physics || strength <= 0.0) return;
		if (dx * dx + dz * dz < 1.0E-5) {
			double angle = this.random.nextDouble() * Math.PI * 2.0;
			dx = Math.cos(angle);
			dz = Math.sin(angle);
		}
		double len = Math.sqrt(dx * dx + dz * dz);
		Vec3 v = this.getDeltaMovement();
		this.setDeltaMovement(v.x / 2.0 - dx / len * strength, this.onGround() ? Math.min(0.4, v.y / 2.0 + strength) : v.y, v.z / 2.0 - dz / len * strength);
	}

	@Override
	public boolean causeFallDamage(double fallDistance, float damageModifier, DamageSource source) {
		if (!physics) return false;
		int damage = Mth.ceil((fallDistance - 3.0) * damageModifier);
		if (damage <= 0) return false;
		applyDamage(damage, true);
		return true;
	}

	@Override
	public Component getDisplayName() {
		Component base = super.getDisplayName();
		if (!physics) return base;
		float health = this.getHealth();
		String text = health == Math.floor(health) ? Integer.toString((int) health) : String.format(java.util.Locale.ROOT, "%.1f", health);
		return Component.empty().append(base).append(Component.literal(" \u2764" + text).withStyle(ChatFormatting.RED));
	}

	@Override
	public void aiStep() {
	}

	@Override
	public boolean isPickable() {
		return false;
	}

	@Override
	public boolean isPushable() {
		return physics && !this.isDeadOrDying();
	}

	@Override
	public boolean canBeCollidedWith(Entity other) {
		return false;
	}

	@Override
	public boolean hurtClient(DamageSource source) {
		return false;
	}
}
