package com.seolwoni.buildassist.build;

import java.util.ArrayList;
import java.util.List;

import com.seolwoni.assistcore.util.InvBase;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Building a tall plan the way a player does on scaffolding, band by band (four layers each):
 * <ol>
 * <li>a tower of temporary blocks at a corner just outside the plan, up to the band's walkway level;</li>
 * <li>a ring of temporary blocks round the plan on that level (one cell out), laid from the tower top;</li>
 * <li>laps round the ring: at every other cell, every block of the band in reach that can be put down now is put
 * down (lower ones first, so the ones above have something to go against); laps until a lap puts nothing;</li>
 * <li>the ring taken up again from its far end back to the tower, and the tower raised to the next band.</li>
 * </ol>
 * Only one ring stands at a time, and the towers stack into one column, dug down at the end. What the ring cannot
 * reach (deep inside a wide plan) is left to the block-by-block way, standing on what is built.
 */
final class Ring {
	private Ring() {
	}

	static final int BAND = 4;

	/** The band whose ring stands (its walkway level), its cells in order from the tower top round. */
	private static int level = Integer.MIN_VALUE;
	private static final List<BlockPos> ring = new ArrayList<>();
	private static int laid; // cells of the ring put so far (ring.get(0) is the tower top)
	private static int stop, lapPlaced, laps;
	private static boolean lapsDone;
	private static int fails;
	static int bands, rings, ringBlocks, lapsTotal, placedFromRing;
	private static final java.util.Set<Integer> givenUp = new java.util.HashSet<>();

	static void reset() {
		level = Integer.MIN_VALUE;
		ring.clear();
		laid = 0;
		stop = 0;
		lapPlaced = 0;
		laps = 0;
		lapsDone = false;
		fails = 0;
		bands = rings = ringBlocks = lapsTotal = placedFromRing = 0;
		givenUp.clear();
	}

	/** The walkway level for the layer at {@code y}, or null when the ground is enough. */
	static Integer levelFor(int y) {
		int k = (y - Builder.origin.getY()) / BAND;
		if (k <= 0) return null;
		return Builder.origin.getY() - 1 + BAND * k;
	}

	private static int x0() {
		return Builder.origin.getX() - 1;
	}

	private static int z0() {
		return Builder.origin.getZ() - 1;
	}

	private static int x1() {
		return Builder.origin.getX() + Builder.plan.sizeX;
	}

	private static int z1() {
		return Builder.origin.getZ() + Builder.plan.sizeZ;
	}

	/** On the ring's rectangle (one cell outside the plan, all round)? */
	static boolean onRing(int x, int z) {
		boolean inX = x >= x0() && x <= x1(), inZ = z >= z0() && z <= z1();
		return inX && inZ && (x == x0() || x == x1() || z == z0() || z == z1());
	}

	/** The ring's cells on level {@code w}, in order round it, starting at {@code (sx, sz)}. */
	private static List<BlockPos> cycle(int w, int sx, int sz) {
		List<BlockPos> all = new ArrayList<>();
		for (int x = x0(); x <= x1(); x++) all.add(new BlockPos(x, w, z0()));
		for (int z = z0() + 1; z <= z1(); z++) all.add(new BlockPos(x1(), w, z));
		for (int x = x1() - 1; x >= x0(); x--) all.add(new BlockPos(x, w, z1()));
		for (int z = z1() - 1; z > z0(); z--) all.add(new BlockPos(x0(), w, z));
		int at = 0;
		for (int i = 0; i < all.size(); i++) if (all.get(i).getX() == sx && all.get(i).getZ() == sz) at = i;
		List<BlockPos> out = new ArrayList<>();
		for (int i = 0; i < all.size(); i++) out.add(all.get((at + i) % all.size()));
		return out;
	}

	private static boolean standing(Minecraft mc, BlockPos q) {
		BlockState s = mc.level.getBlockState(q);
		return !s.isAir() && !s.canBeReplaced();
	}

	private static boolean solid(Minecraft mc, BlockPos q) {
		BlockState s = mc.level.getBlockState(q);
		return s.isCollisionShapeFullBlock(mc.level, q) && s.getFluidState().isEmpty();
	}

	private static boolean open(Minecraft mc, BlockPos q) {
		return mc.level.getBlockState(q).getCollisionShape(mc.level, q).isEmpty();
	}

	/** Blocks of the band (its four layers) still to put, as the block-by-block way would put them. */
	private static List<BlockPos> pending(Minecraft mc, int w) {
		List<BlockPos> out = new ArrayList<>();
		BlockPos o = Builder.origin;
		for (int y = w + 1; y <= w + BAND && y < o.getY() + Builder.plan.sizeY; y++) {
			for (int z = o.getZ(); z < o.getZ() + Builder.plan.sizeZ; z++) {
				for (int x = o.getX(); x < o.getX() + Builder.plan.sizeX; x++) {
					BlockPos c = new BlockPos(x, y, z);
					if (Builder.wanted(mc, c)) out.add(c);
				}
			}
		}
		return out;
	}

