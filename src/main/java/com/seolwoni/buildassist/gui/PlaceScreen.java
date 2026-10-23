package com.seolwoni.buildassist.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.seolwoni.assistcore.util.Alerts;
import com.seolwoni.buildassist.build.Builder;
import com.seolwoni.buildassist.schematic.Schematic;
import com.seolwoni.buildassist.schematic.States;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Choosing where to build, from the sky: the view leaves the character and looks down on the spot from above, the plan
 * stands there as a see-only copy (only on this screen, the world is not changed), and buttons turn it, mirror it, move
 * it and build it there.
 */
public class PlaceScreen extends Screen {
	/** Larger plans show only their outline (putting tens of thousands of see-only blocks would stall the game). */
	private static final int GHOST_LIMIT = 40000;

	private final Screen parent;
	private final Schematic base;
	private Schematic turned;
	private BlockState[] states;
	private int turns;
	private boolean mirror;
	private BlockPos origin;
	private int step = 1;

	/** The view: which side it looks from (quarter turns), and how far back. */
	private int viewSide;
	private double zoom = 1.0;
	private ArmorStand eye;
	private CameraType oldCamera;

	/** See-only blocks put in the world on this screen, with what was really there. */
	private final Map<BlockPos, BlockState> ghosts = new HashMap<>();
	private int ticks;
	private final List<Object[]> labels = new ArrayList<>();

	public PlaceScreen(Screen parent, Schematic plan) {
		super(Component.literal("어디에 지을까요"));
		this.parent = parent;
		this.base = plan;
		this.turned = plan;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		Minecraft mc = this.minecraft;
		if (mc.player == null || mc.level == null) {
			mc.gui.setScreen(parent);
			return;
		}
		if (eye == null) {
			// First opening: the plan in front of the character, the view from behind the character's back.
			retune();
			Vec3 look = mc.player.getLookAngle();
			Vec3 flat = new Vec3(look.x, 0, look.z);
			flat = flat.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : flat.normalize();
			double ahead = Math.max(turned.sizeX, turned.sizeZ) / 2.0 + 3;
			BlockPos c = BlockPos.containing(mc.player.position().add(flat.scale(ahead)));
			origin = onGround(mc, c).offset(-turned.sizeX / 2, 0, -turned.sizeZ / 2);
			viewSide = Math.floorMod(Math.round(mc.player.getYRot() / 90f), 4);
			eye = new ArmorStand(mc.level, mc.player.getX(), mc.player.getY(), mc.player.getZ());
			eye.setInvisible(true);
			oldCamera = mc.options.getCameraType();
			// Looking from a bit behind the view point hides the hand and keeps the character visible.
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			mc.setCameraEntity(eye);
			showGhosts();
		}
		placeEye();
		layout();
	}

	private void layout() {
		labels.clear();
		int w = this.width, by = this.height - 48;
		int bw = 58, gap = 2;
		// Bottom row 1: turn, mirror, view, closer, further.
		int x = w / 2 - (bw * 6 + gap * 5) / 2;
		addRenderableWidget(Button.builder(Component.literal("돌리기 " + turns * 90 + "°"), b -> {
			turns = (turns + 1) % 4;
			reshape();
		}).bounds(x, by, bw, 20).build());
		x += bw + gap;
		addRenderableWidget(Button.builder(Component.literal("좌우 반전 " + (mirror ? "켬" : "끔")), b -> {
			mirror = !mirror;
			reshape();
		}).bounds(x, by, bw, 20).build());
		x += bw + gap;
		addRenderableWidget(Button.builder(Component.literal("시점 돌리기"), b -> {
			viewSide = (viewSide + 1) % 4;
			placeEye();
			rebuildWidgets();
		}).bounds(x, by, bw, 20).build());
		x += bw + gap;
		addRenderableWidget(Button.builder(Component.literal("가까이"), b -> {
			zoom = Math.max(0.4, zoom - 0.2);
			placeEye();
		}).bounds(x, by, bw, 20).build());
		x += bw + gap;
		addRenderableWidget(Button.builder(Component.literal("멀리"), b -> {
			zoom = Math.min(3.0, zoom + 0.2);
			placeEye();
		}).bounds(x, by, bw, 20).build());
		x += bw + gap;
		addRenderableWidget(Button.builder(Component.literal("내 앞으로"), b -> {
			BlockPos c = BlockPos.containing(this.minecraft.player.position().add(this.minecraft.player.getLookAngle().multiply(1, 0, 1).normalize()
					.scale(Math.max(turned.sizeX, turned.sizeZ) / 2.0 + 3)));
			hideGhosts();
			origin = onGround(this.minecraft, c).offset(-turned.sizeX / 2, 0, -turned.sizeZ / 2);
			moved();
		}).bounds(x, by, bw, 20).build());

		// Bottom row 2: moving (as seen on the screen), up/down, step, build, cancel.
		by += 24;
		x = w / 2 - (bw * 6 + gap * 5) / 2;
		int sw = 28;
		addRenderableWidget(Button.builder(Component.literal("◀"), b -> move(0, -1)).bounds(x, by, sw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("▲"), b -> move(1, 0)).bounds(x + sw + gap, by, sw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("▼"), b -> move(-1, 0)).bounds(x + 2 * (sw + gap), by, sw, 20).build());
		addRenderableWidget(Button.builder(Component.literal("▶"), b -> move(0, 1)).bounds(x + 3 * (sw + gap), by, sw, 20).build());
		x += 4 * (sw + gap) + 4;
		addRenderableWidget(Button.builder(Component.literal("위로"), b -> lift(1)).bounds(x, by, 34, 20).build());
		addRenderableWidget(Button.builder(Component.literal("아래로"), b -> lift(-1)).bounds(x + 36, by, 40, 20).build());
		x += 80;
		addRenderableWidget(Button.builder(Component.literal(step + "칸씩"), b -> {
			step = step == 1 ? 5 : step == 5 ? 10 : 1;
			rebuildWidgets();
		}).bounds(x, by, 44, 20).build());
		x += 48;
		addRenderableWidget(Button.builder(Component.literal("§a여기에 짓기"), b -> build()).bounds(x, by, 70, 20).build());
		x += 72;
		addRenderableWidget(Button.builder(Component.literal("취소"), b -> onClose()).bounds(x, by, 40, 20).build());

		String size = turned.sizeX + " × " + turned.sizeY + " × " + turned.sizeZ;
		labels.add(new Object[] {"크기 " + size + " · 자리 " + origin.toShortString() + (ghosts.isEmpty() && turned.solidCount() > GHOST_LIMIT ? " · 너무 커서 테두리만 보여요" : ""),
				w / 2, 20});
		labels.add(new Object[] {"보이는 건물은 미리보기예요 (실제로 놓인 게 아니에요)", w / 2, 32});
	}

