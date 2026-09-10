package madoku.craft.java.farming;

import madoku.craft.java.core.data.DataSaveParticipant;
import madoku.craft.java.core.data.DataSaveParticipantAPIManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/** Public contract for the Madoku Farming subsystem. */
public final class FarmingAPIManager {
	private static final FarmingProvider UNAVAILABLE_PROVIDER = new FarmingProvider() { };
	private static volatile FarmingProvider provider = UNAVAILABLE_PROVIDER;
	private static final FarmingLootAdapter NO_LOOT_ADAPTER = new FarmingLootAdapter() { };
	private static volatile FarmingLootAdapter lootAdapter = NO_LOOT_ADAPTER;
	private static final DataSaveParticipant DATA_SAVE_PARTICIPANT = new DataSaveParticipant() {
		@Override public String id() { return "farming"; }
		@Override public void autosavePersistedData(MinecraftServer server) { FarmingAPIManager.autosavePersistedData(server); }
		@Override public void savePersistedData(MinecraftServer server) { FarmingAPIManager.savePersistedData(server); }
	};

	private FarmingAPIManager() { }

	public static void registerProvider(FarmingProvider candidate) {
		if (candidate == null) throw new IllegalArgumentException("Farming provider must not be null.");
		provider = candidate;
		DataSaveParticipantAPIManager.register(DATA_SAVE_PARTICIPANT);
	}
	public static void unregisterProvider() {
		provider = UNAVAILABLE_PROVIDER;
		DataSaveParticipantAPIManager.unregister(DATA_SAVE_PARTICIPANT.id());
	}
	public static void registerLootAdapter(FarmingLootAdapter candidate) {
		if (candidate == null) throw new IllegalArgumentException("Farming loot adapter must not be null.");
		lootAdapter = candidate;
	}
	public static void unregisterLootAdapter() { lootAdapter = NO_LOOT_ADAPTER; }
	public static List<ItemStack> generateManagedLootForTable(String tableId, RandomSource random) {
		return lootAdapter.generateManagedLootForTable(tableId, random);
	}
	public static boolean hasManagedLootTable(String tableId) {
		return lootAdapter.hasManagedLootTable(tableId);
	}
	public static void initialize() { provider.initialize(); }
	public static void reset() { provider.reset(); }
	public static void onServerStarted(MinecraftServer server) { provider.onServerStarted(server); }
	public static void loadPersistedData(MinecraftServer server) { provider.loadPersistedData(server); }
	public static void autosavePersistedData(MinecraftServer server) { provider.autosavePersistedData(server); }
	public static void savePersistedData(MinecraftServer server) { provider.savePersistedData(server); }
	public static void applyCropItemMetadata() { provider.applyCropItemMetadata(); }
	public static boolean isEnabled() { return provider.isEnabled(); }
	public static boolean isComposterEnabled() { return provider.isComposterEnabled(); }
	public static boolean applyExternalGrowthPercent(ServerLevel world, BlockPos cropPos, double growthPercent, String source) { return provider.applyExternalGrowthPercent(world, cropPos, growthPercent, source); }
	public static boolean isCropPlantItem(ItemStack stack) { return provider.isCropPlantItem(stack); }
	public static boolean isFarmland(BlockState state) { return provider.isFarmland(state); }
	public static boolean isManagedCrop(BlockState state) { return provider.isManagedCrop(state); }
	public static boolean isManagedCrop(ServerLevel world, BlockPos cropPos, BlockState state) { return provider.isManagedCrop(world, cropPos, state); }
	public static boolean isFertilized(ServerLevel world, BlockPos soilPos) { return provider.isFertilized(world, soilPos); }
	public static boolean isManagedPlot(ServerLevel world, BlockPos soilPos) { return provider.isManagedPlot(world, soilPos); }
	public static void fertilizeSoil(ServerLevel world, BlockPos soilPos) { provider.fertilizeSoil(world, soilPos); }
	public static void syncPlotFromSoil(ServerLevel world, BlockPos soilPos, boolean fertilized) { provider.syncPlotFromSoil(world, soilPos, fertilized); }
	public static void handleFarmlandRandomTick(ServerLevel world, BlockPos soilPos) { provider.handleFarmlandRandomTick(world, soilPos); }
	public static boolean shouldMaintainSeasonalMoisture(ServerLevel world, BlockPos soilPos) { return provider.shouldMaintainSeasonalMoisture(world, soilPos); }
	public static boolean applySeasonalMoisture(ServerLevel world, BlockPos soilPos, BlockState soilState) { return provider.applySeasonalMoisture(world, soilPos, soilState); }
	public static void trackCrop(ServerLevel world, BlockPos cropPos, BlockState cropState) { provider.trackCrop(world, cropPos, cropState); }
	public static boolean handleCropRandomTick(ServerLevel world, BlockPos cropPos, BlockState state, RandomSource random) { return provider.handleCropRandomTick(world, cropPos, state, random); }
	public static boolean isCropHarvestReady(ServerLevel world, BlockPos cropPos, BlockState state) { return provider.isCropHarvestReady(world, cropPos, state); }
	public static boolean isManagedHarvestState(ServerLevel world, BlockPos cropPos, BlockState state) { return provider.isManagedHarvestState(world, cropPos, state); }
	public static void prepareCropHarvest(ServerLevel world, BlockPos cropPos, BlockState state) { provider.prepareCropHarvest(world, cropPos, state); }
	public static List<ItemStack> calculateCropHarvestDrops(ServerLevel world, BlockPos cropPos, BlockState state, RandomSource random) { return provider.calculateCropHarvestDrops(world, cropPos, state, random); }
	public static boolean hasCropHarvestLootTable(ServerLevel world, BlockPos cropPos, BlockState state) { return provider.hasCropHarvestLootTable(world, cropPos, state); }
	public static void completeCropHarvest(ServerLevel world, BlockPos cropPos, BlockState state) { provider.completeCropHarvest(world, cropPos, state); }
	public static boolean isComposterItem(Item item) { return provider.isComposterItem(item); }
	public static boolean isComposterItem(ItemStack stack) { return provider.isComposterItem(stack); }
	public static int getComposterAdjustment(ItemStack stack) { return provider.getComposterAdjustment(stack); }
}
