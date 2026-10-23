package com.seolwoni.buildassist.build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import com.seolwoni.assistcore.util.InvBase;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Scaffolding for tall builds, the way a player does it: the plan is built in bands four layers high. For each band
 * above the ground, a tower of temporary blocks just outside the plan's corner is raised to the band's walkway level,
 * and a walkway of temporary blocks is laid on that level out from the tower to the few spots from which every block
 * of the band is in reach (standing on blocks already built where there are some). The band is then built from the
 * walkway. Everything temporary comes out again at the end, top down.
 */
final class Scaffold {
	private Scaffold() {
	}

	/** Layers built from one walkway. */
	static final int BAND = 4;

	/** The band whose walkway is laid out (its level), and the cells still to put, in the order to put them. */
	private static int bandLevel = Integer.MIN_VALUE;
	private static boolean laidOut;
	private static final LinkedHashMap<BlockPos, BlockPos> todo = new LinkedHashMap<>(); // cell -> the walkway cell it is put from
	private static int fails;
	static int walkwayBlocks, bands, towers;

	/** What was put up for one band, to take down again in reverse: its towers and its walkway (cell, from). */
	static final class Band {
		final int level;
		final List<BlockPos[]> towers = new ArrayList<>(); // {base, top}
		final List<BlockPos[]> walkway = new ArrayList<>(); // {cell, from}

		Band(int level) {
			this.level = level;
		}
	}

	static final java.util.TreeMap<Integer, Band> built = new java.util.TreeMap<>();
	/** What the scaffolding did and why it gave up (for the tests' reports). */
	static final List<String> log = new ArrayList<>();
	private static BlockPos lastTowerSpot;

	static void note(String s) {
		if (log.size() < 80) log.add("t=" + Builder.ticks + " " + s);
	}
	private static int downFails, climbs;
	private static BlockPos downCell;

	private static Band band(int w) {
		return built.computeIfAbsent(w, Band::new);
	}

	static void reset() {
		bandLevel = Integer.MIN_VALUE;
		laidOut = false;
		built.clear();
		log.clear();
		lastTowerSpot = null;
		downFails = 0;
		climbs = 0;
		downCell = null;
		todo.clear();
		fails = 0;
		walkwayBlocks = 0;
		bands = 0;
		towers = 0;
	}

	/** The walkway level (the y of the blocks stood on) for the layer at {@code y}, or null when the ground does. */
	static Integer levelFor(int y) {
		int k = (y - Builder.origin.getY()) / BAND;
		if (k <= 0) return null;
		return Builder.origin.getY() - 1 + BAND * k;
	}

