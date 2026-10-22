package com.seolwoni.buildassist.gui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.seolwoni.buildassist.BuildAssistClient;
import com.seolwoni.buildassist.schematic.Materials;
import com.seolwoni.buildassist.schematic.Schematic;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Build Assist: pick a plan from the "schematics" folder and see what it needs: its size, the blocks, and the
 * materials, with how many of each are in the inventory and how many are still missing.
 */
public class BuildScreen extends Screen {
	private static final int ROW = 22, LINE = 11;
	private final Screen parent;
	private final List<Object[]> labels = new ArrayList<>();

	/** The plan picked last (kept while the game runs). */
	private static Path picked;
	private static Schematic plan;
	private static Materials materials;
	private static String error;
	private static int filePage, matPage;

	public BuildScreen(Screen parent) {
		super(Component.literal("Build Assist"));
		this.parent = parent;
	}

	/** Loads a plan (also used by the tests). */
	public static String load(Path file) {
		picked = file;
		matPage = 0;
		try {
			plan = Schematic.load(file);
			materials = Materials.of(plan);
			error = null;
		} catch (Exception e) {
			plan = null;
			materials = null;
			error = e.getMessage() == null ? e.toString() : e.getMessage();
		}
		return error;
	}

	public static Schematic plan() {
		return plan;
	}

	public static Materials materials() {
		return materials;
	}

