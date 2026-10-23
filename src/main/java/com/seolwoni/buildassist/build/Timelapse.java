package com.seolwoni.buildassist.build;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

import com.seolwoni.buildassist.BuildAssistClient;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

/**
 * A build timelapse: every so many blocks put, a picture of the whole plan from the same spot in the sky (the view
 * leaves the character for a moment and comes back), and at the end the pictures joined into an animated GIF. Both are
 * kept: the pictures in a folder, the GIF beside them.
 */
public final class Timelapse {
	private Timelapse() {
	}

	/** On or off (the build screen's switch). */
	public static boolean enabled = true;
	/** The plan's file name, for the folder's name. */
	public static String planName = "build";

	private static boolean active;
	private static Path folder;
	private static int frames, interval, lastAt, stage;
	private static ArmorStand eye;
	private static Entity oldView;
	private static CameraType oldCamera;
	private static boolean oldHide;
	private static int finishFrames;
	public static Path lastGif;
	public static volatile boolean gifDone;

	static void start(Minecraft mc) {
		String name = planName;
		active = enabled;
		if (!active) return;
		frames = 0;
		lastAt = -1;
		stage = 0;
		finishFrames = 0;
		gifDone = false;
		lastGif = null;
		int blocks = Math.max(1, Builder.plan.solidCount());
		interval = Math.max(4, blocks / 60);
		String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
		String safe = name.replaceAll("[^A-Za-z0-9가-힣_.-]", "_");
		folder = mc.gameDirectory.toPath().resolve("buildassist-timelapse").resolve(safe + "-" + stamp);
		try {
			Files.createDirectories(folder);
		} catch (IOException e) {
			BuildAssistClient.LOGGER.warn("Timelapse folder", e);
			active = false;
		}
	}

	public static Path folder() {
		return folder;
	}

	public static int frames() {
		return frames;
	}

	/** Every tick while building: time for a picture? Takes two ticks (look from the sky, then take it). True while the
	 * view is in the sky (the builder waits). */
	static boolean tick(Minecraft mc) {
		if (!active) return false;
		if (stage == 1) {
			take(mc);
			return false;
		}
		int done = Builder.placed;
		if (lastAt < 0 || done - lastAt >= interval) {
			lastAt = done;
			look(mc);
			return stage == 1;
		}
		return false;
	}

	/** The building has stopped: a last few pictures of the finished plan, then the GIF. */
	static void finish(Minecraft mc) {
		if (!active) return;
		if (stage == 1) take(mc);
		look(mc);
		finishFrames = 3; // the last picture is repeated in the GIF so the end stays on screen
		active = false;
		pendingFinish = true;
	}

	private static boolean pendingFinish;

	/** Called every client tick (also after building stopped), to take the last picture. */
	public static void clientTick(Minecraft mc) {
		if (pendingFinish && stage == 1) {
			take(mc);
			pendingFinish = false;
			Path dir = folder;
			int hold = finishFrames;
			Thread t = new Thread(() -> makeGif(dir, hold), "Build Assist timelapse");
			t.setDaemon(true);
			t.start();
		}
	}

	private static void look(Minecraft mc) {
		if (mc.player == null || mc.level == null) return;
		var plan = Builder.plan;
		var o = Builder.origin;
		double cx = o.getX() + plan.sizeX / 2.0, cy = o.getY() + plan.sizeY * 0.4, cz = o.getZ() + plan.sizeZ / 2.0;
		double span = Math.sqrt(plan.sizeX * plan.sizeX + plan.sizeZ * plan.sizeZ + plan.sizeY * plan.sizeY);
		double dist = Math.max(6, span * 1.1 + 6 - 4);
		float yaw = 225f, pitch = 35f; // from the corner where the plan starts, a little above
		double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
		Vec3 fwd = new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
		Vec3 at = new Vec3(cx, cy, cz).subtract(fwd.scale(dist));
		if (eye == null || eye.level() != mc.level) {
			eye = new ArmorStand(mc.level, at.x, at.y, at.z);
			eye.setInvisible(true);
		}
		eye.setPos(at.x, at.y - eye.getEyeHeight(), at.z);
		eye.setYRot(yaw);
		eye.setXRot(pitch);
		eye.setYHeadRot(yaw);
		eye.setOldPosAndRot();
		oldView = mc.getCameraEntity();
		oldCamera = mc.options.getCameraType();
		oldHide = hideGui(mc, true);
		mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		mc.setCameraEntity(eye);
		stage = 1;
	}

	private static void take(Minecraft mc) {
		stage = 0;
		final int n = ++frames;
		final Path file = folder.resolve(String.format("frame-%04d.png", n));
		try {
			com.mojang.blaze3d.pipeline.RenderTarget target = mainTarget(mc);
			if (target == null) throw new IllegalStateException("no render target found");
			Screenshot.takeScreenshot(target, image -> {
				try {
					image.writeToFile(file);
				} catch (IOException e) {
					BuildAssistClient.LOGGER.warn("Timelapse frame", e);
				} finally {
					image.close();
				}
			});
		} catch (Throwable e) {
			BuildAssistClient.LOGGER.warn("Timelapse frame", e);
		}
		mc.setCameraEntity(oldView != null ? oldView : mc.player);
		if (oldCamera != null) mc.options.setCameraType(oldCamera);
		hideGui(mc, oldHide);
	}

	private static com.mojang.blaze3d.pipeline.RenderTarget target;

