package dev.plainsight.editor;

import org.joml.Matrix4f;
import org.joml.Vector3d;

/**
 * The part of the window that shows the world (between the editor panels), in window pixels. The level
 * is rendered with a projection whose centre is the viewport's centre, so the view looks like it was
 * rendered into the viewport alone while the panels simply cover the rest.
 */
public final class Viewport {
	public float x, y, width = 1, height = 1;
	/** Window (framebuffer) size the rect refers to. */
	public float windowWidth = 1, windowHeight = 1;

	public void set(float x, float y, float w, float h, float windowW, float windowH) {
		this.x = x;
		this.y = y;
		this.width = Math.max(1, w);
		this.height = Math.max(1, h);
		this.windowWidth = Math.max(1, windowW);
		this.windowHeight = Math.max(1, windowH);
	}

	public boolean contains(double px, double py) {
		return px >= x && py >= y && px < x + width && py < y + height;
	}

	public float aspect() {
		return width / height;
	}

	/**
	 * Turns a projection made for the whole window (same vertical fov) into one centred on the viewport:
	 * the x scale is corrected to the viewport's aspect, then clip space is scaled and shifted into the
	 * viewport rectangle.
	 */
	public Matrix4f adjust(Matrix4f windowProjection) {
		float aspectFix = (windowWidth / windowHeight) / aspect();
		float sx = width / windowWidth, sy = height / windowHeight;
		float tx = (2 * x + width) / windowWidth - 1, ty = 1 - (2 * y + height) / windowHeight;
		Matrix4f m = new Matrix4f(
				sx * aspectFix, 0, 0, 0,
				0, sy, 0, 0,
				0, 0, 1, 0,
				tx, ty, 0, 1);
		return m.mul(windowProjection, windowProjection);
	}

	/** Direction of the ray through window pixel (px, py) for a camera with the given vertical fov (degrees). */
	public Vector3d ray(EditorCamera camera, double px, double py, double fovDegrees, Vector3d dest) {
		double nx = 2 * (px - x) / width - 1, ny = 1 - 2 * (py - y) / height;
		double t = Math.tan(Math.toRadians(fovDegrees) / 2);
		Vector3d f = camera.forward(new Vector3d()), r = camera.right(new Vector3d()), u = camera.up(new Vector3d());
		return dest.set(f).fma(nx * t * aspect(), r).fma(ny * t, u).normalize();
	}

	/** Projects a world point to window pixels; returns false when it is behind the camera. */
	public boolean project(EditorCamera camera, Vector3d world, double fovDegrees, double[] out) {
		Vector3d d = new Vector3d(world).sub(camera.pos);
		Vector3d f = camera.forward(new Vector3d()), r = camera.right(new Vector3d()), u = camera.up(new Vector3d());
		double z = d.dot(f);
		if (z <= 1e-4) return false;
		double t = Math.tan(Math.toRadians(fovDegrees) / 2);
		double nx = d.dot(r) / (z * t * aspect()), ny = d.dot(u) / (z * t);
		out[0] = x + (nx + 1) / 2 * width;
		out[1] = y + (1 - ny) / 2 * height;
		return true;
	}

	/** World size of one pixel at distance 1 along the view direction. */
	public double worldPerPixel(double fovDegrees) {
		return 2 * Math.tan(Math.toRadians(fovDegrees) / 2) / height;
	}
}
