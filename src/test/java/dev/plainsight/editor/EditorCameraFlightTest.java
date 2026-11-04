package dev.plainsight.editor;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorCameraFlightTest {
	@Test
	void flyingAfterAnArcballDragDoesNotCircleThePivot() {
		EditorCamera cam = new EditorCamera();
		cam.set(new Vector3d(0, 0, 0), 0, 0);
		// a pivot off the view's centre: the camera starts turning towards it
		cam.beginDrag(EditorCamera.Drag.ORBIT, new Vector3d(5, 0, 20));
		cam.update(0.01);
		cam.endDrag();
		float yaw = cam.yaw;
		cam.left = true;
		for (int i = 0; i < 100; i++) cam.update(0.01);
		assertEquals(yaw, cam.yaw, 1e-4, "the view keeps its direction while strafing");
		assertTrue(cam.pos.x > 1, "and the camera strafes left (+x at yaw 0): " + cam.pos);
	}
}