	/**
	 * Called with the lowest layer that has blocks still to put. True if this round's work was queued here; false when
	 * the block-by-block way should go on (the ground band, or what the ring could not reach).
	 */
	static boolean work(Minecraft mc, LocalPlayer p, int layerY) {
		Integer lv = levelFor(layerY);
		if (lv == null) return false;
		int w = lv;
		if (givenUp.contains(w)) return false;
		if (!p.onGround()) return true;
		BlockPos feet = p.blockPosition();

		// A new band: take the old ring up (when we are on it), then up the tower.
		if (w != level) {
			if (!ring.isEmpty() && level != Integer.MIN_VALUE && feet.getY() == level + 1) {
				if (takeUp(mc, p)) return true;
			}
			ring.clear();
			laid = 0;
			level = w;
			stop = 0;
			lapPlaced = 0;
			laps = 0;
			lapsDone = false;
			fails = 0;
			bands++;
		}
		if (lapsDone) return false;
		if (fails > 12) {
			Scaffold.note("ring " + w + ": given up (" + fails + " failures), block by block from here");
			givenUp.add(w);
			return false;
		}

		// 1) Up to the walkway level, on a column at a ring position.
		if (ring.isEmpty()) {
			if (feet.getY() < w + 1) {
				int need = w + 1 - feet.getY();
				if (InvBase.count(p, Placement::filler) < need + 8) {
					Builder.missing.merge("흙이나 조약돌 (비계용)", need + 8 - InvBase.count(p, Placement::filler), Integer::sum);
					givenUp.add(w);
					return false;
				}
				BlockPos col = towerSpot(mc, p, feet, w);
				if (col == null) {
					Scaffold.note("ring " + w + ": no column at the ring to climb from (feet " + feet.toShortString() + ")");
					fails += 4;
					return false;
				}
				if (!col.equals(feet)) {
					fails++;
					Builder.queue.add(Help.walk(col));
					Builder.status = "비계 탑 자리로 가는 중";
					return true;
				}
				Scaffold.note("ring " + w + ": tower at " + feet.toShortString() + ", " + need + " high");
				Builder.queue.add(new Help.PillarAction(feet, need));
				Builder.status = "비계 탑 쌓는 중 (" + need + "칸)";
				return true;
			}
			if (feet.getY() > w + 1 || !onRing(feet.getX(), feet.getZ())) {
				// Up on what is built, away from the ring: the block-by-block way for this band.
				Scaffold.note("ring " + w + ": not on a ring column (feet " + feet.toShortString() + "), block by block");
				givenUp.add(w);
				return false;
			}
			ring.addAll(cycle(w, feet.getX(), feet.getZ()));
			laid = 1;
			rings++;
			for (BlockPos c : ring) Builder.temps.add(c.immutable());
			Scaffold.note("ring " + w + ": laying " + (ring.size() - 1) + " cells from " + feet.below().toShortString());
		}

		// 2) Lay the ring, a cell at a time, each from the one before.
		while (laid < ring.size()) {
			BlockPos cell = ring.get(laid), from = ring.get(laid - 1);
			if (standing(mc, cell)) {
				laid++;
				continue;
			}
			if (!open(mc, cell.above()) || !open(mc, cell.above(2)) || !open(mc, cell)) {
				// Something in the way of the ring (a tree, a hill): the ring stops here.
				Scaffold.note("ring " + w + ": blocked at " + cell.toShortString() + ", " + laid + " of " + ring.size() + " cells");
				ring.subList(laid, ring.size()).clear();
				break;
			}
			if (!feet.equals(from.above())) {
				fails++;
				Builder.queue.add(Help.walk(from.above()));
				Builder.status = "비계 고리 놓는 중 (" + laid + "/" + ring.size() + ")";
				return true;
			}
			Placement.PlaceAction a = new Placement.PlaceAction(cell, null);
			a.temp = true;
			Builder.queue.add(a);
			laid++;
			ringBlocks++;
			fails = 0;
			Builder.status = "비계 고리 놓는 중 (" + laid + "/" + ring.size() + ")";
			return true;
		}

		// 3) Laps: at every other ring cell, everything in reach that can go down now, lowest first.
		List<BlockPos> todo = pending(mc, w);
		if (todo.isEmpty()) {
			lapsDone = true;
			return false;
		}
		int n = ring.size();
		for (int tries = 0; tries <= n; tries++) {
			if (stop >= n) {
				// A lap is round: if it put nothing, the ring has done what it can.
				laps++;
				lapsTotal++;
				if (lapPlaced == 0 || laps >= 6) {
					Scaffold.note("ring " + w + ": " + laps + " laps, " + todo.size() + " blocks left to the block-by-block way");
					lapsDone = true;
					return false;
				}
				lapPlaced = 0;
				stop = 0;
			}
			BlockPos at = ring.get(stop).above();
			Vec3 eye = new Vec3(at.getX() + 0.5, at.getY() + 1.62, at.getZ() + 0.5);
			List<BlockPos> here = new ArrayList<>();
			for (BlockPos c : todo) {
				if (Reach.dist(eye, c) > Reach.REACH || c.equals(at) || c.equals(at.above())) continue;
				BlockState t = Builder.target(c);
				if (Placement.solve(mc, p, c, t, eye) == null) continue;
				here.add(c);
			}
			if (here.isEmpty()) {
				stop += 2;
				continue;
			}
			here.sort((a, b) -> a.getY() != b.getY() ? Integer.compare(a.getY(), b.getY()) : Double.compare(Reach.dist(eye, a), Reach.dist(eye, b)));
			if (!feet.equals(at)) {
				Builder.queue.add(Help.walk(at));
				Builder.status = "비계 고리 위를 도는 중 (" + (laps + 1) + "바퀴)";
				return true;
			}
			int k = 0;
			for (BlockPos c : here) {
				if (k >= 6) break;
				BlockState now = mc.level.getBlockState(c);
				if (!now.isAir() && !now.canBeReplaced() && now.getFluidState().isEmpty()) Builder.queue.add(new Placement.BreakAction(c));
				Builder.queue.add(new Placement.PlaceAction(c, Builder.target(c)));
				k++;
			}
			lapPlaced += k;
			placedFromRing += k;
			Builder.status = "비계 고리에서 짓는 중 (" + (laps + 1) + "바퀴, 남은 " + todo.size() + ")";
			if (k == here.size()) stop += 2; // all from here: on to the next
			return true;
		}
		lapsDone = true;
		return false;
	}

