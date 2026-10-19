package madoku.craft.java.farming;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Shared soil classification used by server farming actions and client indicators. */
public final class FarmingSoilRules {
	private FarmingSoilRules() {
	}

	public static boolean isFarmingSoil(BlockState state) {
		return state != null
			&& (state.is(Blocks.FARMLAND)
				|| state.is(Blocks.GRASS_BLOCK)
				|| state.is(BlockTags.DIRT)
				|| state.is(BlockTags.SAND));
	}
}