	/**
	 * Called with the lowest layer that still has blocks to put. True if scaffolding work was queued this round (the
	 * band's own blocks wait until the walkway stands).
	 */
	static boolean work(Minecraft mc, LocalPlayer p, int layerY) {
		Integer lv = levelFor(layerY);
		if (lv == null) return false;
		int w = lv;
		if (w != bandLevel) {
			bandLevel = w;
			laidOut = false;
			todo.clear();
			bands++;
			fails = 0;
		}
		if (fails > 8) return false; // the walkway would not go up: build what can be reached without it
		if (feet0(p).getY() > lv + 1 && !built.containsKey(lv)) return false; // up above it already (on what was built)
		if (!p.onGround()) return true;
		BlockPos feet = p.blockPosition();
		// 1) Up to the walkway level: a tower of temporary blocks (jump, block under the feet), outside the plan.
		if (feet.getY() < w + 1) {
			int need = w + 1 - feet.getY();
			if (InvBase.count(p, Placement::filler) < need) {
				Builder.missing.merge("흙이나 조약돌 (비계용)", need - InvBase.count(p, Placement::filler), Integer::sum);
				note("band " + w + ": not enough filler for a tower of " + need);
				fails = 99;
				return false;
			}
			BlockPos col = towerSpot(mc, p, feet, w);
			if (col == null) {
				note("band " + w + ": no spot for a tower near " + feet.toShortString());
				fails += 3;
				return false;
			}
			if (!col.equals(feet)) {
				// Walking there again (the last walk did not get us there) counts as a failed go.
				if (col.equals(lastTowerSpot)) {
					fails++;
					note("band " + w + ": walk to tower spot " + col.toShortString() + " again (from " + feet.toShortString() + ")");
				}
				lastTowerSpot = col;
				Builder.queue.add(Help.walk(col));
				Builder.status = "비계 탑 자리로 가는 중";
				return true;
			}
			towers++;
			note("band " + w + ": tower at " + feet.toShortString() + ", " + need + " high");
			band(w).towers.add(new BlockPos[] {feet.immutable(), feet.above(need - 1).immutable()});
			Builder.queue.add(new Help.PillarAction(feet, need));
			Builder.status = "비계 탑 쌓는 중 (" + need + "칸)";
			return true;
		}
		if (feet.getY() > w + 1) return false;
		// 2) Up there: lay out the walkway from where we stand, then put it a block at a time.
		if (!laidOut) {
			layout(mc, p, w, feet.below());
			laidOut = true;
			note("band " + w + ": walkway from " + feet.below().toShortString() + ", " + todo.size() + " blocks to put");
		}
		while (!todo.isEmpty()) {
			Map.Entry<BlockPos, BlockPos> e = todo.entrySet().iterator().next();
			BlockPos cell = e.getKey(), from = e.getValue();
			BlockState now = mc.level.getBlockState(cell);
			if (!now.isAir() && !now.canBeReplaced()) {
				todo.remove(cell);
				continue;
			}
			if (!Help.canTemp(mc, p, cell)) {
				todo.remove(cell);
				continue;
			}
			BlockPos stand = from.above();
			boolean here = Reach.inReach(p, cell) && feet.getY() == w + 1 && Placement.clickable(from);
			if (!here && !feet.equals(stand)) {
				Builder.queue.add(Help.walk(stand));
				Builder.status = "비계 발판으로 가는 중";
				if (++fails > 60) {
					note("band " + w + ": gave up the walkway, " + todo.size() + " left");
					todo.clear();
				}
				return true;
			}
			Placement.PlaceAction a = new Placement.PlaceAction(cell, null);
			a.temp = true;
			Builder.queue.add(a);
			todo.remove(cell);
			band(w).walkway.add(new BlockPos[] {cell.immutable(), from.immutable()});
			walkwayBlocks++;
			Builder.status = "비계 발판 놓는 중 (남은 " + todo.size() + ")";
			return true;
		}
		return false;
	}

	private static BlockPos feet0(LocalPlayer p) {
		return p.blockPosition();
	}

	/** Is the player standing (even partly) on {@code cell}? */
	private static boolean standsOn(LocalPlayer p, BlockPos cell) {
		return p.getBoundingBox().move(0, -0.2, 0).intersects(new net.minecraft.world.phys.AABB(cell));
	}

	private static boolean isScaffold(BlockPos q) {
		for (Band b : built.values()) {
			for (BlockPos[] t : b.towers) if (q.getX() == t[0].getX() && q.getZ() == t[0].getZ() && q.getY() >= t[0].getY() && q.getY() <= t[1].getY()) return true;
			for (BlockPos[] c : b.walkway) if (c[0].equals(q)) return true;
		}
		return false;
	}

	private static boolean standing(Minecraft mc, BlockPos q) {
		BlockState s = mc.level.getBlockState(q);
		return !s.isAir() && !s.canBeReplaced();
	}

