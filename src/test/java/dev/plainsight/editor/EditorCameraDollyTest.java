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
}
