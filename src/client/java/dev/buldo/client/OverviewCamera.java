package dev.buldo.client;

import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A small client-side "general's view": the camera lifts away from the builder
 * and can be flown over the site like in an RTS, then dropped back into the
 * ordinary first-person view with the same key.
 *
 * <p>It is purely a camera: the player body stays where it is, its input is
 * frozen and its rotation is left untouched, so leaving the overview puts you
 * back exactly where you were looking.
 */
public final class OverviewCamera {
	public enum Mode {
		OFF,
		ORBIT,
		TOP;

		Mode next() {
			return switch (this) {
				case OFF -> ORBIT;
				case ORBIT -> TOP;
				case TOP -> OFF;
			};
		}

		String label() {
			return switch (this) {
				case OFF -> "off";
				case ORBIT -> "orbit";
				case TOP -> "top-down";
			};
		}
	}

	private static final double MIN_ZOOM = 3.0;
	private static final double MAX_ZOOM = 96.0;
	private static final double ORBIT_ZOOM = 14.0;
	private static final double TOP_ZOOM = 28.0;
	private static final float ORBIT_PITCH = 38.0f;
	private static final float TOP_PITCH = 89.5f;
	private static final long HINT_MILLIS = 6000L;

	private static Mode mode = Mode.OFF;
	private static CameraType savedCameraType = CameraType.FIRST_PERSON;
	private static Vec3 anchor = Vec3.ZERO;
	private static float yaw;
	private static float pitch = ORBIT_PITCH;
	private static double zoom = ORBIT_ZOOM;
	private static long hintUntil;

	private OverviewCamera() {
	}

	// ---------------------------------------------------------------- state

	public static boolean active() {
		return mode != Mode.OFF;
	}

	public static Mode mode() {
		return mode;
	}

	public static double zoom() {
		return zoom;
	}

	public static boolean showHint() {
		return active() && System.currentTimeMillis() < hintUntil;
	}

	// ---------------------------------------------------------------- toggling

	/** Cycles off -> orbit -> top-down -> off. */
	public static void cycle(Minecraft minecraft) {
		if (minecraft.player == null || minecraft.level == null) {
			return;
		}
		enter(minecraft, mode.next(), minecraft.player);
	}

	private static void enter(Minecraft minecraft, Mode next, LocalPlayer player) {
		if (next == Mode.OFF) {
			leave(minecraft);
			return;
		}
		if (mode == Mode.OFF) {
			savedCameraType = minecraft.options.getCameraType();
			anchor = player.position().add(0.0, 0.6, 0.0);
			yaw = player.getYRot();
		}
		mode = next;
		if (next == Mode.ORBIT) {
			pitch = ORBIT_PITCH;
			zoom = ORBIT_ZOOM;
			minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		} else {
			pitch = TOP_PITCH;
			zoom = TOP_ZOOM;
			minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		}
		hintUntil = System.currentTimeMillis() + HINT_MILLIS;
	}

	/** Drops back into the normal view. */
	public static void leave(Minecraft minecraft) {
		mode = Mode.OFF;
		hintUntil = 0L;
		if (minecraft.options != null) {
			minecraft.options.setCameraType(
					savedCameraType == null ? CameraType.FIRST_PERSON : savedCameraType);
		}
	}

	/** Forgets the overview without touching options, e.g. when leaving the world. */
	public static void reset() {
		mode = Mode.OFF;
		hintUntil = 0L;
	}

	// ---------------------------------------------------------------- ticking

	public static void tick(Minecraft minecraft) {
		if (mode == Mode.OFF) {
			return;
		}
		if (minecraft.player == null || minecraft.level == null) {
			reset();
			return;
		}
		if (!minecraft.player.isAlive()) {
			leave(minecraft);
			return;
		}
		pan(minecraft, minecraft.player);
	}

	private static void pan(Minecraft minecraft, LocalPlayer player) {
		var options = minecraft.options;
		double forward = (options.keyUp.isDown() ? 1 : 0) - (options.keyDown.isDown() ? 1 : 0);
		double strafe = (options.keyRight.isDown() ? 1 : 0) - (options.keyLeft.isDown() ? 1 : 0);
		double lift = (options.keyJump.isDown() ? 1 : 0) - (options.keyShift.isDown() ? 1 : 0);

		// Panning speeds up as the camera gets farther away, so the view feels
		// the same whether you are close to a wall or looking at the whole plot.
		double speed = 0.04 * Math.max(zoom, 6.0);
		if (options.keySprint.isDown()) {
			speed *= 2.5;
		}

		if (forward != 0.0 || strafe != 0.0) {
			double radians = Math.toRadians(yaw);
			double sin = Math.sin(radians);
			double cos = Math.cos(radians);
			double dx = (-sin * forward + cos * strafe) * speed;
			double dz = (cos * forward + sin * strafe) * speed;
			anchor = anchor.add(dx, 0.0, dz);
		}
		if (lift != 0.0) {
			anchor = anchor.add(0.0, lift * speed * 1.4, 0.0);
		}

		Level level = minecraft.level;
		double floor = level.getMinY() + 1.0;
		double ceiling = level.getMaxY() - 1.0;
		if (anchor.y < floor || anchor.y > ceiling) {
			anchor = new Vec3(anchor.x, Mth.clamp(anchor.y, floor, ceiling), anchor.z);
		}
	}