	/**
	 * Taking the scaffolding down, the way it went up but backwards, top band first: the temporary blocks put from
	 * that band's walkway, then the walkway from its far ends back to the tower, then down the tower a block at a
	 * time (digging out the block under the feet) to the band below. True if work was queued.
	 */
	static boolean teardown(Minecraft mc, LocalPlayer p) {
		while (!built.isEmpty()) {
			Band b = built.lastEntry().getValue();
			int w = b.level;
			if (!p.onGround()) return true;
			BlockPos feet = p.blockPosition();
			// a) Other temporary blocks up at this band (rows put against, short pillars), while the walkway stands.
			List<BlockPos> high = new ArrayList<>();
			for (BlockPos q : Builder.temps) {
				if (q.getY() <= w || isScaffold(q) || Builder.gaveUp.contains(q)) continue;
				if (standing(mc, q)) high.add(q);
			}
			if (!high.isEmpty() && feet.getY() >= w + 1) {
				Builder.status = "받침 블록 치우는 중 (" + w + "층 위 " + high.size() + ")";
				Builder.clearSome(mc, p, high);
				return true;
			}
			// b) The walkway, last put first: stand on the block it was put from and dig it out.
			for (int i = b.walkway.size() - 1; i >= 0; i--) {
				BlockPos cell = b.walkway.get(i)[0], from = b.walkway.get(i)[1];
				if (!standing(mc, cell) || Builder.gaveUp.contains(cell)) {
					b.walkway.remove(i);
					continue;
				}
				if (feet.getY() < w + 1 && climbs++ < 3) {
					// Down below it (the build ended lower): back up, on a new tower next to the walkway.
					if (InvBase.count(p, Placement::filler) >= w + 1 - feet.getY()) {
						final Band fb = b;
						BlockPos col = towerSpot(mc, p, feet, w, q -> {
							for (BlockPos[] c : fb.walkway) if (c[0].distManhattan(q) == 1 && c[0].getY() == q.getY()) return true;
							return false;
						});
						if (col != null) {
							note("band " + w + ": climbing back up at " + col.toShortString() + " to take the walkway down");
							if (!col.equals(feet)) Builder.queue.add(Help.walk(col));
							int need = w + 1 - col.getY();
							b.towers.add(new BlockPos[] {col.immutable(), col.above(need - 1).immutable()});
							Builder.queue.add(new Help.PillarAction(col, need));
							Builder.status = "비계를 치우러 다시 올라가는 중";
							return true;
						}
					}
				}
				if (feet.getY() < w + 1) {
					// Not up there (came down another way): leave it to the ordinary clean-up from below.
					note("band " + w + ": not up on the walkway at take-down (feet " + feet.toShortString() + "), " + b.walkway.size() + " left to the rest");
					b.walkway.clear();
					break;
				}
				if (!cell.equals(downCell)) {
					downCell = cell;
					downFails = 0;
				}
				if (++downFails > 4) {
					Builder.gaveUp.add(cell);
					b.walkway.remove(i);
					continue;
				}
				// On the block it was put from, and not even partly over the one to dig out (or we fall with it).
				boolean here = feet.equals(from.above()) && !standsOn(p, cell);
				if (!here) {
					Builder.queue.add(Help.walk(from.above()));
					Builder.status = "비계 발판 걷는 중 (남은 " + (i + 1) + ")";
					return true;
				}
				Builder.queue.add(new Placement.BreakAction(cell));
				Builder.status = "비계 발판 걷는 중 (남은 " + (i + 1) + ")";
				return true;
			}
			// c) Down a tower of this band, digging out the block under the feet each time.
			for (BlockPos[] t : b.towers) {
				BlockPos base = t[0], top = t[1];
				if (feet.getY() <= base.getY()) continue;
				boolean onIt = feet.getX() == base.getX() && feet.getZ() == base.getZ();
				if (!onIt) {
					if (!standing(mc, top) || feet.getY() != top.getY() + 1) continue;
					if (!top.equals(downCell)) {
						downCell = top;
						downFails = 0;
					}
					if (++downFails > 4) continue;
					Builder.queue.add(Help.walk(top.above()));
					Builder.status = "비계 탑으로 가는 중";
					return true;
				}
				BlockPos under = feet.below();
				if (under.getY() >= base.getY() && standing(mc, under)) {
					Builder.queue.add(new Placement.BreakAction(under));
					Builder.status = "비계 탑 캐며 내려가는 중";
					return true;
				}
			}
			built.remove(w);
			climbs = 0;
		}
		return false;
	}