	/** Takes the standing ring up, far end first, back to the tower top. True while doing so. */
	private static boolean takeUp(Minecraft mc, LocalPlayer p) {
		BlockPos feet = p.blockPosition();
		for (int i = ring.size() - 1; i >= 1; i--) {
			BlockPos cell = ring.get(i), from = ring.get(i - 1);
			if (!standing(mc, cell) || Builder.gaveUp.contains(cell)) continue;
			if (!standing(mc, from)) continue; // cannot stand there: left to the clean-up at the end
			if (!feet.equals(from.above())) {
				if (fails++ > 30) return false;
				Builder.queue.add(Help.walk(from.above()));
				Builder.status = "비계 고리 걷는 중";
				return true;
			}
			Builder.queue.add(new Placement.BreakAction(cell));
			Builder.status = "비계 고리 걷는 중";
			return true;
		}
		// Back on the tower top: the ring is up.
		if (!ring.isEmpty() && !feet.equals(ring.get(0).above()) && standing(mc, ring.get(0)) && fails++ <= 30) {
			Builder.queue.add(Help.walk(ring.get(0).above()));
			return true;
		}
		fails = 0;
		return false;
	}

	/**
	 * At the end: the last ring taken up, then down the tower column digging out the block under the feet, while it
	 * is a temporary block. True while doing so.
	 */
	static boolean finish(Minecraft mc, LocalPlayer p) {
		if (level == Integer.MIN_VALUE) return false;
		if (!p.onGround()) return true;
		BlockPos feet = p.blockPosition();
		if (!ring.isEmpty() && feet.getY() == level + 1) {
			if (takeUp(mc, p)) return true;
			ring.clear();
		}
		BlockPos under = feet.below();
		if (Builder.temps.contains(under) && standing(mc, under) && onRing(under.getX(), under.getZ()) && under.getY() >= Builder.origin.getY()) {
			Builder.queue.add(new Placement.BreakAction(under));
			Builder.status = "비계 탑 캐며 내려가는 중";
			return true;
		}
		level = Integer.MIN_VALUE;
		return false;
	}

	/** A column at a ring position near us to climb from: free from our feet to above the walkway, ground under it. */
	private static BlockPos towerSpot(Minecraft mc, LocalPlayer p, BlockPos feet, int w) {
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		java.util.Set<BlockPos> walk = Reach.walkable(mc, p);
		for (int dy = 1; dy >= -3; dy--) {
			for (int dx = -12; dx <= 12; dx++) {
				for (int dz = -12; dz <= 12; dz++) {
					BlockPos f = feet.offset(dx, dy, dz);
					if (!onRing(f.getX(), f.getZ())) continue;
					if (!f.equals(feet) && !walk.contains(f)) continue;
					if (!solid(mc, f.below())) continue;
					boolean free = true;
					for (int y = f.getY(); y <= w + 3 && free; y++) if (!open(mc, new BlockPos(f.getX(), y, f.getZ()))) free = false;
					if (!free) continue;
					double d = Math.sqrt(dx * dx + dz * dz) + Math.abs(dy);
					if (d < bestD) {
						bestD = d;
						best = f.immutable();
					}
				}
			}
		}
		return best;
	}
}
