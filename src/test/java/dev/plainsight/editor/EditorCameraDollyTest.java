package dev.plainsight.editor;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorCameraDollyTest {
	private static double zoomBy(double notches, double targetDistance) {
		EditorCamera cam = new EditorCamera();
		cam.set(new Vector3d(0, 0, 0), 0, 0);
		Vector3d dir = new Vector3d(0, 0, 1);
		cam.dolly(notches, dir, targetDistance);
		for (int i = 0; i < 200; i++) cam.update(0.01);
		return cam.pos.z;
	}

	@Test
	void zoomsTowardsAFarTargetWithoutPassingIt() {
		assertEquals(4, zoomBy(1, 20), 1e-3, "20% of the distance");
		assertTrue(zoomBy(10, 20) <= 19 + 1e-6, "stops a block before the target");
	}

	@Test
	void aTargetTheCameraIsInOrAgainstDoesNotStopZoomingIn() {
		// #28: after zooming out into terrain the hit under the cursor is within a block of the camera
		for (double d : new double[]{0.0, 0.3, 0.9, 1.2}) assertTrue(zoomBy(1, d) >= 0.99, "zooms in with a target " + d + " away");
	}

	private static EditorCamera settle(EditorCamera cam) {
		for (int i = 0; i < 200; i++) cam.update(0.01);
		return cam;
	}

	@Test
	void withNothingUnderTheCursorTheStepFollowsTheScale() {
		// far out, the cursor over sky or beyond the LOD: zooming back in must not crawl a block per notch
		EditorCamera cam = new EditorCamera();
		cam.set(new Vector3d(0, 5000, 0), 0, 90);
		Vector3d down = new Vector3d(0, -1, 0);
		cam.dolly(1, down, 0, 5000 - 63);
		settle(cam);
		assertTrue(cam.pos.y < 5000 - 900, "zoomed in by about 20% of the height: " + cam.pos.y);
	}

	@Test
	void zoomingOutStopsAtTheHeightLimit() {
		EditorCamera cam = new EditorCamera();
		cam.set(new Vector3d(0, 7000, 0), 0, 90);
		Vector3d down = new Vector3d(0, -1, 0);
		for (int i = 0; i < 50; i++) cam.dolly(-1, down, 7000 - 63);
		settle(cam);
		assertTrue(cam.pos.y <= EditorCamera.MAX_ZOOM_OUT_Y + 1e-6, "stays at or below the limit: " + cam.pos.y);
		assertTrue(cam.pos.y > EditorCamera.MAX_ZOOM_OUT_Y - 1, "reaches it: " + cam.pos.y);
	}

	@Test
	void zoomingInTowardsTheSkyStopsAtTheHeightLimitToo() {
		// the cursor over sky while looking up: no target, so each notch steps by the height and would climb
		// exponentially (the game froze far out)
		EditorCamera cam = new EditorCamera();
		cam.set(new Vector3d(0, 4000, 0), 0, -10);
		Vector3d up = new Vector3d(0, 0.2, 1).normalize();
		for (int i = 0; i < 50; i++) cam.dolly(1, up, 0, cam.pos.y + cam.pendingZoomY() - 63);
		settle(cam);
		assertTrue(cam.pos.y <= EditorCamera.MAX_ZOOM_OUT_Y + 1e-6, "stays at or below the limit: " + cam.pos.y);
	}
}