	/**
	 * Where to raise a tower from {@code feet}'s level up to the walkway {@code w}: the nearest spot to stand on whose
	 * whole column up to two above the walkway is free and not wanted by the plan (outside it, or air in it).
	 */
	private static BlockPos towerSpot(Minecraft mc, LocalPlayer p, BlockPos feet, int w) {
		return towerSpot(mc, p, feet, w, null);
	}

	/** {@code topOk}: the tower's top block (at the walkway level) must pass this, when given. */
	private static BlockPos towerSpot(Minecraft mc, LocalPlayer p, BlockPos feet, int w, java.util.function.Predicate<BlockPos> topOk) {
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (int dy = 0; dy >= -1; dy--) {
			for (int dx = -8; dx <= 8; dx++) {
				for (int dz = -8; dz <= 8; dz++) {
					BlockPos f = feet.offset(dx, dy, dz);
					if (!solid(mc, f.below())) continue;
					if (topOk != null && !topOk.test(new BlockPos(f.getX(), w, f.getZ()))) continue;
					boolean free = true;
					for (int y = f.getY(); y <= w + 3 && free; y++) {
						BlockPos q = new BlockPos(f.getX(), y, f.getZ());
						if (!mc.level.getBlockState(q).getCollisionShape(mc.level, q).isEmpty()) free = false;
						BlockState t = Builder.target(q);
						if (t != null && !t.isAir()) free = false;
					}
					if (!free) continue;
					// Next to the plan (its walkway starts beside the work), and near us.
					double d = Math.sqrt(dx * dx + dz * dz) + 2.0 * distToBox(f);
					if (d < bestD) {
						bestD = d;
						best = f.immutable();
					}
				}
			}
		}
		return best;
	}

	private static int distToBox(BlockPos f) {
		BlockPos o = Builder.origin;
		int dx = Math.max(0, Math.max(o.getX() - f.getX(), f.getX() - (o.getX() + Builder.plan.sizeX - 1)));
		int dz = Math.max(0, Math.max(o.getZ() - f.getZ(), f.getZ() - (o.getZ() + Builder.plan.sizeZ - 1)));
		return dx + dz;
	}

	private static boolean solid(Minecraft mc, BlockPos q) {
		BlockState s = mc.level.getBlockState(q);
		return s.isCollisionShapeFullBlock(mc.level, q) && s.getFluidState().isEmpty();
	}

	/** Room for a player on top of {@code c}: the two cells above free now and not waiting for a block of the plan. */
	private static boolean headroom(Minecraft mc, BlockPos c) {
		for (int k = 1; k <= 2; k++) {
			BlockPos q = c.above(k);
			if (!mc.level.getBlockState(q).getCollisionShape(mc.level, q).isEmpty()) return false;
			BlockState t = Builder.target(q);
			if (t != null && !t.isAir()) return false;
		}
		return true;
	}

