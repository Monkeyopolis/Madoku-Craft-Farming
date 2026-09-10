package madoku.craft.java.farming;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** Built-in provider backed by the Madoku Farming implementation. */
public final class MadokuFarmingProvider implements FarmingProvider {
	@Override public void initialize() { MadokuFarmingManager.initialize(); }
	@Override public void reset() { MadokuFarmingManager.reset(); }
	@Override public void onServerStarted(MinecraftServer server) { MadokuFarmingManager.onServerStarted(server); }
	@Override public void loadPersistedData(MinecraftServer server) { MadokuFarmingManager.loadPersistedData(server); }
	@Override public void autosavePersistedData(MinecraftServer server) { MadokuFarmingManager.autosavePersistedData(server); }
	@Override public void savePersistedData(MinecraftServer server) { MadokuFarmingManager.savePersistedData(server); }
	@Override public void applyCropItemMetadata() { MadokuFarmingManager.applyCropItemMetadata(); }
	@Override public boolean isEnabled() { return MadokuFarmingManager.isEnabled(); }
	@Override public boolean isComposterEnabled() { return FarmingComposterManager.isEnabled(); }
	@Override public boolean applyExternalGrowthPercent(ServerLevel world, BlockPos cropPos, double growthPercent, String source) { return MadokuFarmingManager.applyExternalGrowthPercent(world, cropPos, growthPercent, source); }
	@Override public boolean isCropPlantItem(ItemStack stack) { return MadokuFarmingManager.isCropPlantItem(stack); }
	@Override public boolean isFarmland(BlockState state) { return MadokuFarmingManager.isFarmland(state); }
	@Override public boolean isManagedCrop(BlockState state) { return MadokuFarmingManager.isManagedCrop(state); }
	@Override public boolean isManagedCrop(ServerLevel world, BlockPos cropPos, BlockState state) { return MadokuFarmingManager.isManagedCrop(world, cropPos, state); }
	@Override public boolean isFertilized(ServerLevel world, BlockPos soilPos) { return MadokuFarmingManager.isFertilized(world, soilPos); }
	@Override public boolean isManagedPlot(ServerLevel world, BlockPos soilPos) { return MadokuFarmingManager.isManagedPlot(world, soilPos); }
	@Override public void fertilizeSoil(ServerLevel world, BlockPos soilPos) { MadokuFarmingManager.fertilizeSoil(world, soilPos); }
	@Override public void syncPlotFromSoil(ServerLevel world, BlockPos soilPos, boolean fertilized) { MadokuFarmingManager.syncPlotFromSoil(world, soilPos, fertilized); }
	@Override public void handleFarmlandRandomTick(ServerLevel world, BlockPos soilPos) { MadokuFarmingManager.handleFarmlandRandomTick(world, soilPos); }
	@Override public boolean shouldMaintainSeasonalMoisture(ServerLevel world, BlockPos soilPos) { return MadokuFarmingManager.shouldMaintainSeasonalMoisture(world, soilPos); }
	@Override public boolean applySeasonalMoisture(ServerLevel world, BlockPos soilPos, BlockState soilState) { return MadokuFarmingManager.applySeasonalMoisture(world, soilPos, soilState); }
	@Override public void trackCrop(ServerLevel world, BlockPos cropPos, BlockState cropState) { MadokuFarmingManager.trackCrop(world, cropPos, cropState); }
	@Override public boolean handleCropRandomTick(ServerLevel world, BlockPos cropPos, BlockState state, RandomSource random) { return MadokuFarmingManager.handleCropRandomTick(world, cropPos, state, random); }
	@Override public boolean isCropHarvestReady(ServerLevel world, BlockPos cropPos, BlockState state) { return MadokuFarmingManager.isCropHarvestReady(world, cropPos, state); }
	@Override public boolean isManagedHarvestState(ServerLevel world, BlockPos cropPos, BlockState state) { return MadokuFarmingManager.isManagedHarvestState(world, cropPos, state); }
	@Override public void prepareCropHarvest(ServerLevel world, BlockPos cropPos, BlockState state) { MadokuFarmingManager.prepareCropHarvest(world, cropPos, state); }
	@Override public List<ItemStack> calculateCropHarvestDrops(ServerLevel world, BlockPos cropPos, BlockState state, RandomSource random) { return MadokuFarmingManager.calculateCropHarvestDrops(world, cropPos, state, random); }
	@Override public boolean hasCropHarvestLootTable(ServerLevel world, BlockPos cropPos, BlockState state) { return MadokuFarmingManager.hasCropHarvestLootTable(world, cropPos, state); }
	@Override public void completeCropHarvest(ServerLevel world, BlockPos cropPos, BlockState state) { MadokuFarmingManager.completeCropHarvest(world, cropPos, state); }
	@Override public boolean isComposterItem(Item item) { return FarmingComposterManager.isComposterItem(item); }
	@Override public boolean isComposterItem(ItemStack stack) { return FarmingComposterManager.isComposterItem(stack); }
	@Override public int getComposterAdjustment(ItemStack stack) { return FarmingComposterManager.getComposterAdjustment(stack); }
}
