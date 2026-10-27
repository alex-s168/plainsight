package dev.plainsight.editor;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ArcballTest {
	private static Viewport viewport() {
		Viewport v = new Viewport();
		v.set(300, 22, 980, 852, 1600, 900);
		return v;
	}

	private static EditorCamera camera() {
		EditorCamera cam = new EditorCamera();
		cam.set(new Vector3d(5, 80, -10), 20, 35);
		return cam;
	}

	@Test
	void orientationRoundTripsWithRoll() {
		Random r = new Random(3);
		EditorCamera cam = new EditorCamera();
		for (int i = 0; i < 500; i++) {
			cam.yaw = (float) (r.nextDouble() * 360 - 180);
			cam.pitch = (float) (r.nextDouble() * 178 - 89);
			cam.roll = (float) (r.nextDouble() * 360 - 180);
			Quaterniond q = cam.orientation(new Quaterniond());
			EditorCamera other = new EditorCamera();
			other.setOrientation(q);
			Quaterniond q2 = other.orientation(new Quaterniond());
			assertTrue(Math.abs(q.dot(q2)) > 1 - 1e-9, "same rotation for " + cam.yaw + "/" + cam.pitch + "/" + cam.roll);
		}
	}

	@Test
	void forwardMatchesMinecraftWithoutRoll() {
		EditorCamera cam = camera();
		assertTrue(cam.forward(new Vector3d()).distance(EditorCamera.direction(20, 35, new Vector3d())) < 1e-9);
		assertTrue(cam.right(new Vector3d()).y < 1e-9, "no roll: right is horizontal");
	}

	@Test
	void trackballKeepsPivotFixedOnScreen() {
		EditorCamera cam = camera();
		Viewport v = viewport();
		Vector3d pivot = new Vector3d(cam.forward(new Vector3d())).mul(15).add(cam.pos).add(2, -1, 0);
		double[] before = new double[2], after = new double[2];
		assertTrue(v.project(cam, pivot, 70, before));
		double d = cam.pos.distance(pivot);
		cam.beginDrag(EditorCamera.Drag.ORBIT, pivot);
		double x = 700, y = 400;
		for (int i = 0; i < 20; i++) {
			cam.dragTo(x, y, x + 13, y + 7, v, v.worldPerPixel(70));
			x += 13;
			y += 7;
		}
		assertEquals(d, cam.pos.distance(pivot), 1e-6);
		assertTrue(v.project(cam, pivot, 70, after));
		assertEquals(before[0], after[0], 1e-4);
		assertEquals(before[1], after[1], 1e-4);
		assertTrue(cam.pos.distance(5, 80, -10) > 1, "the camera moved");
	}

	@Test
	void trackballIsReversible() {
		EditorCamera cam = camera();
		Viewport v = viewport();
		Vector3d start = new Vector3d(cam.pos);
		float yaw = cam.yaw, pitch = cam.pitch;
		cam.beginDrag(EditorCamera.Drag.ORBIT, new Vector3d(5, 64, 10));
		cam.dragTo(800, 450, 950, 300, v, 0);
		cam.dragTo(950, 300, 800, 450, v, 0);
		assertTrue(cam.pos.distance(start) < 1e-6);
		assertEquals(yaw, cam.yaw, 1e-4);
		assertEquals(pitch, cam.pitch, 1e-4);
		assertEquals(0, cam.roll, 1e-4);
	}

	@Test
	void horizontalDragAtCentreTurnsAroundTheViewUpAxis() {
		EditorCamera cam = new EditorCamera();
		cam.set(new Vector3d(0, 70, -20), 0, 0);
		Viewport v = viewport();
		cam.beginDrag(EditorCamera.Drag.ORBIT, new Vector3d(0, 70, 0));
		double cx = v.x + v.width / 2, cy = v.y + v.height / 2;
		cam.dragTo(cx, cy, cx + 100, cy, v, 0);
		assertEquals(0, cam.roll, 1e-4, "no roll for a drag through the centre");
		assertEquals(0, cam.pitch, 1e-4);
		assertEquals(70, cam.pos.y, 1e-9);
		// dragging right turns the scene right: the camera moves left around the pivot (to -x when facing south... +x is left)
		assertTrue(cam.pos.x > 0, "camera orbits to its left: " + cam.pos);
	}

	@Test
	void turntableModeKeepsHorizonLevel() {
		EditorCamera cam = camera();
		cam.arcballMode = EditorCamera.ArcballMode.TURNTABLE;
		Viewport v = viewport();
		cam.beginDrag(EditorCamera.Drag.ORBIT, new Vector3d(5, 64, 10));
		cam.dragTo(1000, 100, 600, 800, v, v.worldPerPixel(70));
		assertEquals(0, cam.roll, 1e-9);
	}

	@Test
	void levelHorizonKeepsViewDirection() {
		EditorCamera cam = camera();
		cam.roll = 37;
		Vector3d f = cam.forward(new Vector3d());
		cam.levelHorizon();
		assertEquals(0, cam.roll, 1e-9);
		assertTrue(cam.forward(new Vector3d()).distance(f) < 1e-5);
	}
}
