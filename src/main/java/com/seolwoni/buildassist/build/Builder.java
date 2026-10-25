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
import com.seolwoni.assistcore.util.InvBase;
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
	/** Temporary blocks we put (to build against, to stand on): taken out at the end. */
	static final Set<BlockPos> temps = new java.util.LinkedHashSet<>();
	/** Goes at helping each cell (temporary blocks, pillars): a few, then it is given up. */
	static final Map<BlockPos, Integer> helps = new java.util.HashMap<>();

	/**
	 * Standing on our own pillar more than three blocks up (deeper than a walk may drop): take out the block under the
	 * feet. True if that was queued.
	 */
	static boolean descend(Minecraft mc, LocalPlayer p) {
		BlockPos under = p.blockPosition().below();
		// Not when that block is gone already (we stand on the edge of the next one: a dig of air is done at once, and
		// this came back every tick: tree-farm, 2 minutes "coming down from the pillar" until time ran out), nor when
		// it was given up.
		if (!temps.contains(under) || !p.onGround() || gaveUp.contains(under) || mc.level.getBlockState(under).isAir()) return false;
		// The scaffolding's walkway and towers stay until it is taken down (its own way, band by band).
		if (Scaffold.isScaffold(under)) return false;
		int depth = 0;
		BlockPos q = under;
		while (temps.contains(q) && depth < 64) {
			q = q.below();
			depth++;
		}
		if (depth <= 3) return false;
		status = com.seolwoni.assistcore.i18n.Tr.t("기둥에서 내려오는 중");
		queue.add(new Placement.BreakAction(under));
		return true;
	}

	/** The cell the last batch of work was for (see the no-progress check in tick). */
	static BlockPos lastTarget;
	/** The last decisions and failures (written to the report when nothing moves for a minute). */
	static final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();

	static void trace(String what) {
		if (recent.size() >= 20) recent.pollFirst();
		recent.addLast("t=" + ticks + " " + what);
	}

	static boolean help(Minecraft mc, LocalPlayer p, BlockPos cell, BlockState t) {
		lastTarget = cell.immutable();
		trace("help " + cell.toShortString() + " (go " + (helps.getOrDefault(cell, 0) + 1) + ")");
		// Two goes at most: a third row of temporary blocks for the same block has never made it.
		if (helps.merge(cell, 1, Integer::sum) > 2) return false;
		return Help.plan(mc, p, cell, t);
	}
	/** Items that ran out: id -> how many more were needed. */
	public static final Map<String, Integer> missing = new LinkedHashMap<>();
	public static int placed, broken, filled, skipped, ticks, wrongWay;
	static int idle;

	public static String scaffoldSummary() {
		return "supply trips=" + Supply.trips + " stacks taken=" + Supply.takenStacks + " | escapes=" + escapes + " bands=" + Scaffold.bands + " towers=" + Scaffold.towers + " walkway blocks=" + Scaffold.walkwayBlocks;
	}

	public static List<String> scaffoldLog() {
		return new ArrayList<>(Scaffold.log);
	}

	/** For tests: would this stage build this block? */
	public static boolean plainForTest(BlockState t) {
		return Placement.buildable(t) || Placement.secondHalf(t);
	}

	public static BlockPos origin() {
		return origin;
	}

	public static Schematic plan() {
		return plan;
	}

	/** Starts building {@code s} with its lowest corner at {@code at} (the plan's first layer at that height). */
	public static String start(Minecraft mc, Schematic s, BlockPos at) {
		if (mc.player == null || mc.level == null) return com.seolwoni.assistcore.i18n.Tr.t("게임에 들어가서 켜주세요");
		Map<String, Integer> unknown = new LinkedHashMap<>();
		BlockState[] st = States.all(s, unknown);
		plan = s;
		states = st;
		plainState = new boolean[st.length];
		for (int i = 0; i < st.length; i++) plainState[i] = st[i] != null && (Placement.buildable(st[i]) || Placement.secondHalf(st[i]));
		origin = at.immutable();
		queue.clear();
		gaveUp.clear();
		retries.clear();
		temps.clear();
		helps.clear();
		badStands.clear();
		walkFails.clear();
		softFail.clear();
		trappedAt = null;
		trappedWalks = 0;
		dropTicks = 0;
		lastTarget = null;
		recent.clear();
		missing.clear();
		pickTries.clear();
		Supply.reset();
		fails = 0;
		lastFailAt = null;
		escapeTries.clear();
		failsHere = 0;
		escapes = 0;
		Scaffold.reset();
		placed = broken = filled = skipped = ticks = wrongWay = 0;
		idle = 0;
		lastStop = "";
		running = true;
		status = com.seolwoni.assistcore.i18n.Tr.t("준비 중");
		com.seolwoni.assistcore.log.UsageLog.start("build", Timelapse.planName + " " + s.sizeX + "×" + s.sizeY + "×" + s.sizeZ + com.seolwoni.assistcore.i18n.Tr.t(", 블록 ") + s.solidCount() + com.seolwoni.assistcore.i18n.Tr.t("개, 자리 ") + origin.toShortString());
		Timelapse.start(mc);
		return null;
	}

	public static void stop(String why) {
		if (running) {
			Timelapse.finish(Minecraft.getInstance());
			com.seolwoni.assistcore.log.UsageLog.end("build", why == null ? com.seolwoni.assistcore.i18n.Tr.t("직접 껐어요") : why, com.seolwoni.assistcore.i18n.Tr.t("놓은 블록 ") + placed + com.seolwoni.assistcore.i18n.Tr.t(", 치운 블록 ") + broken + (temps.isEmpty() ? "" : com.seolwoni.assistcore.i18n.Tr.t(", 받침 ") + temps.size()));
		}
		running = false;
		queue.clear();
		com.seolwoni.assistpath.PathSettings.get().maxFallHeightNoWater.value = 3;
		Minecraft mc = Minecraft.getInstance();
		Body.release(mc);
		mc.options.keyShift.setDown(false);
		if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
		lastStop = why == null ? "" : why;
		status = com.seolwoni.assistcore.i18n.Tr.t("꺼짐");
		if (why != null && mc.player != null) com.seolwoni.assistcore.util.Alerts.actionBar("Build Assist: " + why, 0xFF55FF55);
	}

	private static int fails, lastWork, lastWorkTick;

	static void giveUp(BlockPos q, String why) {
		if (gaveUp.add(q.immutable())) Scaffold.note("gave up " + q.toShortString() + (temps.contains(q) ? " (temp)" : "") + ": " + why);
	}

	/**
	 * Stand spots a walk could not get to lately (tick of the failure): skipped for half a minute, as the walkway
	 * or the building may open a way later. Before this, the blocks queued after a failed walk were tried from
	 * wherever we were and given up ("cannot face it that way from here"): half the walks failed on tall plans.
	 */
	static final Map<BlockPos, Integer> badStands = new java.util.HashMap<>();
	/** Failed walks to a spot for this cell: after three, it is treated as having no spot (a tower, or given up). */
	static final Map<BlockPos, Integer> walkFails = new java.util.HashMap<>();

	/** Cells queued behind a walk that failed (see {@link StandWalk#batch}). */
	static final Set<BlockPos> softFail = new HashSet<>();

	static boolean badStand(BlockPos f) {
		Integer t = badStands.get(f);
		return t != null && ticks - t < 600;
	}

	/** A walk to a spot to work on {@code cell} from. */
	static final class StandWalk implements Action {
		final BlockPos stand, cell;
		/** What was queued to do from there: on a failed walk these get the retries of a passing reason. */
		final List<BlockPos> batch = new ArrayList<>();
		private final Action inner;

		StandWalk(BlockPos stand, BlockPos cell) {
			this.stand = stand.immutable();
			this.cell = cell.immutable();
			inner = new com.seolwoni.assistpath.walk.PathWalkAction(new com.seolwoni.assistpath.goals.GoalBlock(stand), com.seolwoni.assistpath.PathOptions.WALK,
					Action.SKIP);
		}

		private int ticks;
		private String why;

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			// A walk of a few blocks that takes this long is stuck (big pine: 47 walks of half a minute each, wedged
			// in one spot, never getting anywhere): given up sooner, the next plan tries something else.
			if (++ticks > 20 * 12) {
				why = "timed out at " + p.blockPosition().toShortString() + " around " + around(mc, p.blockPosition());
				Body.release(mc);
				return FAIL;
			}
			int r = inner.tick(mc, p);
			if (r == FAIL) why = inner.failReason() + " at " + String.format(java.util.Locale.ROOT, "%.2f %.2f %.2f", p.getX(), p.getY(), p.getZ())
					+ " ground " + p.onGround() + " around " + around(mc, p.blockPosition());
			return r;
		}

		@Override
		public int failMode() {
			return Action.SKIP;
		}

		@Override
		public String failReason() {
			return why == null ? inner.failReason() : why;
		}
	}

	/** What is next to the feet and the head (for the reports): N E S W, then above the head. */
	static String around(Minecraft mc, BlockPos f) {
		StringBuilder sb = new StringBuilder();
		for (int dy = 0; dy <= 1; dy++) {
			sb.append(dy == 0 ? "feet[" : " head[");
			for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
				BlockState st = mc.level.getBlockState(f.above(dy).relative(d));
				sb.append(st.isAir() ? "." : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath()).append(' ');
			}
			sb.append(']');
		}
		BlockState up = mc.level.getBlockState(f.above(2));
		sb.append(" up ").append(up.isAir() ? "." : net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(up.getBlock()).getPath());
		return sb.toString();
	}

	private static void onFail(Action a) {
		trace("failed " + a.getClass().getSimpleName() + (a instanceof Placement.PlaceAction pa0 ? " " + pa0.pos.toShortString() : "")
				+ (a instanceof Placement.BreakAction ba0 ? " " + ba0.pos.toShortString() : "") + ": " + a.failReason());
		if (a instanceof StandWalk sw) {
			// Could not get there: what was queued is still tried from where we are (some of it is in reach), but
			// a block that cannot be done from here is not given up for it: it gets retries, from another spot.
			// (0.12.3 dropped the whole batch instead: fewer blocks were put, 1309 against 1523.)
			badStands.put(sw.stand, ticks);
			walkFails.merge(sw.cell, 1, Integer::sum);
			BlockPos f = Minecraft.getInstance().player.blockPosition();
			if (f.equals(trappedAt)) trappedWalks++;
			else {
				trappedAt = f.immutable();
				trappedWalks = 1;
			}
			softFail.addAll(sw.batch);
			if (fails++ < 300) Scaffold.note("walk to " + sw.stand.toShortString() + " for " + sw.cell.toShortString() + " failed (" + sw.failReason() + "): another spot");
			return;
		}
		if (a instanceof Placement.PlaceAction pa) {
			if (pa.wrongWay) wrongWay++;
			// Passing reasons (we stood there, a little out of reach) get a few more goes, then it is given up.
			boolean soft = softFail.remove(pa.pos);
			if ((!pa.retry && !soft) || retries.merge(pa.pos, 1, Integer::sum) > 4) giveUp(pa.pos, "put failed: " + pa.failReason());
		}
		// A temporary block that would not come out: a few more goes (from another spot) before leaving it.
		// Queued behind a walk that did not get there: not a go at it (out of reach from where we were left). After
		// three failed walks for it, it is reached with a pillar instead (see queueNearest). Before, five such
		// "goes" from the wrong spot and the temporary block stayed for good (big pine: 67 left this way).
		if (a instanceof Placement.BreakAction sb && softFail.remove(sb.pos) && temps.contains(sb.pos)) return;
		if (a instanceof Placement.BreakAction ba && (!temps.contains(ba.pos) || retries.merge(ba.pos, 1, Integer::sum) > 4)) giveUp(ba.pos, "dig failed: " + ba.failReason() + " (feet " + Minecraft.getInstance().player.blockPosition().toShortString() + ")");
		if (fails++ < 300 && !(a instanceof Placement.PlaceAction) && !(a instanceof Placement.BreakAction))
			Scaffold.note("failed: " + a.getClass().getSimpleName() + (a.failReason() == null || a.failReason().isEmpty() ? "" : " (" + a.failReason() + ")"));
		// A tower that would not go up from this spot: another spot next time.
		if (a instanceof Help.PillarAction pa) Scaffold.badSpot(pa.base);
		// A failed walk: the next plan starts over from where we are.
	}

	private static final Map<BlockPos, Integer> escapeTries = new java.util.HashMap<>();
	private static BlockPos lastFailAt;
	private static int failsHere;
	public static int escapes;

	public static void tick(Minecraft mc) {
		if (!running) return;
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null || mc.gameMode == null) {
			stop(null);
			return;
		}
		if (mc.isPaused()) return;
		if (p.isDeadOrDying()) {
			stop(com.seolwoni.assistcore.i18n.Tr.t("죽었어요"));
			return;
		}
		ticks++;
		Human.pace = 1;
		if (dropTicks > 0 && --dropTicks == 0) com.seolwoni.assistpath.PathSettings.get().maxFallHeightNoWater.value = 3;

		// Long without putting or digging anything: note what it is busy with (for the tests' reports).
		if (placed + broken + temps.size() + filled != lastWork) {
			lastWork = placed + broken + temps.size() + filled;
			lastWorkTick = ticks;
		} else if (ticks - lastWorkTick > 0 && (ticks - lastWorkTick) % 1200 == 0) {
			Scaffold.note("no progress for " + (ticks - lastWorkTick) / 20 + " s at " + p.blockPosition().toShortString() + ": " + status
					+ (lastTarget == null ? "" : " (working on " + lastTarget.toShortString() + ")"));
			// A minute with nothing put or dug: the block we keep going for is given up and the plan starts over.
			// (Big pine: 15 minutes in one spot "putting a temporary block to build against", every step finishing
			// without anything changing, until the time ran out with 226 blocks to go.)
			for (String r : recent) Scaffold.note("  recent: " + r);
			if (lastTarget != null && !gaveUp.contains(lastTarget)) {
				giveUp(lastTarget, "no progress for " + (ticks - lastWorkTick) / 20 + " s");
				queue.clear();
				Body.release(mc);
				lastTarget = null;
			}
		}
		edgeCrouch(mc, p);
		// A timelapse picture this tick: the view is in the sky (what the crosshair sees is not ours), so wait.
		if (Timelapse.tick(mc)) return;
		if (mc.getCameraEntity() != p) {
			// The view was left elsewhere: back on the character (digging and putting go by what it looks at).
			Scaffold.note("view was not on the character: put back");
			mc.setCameraEntity(p);
		}
		if (queue.tick(mc, p)) return;
		plan(mc, p);
	}

	private static boolean crouching;

	/**
	 * Up on a walkway or a tower: crouch while standing still next to a drop of three or more, so a slip or a knock
	 * cannot take us over the edge (walking is not crouched: it would stop at every step down).
	 */
	private static void edgeCrouch(Minecraft mc, LocalPlayer p) {
		boolean walking = mc.options.keyUp.isDown() || mc.options.keyDown.isDown() || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();
		boolean edge = false;
		if (!walking && p.onGround()) {
			BlockPos f = p.blockPosition();
			for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
				BlockPos n = f.relative(d);
				if (!mc.level.getBlockState(n).getCollisionShape(mc.level, n).isEmpty()) continue;
				int drop = 0;
				while (drop < 4 && mc.level.getBlockState(n.below(drop + 1)).getCollisionShape(mc.level, n.below(drop + 1)).isEmpty()) drop++;
				if (drop >= 3) {
					edge = true;
					break;
				}
			}
		}
		if (edge) {
			mc.options.keyShift.setDown(true);
			crouching = true;
		} else if (crouching) {
			mc.options.keyShift.setDown(false);
			crouching = false;
		}
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

	/** Where the last failed walks started, and how many in a row (see {@link #unstuck}). */
	private static BlockPos trappedAt;
	private static int trappedWalks, dropTicks;

	/**
	 * Up on a tower or a piece of walkway with a drop on every side and no walk from here working: come down the way
	 * a player does. Our own block under the feet is dug out (a block at a time); otherwise one jump down of up to
	 * five (two hearts at most), only with health to spare. (Big pine, 0.12.13: 100 walks "no way to get there"
	 * from such spots, open air all round the feet.) True if something was done.
	 */
	private static boolean unstuck(Minecraft mc, LocalPlayer p) {
		BlockPos f = p.blockPosition();
		if (trappedWalks < 3 || !f.equals(trappedAt) || !p.onGround()) return false;
		if (Reach.walkable(mc, p).size() > 4) return false;
		trappedWalks = 0;
		BlockPos under = f.below();
		// Not the scaffolding's own tower or walkway: the band is built from up there (crane, 0.12.14: digging its
		// towers down left the arm out of reach, 73 blocks instead of about 100).
		if (temps.contains(under) && !gaveUp.contains(under) && !Scaffold.isScaffold(under)) {
			trace("trapped at " + f.toShortString() + ": digging out our block under the feet");
			Scaffold.note("trapped at " + f.toShortString() + " (" + around(mc, f) + "): coming down, our block under the feet");
			status = com.seolwoni.assistcore.i18n.Tr.t("기둥에서 내려오는 중");
			queue.add(new Placement.BreakAction(under));
			return true;
		}
		if (p.getHealth() >= 16) {
			trace("trapped at " + f.toShortString() + ": a jump down allowed for a while");
			Scaffold.note("trapped at " + f.toShortString() + " (" + around(mc, f) + "): jumping down allowed (up to 5)");
			com.seolwoni.assistpath.PathSettings.get().maxFallHeightNoWater.value = 5;
			dropTicks = 20 * 15;
			badStands.clear();
			return false;
		}
		return false;
	}

	private static void plan(Minecraft mc, LocalPlayer p) {
		if (unstuck(mc, p)) return;
		// 1) Clear the box: whatever stands where the plan has air (top down, so nothing is left hanging).
		List<BlockPos> clear = new ArrayList<>();
		for (int y = plan.sizeY - 1; y >= 0 && clear.isEmpty(); y--) {
			for (int z = 0; z < plan.sizeZ; z++) {
				for (int x = 0; x < plan.sizeX; x++) {
					int c = plan.cells[plan.index(x, y, z)];
					BlockPos w = world(x, y, z);
					if (gaveUp.contains(w) || temps.contains(w)) continue;
					BlockState now = mc.level.getBlockState(w);
					BlockState t = states[c];
					boolean wantAir = t == null || t.isAir() || !plainState[c];
					if (wantAir && !now.isAir() && now.getFluidState().isEmpty() && !(t != null && !t.isAir() && Placement.matches(now, t))) clear.add(w.immutable());
				}
			}
		}
		if (!clear.isEmpty()) {
			status = com.seolwoni.assistcore.i18n.Tr.t("자리 정리하는 중 (") + clear.size() + ")";
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
			status = com.seolwoni.assistcore.i18n.Tr.t("바닥 아래 구멍 메우는 중 (") + holes.size() + ")";
			queueNearest(mc, p, holes, true, 6);
			return;
		}
		// 3) Build, from the bottom up: the lowest layer with blocks still to put. Those with something to put them
		// against go first; when none of that layer has, a temporary block (or a pillar to reach it) helps.
		skipped = 0;
		int remaining = 0;
		List<BlockPos> ready = null, stuck = null;
		int layer = -1;
		for (int y = 0; y < plan.sizeY; y++) {
			for (int z = 0; z < plan.sizeZ; z++) {
				for (int x = 0; x < plan.sizeX; x++) {
					int c = plan.cells[plan.index(x, y, z)];
					BlockState t = states[c];
					if (planAir(t)) continue;
					if (!plainState[c]) {
						skipped++;
						continue;
					}
					if (Placement.secondHalf(t)) continue; // comes with its first half
					BlockPos w = world(x, y, z);
					if (gaveUp.contains(w)) continue;
					BlockState now = mc.level.getBlockState(w);
					if (Placement.matches(now, t)) continue;
					remaining++;
					if (layer == -1) layer = y;
					if (y != layer) continue;
					if (Placement.placedRight(now, t) || Placement.hasSupport(w)) {
						if (ready == null) ready = new ArrayList<>();
						ready.add(w.immutable());
					} else {
						if (stuck == null) stuck = new ArrayList<>();
						stuck.add(w.immutable());
					}
				}
			}
		}
		if (layer >= 0) {
			// Blocks waiting for an item the bag has none of: fetch it from the material chests.
			if (Supply.maybeTrip(mc, p)) return;
			// Running low on filler blocks: pick up the ones dug out earlier first.
			if (InvBase.count(p, Placement::filler) < 32 && pickUp(mc, p)) return;
			// Up high: a walkway to stand on first (scaffolding), then the band is built from it.
			if (Scaffold.work(mc, p, origin.getY() + layer)) return;
			status = com.seolwoni.assistcore.i18n.Tr.t("짓는 중: ") + (layer + 1) + "/" + plan.sizeY + com.seolwoni.assistcore.i18n.Tr.t("층 (남은 블록 ") + remaining + ")";
			if (ready != null) {
				queueNearest(mc, p, ready, false, 8);
				return;
			}
			Vec3 me = p.position();
			stuck.sort((a, b) -> Double.compare(me.distanceToSqr(Vec3.atCenterOf(a)), me.distanceToSqr(Vec3.atCenterOf(b))));
			BlockPos s0 = stuck.get(0);
			if (!help(mc, p, s0, target(s0))) giveUp(s0, "stuck, no help");
			return;
		}
		// 4) Take the temporary blocks out again: the scaffolding band by band from the top, then the rest (top down).
		if (Scaffold.teardown(mc, p)) return;
		List<BlockPos> left = new ArrayList<>();
		for (BlockPos q : temps) {
			BlockState now = mc.level.getBlockState(q);
			if (!now.isAir() && !gaveUp.contains(q)) left.add(q);
		}
		if (!left.isEmpty()) {
			int top = Integer.MIN_VALUE;
			for (BlockPos q : left) top = Math.max(top, q.getY());
			final int ty = top;
			List<BlockPos> highest = new ArrayList<>();
			for (BlockPos q : left) if (q.getY() == ty) highest.add(q);
			status = com.seolwoni.assistcore.i18n.Tr.t("받침 블록 치우는 중 (") + left.size() + ")";
			queueNearest(mc, p, highest, false, 4);
			return;
		}
		// 5) The temporary blocks dropped as items when they were dug out: picked up again.
		if (pickUp(mc, p)) return;
		finish(mc);
	}

	private static void finish(Minecraft mc) {
		int left = 0;
		for (BlockPos g : gaveUp) {
			BlockState t = target(g);
			if (t != null && !t.isAir()) left++;
		}
		String why = com.seolwoni.assistcore.i18n.Tr.t("다 지었어요 (놓은 블록 ") + placed + com.seolwoni.assistcore.i18n.Tr.t(", 치운 블록 ") + broken + (filled > 0 ? com.seolwoni.assistcore.i18n.Tr.t(", 메운 구멍 ") + filled : "") + (skipped > 0 ? com.seolwoni.assistcore.i18n.Tr.t(", 다음 단계로 남긴 블록 ") + skipped : "")
				+ (left > 0 ? com.seolwoni.assistcore.i18n.Tr.t(", 못 놓은 블록 ") + left : "") + (wrongWay > 0 ? com.seolwoni.assistcore.i18n.Tr.t(", 방향이 틀리게 놓인 블록 ") + wrongWay : "") + (temps.isEmpty() ? "" : com.seolwoni.assistcore.i18n.Tr.t(", 쓴 받침 블록 ") + temps.size()) + (missing.isEmpty() ? "" : com.seolwoni.assistcore.i18n.Tr.t(", 모자란 재료 ") + missing.size() + com.seolwoni.assistcore.i18n.Tr.t("가지")) + ")";
		stop(why);
	}

	/** A block of the plan still to put, as this stage puts it (not given up, not the top half of a door...). */
	static boolean wanted(Minecraft mc, BlockPos w) {
		BlockState t = target(w);
		if (t == null || t.isAir() || gaveUp.contains(w)) return false;
		int c = plan.cells[plan.index(w.getX() - origin.getX(), w.getY() - origin.getY(), w.getZ() - origin.getZ())];
		if (!plainState[c] || Placement.secondHalf(t)) return false;
		return !Placement.matches(mc.level.getBlockState(w), t);
	}

	/** Temporary blocks to dig out, nearest first (for the scaffolding's take-down). */
	static void clearSome(Minecraft mc, LocalPlayer p, List<BlockPos> cells) {
		queueNearest(mc, p, new ArrayList<>(cells), false, 4);
	}

	private static final Map<Integer, Integer> pickTries = new java.util.HashMap<>();

	/**
	 * Filler blocks lying about near the plan (dropped when temporary blocks were dug out): walk over them to pick them
	 * up, as a player does. True if a walk was queued.
	 */
	static boolean pickUp(Minecraft mc, LocalPlayer p) {
		net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(origin.getX() - 6, origin.getY() - 3, origin.getZ() - 6, origin.getX() + plan.sizeX + 6,
				origin.getY() + plan.sizeY + 4, origin.getZ() + plan.sizeZ + 6);
		net.minecraft.world.entity.item.ItemEntity best = null;
		double bestD = Double.MAX_VALUE;
		for (net.minecraft.world.entity.item.ItemEntity ie : mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box)) {
			if (!Placement.filler(ie.getItem()) || !ie.onGround() || pickTries.getOrDefault(ie.getId(), 0) >= 2) continue;
			double d = ie.distanceTo(p);
			if (d < bestD) {
				bestD = d;
				best = ie;
			}
		}
		if (best == null) return false;
		pickTries.merge(best.getId(), 1, Integer::sum);
		status = com.seolwoni.assistcore.i18n.Tr.t("떨어진 흙 줍는 중");
		queue.add(Help.walk(best.blockPosition()));
		return true;
	}

	/** From {@code eye}: is the block in reach, and (for a planned block) can it be put down facing the planned way? */
	static boolean doable(Minecraft mc, LocalPlayer p, BlockPos c, boolean fill, Vec3 eye) {
		if (Reach.dist(eye, c) > Reach.REACH) return false;
		if (fill) return true;
		BlockState t = target(c);
		if (t == null || temps.contains(c) || t.isAir() || !plainState[plan.cells[plan.index(c.getX() - origin.getX(), c.getY() - origin.getY(), c.getZ() - origin.getZ())]]) return true;
		BlockState now = mc.level.getBlockState(c);
		if (Placement.placedRight(now, t)) return true; // only clicks left
		if (!now.isAir() && !now.canBeReplaced()) return true; // taken out first; how to put it down is looked at then
		return Placement.solve(mc, p, c, t, eye) != null;
	}

	/**
	 * Queues work on the cells nearest to the player: walk to a spot in reach of the first one if needed, then every
	 * cell of the batch in reach from that spot. {@code fill}: put a filler block (dirt, cobblestone) instead of the plan's.
	 */
	private static void queueNearest(Minecraft mc, LocalPlayer p, List<BlockPos> cells, boolean fill, int max) {
		Vec3 me = p.position();
		cells.sort((a, b) -> Double.compare(me.distanceToSqr(Vec3.atCenterOf(a)), me.distanceToSqr(Vec3.atCenterOf(b))));
		BlockPos first = cells.get(0);
		lastTarget = first.immutable();
		// Standing where a block still has to go: step out of the way first.
		BlockPos feet = p.blockPosition();
		boolean inTheWay = Reach.pending(mc, feet) || Reach.pending(mc, feet.above());
		java.util.function.Predicate<Vec3> canDo = eye -> doable(mc, p, first, fill, eye);
		boolean here = Reach.inReach(p, first) && !inTheWay && canDo.test(p.getEyePosition());
		BlockPos stand = here || walkFails.getOrDefault(first, 0) >= 3 ? null : Reach.standFor(mc, p, first, canDo);
		if (!here && stand == null) {
			// A temporary block nobody can reach from the ground (high up): a pillar next to it, then it comes down.
			if (temps.contains(first) && helps.merge(first, 1, Integer::sum) <= 3) {
				if (descend(mc, p)) return;
				if (Help.pillar(mc, p, first)) return;
			}
			BlockState ft = target(first);
			boolean placeCell = !fill && !temps.contains(first) && ft != null && !ft.isAir()
					&& plainState[plan.cells[plan.index(first.getX() - origin.getX(), first.getY() - origin.getY(), first.getZ() - origin.getZ())]];
			if (placeCell && help(mc, p, first, ft)) return;
			giveUp(first, "no spot to stand" + (temps.contains(first) ? " (temporary)" : ""));
			idle++;
			return;
		}
		trace("nearest " + first.toShortString() + (fill ? " fill" : "") + (here ? " from here" : " stand " + (stand == null ? "none" : stand.toShortString())) + " of " + cells.size());
		Vec3 eye = stand == null ? p.getEyePosition() : new Vec3(stand.getX() + 0.5, stand.getY() + 1.62, stand.getZ() + 0.5);
		StandWalk sw = null;
		if (stand != null) {
			// Up on a pillar of our own with a drop too deep to walk down: come down first, taking the pillar out under
			// the feet a block at a time (the way a player digs down from a pillar).
			if (descend(mc, p)) return;
			sw = new StandWalk(stand, first);
			queue.add(sw);
		}
		int n = 0;
		for (BlockPos c : cells) {
			if (n >= max) break;
			if (Reach.dist(eye, c) > Reach.REACH) continue;
			if (stand != null && (c.equals(stand) || c.equals(stand.above()))) continue;
			if (!doable(mc, p, c, fill, eye)) continue;
			BlockState t = target(c);
			BlockState now = mc.level.getBlockState(c);
			boolean clearing = !fill && (temps.contains(c) || t == null || t.isAir() || !plainState[plan.cells[plan.index(c.getX() - origin.getX(), c.getY() - origin.getY(), c.getZ() - origin.getZ())]]);
			if (clearing) {
				queue.add(new Placement.BreakAction(c));
			} else {
				if (!fill && !now.isAir() && !now.canBeReplaced() && now.getFluidState().isEmpty()) queue.add(new Placement.BreakAction(c));
				queue.add(new Placement.PlaceAction(c, fill ? null : t));
			}
			if (sw != null) sw.batch.add(c.immutable());
			n++;
		}
		if (n == 0) giveUp(first, "nothing doable from " + (stand == null ? "here" : stand.toShortString()));
	}
}
