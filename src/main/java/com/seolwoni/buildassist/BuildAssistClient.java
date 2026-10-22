package com.seolwoni.buildassist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.InputConstants;
import com.seolwoni.buildassist.gui.BuildScreen;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public class BuildAssistClient implements ClientModInitializer {
	public static final String MOD_ID = "buildassist";
	public static final Logger LOGGER = LoggerFactory.getLogger("Build Assist");

	public static KeyMapping openKey;

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	/** Where plans are kept: the game folder's "schematics" (the same folder Litematica and WorldEdit users know). */
	public static Path folder() {
		Path p = Minecraft.getInstance().gameDirectory.toPath().resolve("schematics");
		try {
			Files.createDirectories(p);
		} catch (IOException ignored) {
		}
		return p;
	}

	/** Plan files in the folder (and one level of sub-folders), by name. */
	public static List<Path> files() {
		List<Path> out = new ArrayList<>();
		try (Stream<Path> s = Files.walk(folder(), 2)) {
			s.filter(Files::isRegularFile).filter(f -> {
				String n = f.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
				return n.endsWith(".litematic") || n.endsWith(".schem");
			}).sorted().forEach(out::add);
		} catch (IOException e) {
			LOGGER.warn("Could not list schematics", e);
		}
		return out;
	}

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(id("main"));
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.buildassist.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.consumeClick()) {
				client.gui.setScreen(new BuildScreen(null));
			}
			com.seolwoni.buildassist.build.Builder.tick(client);
		});
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> {
			if (com.seolwoni.buildassist.build.Builder.running) com.seolwoni.buildassist.build.Builder.stop(null);
		});
	}
}
