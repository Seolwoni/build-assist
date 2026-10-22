package com.seolwoni.buildassist.build;

import java.util.Set;

import com.seolwoni.assistcore.engine.Action;
import com.seolwoni.assistcore.util.Aim;
import com.seolwoni.assistcore.util.Body;
import com.seolwoni.assistcore.util.Human;
import com.seolwoni.assistcore.util.InvBase;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Which blocks are plain, what to put a block against, and the actions that put down and take out blocks. */
final class Placement {
	private Placement() {
	}

	/** Properties the game sets by itself (connections, leaf distance...): not compared. */
	static final Set<String> AUTO = Set.of("distance", "persistent", "snowy", "north", "south", "east", "west", "up", "waterlogged");
	/** Properties that depend on how the block is put down: later stages. */
	static final Set<String> DIRECTIONAL = Set.of("facing", "rotation", "half", "hinge", "attachment", "face", "part", "shape", "orientation", "hanging",
			"vertical_direction", "tilt", "type");

	/** Stage 2 can build this: no direction to get right (logs: the axis follows the face they are put on). */
	static boolean plain(BlockState t) {
		if (t.isAir() || !t.getFluidState().isEmpty()) return false;
		if (t.getBlock().asItem() == net.minecraft.world.item.Items.AIR) return false;
		BlockState def = t.getBlock().defaultBlockState();
		for (Property<?> p : t.getProperties()) {
			String n = p.getName();
			if (AUTO.contains(n) || n.equals("axis")) continue;
			if (n.equals("type") && "bottom".equals(String.valueOf(t.getValue(p)).toLowerCase(java.util.Locale.ROOT))) continue; // a bottom slab
			if (DIRECTIONAL.contains(n)) return false;
			if (!t.getValue(p).equals(def.getValue(p))) return false; // lit candles, grown crops...
		}
		return true;
	}

	/** The block is as the plan wants it (ignoring what the game sets by itself). */
	static boolean matches(BlockState now, BlockState t) {
		if (now.getBlock() != t.getBlock()) return false;
		for (Property<?> p : t.getProperties()) {
			if (AUTO.contains(p.getName())) continue;
			if (!now.hasProperty(p) || !now.getValue(p).equals(t.getValue(p))) return false;
		}
		return true;
	}

	private static boolean clickable(BlockPos q) {
		BlockState s = Minecraft.getInstance().level.getBlockState(q);
		return !s.isAir() && !s.canBeReplaced() && s.getFluidState().isEmpty();
	}

