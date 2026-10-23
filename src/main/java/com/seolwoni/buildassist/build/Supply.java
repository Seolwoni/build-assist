package com.seolwoni.buildassist.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.seolwoni.assistcore.engine.Action;
import com.seolwoni.assistcore.storage.ChestEntry;
import com.seolwoni.assistcore.storage.Chests;
import com.seolwoni.assistcore.util.Body;
import com.seolwoni.assistcore.util.Human;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Materials from chests: the player marks material chests (any chest: one another Assist mod stores into works too),
 * and when a block of the plan needs an item the bag does not have, a trip round those chests takes what the rest of
 * the plan needs, as much as the bag holds. Items no marked chest has are remembered, so no trip is made for them again.
 */
public final class Supply {
	private Supply() {
	}

	/** Saved settings: the material chests. */
	public static final class Config {
		public List<ChestEntry> chests = new ArrayList<>();
	}

	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("buildassist.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Config config;

	public static Config config() {
		if (config == null) {
			try {
				config = Files.exists(PATH) ? GSON.fromJson(Files.readString(PATH), Config.class) : new Config();
			} catch (Exception e) {
				config = new Config();
			}
			if (config == null) config = new Config();
			if (config.chests == null) config.chests = new ArrayList<>();
		}
		return config;
	}

	public static void save() {
		try {
			Files.writeString(PATH, GSON.toJson(config()));
		} catch (IOException ignored) {
		}
	}

	public static List<ChestEntry> chests() {
		return config().chests;
	}

	/** Items none of the chests had (no trip for them again in this build). */
	static final Set<String> absent = new HashSet<>();
	private static int nextTrip;
	static int trips, takenStacks;

	static void reset() {
		absent.clear();
		nextTrip = 0;
		trips = 0;
		takenStacks = 0;
	}

	private static String id(ItemStack s) {
		return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
	}

	/** What the rest of the plan needs, item by item, minus what the bag holds already. */
	static Map<String, Integer> stillNeeded(Minecraft mc, LocalPlayer p) {
		Map<String, Integer> need = new HashMap<>();
		var plan = Builder.plan;
		var o = Builder.origin;
		for (int y = 0; y < plan.sizeY; y++) {
			for (int z = 0; z < plan.sizeZ; z++) {
				for (int x = 0; x < plan.sizeX; x++) {
					BlockPos w = o.offset(x, y, z);
					if (!Builder.wanted(mc, w)) continue;
					BlockState t = Builder.target(w);
					var item = Placement.itemFor(t);
					if (item == null) continue;
					need.merge(BuiltInRegistries.ITEM.getKey(item).toString(), 1, Integer::sum);
				}
			}
		}
		var inv = p.getInventory();
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) continue;
			need.computeIfPresent(id(s), (k, v) -> v - s.getCount() > 0 ? v - s.getCount() : null);
		}
		return need;
	}

	/**
	 * Called before building: when blocks still to put need an item the bag has none of, and a chest may have it, a
	 * trip is queued. True if it was.
	 */
	static boolean maybeTrip(Minecraft mc, LocalPlayer p) {
		if (chests().isEmpty() || Builder.ticks < nextTrip) return false;
		Map<String, Integer> need = stillNeeded(mc, p);
		boolean lacking = false;
		for (String id : need.keySet()) {
			if (absent.contains(id)) continue;
			if (count(p, id) == 0) lacking = true;
		}
		if (!lacking) return false;
		nextTrip = Builder.ticks + 400;
		trips++;
		Builder.status = "재료 상자에 가는 중";
		Builder.queue.add(new TripAction(need));
		return true;
	}

	private static int count(LocalPlayer p, String id) {
		int n = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (!s.isEmpty() && id(s).equals(id)) n += s.getCount();
		}
		return n;
	}

	private static int freeSlots(LocalPlayer p) {
		int n = 0;
		for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) n++;
		return n;
	}

	/** Round the material chests, nearest first: open, take what is needed, close. */
	static final class TripAction implements Action {
		private final Map<String, Integer> need;
		private final Set<String> seen = new HashSet<>();
		private final List<BlockPos> order = new ArrayList<>();
		private int idx, stage, delay, wait;
		private Action walk;
		private Vec3 aim;
		private Direction face;

		TripAction(Map<String, Integer> need) {
			this.need = new HashMap<>(need);
		}

		@Override
		public int failMode() {
			return SKIP;
		}

		@Override
		public int tick(Minecraft mc, LocalPlayer p) {
			switch (stage) {
				case 0 -> {
					for (ChestEntry c : chests()) order.add(c.pos());
					Vec3 me = p.position();
					order.sort((a, b) -> Double.compare(me.distanceToSqr(Vec3.atCenterOf(a)), me.distanceToSqr(Vec3.atCenterOf(b))));
					stage = 1;
					return RUN;
				}
				case 1 -> { // to the next chest
					if (idx >= order.size() || freeSlots(p) <= 1 || need.isEmpty()) {
						// Whatever was still wanted and no chest had: no more trips for it.
						for (String id : need.keySet()) if (!seen.contains(id) && idx >= order.size()) absent.add(id);
						return DONE;
					}
					BlockPos c = order.get(idx);
					if (mc.level.getBlockEntity(c) == null) {
						idx++;
						return RUN;
					}
					if (p.getEyePosition().distanceTo(Vec3.atCenterOf(c)) <= 4.0) {
						Body.release(mc);
						Vec3 eye = p.getEyePosition();
						double dx = eye.x - (c.getX() + 0.5), dy = eye.y - (c.getY() + 0.5), dz = eye.z - (c.getZ() + 0.5);
						face = Math.abs(dy) > Math.max(Math.abs(dx), Math.abs(dz)) ? (dy > 0 ? Direction.UP : Direction.DOWN)
								: Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
						aim = Vec3.atCenterOf(c).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
						Body.newTurn();
						delay = Human.delay(2, 6);
						stage = 2;
						return RUN;
					}
					if (walk == null) walk = new com.seolwoni.assistpath.walk.PathWalkAction(new com.seolwoni.assistpath.goals.GoalNear(c, 2),
							com.seolwoni.assistpath.PathOptions.WALK, SKIP);
					int r = walk.tick(mc, p);
					if (r == RUN) return RUN;
					walk = null;
					if (r == FAIL || p.getEyePosition().distanceTo(Vec3.atCenterOf(c)) > 4.0) idx++; // cannot get there
					return RUN;
				}
				case 2 -> { // look at it and open
					if (!Body.lookAt(p, aim, 3f) || delay-- > 0) return RUN;
					// Not crouching: a crouched click would put the block in hand against the chest.
					mc.options.keyShift.setDown(false);
					mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(aim, face, order.get(idx), false));
					p.swing(InteractionHand.MAIN_HAND);
					wait = 40;
					stage = 3;
					return RUN;
				}
				case 3 -> { // wait for the chest screen
					if (Chests.openScreen(mc) != null) {
						delay = Human.delay(3, 8);
						stage = 4;
						return RUN;
					}
					if (--wait <= 0) {
						idx++;
						stage = 1;
					}
					return RUN;
				}
				case 4 -> { // take a stack at a time
					AbstractContainerScreen<?> s = Chests.openScreen(mc);
					if (s == null) {
						idx++;
						stage = 1;
						return RUN;
					}
					if (delay-- > 0) return RUN;
					if (takeOne(mc, p, s.getMenu())) {
						delay = Human.delay(1, 4);
						return RUN;
					}
					delay = Human.delay(3, 8);
					stage = 5;
					return RUN;
				}
				default -> { // close, next chest
					if (delay-- > 0) return RUN;
					if (Chests.openScreen(mc) != null) p.closeContainer();
					idx++;
					stage = 1;
					return RUN;
				}
			}
		}

		private boolean takeOne(Minecraft mc, LocalPlayer p, AbstractContainerMenu menu) {
			if (freeSlots(p) <= 1) return false;
			for (int i = 0; i < menu.slots.size(); i++) {
				Slot slot = menu.slots.get(i);
				if (slot.container instanceof Inventory) continue;
				ItemStack s = slot.getItem();
				if (s.isEmpty()) continue;
				String id = id(s);
				seen.add(id);
				Integer left = need.get(id);
				if (left == null || left <= 0) continue;
				int n = s.getCount();
				mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, p);
				takenStacks++;
				if (left - n <= 0) need.remove(id);
				else need.put(id, left - n);
				return true;
			}
			return false;
		}
	}
}
