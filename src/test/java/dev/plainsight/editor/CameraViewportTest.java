package dev.plainsight.editor;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CameraViewportTest {
	private static Viewport viewport() {
		Viewport v = new Viewport();
		v.set(300, 22, 980, 852, 1600, 900);
		return v;
	}

	@Test
	void directionFollowsMinecraftConvention() {
		assertTrue(EditorCamera.direction(0, 0, new Vector3d()).distance(0, 0, 1) < 1e-9, "yaw 0 looks south (+z)");
		assertTrue(EditorCamera.direction(90, 0, new Vector3d()).distance(-1, 0, 0) < 1e-9, "yaw 90 looks west (-x)");
		assertTrue(EditorCamera.direction(0, 90, new Vector3d()).distance(0, -1, 0) < 1e-9, "pitch 90 looks down");
	}

	@Test
	void rayThroughProjectedPointHitsIt() {
		EditorCamera cam = new EditorCamera();
		cam.set(new Vector3d(3, 70, -2), 33, 21);
		Viewport v = viewport();
		Vector3d p = new Vector3d(9, 64, 11);
		double[] px = new double[2];
		assertTrue(v.project(cam, p, 70, px));
		Vector3d dir = v.ray(cam, px[0], px[1], 70, new Vector3d());
		Vector3d toP = new Vector3d(p).sub(cam.pos).normalize();
		assertTrue(dir.distance(toP) < 1e-9);
	}

	@Test
	void viewportCentreIsTheViewDirection() {
		EditorCamera cam = new EditorCamera();
		cam.set(new Vector3d(), -50, 10);
		Viewport v = viewport();
		Vector3d dir = v.ray(cam, v.x + v.width / 2, v.y + v.height / 2, 70, new Vector3d());
		assertTrue(dir.distance(cam.forward(new Vector3d())) < 1e-9);
	}

	@Test
	void adjustedProjectionMapsViewportToItsWindowRect() {
		Viewport v = viewport();
		float fov = (float) Math.toRadians(70);
		Matrix4f full = new Matrix4f().perspective(fov, 1600f / 900f, 0.05f, 1000f);
		Matrix4f adjusted = v.adjust(new Matrix4f(full));
		// a point at the viewport's top-right edge of the frustum (camera space) lands at the rect's corner
		double t = Math.tan(fov / 2);
		float z = -10;
		Vector4f corner = new Vector4f((float) (t * v.aspect() * 10), (float) (t * 10), z, 1);
		adjusted.transform(corner);
		float ndcX = corner.x / corner.w, ndcY = corner.y / corner.w;
		float winX = (ndcX + 1) / 2 * 1600, winY = (1 - ndcY) / 2 * 900;
		assertEquals(v.x + v.width, winX, 0.01);
		assertEquals(v.y, winY, 0.01);
		Vector4f centre = adjusted.transform(new Vector4f(0, 0, z, 1));
		assertEquals(v.x + v.width / 2, (centre.x / centre.w + 1) / 2 * 1600, 0.01);
		assertEquals(v.y + v.height / 2, (1 - centre.y / centre.w) / 2 * 900, 0.01);
	}

	@Test
	void orbitKeepsDistanceAndPivotOnScreen() {
		EditorCamera cam = new EditorCamera();
		cam.set(new Vector3d(0, 70, 0), 10, 30);
		Vector3d pivot = new Vector3d(cam.forward(new Vector3d())).mul(12).add(cam.pos).add(1, 0.5, 0);
		Viewport v = viewport();
		double[] before = new double[2], after = new double[2];
		v.project(cam, pivot, 70, before);
		double d0 = cam.pos.distance(pivot);
		cam.beginDrag(EditorCamera.Drag.ORBIT, pivot);
		cam.dragBy(120, -45, v.worldPerPixel(70));
		cam.endDrag();
		assertEquals(d0, cam.pos.distance(pivot), 1e-9);
		assertTrue(v.project(cam, pivot, 70, after));
		assertEquals(before[0], after[0], 1e-6);
		assertEquals(before[1], after[1], 1e-6);
		assertEquals(10 + 24, cam.yaw, 1e-4);
	}

	@Test
	void panKeepsGrabbedPointUnderCursor() {
		EditorCamera cam = new EditorCamera();
		cam.set(new Vector3d(0, 70, 0), 0, 0);
		Viewport v = viewport();
		Vector3d grabbed = new Vector3d(0, 70, 20);
		double[] a = new double[2], b = new double[2];
		v.project(cam, grabbed, 70, a);
		cam.beginDrag(EditorCamera.Drag.PAN, grabbed);
		cam.dragBy(100, 40, v.worldPerPixel(70));
		v.project(cam, grabbed, 70, b);
		assertEquals(a[0] + 100, b[0], 0.01);
		assertEquals(a[1] + 40, b[1], 0.01);
	}

	@Test
	void dollyStopsBeforeTheTarget() {
		EditorCamera cam = new EditorCamera();
		cam.set(new Vector3d(0, 70, 0), 0, 0);
		Vector3d dir = new Vector3d(0, 0, 1);
		for (int i = 0; i < 50; i++) cam.dolly(1, dir, 10 - cam.pos.z);
		assertTrue(cam.pos.z <= 9.0 + 1e-9 && cam.pos.z > 8, "approaches but never passes: " + cam.pos.z);
		cam.dolly(-1, dir, 10 - cam.pos.z);
		assertTrue(cam.pos.z < 9);
	}

	@Test
	void flightIsHorizontalAndScalesWithSpeed() {
		EditorCamera cam = new EditorCamera();
		cam.set(new Vector3d(), 0, 60);
		cam.forward = true;
		for (int i = 0; i < 100; i++) cam.update(0.01);
		assertEquals(0, cam.pos.y, 1e-9, "W ignores pitch");
		double normal = cam.pos.z;
		EditorCamera fast = new EditorCamera();
		fast.set(new Vector3d(), 0, 0);
		fast.forward = true;
		fast.speed = 2;
		for (int i = 0; i < 100; i++) fast.update(0.01);
		assertEquals(2 * normal, fast.pos.z, 1e-6);
		assertTrue(normal > 8 && normal < 10.5, "about BASE_SPEED blocks in one second: " + normal);
	}
}
