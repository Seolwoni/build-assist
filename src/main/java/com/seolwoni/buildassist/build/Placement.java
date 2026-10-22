package com.seolwoni.buildassist.build;

import java.util.Set;

import com.seolwoni.assistcore.engine.Action;
import com.seolwoni.assistcore.util.Aim;
import com.seolwoni.assistcore.util.Body;
import com.seolwoni.assistcore.util.Human;
import com.seolwoni.assistcore.util.InvBase;
import com.seolwoni.buildassist.schematic.Materials;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Putting blocks down the way a player does: which side to click, where on it, and which way to look, so the block
 * comes out facing the way the plan wants. The game decides a block's direction from the face clicked, the point on
 * it and the way the player looks; here the same game code is asked, for each choice, what it would make, and the
 * first choice that makes the planned block is used.
 */
final class Placement {
	private Placement() {
	}

	/** Properties that must be as planned. Everything else the game sets by itself (connections, power, stair shape...). */
	static final Set<String> KEY = Set.of("facing", "axis", "rotation", "half", "hinge", "attachment", "face", "part", "orientation", "hanging",
			"vertical_direction", "type", "open", "delay", "mode", "candles", "pickles", "eggs", "layers", "flower_amount", "segment_amount");
	/** Set after the block is down: by clicking it (open, delay, mode) or by putting more of it (candles...). */
	static final Set<String> AFTER = Set.of("open", "delay", "mode", "candles", "pickles", "eggs", "layers", "flower_amount", "segment_amount");

	static String stateString(BlockState t) {
		StringBuilder sb = new StringBuilder(BuiltInRegistries.BLOCK.getKey(t.getBlock()).toString());
		boolean first = true;
		for (Property<?> p : t.getProperties()) {
			sb.append(first ? '[' : ',').append(p.getName()).append('=').append(String.valueOf(t.getValue(p)).toLowerCase(java.util.Locale.ROOT));
			first = false;
		}
		if (!first) sb.append(']');
		return sb.toString();
	}

	/** The item that puts this block down, or null (cannot be had, made in place, the second half of a door...). */
	static Item itemFor(BlockState t) {
		String id = Materials.itemFor(stateString(t));
		if (id == null) return null;
		Item it = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(Items.AIR);
		return it == Items.AIR ? null : it;
	}