	/**
	 * The side of {@code cell} to put the block against: a neighbour to click on. A log goes against a block along its
	 * axis (its axis follows the face clicked). Prefers the block below (put down on top), then the sides, then above.
	 */
	static Direction support(BlockPos cell, BlockState t) {
		Direction[] order;
		String axis = t == null ? null : axisOf(t);
		if ("y".equals(axis)) order = new Direction[] {Direction.DOWN, Direction.UP};
		else if ("x".equals(axis)) order = new Direction[] {Direction.WEST, Direction.EAST};
		else if ("z".equals(axis)) order = new Direction[] {Direction.NORTH, Direction.SOUTH};
		else order = new Direction[] {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
		for (Direction d : order) if (clickable(cell.relative(d))) return d;
		return null;
	}

	static String axisOf(BlockState t) {
		for (Property<?> p : t.getProperties()) if (p.getName().equals("axis")) return String.valueOf(t.getValue(p)).toLowerCase(java.util.Locale.ROOT);
		return null;
	}

	static final Set<String> FILLERS = Set.of("minecraft:dirt", "minecraft:coarse_dirt", "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:stone",
			"minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:netherrack");

	static boolean filler(ItemStack s) {
		return !s.isEmpty() && FILLERS.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
	}

	static boolean tool(ItemStack s) {
		return !s.isEmpty() && s.isDamageableItem();
	}

	// ------------------------------------------------------------------ put down

	/** Puts the plan's block (or, with no target, a filler block) at {@code pos}. */
	static final class PlaceAction implements Action {
		final BlockPos pos;
		private final BlockState target;
		private int stage, wait, attempts, delay, slot;
		private BlockPos support;
		private Direction face;
		private Vec3 hit;
		private String reason = "";
		/** Failed for a reason that passes (we stood there, it was out of reach): try again later. */
		boolean retry;

		PlaceAction(BlockPos pos, BlockState target) {
			this.pos = pos;
			this.target = target;
		}

		@Override
		public int failMode() {
			return SKIP;
		}

		@Override
		public String failReason() {
			return reason;
		}

		private boolean done(Minecraft mc) {
			BlockState now = mc.level.getBlockState(pos);
			return target == null ? (!now.isAir() && !now.canBeReplaced() && now.getFluidState().isEmpty()) : matches(now, target);
		}

		private boolean wants(ItemStack s) {
			if (s.isEmpty()) return false;
			return target == null ? filler(s) : s.is(target.getBlock().asItem());
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			switch (stage) {
				case 0 -> {
					if (done(mc)) return DONE;
					if (Reach.occupies(p, pos)) {
						reason = "서 있는 자리예요";
						retry = true;
						return FAIL;
					}
					BlockState now = mc.level.getBlockState(pos);
					if (!now.isAir() && !now.canBeReplaced()) {
						reason = "다른 블록이 있어요";
						return FAIL;
					}
					Direction d = support(pos, target);
					if (d == null) {
						reason = "붙일 곳이 없어요";
						return FAIL;
					}
					support = pos.relative(d);
					face = d.getOpposite();
					// The face of the support that looks at the cell; on a side, its lower half (bottom slabs).
					Vec3 c = Aim.facePoint(support, face, 0.12);
					hit = face.getAxis() == Direction.Axis.Y ? c : new Vec3(c.x, support.getY() + 0.3 + Human.jitter(0.05), c.z);
					if (p.getEyePosition().distanceTo(hit) > 4.45) {
						reason = "손이 닿지 않아요";
						retry = true;
						return FAIL;
					}
					slot = InvBase.bringToHotbar(mc, p, this::wants, Placement::tool);
					if (slot == -1) {
						String id = target == null ? "흙이나 조약돌" : BuiltInRegistries.ITEM.getKey(target.getBlock().asItem()).toString();
						Builder.missing.merge(id, 1, Integer::sum);
						reason = "재료가 없어요: " + id;
						return FAIL;
					}
					delay = Human.delay(1, 4);
					Body.newTurn();
					stage = 1;
					return RUN;
				}
				case 1 -> {
					// Crouch while putting it down: clicking a crafting table or a chest then places instead of opening it.
					mc.options.keyShift.setDown(true);
					if (delay-- > 0) return RUN;
					InvBase.select(p, slot);
					stage = 2;
					return RUN;
				}
				case 2 -> {
					mc.options.keyShift.setDown(true);
					if (!Body.lookAt(p, hit, 3f) || !p.isShiftKeyDown()) return RUN;
					mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, support, false));
					p.swing(InteractionHand.MAIN_HAND);
					wait = Human.rand(2, 4);
					stage = 3;
					return RUN;
				}
				default -> {
					mc.options.keyShift.setDown(false);
					if (wait-- > 0) return RUN;
					if (done(mc)) {
						if (target == null) Builder.filled++;
						else Builder.placed++;
						return DONE;
					}
					if (++attempts >= 3) {
						reason = "블록이 놓이지 않아요";
						return FAIL;
					}
					stage = 0;
					return RUN;
				}
			}
		}
	}

	// ------------------------------------------------------------------ take out

	/** Takes out the block at {@code pos} with the best tool carried. */
	static final class BreakAction implements Action {
		final BlockPos pos;
		private int stage, ticks, delay;
		private Direction face;
		private Vec3 aim;
		private String reason = "";

		BreakAction(BlockPos pos) {
			this.pos = pos;
		}

		@Override
		public int failMode() {
			return SKIP;
		}

		@Override
		public String failReason() {
			return reason;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			BlockState now = mc.level.getBlockState(pos);
			switch (stage) {
				case 0 -> {
					if (now.isAir()) return DONE;
					if (!now.getFluidState().isEmpty() && now.canBeReplaced()) {
						reason = "물이나 용암이에요";
						return FAIL;
					}
					if (now.getDestroySpeed(mc.level, pos) < 0) {
						reason = "캘 수 없는 블록이에요";
						return FAIL;
					}
					if (Reach.dist(p.getEyePosition(), pos) > 4.45) {
						reason = "손이 닿지 않아요";
						return FAIL;
					}
					// The quickest tool for it (or the bare hand).
					ItemStack best = null;
					float speed = 1.0f;
					for (int i = 0; i < 36; i++) {
						ItemStack s = p.getInventory().getItem(i);
						if (s.isEmpty()) continue;
						float v = s.getDestroySpeed(now);
						if (v > speed) {
							speed = v;
							best = s;
						}
					}
					if (best != null) {
						final ItemStack b = best;
						int slot = InvBase.bringToHotbar(mc, p, s -> s == b, Placement::tool);
						if (slot >= 0) InvBase.select(p, slot);
					}
					face = Aim.faceToward(p.getEyePosition(), pos);
					aim = Aim.facePoint(pos, face, 0.15);
					delay = Human.delay(0, 3);
					Body.newTurn();
					stage = 1;
					return RUN;
				}
				case 1 -> {
					if (Body.lookAt(p, aim, 2.5f) && delay-- <= 0) {
						stage = 2;
						ticks = 0;
					}
					return RUN;
				}
				default -> {
					Body.lookAt(p, aim, 1.5f);
					if (now.isAir()) {
						Builder.broken++;
						return DONE;
					}
					mc.gameMode.continueDestroyBlock(pos, face);
					p.swing(InteractionHand.MAIN_HAND);
					if (++ticks > 400) {
						mc.gameMode.stopDestroyBlock();
						reason = "블록이 캐지지 않아요";
						return FAIL;
					}
					return RUN;
				}
			}
		}
	}
}
