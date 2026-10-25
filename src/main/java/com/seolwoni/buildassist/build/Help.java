package com.seolwoni.buildassist.build;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.seolwoni.assistcore.engine.Action;
import com.seolwoni.assistcore.util.Body;
import com.seolwoni.assistcore.util.Human;
import com.seolwoni.assistcore.util.InvBase;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What a player does when a block cannot simply be put down: a temporary block next to it to put it against (a log
 * lying sideways at the bottom, a part sticking out into the air), a short row of them out to it from what stands, or
 * a pillar to stand on to reach it up high. Every temporary block is taken out again at the end.
 */
final class Help {
	private Help() {
	}

	/** A cell where a temporary block may go: the plan has air there (or it is outside the plan), and it is free. */
	static boolean canTemp(Minecraft mc, LocalPlayer p, BlockPos q) {
		BlockState t = Builder.target(q);
		if (t != null && !t.isAir()) return false;
		BlockState now = mc.level.getBlockState(q);
		if (!(now.isAir() || now.canBeReplaced()) || !now.getFluidState().isEmpty()) return false;
		return !Reach.occupies(p, q);
	}

	private static boolean hasFiller(LocalPlayer p) {
		return InvBase.count(p, Placement::filler) > 0;
	}

	/**
	 * Queues help for {@code cell} (which cannot be put down now as planned). True if something was queued; the
	 * next planning round then builds the block itself.
	 */
	static boolean plan(Minecraft mc, LocalPlayer p, BlockPos cell, BlockState t) {
		if (!hasFiller(p)) return false;
		// 1) Up high: no spot on the ground reaches it. Make a pillar to stand on.
		BlockPos reachSpot = Reach.standFor(mc, p, cell, eye -> true);
		// A spot exists but we cannot walk to it (up in the branches of a tree): a pillar from where we can get
		// is the way up, as for no spot at all. (0.12.18: the small pine's top was still "stuck" 29-43 times a run
		// with a pillar tried once, because such an unreachable spot counted as a way.)
		boolean unreachable = reachSpot != null && !Reach.walkable(mc, p).contains(reachSpot);
		if ((reachSpot == null || unreachable) && !Reach.inReach(p, cell)) {
			if (pillar(mc, p, cell)) return true;
			if (reachSpot == null) return false;
		}

		// 2) One temporary block next to it makes it possible (the side a sideways log needs, or any side at all).
		for (Direction d : Direction.values()) {
			BlockPos n = cell.relative(d);
			if (!canTemp(mc, p, n) || !Placement.hasSupport(n)) continue;
			java.util.function.Predicate<Vec3> ok = eye -> Reach.dist(eye, n) <= Reach.REACH && Placement.solve(mc, p, cell, t, eye, n) != null;
			boolean here = ok.test(p.getEyePosition()) && !Reach.occupies(p, cell) && !Reach.occupies(p, n);
			BlockPos stand = here ? null : Reach.standFor(mc, p, cell, ok);
			if (!here && stand == null) continue;
			if (stand != null) {
				Builder.StandWalk sw = new Builder.StandWalk(stand, cell);
				sw.batch.add(n.immutable());
				sw.batch.add(cell.immutable());
				Builder.queue.add(sw);
			}
			Placement.PlaceAction tmp = new Placement.PlaceAction(n, null);
			tmp.temp = true;
			Builder.queue.add(tmp);
			Builder.queue.add(new Placement.PlaceAction(cell, t));
			Builder.status = com.seolwoni.assistcore.i18n.Tr.t("받침 블록을 놓고 짓는 중");
			return true;
		}

		// 3) Nothing to build out from next to it: a short row of temporary blocks from the nearest thing that stands.
		List<BlockPos> chain = chain(mc, p, cell, t);
		if (!chain.isEmpty()) {
			BlockPos first = chain.get(0);
			BlockPos stand = Reach.inReach(p, first) ? null : Reach.standFor(mc, p, first, eye -> true);
			if (stand == null && !Reach.inReach(p, first)) return pillar(mc, p, first);
			if (stand != null) Builder.queue.add(new Builder.StandWalk(stand, cell));
			for (BlockPos c : chain) {
				Placement.PlaceAction tmp = new Placement.PlaceAction(c, null);
				tmp.temp = true;
				Builder.queue.add(tmp);
			}
			Builder.status = com.seolwoni.assistcore.i18n.Tr.t("받침 블록 이어 놓는 중");
			return true;
		}
		return false;
	}

