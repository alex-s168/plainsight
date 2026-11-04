package dev.plainsight.guess;

import dev.plainsight.mixin.TemplateStructurePieceAccessor;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a located structure is exactly, from the pieces it was generated with: a bastion's type, whether an
 * end city has a ship, an igloo a basement, a village's number of houses, ... Null when there is nothing
 * more to tell than the structure's id.
 */
public final class StructureDetails {
	private StructureDetails() {
	}

	public static @Nullable String describe(String structureId, StructureStart start) {
		List<String> templates = templates(start);
		String id = structureId.substring(structureId.indexOf(':') + 1);
		return describe(id, templates, start.getPieces());
	}

	/** By structure path ("bastion_remnant") and the template ids of its pieces (first = start piece). */
	static @Nullable String describe(String id, List<String> templates, List<StructurePiece> pieces) {
		String first = templates.isEmpty() ? "" : templates.get(0);
		if (id.equals("bastion_remnant")) {
			if (first.contains("bastion/units/")) return "housing units";
			if (first.contains("bastion/hoglin_stable/")) return "hoglin stables";
			if (first.contains("bastion/treasure/")) return "treasure room";
			if (first.contains("bastion/bridge/")) return "bridge";
			return null;
		}
		if (id.equals("end_city")) return templates.stream().anyMatch(t -> t.endsWith("ship")) ? "with an end ship" : "no end ship";
		if (id.equals("igloo")) return templates.stream().anyMatch(t -> t.contains("igloo/bottom")) ? "with a basement" : "no basement";
		if (id.startsWith("shipwreck")) return first.isEmpty() ? null : words(after(first, "shipwreck/"));
		if (id.startsWith("ruined_portal")) return first.contains("giant_portal") ? "giant" : first.isEmpty() ? null : "small";
		if (id.startsWith("ocean_ruin")) return first.contains("big_") ? "big" : first.isEmpty() ? null : "small";
		if (id.startsWith("village_")) {
			long houses = templates.stream().filter(t -> t.contains("/houses/")).count();
			return houses + (houses == 1 ? " house" : " houses");
		}
		if (id.equals("fortress")) {
			long spawners = pieces.stream().filter(p -> p.getClass().getSimpleName().equals("MonsterThrone")).count();
			return spawners + (spawners == 1 ? " blaze spawner" : " blaze spawners");
		}
		return null;
	}

	/** The template id each piece was placed from ("" for pieces without one), in generation order. */
	private static List<String> templates(StructureStart start) {
		List<String> out = new ArrayList<>();
		for (StructurePiece p : start.getPieces()) {
			String t = "";
			try {
				if (p instanceof PoolElementStructurePiece pool && pool.getElement() instanceof SinglePoolElement single) t = single.getTemplateLocation().toString();
				else if (p instanceof TemplateStructurePiece template) t = ((TemplateStructurePieceAccessor) template).plainsight$templateName();
			} catch (RuntimeException e) {
				// a template given directly (not by id): nothing to tell
			}
			out.add(t);
		}
		return out;
	}

	private static String after(String s, String marker) {
		int i = s.indexOf(marker);
		return i < 0 ? s : s.substring(i + marker.length());
	}

	/** "rightsideup_backhalf_degraded" -> "rightsideup backhalf degraded". */
	private static String words(String s) {
		return s.replace('_', ' ').replace('/', ' ').toLowerCase(Locale.ROOT).trim();
	}
}
