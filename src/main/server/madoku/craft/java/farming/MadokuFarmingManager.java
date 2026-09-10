package madoku.craft.java.farming;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** Orchestrates Madoku Farming's configuration and runtime subsystems. */
public final class MadokuFarmingManager {
	private MadokuFarmingManager() {
	}

	public static void initialize() {
		FarmingAPIManager.registerProvider(new MadokuFarmingProvider());
		FarmingConfigManager.initialize();
		FarmingCropsManager.initialize();
		FarmingComposterManager.initialize();
	}

	public static void reset() {
		FarmingCropsManager.reset();
		FarmingComposterManager.reset();
	}

	public static void onServerStarted(MinecraftServer server) {
		FarmingCropsManager.onServerStarted(server);
	}

	public static void loadPersistedData(MinecraftServer server) {
		FarmingCropsManager.loadPersistedData(server);
	}

	public static void autosavePersistedData(MinecraftServer server) {
		FarmingCropsManager.autosavePersistedData(server);
	}

	public static void savePersistedData(MinecraftServer server) {
		FarmingCropsManager.savePersistedData(server);
	}

	public static void applyCropItemMetadata() {
		FarmingCropsManager.applyCropItemMetadata();
	}

	public static boolean isEnabled() {
		return FarmingCropsManager.isEnabled();
	}

	public static boolean isComposterItem(net.minecraft.world.item.Item item) { return FarmingComposterManager.isComposterItem(item); }
	public static boolean isComposterItem(ItemStack stack) { return FarmingComposterManager.isComposterItem(stack); }
	public static int getComposterAdjustment(ItemStack stack) { return FarmingComposterManager.getComposterAdjustment(stack); }

	public static boolean applyExternalGrowthPercent(ServerLevel world, BlockPos cropPos, double growthPercent, String source) {
		return FarmingCropsManager.applyExternalGrowthPercent(world, cropPos, growthPercent, source);
	}

	public static boolean isCropPlantItem(ItemStack stack) { return FarmingCropsManager.isCropPlantItem(stack); }
	public static boolean isFarmland(BlockState state) { return FarmingCropsManager.isFarmland(state); }
	public static boolean isManagedCrop(BlockState state) { return FarmingCropsManager.isManagedCrop(state); }
	public static boolean isManagedCrop(ServerLevel world, BlockPos cropPos, BlockState state) {
		return FarmingCropsManager.isManagedCrop(world, cropPos, state);
	}
	public static boolean isFertilized(ServerLevel world, BlockPos soilPos) {
		return FarmingPlotsManager.isFertilized(world, soilPos);
	}
	public static boolean isManagedPlot(ServerLevel world, BlockPos soilPos) {
		return FarmingPlotsManager.isManagedPlot(world, soilPos);
	}
	public static void fertilizeSoil(ServerLevel world, BlockPos soilPos) {
		FarmingPlotsManager.fertilizeSoil(world, soilPos);
	}
	public static void syncPlotFromSoil(ServerLevel world, BlockPos soilPos, boolean fertilized) {
		FarmingPlotsManager.syncPlotFromSoil(world, soilPos, fertilized);
	}
	public static void handleFarmlandRandomTick(ServerLevel world, BlockPos soilPos) {
		FarmingPlotsManager.handleFarmlandRandomTick(world, soilPos);
	}
	public static boolean shouldMaintainSeasonalMoisture(ServerLevel world, BlockPos soilPos) {
		return FarmingPlotsManager.shouldMaintainSeasonalMoisture(world, soilPos);
	}
	public static boolean applySeasonalMoisture(ServerLevel world, BlockPos soilPos, BlockState soilState) {
		return FarmingPlotsManager.applySeasonalMoisture(world, soilPos, soilState);
	}
	public static void trackCrop(ServerLevel world, BlockPos cropPos, BlockState cropState) {
		FarmingCropsManager.trackCrop(world, cropPos, cropState);
	}
	public static boolean handleCropRandomTick(ServerLevel world, BlockPos cropPos, BlockState state, RandomSource random) {
		return FarmingCropsManager.handleCropRandomTick(world, cropPos, state, random);
	}
	public static boolean isCropHarvestReady(ServerLevel world, BlockPos cropPos, BlockState state) {
		return FarmingCropsManager.isCropHarvestReady(world, cropPos, state);
	}
	public static boolean isManagedHarvestState(ServerLevel world, BlockPos cropPos, BlockState state) {
		return FarmingCropsManager.isManagedHarvestState(world, cropPos, state);
	}
	public static void prepareCropHarvest(ServerLevel world, BlockPos cropPos, BlockState state) {
		FarmingCropsManager.prepareCropHarvest(world, cropPos, state);
	}
	public static List<ItemStack> calculateCropHarvestDrops(ServerLevel world, BlockPos cropPos, BlockState state, RandomSource random) {
		return FarmingCropsManager.calculateCropHarvestDrops(world, cropPos, state, random);
	}
	public static boolean hasCropHarvestLootTable(ServerLevel world, BlockPos cropPos, BlockState state) {
		return FarmingCropsManager.hasCropHarvestLootTable(world, cropPos, state);
	}
	public static void completeCropHarvest(ServerLevel world, BlockPos cropPos, BlockState state) {
		FarmingCropsManager.completeCropHarvest(world, cropPos, state);
	}
}
