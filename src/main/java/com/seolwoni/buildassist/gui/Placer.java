package com.seolwoni.buildassist.gui;

import com.seolwoni.assistcore.util.Alerts;
import com.seolwoni.buildassist.BuildAssistClient;
import com.seolwoni.buildassist.build.Builder;
import com.seolwoni.buildassist.schematic.Schematic;
import com.seolwoni.buildassist.schematic.States;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Choosing where to build: the plan's outline follows the block looked at (drawn with small lights along its edges),
 * turned a quarter at a time and mirrored left to right with keys, and built where it stands when confirmed.
 */
public final class Placer {
	private Placer() {
	}

	private static boolean active;
	private static Schematic base, turned;
	private static int turns;
	private static boolean mirror;
	private static BlockPos origin;
	private static int ticks;

	public static boolean active() {
		return active;
	}

	public static void begin(Schematic plan) {
		base = plan;
		turns = 0;
		mirror = false;
		turned = plan;
		active = true;
		origin = null;
		ticks = 0;
	}

	public static void cancel() {
		active = false;
		Alerts.actionBar("자리 고르기를 그만뒀어요", 0xFFAAAAAA);
	}

	private static void retune() {
		turned = States.turned(base, turns, mirror);
	}

	public static void tick(Minecraft mc) {
		if (!active) {
			// Drain clicks pressed while not choosing.
			while (BuildAssistClient.rotateKey.consumeClick()) {
			}
			while (BuildAssistClient.mirrorKey.consumeClick()) {
			}
			while (BuildAssistClient.confirmKey.consumeClick()) {
			}
			while (BuildAssistClient.cancelKey.consumeClick()) {
			}
			return;
		}
		if (mc.player == null || mc.level == null) {
			active = false;
			return;
		}
		while (BuildAssistClient.rotateKey.consumeClick()) {
			turns = (turns + 1) % 4;
			retune();
		}
		while (BuildAssistClient.mirrorKey.consumeClick()) {
			mirror = !mirror;
			retune();
		}
		while (BuildAssistClient.cancelKey.consumeClick()) {
			cancel();
			return;
		}
		// The block looked at: the plan stands on it, centred on it.
		HitResult hr = mc.hitResult;
		if (hr instanceof BlockHitResult bh && hr.getType() == HitResult.Type.BLOCK) {
			BlockPos ground = bh.getDirection() == Direction.UP || mc.level.getBlockState(bh.getBlockPos()).canBeReplaced() ? bh.getBlockPos()
					: bh.getBlockPos().relative(bh.getDirection()).below();
			if (mc.level.getBlockState(ground).canBeReplaced()) ground = ground.below();
			origin = ground.above().offset(-turned.sizeX / 2, 0, -turned.sizeZ / 2);
		}
		while (BuildAssistClient.confirmKey.consumeClick()) {
			if (origin == null) continue;
			String err = Builder.start(mc, turned, origin);
			active = false;
			if (err != null) Alerts.actionBar(err, 0xFFFF5555);
			else Alerts.actionBar("여기에 지어요: " + origin.toShortString(), 0xFF55FF55);
			return;
		}
		if (origin != null && ticks % 5 == 0) outline(mc, origin, turned);
		if (ticks % 20 == 0) {
			Alerts.actionBar("[" + key(BuildAssistClient.rotateKey) + "] 돌리기 (" + turns * 90 + "°) · [" + key(BuildAssistClient.mirrorKey) + "] 좌우 반전 ("
					+ (mirror ? "켬" : "끔") + ") · [" + key(BuildAssistClient.confirmKey) + "] 여기에 짓기 · [" + key(BuildAssistClient.cancelKey) + "] 취소", 0xFFFFFF55);
		}
		ticks++;
	}

	private static String key(net.minecraft.client.KeyMapping k) {
		return k.getTranslatedKeyMessage().getString();
	}

	/** Small lights along the twelve edges of the plan's box (and a marker at its front-left corner). */
	private static void outline(Minecraft mc, BlockPos o, Schematic s) {
		double x0 = o.getX(), y0 = o.getY(), z0 = o.getZ(), x1 = x0 + s.sizeX, y1 = y0 + s.sizeY, z1 = z0 + s.sizeZ;
		double step = Math.max(1.0, Math.max(s.sizeX, Math.max(s.sizeY, s.sizeZ)) / 40.0);
		for (double t = 0; t <= 1.0001; t += step / Math.max(1, s.sizeX)) {
			double x = x0 + (x1 - x0) * t;
			dot(mc, x, y0, z0);
			dot(mc, x, y0, z1);
			dot(mc, x, y1, z0);
			dot(mc, x, y1, z1);
		}
		for (double t = 0; t <= 1.0001; t += step / Math.max(1, s.sizeZ)) {
			double z = z0 + (z1 - z0) * t;
			dot(mc, x0, y0, z);
			dot(mc, x1, y0, z);
			dot(mc, x0, y1, z);
			dot(mc, x1, y1, z);
		}
		for (double t = 0; t <= 1.0001; t += step / Math.max(1, s.sizeY)) {
			double y = y0 + (y1 - y0) * t;
			dot(mc, x0, y, z0);
			dot(mc, x1, y, z0);
			dot(mc, x0, y, z1);
			dot(mc, x1, y, z1);
		}
	}

	private static void dot(Minecraft mc, double x, double y, double z) {
		mc.level.addParticle(ParticleTypes.END_ROD, x, y, z, 0, 0, 0);
	}
}