	/** How many of each item the player carries. */
	public static Map<String, Integer> carried() {
		Map<String, Integer> m = new HashMap<>();
		var p = net.minecraft.client.Minecraft.getInstance().player;
		if (p == null) return m;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.isEmpty()) continue;
			m.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
		}
		return m;
	}

	static String itemName(String id) {
		try {
			Item it = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
			if (it != Items.AIR) return it.getName().getString();
		} catch (Exception ignored) {
		}
		return id.replace("minecraft:", "");
	}

	static String blockName(String id) {
		try {
			var b = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
			return b.getName().getString();
		} catch (Exception e) {
			return id.replace("minecraft:", "");
		}
	}

	@Override
	protected void init() {
		labels.clear();
		int left = this.width / 2 - 155;
		int y = 24;
		addRenderableWidget(Button.builder(Component.literal("완료"), b -> onClose()).bounds(left + 210, 4, 100, 18).build());

		// Files.
		List<Path> files = BuildAssistClient.files();
		Path folder = BuildAssistClient.folder();
		if (files.isEmpty()) {
			labels.add(new Object[] {"설계도가 없어요. 이 폴더에 .litematic 또는 .schem 파일을 넣어주세요:", this.width / 2, y + 4, 0xFFFFAA55, true});
			labels.add(new Object[] {folder.toString(), this.width / 2, y + 16, 0xFFAAAAAA, true});
			return;
		}
		int per = 4, pages = (files.size() + per - 1) / per;
		filePage = Math.max(0, Math.min(filePage, pages - 1));
		for (int i = filePage * per; i < Math.min(files.size(), (filePage + 1) * per); i++) {
			Path f = files.get(i);
			String name = folder.relativize(f).toString();
			boolean on = f.equals(picked);
			int col = (i - filePage * per) % 2, row = (i - filePage * per) / 2;
			Button b = Button.builder(Component.literal(shorten(name, 24)).withColor(on ? 0xFF55FF55 : 0xFFFFFFFF), x -> {
				load(f);
				rebuildWidgets();
			}).bounds(left + col * 156, y + row * ROW, 154, 20).build();
			addRenderableWidget(b);
		}
		y += 2 * ROW;
		if (pages > 1) {
			addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
				filePage--;
				rebuildWidgets();
			}).bounds(left, y, 30, 18).build()).active = filePage > 0;
			addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
				filePage++;
				rebuildWidgets();
			}).bounds(left + 280, y, 30, 18).build()).active = filePage < pages - 1;
			labels.add(new Object[] {"파일 " + (filePage + 1) + "/" + pages + " 쪽 (" + files.size() + "개)", this.width / 2, y + 5, 0xFFAAAAAA, true});
			y += 22;
		}

		if (picked == null) {
			labels.add(new Object[] {"위에서 설계도를 고르면 크기와 필요한 재료를 보여줘요", this.width / 2, y + 6, 0xFFAAAAAA, true});
			return;
		}
		if (error != null) {
			labels.add(new Object[] {"읽지 못했어요: " + error, this.width / 2, y + 6, 0xFFFF5555, true});
			return;
		}
		// Summary.
		labels.add(new Object[] {"크기 " + plan.sizeX + " × " + plan.sizeY + " × " + plan.sizeZ + " · 블록 " + plan.solidCount() + "개 · " + plan.blockCounts().size() + "가지 · 재료 약 "
				+ materials.stacks() + "묶음", left, y, 0xFFFFFFFF, false});
		y += LINE;
		StringBuilder extra = new StringBuilder();
		if (!materials.unobtainable.isEmpty()) extra.append("서바이벌에서 못 구함 ").append(sum(materials.unobtainable)).append("개 (건너뜀) · ");
		if (!materials.special.isEmpty()) extra.append("밭·작물처럼 만들어야 하는 것 ").append(sum(materials.special)).append("개 · ");
		if (plan.blockEntities + plan.entities > 0) extra.append("내용물(글씨·상자 속)·그림 ").append(plan.blockEntities + plan.entities).append("개는 블록만");
		if (extra.length() > 0) {
			labels.add(new Object[] {extra.toString(), left, y, 0xFFAAAAAA, false});
			y += LINE;
		}
		y += 3;

		// Materials, page by page: needed / carried / missing.
		Map<String, Integer> have = carried();
		List<Map.Entry<String, Integer>> list = materials.sorted();
		int missingKinds = 0;
		for (Map.Entry<String, Integer> e : list) if (have.getOrDefault(e.getKey(), 0) < e.getValue()) missingKinds++;
		labels.add(new Object[] {"재료 " + list.size() + "가지 · 모자란 것 " + missingKinds + "가지 (가진 것은 인벤토리 기준)", left, y, 0xFFFFFF55, false});
		y += LINE + 2;
		labels.add(new Object[] {"재료", left, y, 0xFFAAAAAA, false});
		labels.add(new Object[] {"필요", left + 190, y, 0xFFAAAAAA, false});
		labels.add(new Object[] {"가짐", left + 230, y, 0xFFAAAAAA, false});
		labels.add(new Object[] {"모자람", left + 270, y, 0xFFAAAAAA, false});
		y += LINE;
		int rows = Math.max(4, (this.height - y - 30) / LINE);
		int mPages = Math.max(1, (list.size() + rows - 1) / rows);
		matPage = Math.max(0, Math.min(matPage, mPages - 1));
		for (int i = matPage * rows; i < Math.min(list.size(), (matPage + 1) * rows); i++) {
			Map.Entry<String, Integer> e = list.get(i);
			int need = e.getValue(), got = have.getOrDefault(e.getKey(), 0), miss = Math.max(0, need - got);
			labels.add(new Object[] {shorten(itemName(e.getKey()), 30), left, y, 0xFFFFFFFF, false});
			labels.add(new Object[] {String.valueOf(need), left + 190, y, 0xFFFFFFFF, false});
			labels.add(new Object[] {String.valueOf(got), left + 230, y, got > 0 ? 0xFF55FF55 : 0xFF888888, false});
			labels.add(new Object[] {miss == 0 ? "-" : String.valueOf(miss), left + 270, y, miss == 0 ? 0xFF55FF55 : 0xFFFF7777, false});
			y += LINE;
		}
		if (mPages > 1) {
			int by = this.height - 24;
			addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
				matPage--;
				rebuildWidgets();
			}).bounds(left, by, 30, 18).build()).active = matPage > 0;
			addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
				matPage++;
				rebuildWidgets();
			}).bounds(left + 280, by, 30, 18).build()).active = matPage < mPages - 1;
			labels.add(new Object[] {"재료 " + (matPage + 1) + "/" + mPages + " 쪽", this.width / 2, by + 5, 0xFFAAAAAA, true});
		}
	}

	private static int sum(Map<String, Integer> m) {
		int n = 0;
		for (int v : m.values()) n += v;
		return n;
	}

	private static String shorten(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
		super.extractRenderState(g, mx, my, delta);
		g.centeredText(this.font, this.title, this.width / 2, 9, 0xFFFFFFFF);
		for (Object[] l : labels) {
			if ((boolean) l[4]) g.centeredText(this.font, Component.literal((String) l[0]), (int) l[1], (int) l[2], (int) l[3]);
			else g.text(this.font, Component.literal((String) l[0]).getVisualOrderText(), (int) l[1], (int) l[2], (int) l[3], true);
		}
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(parent);
	}
}
