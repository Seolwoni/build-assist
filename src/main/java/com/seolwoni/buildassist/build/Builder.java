package com.seolwoni.buildassist.build;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.seolwoni.assistcore.engine.Action;
import com.seolwoni.assistcore.engine.ActionQueue;
import com.seolwoni.assistcore.util.Body;
import com.seolwoni.assistcore.util.Human;
import com.seolwoni.buildassist.schematic.Schematic;
import com.seolwoni.buildassist.schematic.States;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Builds a plan where the player put it, the way a player would: clears the plan's box first (top down, everything
 * that should not be there), fills holes under the first layer, then puts the blocks down layer by layer from the
 * bottom, walking to a spot in reach for each group of blocks. Stage 2: only blocks whose look does not depend on how
 * they are put down (plain blocks, logs standing up or lying along the face they are put on, fences); the others are
 * left for later and counted.
 */
public final class Builder {
	private Builder() {
	}

	static final ActionQueue queue = new ActionQueue(Builder::onFail);
	public static boolean running;
	static Schematic plan;
	static BlockState[] states;
	static boolean[] plainState;
	static BlockPos origin;
	public static String status = "", lastStop = "";
	/** Cells given up on (no spot to stand, could not be put down, nothing to put it against). */
	static final Set<BlockPos> gaveUp = new HashSet<>();
	static final Map<BlockPos, Integer> retries = new java.util.HashMap<>();
	/** Items that ran out: id -> how many more were needed. */
	public static final Map<String, Integer> missing = new LinkedHashMap<>();
	public static int placed, broken, filled, skipped, ticks;
	static int idle;

	/** For tests: would this stage build this block? */
	public static boolean plainForTest(BlockState t) {
		return Placement.plain(t);
	}

	public static BlockPos origin() {
		return origin;
	}

	public static Schematic plan() {
		return plan;
	}

	/** Starts building {@code s} with its lowest corner at {@code at} (the plan's first layer at that height). */
	public static String start(Minecraft mc, Schematic s, BlockPos at) {
		if (mc.player == null || mc.level == null) return "게임에 들어가서 켜주세요";
		Map<String, Integer> unknown = new LinkedHashMap<>();
		BlockState[] st = States.all(s, unknown);
		plan = s;
		states = st;
		plainState = new boolean[st.length];
		for (int i = 0; i < st.length; i++) plainState[i] = st[i] != null && Placement.plain(st[i]);
		origin = at.immutable();
		queue.clear();
		gaveUp.clear();
		retries.clear();
		missing.clear();
		placed = broken = filled = skipped = ticks = 0;
		idle = 0;
		lastStop = "";
		running = true;
		status = "준비 중";
		return null;
	}

	public static void stop(String why) {
		running = false;
		queue.clear();
		Minecraft mc = Minecraft.getInstance();
		Body.release(mc);
		mc.options.keyShift.setDown(false);
		if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
		lastStop = why == null ? "" : why;
		status = "꺼짐";
		if (why != null && mc.player != null) com.seolwoni.assistcore.util.Alerts.actionBar("Build Assist: " + why, 0xFF55FF55);
	}

	private static void onFail(Action a) {
		if (a instanceof Placement.PlaceAction pa) {
			// Passing reasons (we stood there, a little out of reach) get a few more goes, then it is given up.
			if (!pa.retry || retries.merge(pa.pos, 1, Integer::sum) > 4) gaveUp.add(pa.pos);
		}
		if (a instanceof Placement.BreakAction ba) gaveUp.add(ba.pos);
		// A failed walk: the next plan starts over from where we are.
	}

