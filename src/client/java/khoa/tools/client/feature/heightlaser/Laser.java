package khoa.tools.client.feature.heightlaser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix3d;
import org.joml.Vector3d;

/**
 * One laser device (client side only, never sent to a server). It sits in a block cell, shifted by an offset, rotated by
 * yaw / pitch / roll (degrees), and has up to 6 ports (the 6 local directions); only enabled ports fire a beam.
 */
public final class Laser {
	public static final double MAX_LENGTH = 1024;
	/** Distance from the device centre to the tip of a port, where the beam starts. */
	public static final double PORT_TIP = 0.32;

	public final BlockPos cell;
	/** Offset of the device centre from the centre of its cell, in blocks (-1..1). */
	public double offX;
	public double offY;
	public double offZ;
	public double yaw;
	public double pitch;
	public double roll;
	public double length = 64;
	public boolean on;
	/** Indexed by {@link Direction#get3DDataValue()} of the LOCAL direction. */
	public final boolean[] ports = new boolean[6];

	/** Bumped on every change so cached beams are recomputed. */
	public int revision;

	public Laser(BlockPos cell) {
		this.cell = cell.immutable();
	}

	public void changed() {
		revision++;
	}

	public Vec3 center() {
		return new Vec3(cell.getX() + 0.5 + offX, cell.getY() + 0.5 + offY, cell.getZ() + 0.5 + offZ);
	}

	public Matrix3d rotation() {
		return new Matrix3d().rotateY(Math.toRadians(-yaw)).rotateX(Math.toRadians(pitch)).rotateZ(Math.toRadians(roll));
	}

	/** World direction of a local direction. */
	public static Vec3 rotate(Matrix3d m, Vec3 local) {
		Vector3d v = m.transform(new Vector3d(local.x, local.y, local.z));
		return new Vec3(v.x, v.y, v.z);
	}

	public static Vec3 axis(int port) {
		Direction d = Direction.from3DDataValue(port);
		return new Vec3(d.getStepX(), d.getStepY(), d.getStepZ());
	}

	/** World direction of the beam of a port. */
	public Vec3 beamDirection(int port) {
		return rotate(rotation(), axis(port)).normalize();
	}

	/** World start of the beam of a port. */
	public Vec3 beamStart(int port) {
		return center().add(beamDirection(port).scale(PORT_TIP));
	}

	public boolean anyPort() {
		for (boolean b : ports) {
			if (b) {
				return true;
			}
		}
		return false;
	}

	/** One line of text for saving. */
	public String serialize() {
		StringBuilder faces = new StringBuilder();
		for (boolean b : ports) {
			faces.append(b ? '1' : '0');
		}
		return String.join(",", Integer.toString(cell.getX()), Integer.toString(cell.getY()), Integer.toString(cell.getZ()),
			Double.toString(offX), Double.toString(offY), Double.toString(offZ),
			Double.toString(yaw), Double.toString(pitch), Double.toString(roll), Double.toString(length),
			on ? "1" : "0", faces.toString());
	}

	public static Laser deserialize(String s) {
		String[] p = s.split(",");
		if (p.length < 12) {
			return null;
		}
		try {
			Laser l = new Laser(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
			l.offX = Double.parseDouble(p[3]);
			l.offY = Double.parseDouble(p[4]);
			l.offZ = Double.parseDouble(p[5]);
			l.yaw = Double.parseDouble(p[6]);
			l.pitch = Double.parseDouble(p[7]);
			l.roll = Double.parseDouble(p[8]);
			l.length = Math.max(1, Math.min(MAX_LENGTH, Double.parseDouble(p[9])));
			l.on = p[10].equals("1");
			for (int i = 0; i < 6 && i < p[11].length(); i++) {
				l.ports[i] = p[11].charAt(i) == '1';
			}
			return l;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