	/** This stage can build it: there is an item for it and it is not a fluid. */
	static boolean buildable(BlockState t) {
		if (t.isAir() || !t.getFluidState().isEmpty() && !t.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)) return false;
		return itemFor(t) != null;
	}

	/** The second half of a two-cell block: it comes with the first, nothing to do. */
	static boolean secondHalf(BlockState t) {
		String s = stateString(t);
		return Materials.itemFor(s) == null && (s.contains("half=upper") || s.contains("part=head"));
	}

	/** As the plan wants it (key properties). */
	static boolean matches(BlockState now, BlockState t) {
		if (now.getBlock() != t.getBlock()) return false;
		for (Property<?> p : t.getProperties()) {
			if (!KEY.contains(p.getName())) continue;
			if (!now.hasProperty(p) || !now.getValue(p).equals(t.getValue(p))) return false;
		}
		return true;
	}

	/** As placed, before the clicks afterwards: everything key except what is set after. */
	static boolean placedRight(BlockState now, BlockState t) {
		if (now.getBlock() != t.getBlock()) return false;
		for (Property<?> p : t.getProperties()) {
			if (!KEY.contains(p.getName()) || AFTER.contains(p.getName())) continue;
			if (!now.hasProperty(p) || !now.getValue(p).equals(t.getValue(p))) return false;
		}
		return true;
	}

	static boolean clickable(BlockPos q) {
		BlockState s = Minecraft.getInstance().level.getBlockState(q);
		return !s.isAir() && !s.canBeReplaced() && s.getFluidState().isEmpty();
	}

	/** Something next to the cell to put a block against (any side). */
	static boolean hasSupport(BlockPos cell) {
		for (Direction d : Direction.values()) if (clickable(cell.relative(d))) return true;
		return false;
	}

	record Option(BlockPos support, Direction face, Vec3 hit) {
	}

	private static final double[] SIDE = {0.5, 0.22, 0.78};

	/**
	 * How to put {@code t} down at {@code cell} from {@code eye}: the support, the face and the point to click, such
	 * that the game makes the planned block (checked a few degrees either way, so a hand a little off still makes it).
	 * Null if there is no way from there.
	 */
	static Option solve(Minecraft mc, LocalPlayer p, BlockPos cell, BlockState t, Vec3 eye) {
		return solve(mc, p, cell, t, eye, null);
	}

	/** As {@link #solve}, also counting {@code extra} as a block to put it against (a support not built yet). */
	static Option solve(Minecraft mc, LocalPlayer p, BlockPos cell, BlockState t, Vec3 eye, BlockPos extra) {
		Item item = itemFor(t);
		if (item == null) return null;
		ItemStack stack = new ItemStack(item);
		String id = BuiltInRegistries.BLOCK.getKey(t.getBlock()).getPath();
		boolean wall = id.contains("wall_") && !id.endsWith("_wall");
		float yaw0 = p.getYRot(), pitch0 = p.getXRot(), head0 = p.getYHeadRot();
		try {
			for (Direction d : order(t)) {
				BlockPos support = cell.relative(d);
				if (!clickable(support) && !support.equals(extra)) continue;
				Direction face = d.getOpposite();
				// The item decides between a standing and a wall form by the face clicked.
				if (wall && face.getAxis() == Direction.Axis.Y) continue;
				if (!wall && standingOrWall(item) && face != Direction.UP && !id.endsWith("hanging_sign")) continue;
				if (id.endsWith("hanging_sign") && !wall && face != Direction.DOWN) continue;
				for (double a : SIDE) {
					for (double b : SIDE) {
						Vec3 hit = point(support, face, a, b);
						if (eye.distanceTo(hit) > 4.4) continue;
						if (!makes(mc, p, cell, t, stack, support, face, hit, eye, 0, 0)) continue;
						if (!makes(mc, p, cell, t, stack, support, face, hit, eye, 5, 3) || !makes(mc, p, cell, t, stack, support, face, hit, eye, -5, -3)) continue;
						return new Option(support, face, hit);
					}
				}
			}
			return null;
		} finally {
			p.setYRot(yaw0);
			p.setXRot(pitch0);
			p.setYHeadRot(head0);
		}
	}

	private static boolean standingOrWall(Item item) {
		return item instanceof net.minecraft.world.item.StandingAndWallBlockItem;
	}

	/** Supports to try: the one below first (put down on top), then the sides, then above; logs along their axis. */
	private static Direction[] order(BlockState t) {
		return new Direction[] {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
	}

	/** A point on {@code face} of {@code support}: (a, b) across the face, 0..1. */
	private static Vec3 point(BlockPos s, Direction face, double a, double b) {
		double x = s.getX() + 0.5 + face.getStepX() * 0.5, y = s.getY() + 0.5 + face.getStepY() * 0.5, z = s.getZ() + 0.5 + face.getStepZ() * 0.5;
		switch (face.getAxis()) {
			case Y -> {
				x = s.getX() + a;
				z = s.getZ() + b;
			}
			case X -> {
				z = s.getZ() + a;
				y = s.getY() + b;
			}
			default -> {
				x = s.getX() + a;
				y = s.getY() + b;
			}
		}
		return new Vec3(x, y, z);
	}

	/** Would clicking there, looking from {@code eye} (turned by dYaw/dPitch), make the planned block? */
	private static boolean makes(Minecraft mc, LocalPlayer p, BlockPos cell, BlockState t, ItemStack stack, BlockPos support, Direction face, Vec3 hit, Vec3 eye, float dYaw,
			float dPitch) {
		double dx = hit.x - eye.x, dy = hit.y - eye.y, dz = hit.z - eye.z;
		float yaw = (float) (Mth.atan2(dz, dx) * (180 / Math.PI)) - 90f + dYaw;
		float pitch = (float) (-(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * (180 / Math.PI))) + dPitch;
		p.setYRot(yaw);
		p.setXRot(pitch);
		p.setYHeadRot(yaw);
		BlockHitResult hr = new BlockHitResult(hit, face, support, false);
		BlockPlaceContext ctx;
		if (clickable(support)) {
			ctx = new BlockPlaceContext(p, InteractionHand.MAIN_HAND, stack, hr);
			if (!ctx.getClickedPos().equals(cell) || !ctx.canPlace()) return false;
		} else {
			// A support not built yet: the game would see air there; ask as if it stood.
			ctx = new BlockPlaceContext(p, InteractionHand.MAIN_HAND, stack, hr) {
				@Override
				public BlockPos getClickedPos() {
					return cell;
				}

				@Override
				public boolean replacingClickedOnBlock() {
					return false;
				}

				@Override
				public boolean canPlace() {
					return true;
				}
			};
		}
		BlockState made;
		try {
			made = t.getBlock().getStateForPlacement(ctx);
		} catch (Exception e) {
			return false;
		}
		return made != null && placedRight(made, t);
	}

	static boolean tool(ItemStack s) {
		return !s.isEmpty() && s.isDamageableItem();
	}

	static final Set<String> FILLERS = Set.of("minecraft:dirt", "minecraft:coarse_dirt", "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:stone",
			"minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:netherrack");

	static boolean filler(ItemStack s) {
		return !s.isEmpty() && FILLERS.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
	}

	// ------------------------------------------------------------------ put down

	/** Puts the plan's block (or, with no target, a filler block) at {@code pos}, facing the planned way. */
	static final class PlaceAction implements Action {
		final BlockPos pos;
		private final BlockState target;
		private final Item item;
		private int stage, wait, attempts, delay, slot, clicks;
		private Option opt;
		private String reason = "";
		/** Failed for a reason that passes (we stood there, out of reach, the wrong side to face it): try again later. */
		boolean retry;
		/** Put down facing the wrong way (the game decided otherwise than asked). */
		boolean wrongWay;
		/** A filler block put only to build against or to stand on: taken out again at the end. */
		boolean temp;

		PlaceAction(BlockPos pos, BlockState target) {
			this.pos = pos;
			this.target = target;
			this.item = target == null ? null : itemFor(target);
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
			return target == null ? filler(s) : s.is(item);
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			switch (stage) {
				case 0 -> {
					if (done(mc)) return DONE;
					BlockState now = mc.level.getBlockState(pos);
					// Down already, the right way: only the clicks afterwards are left (open it, set the delay...).
					if (target != null && placedRight(now, target)) {
						stage = 10;
						return RUN;
					}
					if (Reach.occupies(p, pos)) {
						reason = "서 있는 자리예요";
						retry = true;
						return FAIL;
					}
					if (!now.isAir() && !now.canBeReplaced()) {
						reason = "다른 블록이 있어요";
						return FAIL;
					}
					if (target == null) {
						// Filler: anything against anything.
						for (Direction d : Direction.values()) {
							if (!clickable(pos.relative(d))) continue;
							Vec3 hit = Aim.facePoint(pos.relative(d), d.getOpposite(), 0.12);
							if (p.getEyePosition().distanceTo(hit) <= 4.4) {
								opt = new Option(pos.relative(d), d.getOpposite(), hit);
								break;
							}
						}
					} else {
						opt = solve(mc, p, pos, target, p.getEyePosition());
					}
					if (opt == null) {
						reason = hasSupport(pos) ? "여기서는 방향을 맞출 수 없어요" : "붙일 곳이 없어요";
						retry = true;
						return FAIL;
					}
					slot = InvBase.bringToHotbar(mc, p, this::wants, Placement::tool);
					if (slot == -1) {
						String id = target == null ? "흙이나 조약돌" : BuiltInRegistries.ITEM.getKey(item).toString();
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
					if (!Body.lookAt(p, opt.hit(), 1.2f) || !p.isShiftKeyDown()) return RUN;
					mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(opt.hit(), opt.face(), opt.support(), false));
					p.swing(InteractionHand.MAIN_HAND);
					wait = Human.rand(2, 4);
					stage = 3;
					return RUN;
				}
				case 3 -> {
					mc.options.keyShift.setDown(false);
					if (wait-- > 0) return RUN;
					BlockState now = mc.level.getBlockState(pos);
					if (done(mc)) {
						if (temp) Builder.temps.add(pos.immutable());
						else if (target == null) Builder.filled++;
						else Builder.placed++;
						return DONE;
					}
					if (target != null && placedRight(now, target)) {
						Builder.placed++;
						stage = 10;
						return RUN;
					}
					if (target != null && now.getBlock() == target.getBlock()) {
						reason = "방향이 다르게 놓였어요";
						wrongWay = true;
						return FAIL;
					}
					if (++attempts >= 3) {
						reason = "블록이 놓이지 않아요";
						return FAIL;
					}
					stage = 0;
					return RUN;
				}
				default -> {
					// Afterwards: open it, set the delay or the mode (a click on it), or put more of it (candles...).
					BlockState now = mc.level.getBlockState(pos);
					if (done(mc)) return DONE;
					if (++clicks > 12) {
						reason = "모양을 맞추지 못했어요";
						return FAIL;
					}
					boolean more = false;
					for (Property<?> pr : target.getProperties()) {
						String n = pr.getName();
						if (!AFTER.contains(n) || n.equals("open") || n.equals("delay") || n.equals("mode")) continue;
						if (!now.getValue(pr).equals(target.getValue(pr))) more = true;
					}
					boolean iron = BuiltInRegistries.BLOCK.getKey(target.getBlock()).getPath().startsWith("iron_");
					if (!more && iron) return DONE; // iron doors and trapdoors open only with redstone
					Vec3 hit = Aim.facePoint(pos, Aim.faceToward(p.getEyePosition(), pos), 0.1);
					if (!Body.lookAt(p, hit, 2f)) return RUN;
					if (more) {
						int s = InvBase.bringToHotbar(mc, p, this::wants, Placement::tool);
						if (s == -1) {
							Builder.missing.merge(BuiltInRegistries.ITEM.getKey(item).toString(), 1, Integer::sum);
							return DONE;
						}
						InvBase.select(p, s);
					}
					// Not crouching: a click on the block uses it (opens a trapdoor, turns a repeater's delay).
					mc.options.keyShift.setDown(false);
					if (p.isShiftKeyDown()) return RUN;
					mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Aim.faceToward(p.getEyePosition(), pos), pos, false));
					p.swing(InteractionHand.MAIN_HAND);
					wait = Human.rand(3, 6);
					stage = 11;
					return RUN;
				}
				case 11 -> {
					if (wait-- > 0) return RUN;
					stage = 10;
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
