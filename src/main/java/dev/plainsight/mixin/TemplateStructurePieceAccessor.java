package dev.plainsight.mixin;

import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Which template a structure piece was placed from (/plainsight locate tells structure variants by it). */
@Mixin(TemplateStructurePiece.class)
public interface TemplateStructurePieceAccessor {
	@Accessor("templateName")
	String plainsight$templateName();
}
