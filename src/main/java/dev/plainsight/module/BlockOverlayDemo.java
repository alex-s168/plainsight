package dev.plainsight.module;

import dev.plainsight.editor.Editor;
import dev.plainsight.render.BlockOverlay;
import imgui.ImDrawList;
import imgui.ImGui;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Demo of block overlays: a small fixed hut shown as a (lit) preview in front of the player. */
public final class BlockOverlayDemo implements Module {
	private final Editor editor;
	public final BlockOverlay overlay = new BlockOverlay();
	private boolean enabled;

	public BlockOverlayDemo(Editor editor) {
		this.editor = editor;
		build();
	}

	@Override
	public String id() {
		return "block_overlay_demo";
	}

	@Override
	public String name() {
		return "Block Overlay Demo";
	}

	@Override
	public String description() {
		return "Shows a fixed structure as a block overlay (like a paste preview), lit by the world.";
	}

	@Override
	public boolean enabled() {
		return enabled;
	}

	@Override
	public void setEnabled(boolean e) {
		if (e && !enabled) anchor();
		enabled = e;
		if (e) overlay.show();
		else overlay.hide();
	}

	/** Places the structure a few blocks in front of the player, standing on their feet level. */
	public void anchor() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		Direction d = mc.player.getDirection();
		BlockPos feet = mc.player.blockPosition();
		// centre of the 7x7 hut eight blocks ahead; the overlay origin is its corner
		BlockPos centre = feet.relative(d, 8);
		overlay.offset.set(centre.offset(-3, 0, -3));
	}

	private void build() {
		overlay.clear();
		BlockState floor = Blocks.STONE_BRICKS.defaultBlockState();
		BlockState wall = Blocks.OAK_PLANKS.defaultBlockState();
		BlockState log = Blocks.OAK_LOG.defaultBlockState();
		BlockState glass = Blocks.GLASS.defaultBlockState();
		int n = 7;
		for (int x = 0; x < n; x++) {
			for (int z = 0; z < n; z++) {
				overlay.put(new BlockPos(x, 0, z), floor);
				boolean edgeX = x == 0 || x == n - 1, edgeZ = z == 0 || z == n - 1;
				if (!edgeX && !edgeZ) continue;
				for (int y = 1; y <= 3; y++) {
					BlockState s = edgeX && edgeZ ? log : wall;
					boolean window = y == 2 && (x == 3 || z == 3) && !(edgeX && edgeZ);
					boolean door = z == 0 && x == 3 && y <= 2;
					if (door) continue;
					overlay.put(new BlockPos(x, y, z), window ? glass : s);
				}
			}
		}
		// gable roof: stairs rising from both long sides, a slab ridge, plank gables at the ends
		BlockState east = Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
		BlockState west = Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST);
		for (int z = -1; z <= n; z++) {
			for (int i = 0; i < 3; i++) {
				overlay.put(new BlockPos(i, 4 + i, z), east);
				overlay.put(new BlockPos(n - 1 - i, 4 + i, z), west);
			}
			overlay.put(new BlockPos(3, 6, z), Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
		}
		for (int z : new int[]{0, n - 1}) {
			for (int x = 1; x <= n - 2; x++) overlay.put(new BlockPos(x, 4, z), wall);
			for (int x = 2; x <= n - 3; x++) overlay.put(new BlockPos(x, 5, z), wall);
		}
		overlay.put(new BlockPos(3, 2, 1), Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.SOUTH));
		overlay.put(new BlockPos(5, 1, 5), Blocks.CRAFTING_TABLE.defaultBlockState());
		overlay.put(new BlockPos(1, 1, 5), Blocks.BARREL.defaultBlockState());
	}

	@Override
	public void options() {
		ImGui.text(overlay.size() + " blocks at " + overlay.offset.toShortString());
		if (ImGui.button("Move in front of player")) anchor();
		float[] op = {overlay.opacity};
		ImGui.setNextItemWidth(160);
		if (ImGui.sliderFloat("Opacity", op, 0.1f, 1f, "%.2f")) overlay.opacity = op[0];
		boolean outline = overlay.outlineColor != 0;
		if (ImGui.checkbox("Outline", outline)) overlay.outlineColor = outline ? 0 : 0xFF60C0FF;
	}

	@Override
	public void drawIcon(ImDrawList dl, float x, float y, float size, int color) {
		float cx = x + size / 2, s = size * 0.3f;
		float top = y + size * 0.18f, mid = y + size * 0.38f, bot = y + size * 0.82f;
		dl.addQuadFilled(cx, top, cx + s, top + s * 0.55f, cx, mid + s * 0.4f, cx - s, top + s * 0.55f, color);
		dl.addQuadFilled(cx - s, top + s * 0.55f, cx, mid + s * 0.4f, cx, bot, cx - s, bot - s * 0.55f, (color & 0x00FFFFFF) | 0xB0000000);
		dl.addQuadFilled(cx + s, top + s * 0.55f, cx, mid + s * 0.4f, cx, bot, cx + s, bot - s * 0.55f, (color & 0x00FFFFFF) | 0x80000000);
	}
}