	// ---------------------------------------------------------------- input

	/** Mouse look while the overview is up. Returns true when it was consumed. */
	public static boolean onMouseTurn(double dx, double dy) {
		if (mode == Mode.OFF) {
			return false;
		}
		Minecraft minecraft = Minecraft.getInstance();
		double sensitivity = minecraft.options.sensitivity().get();
		double factor = sensitivity * 0.6 + 0.2;
		factor = factor * factor * factor * 8.0;

		boolean invertX = minecraft.options.invertMouseX().get();
		boolean invertY = minecraft.options.invertMouseY().get();
		float dYaw = (float) (dx * factor * 0.15 * (invertX ? -1.0 : 1.0));
		float dPitch = (float) (dy * factor * 0.15 * (invertY ? -1.0 : 1.0));

		yaw = Mth.wrapDegrees(yaw + dYaw);
		if (mode == Mode.ORBIT) {
			pitch = Mth.clamp(pitch + dPitch, 1.0f, 89.0f);
		}
		return true;
	}

	/** Scroll wheel while the overview is up: zoom in and out. */
	public static boolean onScroll(double delta) {
		if (mode == Mode.OFF || delta == 0.0) {
			return mode != Mode.OFF;
		}
		double factor = Math.pow(0.86, delta);
		zoom = Mth.clamp(zoom * factor, MIN_ZOOM, MAX_ZOOM);
		return true;
	}

	/** Mouse buttons are swallowed in the overview so you cannot mine from the sky. */
	public static boolean blocksClicks() {
		if (mode == Mode.OFF) {
			return false;
		}
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.gui.screen() == null && minecraft.player != null && minecraft.player.isAlive();
	}

	// ---------------------------------------------------------------- camera

	/** Where the camera should be and look this frame, or null when inactive. */
	public record Frame(Vec3 position, float yaw, float pitch) {
	}

	/**
	 * Computes the camera placement for this frame. The camera itself is moved
	 * by the client mixin, because {@code setPosition} and {@code setRotation}
	 * are protected on {@link Camera}.
	 */
	public static Frame frame() {
		Minecraft minecraft = Minecraft.getInstance();
		if (mode == Mode.OFF || minecraft.level == null || minecraft.player == null) {
			return null;
		}

		double radiansYaw = Math.toRadians(yaw);
		double radiansPitch = Math.toRadians(pitch);
		double cosPitch = Math.cos(radiansPitch);
		// The direction the camera looks in, straight at the anchor.
		double lx = -Math.sin(radiansYaw) * cosPitch;
		double ly = -Math.sin(radiansPitch);
		double lz = Math.cos(radiansYaw) * cosPitch;

		double distance = clearDistance(minecraft.level, anchor, lx, ly, lz, zoom);
		Vec3 position = new Vec3(anchor.x - lx * distance, anchor.y - ly * distance,
				anchor.z - lz * distance);

		double floor = minecraft.level.getMinY() + 1.0;
		if (position.y < floor) {
			position = new Vec3(position.x, floor, position.z);
		}

		return new Frame(position, yaw, pitch);
	}

	/**
	 * Walks from the anchor out towards the wanted camera spot and stops before
	 * the first block that would swallow the camera, so an orbit behind a hill
	 * pulls in instead of ending up inside the terrain.
	 */
	private static double clearDistance(Level level, Vec3 anchor, double lx, double ly, double lz,
			double wanted) {
		double step = 0.5;
		double distance = wanted;
		for (double travelled = 1.0; travelled <= wanted; travelled += step) {
			double x = anchor.x - lx * travelled;
			double y = anchor.y - ly * travelled;
			double z = anchor.z - lz * travelled;
			BlockPos pos = BlockPos.containing(x, y, z);
			if (!level.isLoaded(pos)) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			if (state.blocksMotion()) {
				distance = Math.max(0.75, travelled - 0.9);
				break;
			}
		}
		return distance;
	}
}
