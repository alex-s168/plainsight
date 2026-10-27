package dev.plainsight.render;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** Forwards vertices while multiplying their alpha. */
public final class AlphaVertexConsumer implements VertexConsumer {
	private final VertexConsumer delegate;
	private final float alpha;

	public AlphaVertexConsumer(VertexConsumer delegate, float alpha) {
		this.delegate = delegate;
		this.alpha = alpha;
	}

	@Override
	public VertexConsumer addVertex(float x, float y, float z) {
		delegate.addVertex(x, y, z);
		return this;
	}

	@Override
	public VertexConsumer setColor(int r, int g, int b, int a) {
		delegate.setColor(r, g, b, Math.round(a * alpha));
		return this;
	}

	@Override
	public VertexConsumer setColor(int color) {
		int a = Math.round((color >>> 24) * alpha);
		delegate.setColor(a << 24 | (color & 0xFFFFFF));
		return this;
	}

	@Override
	public VertexConsumer setUv(float u, float v) {
		delegate.setUv(u, v);
		return this;
	}

	@Override
	public VertexConsumer setUv1(int u, int v) {
		delegate.setUv1(u, v);
		return this;
	}

	@Override
	public VertexConsumer setUv2(int u, int v) {
		delegate.setUv2(u, v);
		return this;
	}

	@Override
	public VertexConsumer setUv3(float u, float v) {
		delegate.setUv3(u, v);
		return this;
	}

	@Override
	public VertexConsumer setNormal(float x, float y, float z) {
		delegate.setNormal(x, y, z);
		return this;
	}

	@Override
	public VertexConsumer setLineWidth(float width) {
		delegate.setLineWidth(width);
		return this;
	}
}