	/** The game's main picture (what is on the screen), wherever this game version keeps it. */
	static com.mojang.blaze3d.pipeline.RenderTarget mainTarget(Minecraft mc) {
		if (target != null) return target;
		List<Object> where = new ArrayList<>(List.of(mc, mc.gameRenderer));
		try {
			where.add(mc.getClass().getMethod("getWindow").invoke(mc));
		} catch (Exception ignored) {
		}
		for (Object o : where) {
			for (var m : o.getClass().getMethods()) {
				if (m.getParameterCount() == 0 && com.mojang.blaze3d.pipeline.RenderTarget.class.isAssignableFrom(m.getReturnType())) {
					try {
						Object r = m.invoke(o);
						if (r != null) return target = (com.mojang.blaze3d.pipeline.RenderTarget) r;
					} catch (Exception ignored) {
					}
				}
			}
			for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
				for (var f : c.getDeclaredFields()) {
					if (!com.mojang.blaze3d.pipeline.RenderTarget.class.isAssignableFrom(f.getType())) continue;
					try {
						f.setAccessible(true);
						Object r = f.get(o);
						if (r != null) return target = (com.mojang.blaze3d.pipeline.RenderTarget) r;
					} catch (Exception ignored) {
					}
				}
			}
		}
		return null;
	}

	/** The F1 switch (no HUD), wherever this game version keeps it. Returns what it was. */
	static boolean hideGui(Minecraft mc, boolean on) {
		Object hud = null;
		try {
			hud = mc.gui.getClass().getField("hud").get(mc.gui);
		} catch (Exception ignored) {
		}
		for (Object o : new Object[] {mc.options, hud, mc.gui, mc}) {
			if (o == null) continue;
			for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
				for (var f : c.getDeclaredFields()) {
					if (!f.getName().equals("hideGui") || f.getType() != boolean.class) continue;
					try {
						f.setAccessible(true);
						boolean was = f.getBoolean(o);
						f.setBoolean(o, on);
						return was;
					} catch (Exception ignored) {
					}
				}
			}
		}
		return false;
	}

	/** Joins the pictures into an animated GIF (a fifth of a second each, at most 640 wide). */
	static void makeGif(Path dir, int hold) {
		try {
			Thread.sleep(1500); // the last pictures are written on another thread
			List<Path> pics = new ArrayList<>();
			try (Stream<Path> s = Files.list(dir)) {
				s.filter(f -> f.getFileName().toString().startsWith("frame-") && f.getFileName().toString().endsWith(".png")).sorted().forEach(pics::add);
			}
			if (pics.isEmpty()) return;
			Path gif = dir.resolve("timelapse.gif");
			ImageWriter w = ImageIO.getImageWritersByFormatName("gif").next();
			try (ImageOutputStream out = ImageIO.createImageOutputStream(gif.toFile())) {
				w.setOutput(out);
				w.prepareWriteSequence(null);
				for (int i = 0; i < pics.size(); i++) {
					BufferedImage img = ImageIO.read(pics.get(i).toFile());
					if (img == null) continue;
					img = scale(img, 640);
					boolean last = i == pics.size() - 1;
					IIOMetadata meta = w.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), null);
					String fmt = meta.getNativeMetadataFormatName();
					IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(fmt);
					IIOMetadataNode gce = child(root, "GraphicControlExtension");
					gce.setAttribute("disposalMethod", "none");
					gce.setAttribute("userInputFlag", "FALSE");
					gce.setAttribute("transparentColorFlag", "FALSE");
					gce.setAttribute("delayTime", String.valueOf(last ? 100 * Math.max(1, hold) : 20));
					gce.setAttribute("transparentColorIndex", "0");
					if (i == 0) {
						IIOMetadataNode apps = child(root, "ApplicationExtensions");
						IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
						app.setAttribute("applicationID", "NETSCAPE");
						app.setAttribute("authenticationCode", "2.0");
						app.setUserObject(new byte[] {1, 0, 0}); // loop for ever
						apps.appendChild(app);
					}
					meta.setFromTree(fmt, root);
					w.writeToSequence(new IIOImage(img, null, meta), null);
				}
				w.endWriteSequence();
			} finally {
				w.dispose();
			}
			lastGif = gif;
			BuildAssistClient.LOGGER.info("Timelapse: {} pictures, {}", pics.size(), gif);
		} catch (Exception e) {
			BuildAssistClient.LOGGER.warn("Timelapse GIF", e);
		} finally {
			gifDone = true;
		}
	}

	private static BufferedImage scale(BufferedImage src, int maxW) {
		int w = src.getWidth(), h = src.getHeight();
		if (w <= maxW) {
			BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			rgb.getGraphics().drawImage(src, 0, 0, null);
			return rgb;
		}
		int nh = h * maxW / w;
		BufferedImage out = new BufferedImage(maxW, nh, BufferedImage.TYPE_INT_RGB);
		var g = out.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(src, 0, 0, maxW, nh, null);
		g.dispose();
		return out;
	}

	private static IIOMetadataNode child(IIOMetadataNode root, String name) {
		for (int i = 0; i < root.getLength(); i++) if (root.item(i).getNodeName().equalsIgnoreCase(name)) return (IIOMetadataNode) root.item(i);
		IIOMetadataNode n = new IIOMetadataNode(name);
		root.appendChild(n);
		return n;
	}
}
