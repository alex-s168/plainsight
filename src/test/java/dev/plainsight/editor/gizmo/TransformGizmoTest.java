package dev.plainsight.editor.gizmo;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransformGizmoTest {
	private static TransformGizmo gizmo(Vector3d camera) {
		TransformGizmo g = new TransformGizmo();
		g.origin.set(10, 64, 10);
		g.rings = true;
		g.update(camera);
		return g;
	}

	@Test
	void arrowsPointTowardsTheCamera() {
		Vector3d cam = new Vector3d(0, 80, 30);
		TransformGizmo g = gizmo(cam);
		assertEquals(-1, g.dir(0).x, 1e-9);
		assertEquals(1, g.dir(1).y, 1e-9);
		assertEquals(1, g.dir(2).z, 1e-9);
	}

	@Test
	void picksTheArrowUnderTheRay() {
		Vector3d cam = new Vector3d(0, 80, 30);
		TransformGizmo g = gizmo(cam);
		for (int axis = 0; axis < 3; axis++) {
			Vector3d onArrow = new Vector3d(g.dir(axis)).mul(g.size() * 0.6).add(g.origin);
			Vector3d dir = new Vector3d(onArrow).sub(cam).normalize();
			assertEquals(TransformGizmo.Part.values()[axis], g.pick(cam, dir), "axis " + axis);
		}
		Vector3d away = new Vector3d(g.origin).add(0, -5, 0).sub(cam).normalize();
		assertNull(g.pick(cam, away));
	}

	@Test
	void theMapOnlyOffersPartsAcrossIt() {
		Vector3d cam = new Vector3d(0, 80, 30);
		TransformGizmo g = gizmo(cam);
		g.allowed = TransformGizmo.MAP_PARTS;
		// the Y arrow cannot be grabbed in the map; the X and Z arrows can
		Vector3d onY = new Vector3d(g.dir(1)).mul(g.size() * 0.6).add(g.origin);
		TransformGizmo.Part p = g.pick(cam, new Vector3d(onY).sub(cam).normalize());
		assertNotEquals(TransformGizmo.Part.AXIS_Y, p);
		for (int axis : new int[]{0, 2}) {
			Vector3d onArrow = new Vector3d(g.dir(axis)).mul(g.size() * 0.6).add(g.origin);
			assertEquals(TransformGizmo.Part.values()[axis], g.pick(cam, new Vector3d(onArrow).sub(cam).normalize()), "axis " + axis);
		}
	}

	@Test
	void picksPlaneSquaresAndRings() {
		Vector3d cam = new Vector3d(25, 90, 35);
		TransformGizmo g = gizmo(cam);
		Vector3d inXZ = new Vector3d(g.origin).fma(g.size() * 0.375, g.dir(2)).fma(g.size() * 0.375, g.dir(0));
		assertEquals(TransformGizmo.Part.PLANE_XZ, g.pick(cam, new Vector3d(inXZ).sub(cam).normalize()));
		// a point on the y ring away from the arrows and squares
		double a = Math.toRadians(200);
		Vector3d onRing = new Vector3d(g.origin).add(Math.cos(a) * g.size() * 0.85, 0, Math.sin(a) * g.size() * 0.85);
		assertEquals(TransformGizmo.Part.RING_Y, g.pick(cam, new Vector3d(onRing).sub(cam).normalize()));
	}

	@Test
	void axisDragFollowsTheCursorAlongTheAxis() {
		Vector3d cam = new Vector3d(0, 80, 30);
		TransformGizmo g = gizmo(cam);
		Vector3d grab = new Vector3d(g.dir(2)).mul(g.size() * 0.6).add(g.origin);
		g.beginDrag(TransformGizmo.Part.AXIS_Z, cam, new Vector3d(grab).sub(cam).normalize());
		Vector3d target = new Vector3d(grab).add(0, 0, 3.0);
		Vector3d t = g.dragTranslation(cam, new Vector3d(target).sub(cam).normalize());
		assertEquals(3.0, t.z, 1e-6);
		assertEquals(0, t.x, 1e-9);
		assertEquals(0, t.y, 1e-9);
	}

	@Test
	void ringDragMeasuresTheAngle() {
		Vector3d cam = new Vector3d(10, 90, 10.001);
		TransformGizmo g = gizmo(cam);
		double r = g.size() * 0.85;
		Vector3d start = new Vector3d(g.origin).add(r, 0, 0);
		g.beginDrag(TransformGizmo.Part.RING_Y, cam, new Vector3d(start).sub(cam).normalize());
		double a = Math.toRadians(40);
		// rotating +x towards -z is a positive rotation about +y
		Vector3d now = new Vector3d(g.origin).add(Math.cos(a) * r, 0, -Math.sin(a) * r);
		assertEquals(40, Math.toDegrees(g.dragAngle(cam, new Vector3d(now).sub(cam).normalize())), 1e-6);
	}

	@Test
	void snapping() {
		assertEquals(3.0, TransformGizmo.snap(2.6, 1), 1e-12);
		assertEquals(2.5, TransformGizmo.snap(2.6, 0.5), 1e-12);
		assertEquals(2.6, TransformGizmo.snap(2.6, 0), 1e-12);
	}
}