	public static void tick(Minecraft mc) {
		if (!running) return;
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null || mc.gameMode == null) {
			stop(null);
			return;
		}
		if (mc.isPaused()) return;
		if (p.isDeadOrDying()) {
			stop("죽었어요");
			return;
		}
		ticks++;
		Human.pace = 1;
		if (queue.tick(mc, p)) return;
		plan(mc, p);
	}

	// ------------------------------------------------------------------ planning

	static BlockPos world(int x, int y, int z) {
		return origin.offset(x, y, z);
	}

	/** The plan's state at a world position (null outside the box). */
	static BlockState target(BlockPos w) {
		int x = w.getX() - origin.getX(), y = w.getY() - origin.getY(), z = w.getZ() - origin.getZ();
		if (x < 0 || y < 0 || z < 0 || x >= plan.sizeX || y >= plan.sizeY || z >= plan.sizeZ) return null;
		return states[plan.cells[plan.index(x, y, z)]];
	}

	static boolean planAir(BlockState t) {
		return t == null || t.isAir();
	}

	private static void plan(Minecraft mc, LocalPlayer p) {
		// 1) Clear the box: whatever stands where the plan has air (top down, so nothing is left hanging).
		List<BlockPos> clear = new ArrayList<>();
		for (int y = plan.sizeY - 1; y >= 0 && clear.isEmpty(); y--) {
			for (int z = 0; z < plan.sizeZ; z++) {
				for (int x = 0; x < plan.sizeX; x++) {
					int c = plan.cells[plan.index(x, y, z)];
					BlockPos w = world(x, y, z);
					if (gaveUp.contains(w)) continue;
					BlockState now = mc.level.getBlockState(w);
					BlockState t = states[c];
					boolean wantAir = t == null || t.isAir() || !plainState[c];
					if (wantAir && !now.isAir() && now.getFluidState().isEmpty() && !(t != null && !t.isAir() && Placement.matches(now, t))) clear.add(w.immutable());
				}
			}
		}
		if (!clear.isEmpty()) {
			status = "자리 정리하는 중 (" + clear.size() + ")";
			queueNearest(mc, p, clear, false, 6);
			return;
		}
		// 2) Holes under the first layer: filled so the building stands on the ground.
		List<BlockPos> holes = new ArrayList<>();
		for (int z = 0; z < plan.sizeZ; z++) {
			for (int x = 0; x < plan.sizeX; x++) {
				BlockState t = states[plan.cells[plan.index(x, 0, z)]];
				if (planAir(t)) continue;
				BlockPos below = world(x, -1, z);
				if (gaveUp.contains(below)) continue;
				BlockState b = mc.level.getBlockState(below);
				if (b.isAir() || !b.getFluidState().isEmpty() || b.canBeReplaced()) holes.add(below.immutable());
			}
		}
		if (!holes.isEmpty()) {
			status = "바닥 아래 구멍 메우는 중 (" + holes.size() + ")";
			queueNearest(mc, p, holes, true, 6);
			return;
		}
		// 3) Build, layer by layer from the bottom: blocks that are not there yet (a wrong block is taken out first).
		skipped = 0;
		for (int y = 0; y < plan.sizeY; y++) {
			List<BlockPos> todo = new ArrayList<>();
			for (int z = 0; z < plan.sizeZ; z++) {
				for (int x = 0; x < plan.sizeX; x++) {
					int c = plan.cells[plan.index(x, y, z)];
					BlockState t = states[c];
					if (planAir(t)) continue;
					if (!plainState[c]) {
						skipped++;
						continue;
					}
					BlockPos w = world(x, y, z);
					if (gaveUp.contains(w)) continue;
					if (!Placement.matches(mc.level.getBlockState(w), t)) todo.add(w.immutable());
				}
			}
			if (!todo.isEmpty()) {
				status = "짓는 중: " + (y + 1) + "/" + plan.sizeY + "층 (남은 블록 " + todo.size() + ")";
				// Only blocks that have something to be put against now; the rest follow.
				List<BlockPos> ready = new ArrayList<>();
				for (BlockPos w : todo) if (Placement.support(w, target(w)) != null) ready.add(w);
				if (ready.isEmpty()) {
					// Nothing to build against in this layer (a floating part): later stages (scaffolding).
					gaveUp.addAll(todo);
					return;
				}
				queueNearest(mc, p, ready, false, 8);
				return;
			}
		}
		finish(mc);
	}

	private static void finish(Minecraft mc) {
		int left = 0;
		for (BlockPos g : gaveUp) {
			BlockState t = target(g);
			if (t != null && !t.isAir()) left++;
		}
		String why = "다 지었어요 (놓은 블록 " + placed + ", 치운 블록 " + broken + (filled > 0 ? ", 메운 구멍 " + filled : "") + (skipped > 0 ? ", 다음 단계로 남긴 블록 " + skipped : "")
				+ (left > 0 ? ", 못 놓은 블록 " + left : "") + (missing.isEmpty() ? "" : ", 모자란 재료 " + missing.size() + "가지") + ")";
		stop(why);
	}

	/**
	 * Queues work on the cells nearest to the player: walk to a spot in reach of the first one if needed, then every
	 * cell of the batch in reach from that spot. {@code fill}: put a filler block (dirt, cobblestone) instead of the plan's.
	 */
	private static void queueNearest(Minecraft mc, LocalPlayer p, List<BlockPos> cells, boolean fill, int max) {
		Vec3 me = p.position();
		cells.sort((a, b) -> Double.compare(me.distanceToSqr(Vec3.atCenterOf(a)), me.distanceToSqr(Vec3.atCenterOf(b))));
		BlockPos first = cells.get(0);
		// Standing where a block still has to go: step out of the way first.
		BlockPos feet = p.blockPosition();
		boolean inTheWay = Reach.pending(mc, feet) || Reach.pending(mc, feet.above());
		BlockPos stand = Reach.inReach(p, first) && !inTheWay ? null : Reach.standFor(mc, p, first);
		if ((!Reach.inReach(p, first) || inTheWay) && stand == null) {
			gaveUp.add(first);
			idle++;
			return;
		}
		Vec3 eye = stand == null ? p.getEyePosition() : new Vec3(stand.getX() + 0.5, stand.getY() + 1.62, stand.getZ() + 0.5);
		if (stand != null) {
			queue.add(new com.seolwoni.assistpath.walk.PathWalkAction(new com.seolwoni.assistpath.goals.GoalBlock(stand),
					com.seolwoni.assistpath.PathOptions.WALK, Action.SKIP));
		}
		int n = 0;
		for (BlockPos c : cells) {
			if (n >= max) break;
			if (Reach.dist(eye, c) > Reach.REACH) continue;
			if (stand != null && (c.equals(stand) || c.equals(stand.above()))) continue;
			BlockState t = target(c);
			BlockState now = mc.level.getBlockState(c);
			boolean clearing = !fill && (t == null || t.isAir() || !plainState[plan.cells[plan.index(c.getX() - origin.getX(), c.getY() - origin.getY(), c.getZ() - origin.getZ())]]);
			if (clearing) {
				queue.add(new Placement.BreakAction(c));
			} else {
				if (!fill && !now.isAir() && !now.canBeReplaced() && now.getFluidState().isEmpty()) queue.add(new Placement.BreakAction(c));
				queue.add(new Placement.PlaceAction(c, fill ? null : t));
			}
			n++;
		}
		if (n == 0) gaveUp.add(first);
	}
}