	static Action walk(BlockPos stand) {
		return new com.seolwoni.assistpath.walk.PathWalkAction(new com.seolwoni.assistpath.goals.GoalBlock(stand), com.seolwoni.assistpath.PathOptions.WALK,
				Action.SKIP);
	}

	/**
	 * Cells for temporary blocks, from one next to something standing out to a neighbour of {@code cell} (at most 5).
	 * The row ends on a side the block can be put against facing the planned way (a log lying east-west needs its
	 * east or west side, not the one below): a row to any other side only leaves temporary blocks behind.
	 */
	private static List<BlockPos> chain(Minecraft mc, LocalPlayer p, BlockPos cell, BlockState t) {
		Map<BlockPos, BlockPos> parent = new HashMap<>();
		Map<BlockPos, Integer> depth = new HashMap<>();
		Deque<BlockPos> open = new ArrayDeque<>();
		for (Direction d : Direction.values()) {
			BlockPos n = cell.relative(d);
			if (!canTemp(mc, p, n)) continue;
			if (!Placement.worksAgainst(mc, p, cell, t, n)) continue;
			open.add(n);
			depth.put(n, 1);
			parent.put(n, null);
		}
		while (!open.isEmpty()) {
			BlockPos c = open.poll();
			if (Placement.hasSupport(c)) {
				List<BlockPos> out = new ArrayList<>();
				for (BlockPos x = c; x != null; x = parent.get(x)) out.add(x);
				return out; // from the supported end towards the cell
			}
			int dep = depth.get(c);
			if (dep >= 5) continue;
			// Down first: rows of temporary blocks are best built up from the ground.
			for (Direction d : new Direction[] {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP}) {
				BlockPos q = c.relative(d);
				if (depth.containsKey(q) || q.equals(cell) || !canTemp(mc, p, q)) continue;
				depth.put(q, dep + 1);
				parent.put(q, c);
				open.add(q);
			}
		}
		return List.of();
	}