	// ------------------------------------------------------------------ moving the plan

	private void retune() {
		turned = States.turned(base, turns, mirror);
		Map<String, Integer> unknown = new LinkedHashMap<>();
		states = turned.solidCount() > GHOST_LIMIT ? null : States.all(turned, unknown);
	}

	/** Turned or mirrored: the plan keeps its middle where it was. */
	private void reshape() {
		hideGhosts();
		int cx = origin.getX() + turned.sizeX / 2, cz = origin.getZ() + turned.sizeZ / 2;
		retune();
		origin = new BlockPos(cx - turned.sizeX / 2, origin.getY(), cz - turned.sizeZ / 2);
		moved();
	}

	/** Moves as seen on the screen: forward is away from the view, right is to the right of it. */
	private void move(int forward, int right) {
		float yaw = viewSide * 90f;
		int fx = Math.round(-Mth.sin(yaw * Mth.DEG_TO_RAD)), fz = Math.round(Mth.cos(yaw * Mth.DEG_TO_RAD));
		int rx = -fz, rz = fx;
		hideGhosts();
		origin = origin.offset((forward * fx + right * rx) * step, 0, (forward * fz + right * rz) * step);
		moved();
	}

	private void lift(int dy) {
		hideGhosts();
		origin = origin.above(dy * step);
		moved();
	}

	private void moved() {
		showGhosts();
		placeEye();
		rebuildWidgets();
	}

	/** The first block a plan would stand on at {@code c}: down from a little above until something solid. */
	private BlockPos onGround(Minecraft mc, BlockPos c) {
		int top = mc.player.blockPosition().getY() + 8;
		for (int y = top; y > top - 40; y--) {
			BlockPos q = new BlockPos(c.getX(), y, c.getZ());
			BlockState s = real(q);
			if (!s.isAir() && !s.canBeReplaced() && s.getFluidState().isEmpty()) return q.above();
			if (!s.getFluidState().isEmpty()) return q.above();
		}
		return mc.player.blockPosition();
	}

	private BlockState real(BlockPos q) {
		BlockState g = ghosts.get(q);
		return g != null ? g : this.minecraft.level.getBlockState(q);
	}

	// ------------------------------------------------------------------ the view from the sky

	private void placeEye() {
		if (eye == null) return;
		double cx = origin.getX() + turned.sizeX / 2.0, cy = origin.getY() + turned.sizeY * 0.35, cz = origin.getZ() + turned.sizeZ / 2.0;
		double span = Math.sqrt(turned.sizeX * turned.sizeX + turned.sizeZ * turned.sizeZ + turned.sizeY * turned.sizeY);
		double dist = Math.max(6, (span * 1.1 + 6) * zoom - 4); // the game puts the view 4 further back itself
		float yaw = viewSide * 90f + 20f, pitch = 50f;
		double yr = yaw * Mth.DEG_TO_RAD, pr = pitch * Mth.DEG_TO_RAD;
		Vec3 fwd = new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
		Vec3 at = new Vec3(cx, cy, cz).subtract(fwd.scale(dist));
		eye.setPos(at.x, at.y - eye.getEyeHeight(), at.z);
		eye.setYRot(yaw);
		eye.setXRot(pitch);
		eye.setYHeadRot(yaw);
		eye.setOldPosAndRot();
	}

