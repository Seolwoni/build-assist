package com.seolwoni.buildassist.build;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Where a block can be reached from, and where to stand to reach one. */
final class Reach {
	private Reach() {
	}

	/** How far we work (a little under the game's 4.5, so the aim point on a face is always in range). */
	static final double REACH = 4.2;

	/** Distance from the eye to the nearest point of the cell. */
	static double dist(Vec3 eye, BlockPos c) {
		double x = Math.max(c.getX(), Math.min(eye.x, c.getX() + 1)), y = Math.max(c.getY(), Math.min(eye.y, c.getY() + 1)),
				z = Math.max(c.getZ(), Math.min(eye.z, c.getZ() + 1));
		return eye.distanceTo(new Vec3(x, y, z));
	}

	static boolean occupies(LocalPlayer p, BlockPos c) {
		return p.getBoundingBox().intersects(new AABB(c));
	}

	static boolean inReach(LocalPlayer p, BlockPos c) {
		return dist(p.getEyePosition(), c) <= REACH && !occupies(p, c) && !occupies(p, c);
	}

	private static boolean free(Minecraft mc, BlockPos q) {
		BlockState s = mc.level.getBlockState(q);
		return s.getCollisionShape(mc.level, q).isEmpty() && s.getFluidState().isEmpty();
	}

	/** A spot to stand on, near {@code cell}, from which it is in reach: on solid ground, not in the way of the plan. */
	static BlockPos standFor(Minecraft mc, LocalPlayer p, BlockPos cell) {
		BlockPos best = null;
		double bestCost = Double.MAX_VALUE;
		Vec3 me = p.position();
		for (int dy = -4; dy <= 1; dy++) {
			for (int dx = -4; dx <= 4; dx++) {
				for (int dz = -4; dz <= 4; dz++) {
					BlockPos f = cell.offset(dx, dy, dz);
					if (f.equals(cell) || f.above().equals(cell)) continue;
					if (!free(mc, f) || !free(mc, f.above())) continue;
					BlockState below = mc.level.getBlockState(f.below());
					if (!below.isCollisionShapeFullBlock(mc.level, f.below()) || !below.getFluidState().isEmpty()) continue;
					if (Builder.gaveUp.contains(f)) continue;
					// Not where the plan still has a block to put (we would be standing in our own way).
					if (pending(mc, f) || pending(mc, f.above())) continue;
					Vec3 eye = new Vec3(f.getX() + 0.5, f.getY() + 1.62, f.getZ() + 0.5);
					double d = dist(eye, cell);
					if (d > REACH - 0.2) continue;
					// Close to where we are, and not right on top of the work.
					double cost = me.distanceTo(Vec3.atBottomCenterOf(f)) + (d < 1.5 ? 3 : 0) + Math.abs(dy) * 0.3;
					if (cost < bestCost) {
						bestCost = cost;
						best = f.immutable();
					}
				}
			}
		}
		return best;
	}

	/** The plan has a block here that is not there yet. */
	static boolean pending(Minecraft mc, BlockPos w) {
		BlockState t = Builder.target(w);
		return t != null && !t.isAir() && !Placement.matches(mc.level.getBlockState(w), t);
	}
}
