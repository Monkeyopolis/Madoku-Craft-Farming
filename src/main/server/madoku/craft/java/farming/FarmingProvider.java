package madoku.craft.java.farming;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** Provider contract for the Madoku Farming subsystem. */
public interface FarmingProvider {
	default void initialize() { }
	default void reset() { }
	default void onServerStarted(MinecraftServer server) { }
	default void loadPersistedData(MinecraftServer server) { }
	default void autosavePersistedData(MinecraftServer server) { }
	default void savePersistedData(MinecraftServer server) { }
	default void applyCropItemMetadata() { }
	default boolean isEnabled() { return false; }
	default boolean isComposterEnabled() { return false; }
	default boolean applyExternalGrowthPercent(ServerLevel world, BlockPos cropPos, double growthPercent, String source) { return false; }
	default boolean isCropPlantItem(ItemStack stack) { return false; }
	default boolean isFarmland(BlockState state) { return false; }
	default boolean isManagedCrop(BlockState state) { return false; }
	default boolean isManagedCrop(ServerLevel world, BlockPos cropPos, BlockState state) { return false; }
	default boolean isFertilized(ServerLevel world, BlockPos soilPos) { return false; }
	default boolean isManagedPlot(ServerLevel world, BlockPos soilPos) { return false; }
	default void fertilizeSoil(ServerLevel world, BlockPos soilPos) { }
	default void syncPlotFromSoil(ServerLevel world, BlockPos soilPos, boolean fertilized) { }
	default void handleFarmlandRandomTick(ServerLevel world, BlockPos soilPos) { }
	default boolean shouldMaintainSeasonalMoisture(ServerLevel world, BlockPos soilPos) { return false; }
	default boolean applySeasonalMoisture(ServerLevel world, BlockPos soilPos, BlockState soilState) { return false; }
	default void trackCrop(ServerLevel world, BlockPos cropPos, BlockState cropState) { }
	default boolean handleCropRandomTick(ServerLevel world, BlockPos cropPos, BlockState state, RandomSource random) { return false; }
	default boolean isCropHarvestReady(ServerLevel world, BlockPos cropPos, BlockState state) { return false; }
	default boolean isManagedHarvestState(ServerLevel world, BlockPos cropPos, BlockState state) { return false; }
	default void prepareCropHarvest(ServerLevel world, BlockPos cropPos, BlockState state) { }
	default List<ItemStack> calculateCropHarvestDrops(ServerLevel world, BlockPos cropPos, BlockState state, RandomSource random) { return List.of(); }
	default boolean hasCropHarvestLootTable(ServerLevel world, BlockPos cropPos, BlockState state) { return false; }
	default void completeCropHarvest(ServerLevel world, BlockPos cropPos, BlockState state) { }
	default boolean isComposterItem(Item item) { return false; }
	default boolean isComposterItem(ItemStack stack) { return false; }
	default int getComposterAdjustment(ItemStack stack) { return 1; }
}