	// ------------------------------------------------------------------ the see-only copy

	private void showGhosts() {
		if (states == null || this.minecraft.level == null) return;
		var level = this.minecraft.level;
		AABB me = this.minecraft.player.getBoundingBox().inflate(0.3);
		for (int y = 0; y < turned.sizeY; y++) {
			for (int z = 0; z < turned.sizeZ; z++) {
				for (int x = 0; x < turned.sizeX; x++) {
					BlockState t = states[turned.cells[turned.index(x, y, z)]];
					if (t == null || t.isAir()) continue;
					BlockPos q = origin.offset(x, y, z);
					if (!level.hasChunkAt(q) || me.intersects(new AABB(q))) continue;
					BlockState now = level.getBlockState(q);
					if (!(now.isAir() || now.canBeReplaced())) continue;
					ghosts.put(q, now);
					// Only on this side, without touching the neighbours.
					level.setBlock(q, t, 2 | 16);
				}
			}
		}
	}

	private void hideGhosts() {
		var level = this.minecraft.level;
		if (level != null) for (Map.Entry<BlockPos, BlockState> e : ghosts.entrySet()) level.setBlock(e.getKey(), e.getValue(), 2 | 16);
		ghosts.clear();
	}

	// ------------------------------------------------------------------ for the tests

	public BlockPos origin() {
		return origin;
	}

	public Schematic plan() {
		return turned;
	}

	public int ghostCount() {
		return ghosts.size();
	}

	public void turnForTest() {
		turns = (turns + 1) % 4;
		reshape();
	}

	public boolean skyView() {
		return eye != null && this.minecraft.getCameraEntity() == eye;
	}

	// ------------------------------------------------------------------ screen

	@Override
	public void tick() {
		if (this.minecraft.player == null || this.minecraft.level == null) {
			onClose();
			return;
		}
		if (ticks++ % 10 == 0) outline(origin, turned);
		placeEye();
	}

	private void outline(BlockPos o, Schematic s) {
		double x0 = o.getX(), y0 = o.getY(), z0 = o.getZ(), x1 = x0 + s.sizeX, y1 = y0 + s.sizeY, z1 = z0 + s.sizeZ;
		double step = Math.max(1.0, Math.max(s.sizeX, Math.max(s.sizeY, s.sizeZ)) / 40.0);
		for (double x = x0; x <= x1 + 1e-3; x += step) {
			dot(x, y0, z0);
			dot(x, y0, z1);
			dot(x, y1, z0);
			dot(x, y1, z1);
		}
		for (double z = z0; z <= z1 + 1e-3; z += step) {
			dot(x0, y0, z);
			dot(x1, y0, z);
			dot(x0, y1, z);
			dot(x1, y1, z);
		}
		for (double y = y0; y <= y1 + 1e-3; y += step) {
			dot(x0, y, z0);
			dot(x1, y, z0);
			dot(x0, y, z1);
			dot(x1, y, z1);
		}
	}

	private void dot(double x, double y, double z) {
		// Seen from far away as well (ordinary particles only show near the view).
		this.minecraft.level.addAlwaysVisibleParticle(ParticleTypes.END_ROD, x, y, z, 0, 0, 0);
	}

	private void build() {
		Minecraft mc = this.minecraft;
		restore();
		if (BuildScreen.pickedName() != null) com.seolwoni.buildassist.build.Timelapse.planName = BuildScreen.pickedName();
		String err = Builder.start(mc, turned, origin);
		if (err != null) Alerts.actionBar(err, 0xFFFF5555);
		else Alerts.actionBar("여기에 지어요: " + origin.toShortString(), 0xFF55FF55);
		mc.gui.setScreen(null);
	}

	/** Back to the character's view, and the world as it really is. */
	private void restore() {
		hideGhosts();
		Minecraft mc = this.minecraft;
		if (eye != null) {
			if (mc.player != null) mc.setCameraEntity(mc.player);
			if (oldCamera != null) mc.options.setCameraType(oldCamera);
			eye = null;
		}
	}

	@Override
	public void removed() {
		restore();
		super.removed();
	}

	@Override
	public void onClose() {
		restore();
		this.minecraft.gui.setScreen(parent);
	}

	/** No darkening or blur: the world behind is what this screen is for. */
	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		g.fill(0, 0, this.width, 44, 0x88000000);
		g.fill(0, this.height - 52, this.width, this.height, 0x88000000);
		super.extractRenderState(g, mx, my, delta);
		g.centeredText(this.font, this.title, this.width / 2, 6, 0xFFFFFF55);
		for (Object[] l : labels) g.centeredText(this.font, Component.literal((String) l[0]), (int) l[1], (int) l[2], 0xFFDDDDDD);
	}
}