	/**
	 * A pillar of temporary blocks next to {@code cell}, on the ground, high enough to reach it from the top. Picks a
	 * column the plan leaves empty, walks there, and jumps and puts a block under its feet until high enough.
	 */
	static boolean pillar(Minecraft mc, LocalPlayer p, BlockPos cell) {
		BlockPos best = null;
		int bestHeight = 0;
		double bestCost = Double.MAX_VALUE;
		// Only columns we can walk to (or the one we stand in). Before, the column was picked on the ground far below
		// or across a gap, the walk there failed and the tower was never started ("not on the tower spot"): the
		// small pine's top was given up that way, every block of it, from a pocket in its branches at height 108.
		java.util.Set<BlockPos> reach = Reach.walkable(mc, p);
		BlockPos here = p.onGround() ? p.blockPosition() : null;
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				if (dx * dx + dz * dz > 10 || (dx == 0 && dz == 0)) continue;
				// The ground under that column, below the cell.
				BlockPos top = null;
				for (int y = cell.getY() - 2; y >= cell.getY() - 48; y--) {
					BlockPos q = new BlockPos(cell.getX() + dx, y, cell.getZ() + dz);
					BlockState s = mc.level.getBlockState(q);
					if (!s.isAir() && !s.canBeReplaced()) {
						if (s.isCollisionShapeFullBlock(mc.level, q) && s.getFluidState().isEmpty()) top = q.above();
						break;
					}
				}
				if (top == null) continue;
				// Standing above the ground there (on a walkway, in the branches): the tower starts where we can stand.
				if (!top.equals(here) && !reach.contains(top)) {
					BlockPos alt = null;
					for (BlockPos r : reach) if (r.getX() == top.getX() && r.getZ() == top.getZ() && r.getY() < cell.getY() - 1 && (alt == null || r.getY() > alt.getY())) alt = r;
					if (alt == null) continue;
					top = alt;
				}
				int h = cell.getY() - top.getY() - 1;
				if (h < 1) continue;
				// How far up the column is free (not a block of the plan): the tower goes as high as that allows with
				// room for the head, and is good if the block is in reach from its top. (Before, the whole column up to
				// the block had to be free: inside a tree's crown there never was such a column, and the small pine's
				// top was given up from a pocket in its branches.)
				int freeUp = 0;
				while (freeUp <= h + 2 && (canTemp(mc, p, top.above(freeUp)) || Reach.occupies(p, top.above(freeUp)))) freeUp++;
				if (freeUp < h + 3) {
					h = freeUp - 2;
					if (h < 1) continue;
					Vec3 eye = new Vec3(top.getX() + 0.5, top.getY() + h + 1.62, top.getZ() + 0.5);
					if (Reach.dist(eye, cell) > Reach.REACH - 0.1) continue;
				}
				double cost = Math.abs(dx) + Math.abs(dz) + p.position().distanceTo(Vec3.atBottomCenterOf(top)) * 0.2;
				if (cost < bestCost) {
					bestCost = cost;
					best = top.immutable();
					bestHeight = h;
				}
			}
		}
		if (best == null) return false;
		if (Builder.descend(mc, p)) return true;
		if (InvBase.count(p, Placement::filler) < bestHeight) {
			Builder.missing.merge(com.seolwoni.assistcore.i18n.Tr.t("흙이나 조약돌 (기둥용)"), bestHeight - InvBase.count(p, Placement::filler), Integer::sum);
			return false;
		}
		if (!p.blockPosition().equals(best)) Builder.queue.add(walk(best));
		Builder.queue.add(new PillarAction(best, bestHeight));
		Builder.status = com.seolwoni.assistcore.i18n.Tr.t("높은 곳에 닿게 기둥 쌓는 중 (") + bestHeight + com.seolwoni.assistcore.i18n.Tr.t("칸)");
		return true;
	}

	/** Jump and put a filler block under the feet, until standing {@code height} blocks above {@code base}. */
	static final class PillarAction implements Action {
		final BlockPos base;
		private String why = "";
		private final int height;
		private int placed, stage, slot, ticks, wait;

		PillarAction(BlockPos base, int height) {
			this.base = base;
			this.height = height;
		}

		@Override
		public int failMode() {
			return SKIP;
		}

		@Override
		public String failReason() {
			return why;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			if (++ticks > 40 * height + 200) {
				mc.options.keyJump.setDown(false);
				BlockPos f = p.blockPosition();
				why = "timed out at stage " + stage + ", placed " + placed + "/" + height + ", feet " + f.toShortString() + ", y " + String.format(java.util.Locale.ROOT, "%.2f", p.getY())
						+ ", on ground " + p.onGround() + ", crouching " + p.isShiftKeyDown() + ", pitch " + Math.round(p.getXRot()) + ", above head "
						+ mc.level.getBlockState(f.above(2)).getBlock().getName().getString() + ", in hand " + p.getMainHandItem().getHoverName().getString();
				return FAIL;
			}
			switch (stage) {
				case 0 -> {
					if (p.blockPosition().getX() != base.getX() || p.blockPosition().getZ() != base.getZ()) {
						why = "not on the tower spot (feet " + p.blockPosition().toShortString() + ")";
						return FAIL;
					}
					slot = InvBase.bringToHotbar(mc, p, Placement::filler, Placement::tool);
					if (slot < 0) return FAIL;
					InvBase.select(p, slot);
					Body.newTurn();
					stage = 1;
					return RUN;
				}
				case 1 -> {
					// Look down at the feet (as a player does to tower up).
					if (!Body.turnTo(p, p.getYRot(), 88f, 3f)) return RUN;
					if (placed >= height) {
						mc.options.keyJump.setDown(false);
						return DONE;
					}
					if (!p.onGround()) return RUN;
					mc.options.keyJump.setDown(true);
					stage = 2;
					return RUN;
				}
				case 2 -> {
					mc.options.keyJump.setDown(false);
					// High enough to put a block where the feet were.
					BlockPos at = base.above(placed);
					if (p.getY() < at.getY() + 1.05) return RUN;
					InvBase.select(p, slot = InvBase.bringToHotbar(mc, p, Placement::filler, Placement::tool));
					if (slot < 0) return FAIL;
					Vec3 hit = new Vec3(at.getX() + 0.5 + Human.jitter(0.1), at.getY(), at.getZ() + 0.5 + Human.jitter(0.1));
					mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, at.below(), false));
					p.swing(InteractionHand.MAIN_HAND);
					wait = 3;
					stage = 3;
					return RUN;
				}
				default -> {
					if (wait-- > 0) return RUN;
					BlockPos at = base.above(placed);
					BlockState s = mc.level.getBlockState(at);
					if (!s.isAir() && !s.canBeReplaced()) {
						Builder.temps.add(at.immutable());
						placed++;
					}
					stage = 1;
					return RUN;
				}
			}
		}
	}
}