	/** Lays out the walkway on level {@code w}: the spots to stand on, and the cells that join them to the tower. */
	private static void layout(Minecraft mc, LocalPlayer p, int w, BlockPos top) {
		todo.clear();
		var plan = Builder.plan;
		BlockPos o = Builder.origin;
		// Two cells round the plan: a block sticking out at the edge of a band's top layer is in reach from there.
		int x0 = Math.min(o.getX() - 2, top.getX()), z0 = Math.min(o.getZ() - 2, top.getZ()), x1 = Math.max(o.getX() + plan.sizeX + 1, top.getX()),
				z1 = Math.max(o.getZ() + plan.sizeZ + 1, top.getZ());
		// Blocks of this band still to put.
		List<BlockPos> pending = new ArrayList<>();
		for (int y = w + 1; y <= w + BAND && y < o.getY() + plan.sizeY; y++) {
			for (int z = o.getZ(); z < o.getZ() + plan.sizeZ; z++) {
				for (int x = o.getX(); x < o.getX() + plan.sizeX; x++) {
					BlockPos c = new BlockPos(x, y, z);
					if (Reach.pending(mc, c)) pending.add(c);
				}
			}
		}
		// Cells of the level a player could stand on (once a temporary block is there, where none is).
		Map<BlockPos, Boolean> walk = new HashMap<>(); // cell -> already solid
		for (int z = z0; z <= z1; z++) {
			for (int x = x0; x <= x1; x++) {
				BlockPos c = new BlockPos(x, w, z);
				if (!headroom(mc, c)) continue;
				if (solid(mc, c)) walk.put(c, true);
				else if (Help.canTemp(mc, p, c)) walk.put(c, false);
			}
		}
		walk.put(top, true); // where we stand (the tower's top)
		// Spots: greedily the one reaching the most blocks not yet covered.
		Set<BlockPos> left = new HashSet<>(pending);
		List<BlockPos> spots = new ArrayList<>();
		spots.add(top);
		cover(top, left);
		while (!left.isEmpty()) {
			BlockPos best = null;
			int bestN = 0;
			for (BlockPos c : walk.keySet()) {
				int n = 0;
				Vec3 eye = new Vec3(c.getX() + 0.5, w + 1 + 1.62, c.getZ() + 0.5);
				for (BlockPos q : left) if (Reach.dist(eye, q) <= Reach.REACH - 0.2) n++;
				// Standing on blocks already there is cheaper than putting new ones.
				if (n > bestN || (n == bestN && n > 0 && walk.get(c) && best != null && !walk.get(best))) {
					bestN = n;
					best = c;
				}
			}
			if (best == null) break;
			spots.add(best);
			cover(best, left);
		}
		// Join the spots to the tower: cheapest paths over the level (existing blocks cost little, new ones more).
		Map<BlockPos, BlockPos> parent = new HashMap<>();
		Map<BlockPos, Double> cost = new HashMap<>();
		PriorityQueue<Object[]> open = new PriorityQueue<>((a, b) -> Double.compare((double) a[1], (double) b[1]));
		cost.put(top, 0.0);
		open.add(new Object[] {top, 0.0});
		while (!open.isEmpty()) {
			Object[] e = open.poll();
			BlockPos c = (BlockPos) e[0];
			double d = (double) e[1];
			if (d > cost.get(c)) continue;
			for (Direction dir : Direction.Plane.HORIZONTAL) {
				BlockPos n = c.relative(dir);
				Boolean there = walk.get(n);
				if (there == null) continue;
				double nd = d + (there ? 1 : 3);
				if (nd < cost.getOrDefault(n, Double.MAX_VALUE)) {
					cost.put(n, nd);
					parent.put(n, c);
					open.add(new Object[] {n, nd});
				}
			}
		}
		// The cells to put, from the tower outwards (each next to one put before it or already standing).
		List<List<BlockPos>> paths = new ArrayList<>();
		for (BlockPos s : spots) {
			if (!cost.containsKey(s)) continue;
			List<BlockPos> path = new ArrayList<>();
			for (BlockPos c = s; c != null; c = parent.get(c)) path.add(0, c);
			paths.add(path);
		}
		paths.sort((a, b) -> Integer.compare(a.size(), b.size()));
		for (List<BlockPos> path : paths) {
			for (int i = 1; i < path.size(); i++) {
				BlockPos c = path.get(i);
				if (c.equals(top) || walk.get(c) || todo.containsKey(c)) continue;
				todo.put(c, path.get(i - 1));
			}
		}
		// Every cell of it is a temporary block, taken out at the end.
		for (BlockPos c : todo.keySet()) Builder.temps.add(c.immutable());
	}

	private static void cover(BlockPos spot, Set<BlockPos> left) {
		Vec3 eye = new Vec3(spot.getX() + 0.5, spot.getY() + 1 + 1.62, spot.getZ() + 0.5);
		left.removeIf(q -> Reach.dist(eye, q) <= Reach.REACH - 0.2);
	}
}
