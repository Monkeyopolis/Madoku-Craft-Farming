package madoku.craft.java.farming;

import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Optional bridge for the loot-table service used by managed crop yields. */
public interface FarmingLootAdapter {
	default List<ItemStack> generateManagedLootForTable(String tableId, RandomSource random) {
		return List.of();
	}

	default boolean hasManagedLootTable(String tableId) {
		return false;
	}
}
