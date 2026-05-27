package madoku.craft.farming.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import madoku.craft.chunk.ChunkManagerSystem;
import madoku.craft.clock.MadokuTicks;
import madoku.craft.config.JsonManagerSystem;
import madoku.craft.config.JsonStaticSystem;
import madoku.craft.data.DataManagerSystem;
import madoku.craft.debug.MadokuDebug;
import madoku.craft.farming.mixin.ItemBuiltInRegistryHolderAccessor;
import madoku.craft.farming.mixin.ItemComponentsAccessor;
import madoku.craft.scheduler.SchedulerManagerSystem;
import madoku.craft.season.MadokuSeason;
import madoku.craft.season.MadokuSeasonConfig;
import madoku.craft.time.MadokuTime;
import net.minecraft.ChatFormatting;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public final class MadokuFarming {
	private static final Logger LOGGER = LoggerFactory.getLogger(MadokuFarming.class);
	private static final String LORE_SEASON_PREFIX = "Season:";
	private static final String LORE_GROWING_TIME_PREFIX = "Growing Time:";
	private static final String LORE_FERTILIZER_PREFIX = "Farmland fertilizer.";
	private static final String LORE_FERTILIZER_EFFECT_PREFIX = "Increases crop growth speed and yield.";
	private static final String[] SEASON_ORDER = {"spring", "summer", "fall", "winter"};

	private static final String FARMING_CONFIG_ROOT_FOLDER_NAME = "madoku-craft-farming";
	private static final String FARMING_CONFIG_FILE_NAME = "madoku-farming";
	private static final String CROP_CONFIG_ROOT_FOLDER_NAME = FARMING_CONFIG_ROOT_FOLDER_NAME + "/madoku-crops";
	private static final String DATA_FOLDER_NAME = "madoku-craft-farming";
	private static final String DATA_FILE_NAME = "madoku-farming";
	private static final String FARMING_DISCOVERY_SCHEDULER_OWNER_ID = "farming_discovery_gameplay";
	private static final String FARMING_PROCESS_SCHEDULER_OWNER_ID = "farming_process_gameplay";
	private static final String TASK_TYPE_FARMING_DISCOVERY_TICK = "farming_discovery_gameplay_tick";
	private static final String TASK_TYPE_FARMING_PROCESS_TICK = "farming_process_gameplay_tick";
	private static final long FARMING_SCHEDULER_MIN_INTERVAL_TICKS = 1L;
	private static final long FARMING_SCHEDULER_MAX_INTERVAL_TICKS = 20L;
	private static final String CHUNK_PROCESSOR_FARMING_DISCOVERY_ID = "farming_discovery";

	private static final String FIELD_PLOTS = "plots";
	private static final String FIELD_CROPS = "crops";
	private static final String FIELD_CHUNK_CURSOR = "chunk-cursor";

	private static final String FIELD_LEVEL_ID = "level-id";
	private static final String FIELD_SOIL_POS = "soil-pos";
	private static final String FIELD_CROP_POS = "crop-pos";
	private static final String FIELD_CROP_ID = "crop-id";
	private static final String FIELD_FERTILIZED = "fertilized";
	private static final String FIELD_FERTILIZED_AT_GAMEPLAY_TICK = "fertilized-at-gameplay-tick";
	private static final String FIELD_DISCOVERED_SEASON_ID = "discovered-season-id";
	private static final String FIELD_REQUIRED_GROWTH_TICKS = "required-growth-ticks";
	private static final String FIELD_PROGRESS_GROWTH_TICKS = "progress-growth-ticks";
	private static final String FIELD_LAST_PROCESSED_ABSOLUTE_DAY_TIME = "last-processed-absolute-day-time";

	private static final int CROP_MAX_AGE = 7;
	private static final long PENDING_HARVEST_TTL_TICKS = 2L;
	private static final long ABSOLUTE_TIME_ROLLBACK_RESET_TICKS = 20L;
	private static final long CHUNK_DEBUG_INTERVAL_TICKS = 200L;
	private static final long PARTICLE_COOLDOWN_MIN_TICKS = 100L;
	private static final long PARTICLE_COOLDOWN_MAX_TICKS = 180L;

	private static volatile Settings settings = Settings.defaults();
	private static volatile String farmingDiscoverySchedulerId = "";
	private static volatile String farmingProcessSchedulerId = "";
	private static volatile boolean farmingProcessTaskScheduled = false;
	private static volatile long lastAutosaveBucket = Long.MIN_VALUE;
	private static volatile long lastChunkProcessDebugTick = Long.MIN_VALUE;
	private static volatile long lastChunkPickDebugTick = Long.MIN_VALUE;
	private static volatile long lastChunkDiscoverDebugTick = Long.MIN_VALUE;
	private static volatile int chunkScanCursor = 0;
	private static volatile boolean dirty = false;

	private static volatile Map<String, CropRule> cropRulesByPlantingItemId = new LinkedHashMap<>();
	private static volatile Map<String, CropRule> cropRulesByCropBlockId = new LinkedHashMap<>();
	private static volatile Map<String, CropRule> cropRulesByMatureBlockId = new LinkedHashMap<>();

	private static final Map<String, PlotState> plotsByKey = new LinkedHashMap<>();
	private static final Map<String, CropState> cropsByKey = new LinkedHashMap<>();
	private static final Map<ChunkRefKey, Set<String>> plotKeysByChunk = new LinkedHashMap<>();
	private static final Map<ChunkRefKey, Set<String>> cropKeysByChunk = new LinkedHashMap<>();
	private static final Map<String, PendingHarvestRule> pendingHarvestRulesByKey = new LinkedHashMap<>();

	private static final ChunkManagerSystem.ChunkLifecycleListener FARMING_CHUNK_LISTENER = new ChunkManagerSystem.ChunkLifecycleListener() {
		@Override
		public void onChunkLoaded(ServerLevel level, int chunkX, int chunkZ) {
			MadokuFarming.onTrackedChunkLoaded(level, chunkX, chunkZ);
		}

		@Override
		public void onChunkUnloaded(ServerLevel level, int chunkX, int chunkZ) {
			MadokuFarming.onTrackedChunkUnloaded(level, chunkX, chunkZ);
		}
	};
	private static final ChunkManagerSystem.ChunkProcessor FARMING_CHUNK_PROCESSOR = new ChunkManagerSystem.ChunkProcessor() {
		@Override
		public boolean acceptsWorld(ServerLevel level) {
			return settings.enabled && level != null;
		}

		@Override
		public boolean requiresSurfaceColumns() {
			return false;
		}

		@Override
		public void discoverLoadedChunk(ServerLevel level, int chunkX, int chunkZ, ChunkManagerSystem.ChunkDiscoverySnapshot snapshot) {
			discoverTrackableBlocksInChunk(level, chunkX, chunkZ, snapshot);
		}

		@Override
		public void processTrackedChunk(ServerLevel level, int chunkX, int chunkZ) {
			if (level == null || !ChunkManagerSystem.isChunkLoaded(level, chunkX, chunkZ)) {
				return;
			}
			processTrackedBlocksInChunk(level, chunkX, chunkZ);
		}
	};

	private MadokuFarming() {
	}

	public static void initialize() {
		loadStaticConfig();
		loadCropConfigs();
		ChunkManagerSystem.registerChunkLifecycleListener(FARMING_CHUNK_LISTENER);
		ChunkManagerSystem.registerChunkProcessor(CHUNK_PROCESSOR_FARMING_DISCOVERY_ID, FARMING_CHUNK_PROCESSOR);
		SchedulerManagerSystem.registerTaskHandler(TASK_TYPE_FARMING_DISCOVERY_TICK, MadokuFarming::runFarmingDiscoveryTask);
		SchedulerManagerSystem.registerTaskHandler(TASK_TYPE_FARMING_PROCESS_TICK, MadokuFarming::runFarmingProcessTask);
		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) ->
			handleBlockBreakBefore(world, pos, state, blockEntity)
		);
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) ->
			handleBlockBreak(world, pos, state, blockEntity)
		);
	}

	public static void reset() {
		plotsByKey.clear();
		cropsByKey.clear();
		plotKeysByChunk.clear();
		cropKeysByChunk.clear();
		pendingHarvestRulesByKey.clear();
		ChunkManagerSystem.resetChunkProcessor(CHUNK_PROCESSOR_FARMING_DISCOVERY_ID);
		farmingDiscoverySchedulerId = "";
		farmingProcessSchedulerId = "";
		farmingProcessTaskScheduled = false;
		lastAutosaveBucket = Long.MIN_VALUE;
		resetChunkProcessingCycle();
		dirty = false;
		SchedulerManagerSystem.clearAdaptiveDelayState(FARMING_PROCESS_SCHEDULER_OWNER_ID);
	}

	public static void onServerStarted(MinecraftServer server) {
		if (server == null) {
			return;
		}
		syncChunkProcessorActivation();
		SchedulerManagerSystem.clearAdaptiveDelayState(FARMING_PROCESS_SCHEDULER_OWNER_ID);
		applyCropItemMetadata();
		ChunkManagerSystem.resetChunkProcessor(CHUNK_PROCESSOR_FARMING_DISCOVERY_ID);
		resetChunkProcessingCycle();
		rebuildTrackedChunkStateFromIndexes();
		farmingDiscoverySchedulerId = SchedulerManagerSystem.createOrGetScheduler(
			SchedulerManagerSystem.SchedulerBinding.global(FARMING_DISCOVERY_SCHEDULER_OWNER_ID)
		);
		farmingProcessSchedulerId = SchedulerManagerSystem.createOrGetScheduler(
			SchedulerManagerSystem.SchedulerBinding.global(FARMING_PROCESS_SCHEDULER_OWNER_ID)
		);
		SchedulerManagerSystem.clearQueuedRequests(farmingDiscoverySchedulerId);
		SchedulerManagerSystem.clearQueuedRequests(farmingProcessSchedulerId);
		requestFarmingProcessing(server, 1L);
	}

	public static void onServerTickIncrement(MinecraftServer server, long tickIncrement) {
		// Farming progression is scheduled in gameplay tick-domain.
	}

	public static void loadPersistedData(MinecraftServer server) {
		if (server == null) {
			return;
		}

		loadStaticConfig();
		loadCropConfigs();
		syncChunkProcessorActivation();
		JsonObject data = DataManagerSystem.loadWorldData(server, DATA_FOLDER_NAME, DATA_FILE_NAME, createDefaultData());
		applyPersistedData(data);
		long autoSaveIntervalTicks = DataManagerSystem.getAutoSaveIntervalTicks(server, DATA_FOLDER_NAME, DATA_FILE_NAME);
		lastAutosaveBucket = Math.floorDiv(MadokuTicks.getGameplayTicks(), autoSaveIntervalTicks);
		dirty = false;
	}

	public static void autosavePersistedData(MinecraftServer server) {
		if (server == null) {
			return;
		}

		long autoSaveIntervalTicks = DataManagerSystem.getAutoSaveIntervalTicks(server, DATA_FOLDER_NAME, DATA_FILE_NAME);
		long bucket = Math.floorDiv(MadokuTicks.getGameplayTicks(), autoSaveIntervalTicks);
		if (bucket == lastAutosaveBucket) {
			return;
		}

		lastAutosaveBucket = bucket;
		if (dirty) {
			savePersistedData(server);
		}
	}

	public static void savePersistedData(MinecraftServer server) {
		if (server == null) {
			return;
		}

		DataManagerSystem.saveWorldData(server, DATA_FOLDER_NAME, DATA_FILE_NAME, toPersistedData());
		dirty = false;
	}

	public static boolean isEnabled() {
		return settings.enabled;
	}

	private static void syncChunkProcessorActivation() {
		ChunkManagerSystem.setChunkProcessorActive(CHUNK_PROCESSOR_FARMING_DISCOVERY_ID, settings.enabled);
	}

	public static boolean isCropPlantItem(ItemStack stack) {
		return resolveCropRuleByPlantingItem(stack) != null;
	}

	public static boolean canPlantCrop(ItemStack stack) {
		CropRule rule = resolveCropRuleByPlantingItem(stack);
		return rule == null || !settings.enabled || isCropGrowingSeason(rule);
	}

	public static boolean canPlantCrop(ItemStack stack, ServerLevel world) {
		CropRule rule = resolveCropRuleByPlantingItem(stack);
		return rule == null || !settings.enabled || isCropGrowingSeason(rule, world);
	}

	public static boolean canPlantCrop(ItemStack stack, String seasonId) {
		CropRule rule = resolveCropRuleByPlantingItem(stack);
		return rule == null || !settings.enabled || isCropGrowingSeason(rule, seasonId);
	}

	public static String getCropSeasonBlockedMessage(ItemStack stack) {
		CropRule rule = resolveCropRuleByPlantingItem(stack);
		return getCropSeasonBlockedMessage(rule, MadokuSeason.getCurrentSeasonId());
	}

	public static String getCropSeasonBlockedMessage(ItemStack stack, ServerLevel world) {
		CropRule rule = resolveCropRuleByPlantingItem(stack);
		return getCropSeasonBlockedMessage(rule, world == null ? MadokuSeason.getCurrentSeasonId() : MadokuSeason.getCurrentSeasonId(world));
	}

	public static String getCropSeasonBlockedMessage(ItemStack stack, String seasonId) {
		CropRule rule = resolveCropRuleByPlantingItem(stack);
		return getCropSeasonBlockedMessage(rule, seasonId);
	}

	public static boolean isFarmland(BlockState state) {
		return state != null && state.getBlock() == Blocks.FARMLAND;
	}

	private static boolean isDryFarmland(BlockState state) {
		if (state == null || !isFarmland(state)) {
			return false;
		}
		for (Property<?> property : state.getProperties()) {
			if (property instanceof IntegerProperty integerProperty && "moisture".equals(property.getName())) {
				Integer moisture = state.getValue(integerProperty);
				return moisture != null && moisture <= 0;
			}
		}
		return false;
	}

	public static boolean isManagedCrop(BlockState state) {
		return resolveCropRuleByCropState(state) != null;
	}

	public static boolean isManagedCrop(ServerLevel world, BlockPos cropPos, BlockState state) {
		if (!settings.enabled || world == null || cropPos == null || state == null) {
			return false;
		}
		if (resolveHarvestRule(world, cropPos, state) != null) {
			return true;
		}
		return resolveCropRuleByCropState(state) != null;
	}

	public static Item getCropHarvestItem(BlockState state) {
		CropRule rule = resolveCropRuleByCropState(state);
		return rule == null ? null : rule.harvestItem();
	}

	public static Item getCropHarvestItem(ServerLevel world, BlockPos cropPos, BlockState state) {
		CropRule rule = resolveHarvestRule(world, cropPos, state);
		return rule == null ? null : rule.harvestItem();
	}

	public static Item getCropSecondaryHarvestItem(BlockState state) {
		CropRule rule = resolveCropRuleByCropState(state);
		return rule == null ? null : rule.secondaryHarvestItem();
	}

	public static Item getCropSecondaryHarvestItem(ServerLevel world, BlockPos cropPos, BlockState state) {
		CropRule rule = resolveHarvestRule(world, cropPos, state);
		return rule == null ? null : rule.secondaryHarvestItem();
	}

	public static boolean isFertilized(ServerLevel world, BlockPos soilPos) {
		PlotState plot = findPlot(world, soilPos);
		return plot != null && plot.fertilized;
	}

	public static boolean isManagedPlot(ServerLevel world, BlockPos soilPos) {
		PlotState plot = findPlot(world, soilPos);
		return plot != null && (plot.fertilized || plot.hasCrop());
	}

	public static void fertilizeSoil(ServerLevel world, BlockPos soilPos) {
		if (!settings.enabled || world == null || soilPos == null) {
			return;
		}

		if (!isFarmland(world.getBlockState(soilPos))) {
			return;
		}

		PlotState plot = getOrCreatePlot(world, soilPos);
		if (plot == null) {
			return;
		}

		long gameplayTick = MadokuTicks.getGameplayTicks();
		if (plot.fertilized && plot.lastParticleEmissionTimeTicks == gameplayTick) {
			return;
		}

		if (!plot.fertilized) {
			plot.fertilized = true;
			plot.fertilizedAtGameplayTick = gameplayTick;
			plot.lastParticleEmissionTimeTicks = Long.MIN_VALUE;
			dirty = true;
		}
		requestFarmingProcessing(world.getServer(), resolveFarmingSchedulerInterval(world.getServer()));
		emitFertilizedParticles(world, soilPos, plot);
	}

	public static void syncPlotFromSoil(ServerLevel world, BlockPos soilPos, boolean fertilized) {
		if (!settings.enabled || world == null || soilPos == null) {
			return;
		}

		BlockState soilState = world.getBlockState(soilPos);
		if (!isFarmland(soilState)) {
			removePlot(world, soilPos);
			return;
		}

		BlockPos cropPos = soilPos.above();
		BlockState cropState = world.getBlockState(cropPos);
		CropRule rule = resolveCropRuleByCropState(cropState);
		if (rule == null && !fertilized) {
			return;
		}

		PlotState plot = getOrCreatePlot(world, soilPos);
		if (plot == null) {
			return;
		}

		boolean changed = false;
		if (fertilized && !plot.fertilized) {
			plot.fertilized = true;
			plot.fertilizedAtGameplayTick = MadokuTicks.getGameplayTicks();
			plot.lastParticleEmissionTimeTicks = Long.MIN_VALUE;
			changed = true;
		}

		if (rule != null) {
			trackCrop(world, cropPos, cropState);
			changed = true;
		}

		if (!plot.hasCrop() && !plot.fertilized) {
			removePlot(world, soilPos);
			return;
		}

		if (changed) {
			dirty = true;
			requestFarmingProcessing(world.getServer(), resolveFarmingSchedulerInterval(world.getServer()));
		}
		if (fertilized && changed) {
			emitFertilizedParticles(world, soilPos, plot);
		}
	}

	public static void registerCropPlanting(ServerLevel world, BlockPos soilPos, ItemStack stack) {
		CropRule rule = resolveCropRuleByPlantingItem(stack);
		if (rule == null) {
			return;
		}
		registerCropPlanting(world, soilPos, rule);
	}

	private static void registerCropPlanting(ServerLevel world, BlockPos soilPos, CropRule rule) {
		if (!settings.enabled || world == null || soilPos == null || rule == null) {
			return;
		}

		if (!isFarmland(world.getBlockState(soilPos))) {
			return;
		}

		BlockPos cropPos = soilPos.above();
		BlockState cropState = world.getBlockState(cropPos);
		if (!isCropBlock(cropState, rule)) {
			return;
		}

		trackCrop(world, cropPos, cropState);
		requestFarmingProcessing(world.getServer(), resolveFarmingSchedulerInterval(world.getServer()));
	}

	public static void trackCrop(ServerLevel world, BlockPos cropPos, BlockState cropState) {
		if (!settings.enabled || world == null || cropPos == null || cropState == null) {
			return;
		}

		CropRule rule = resolveCropRuleByCropState(cropState);
		if (rule == null) {
			return;
		}

		BlockPos soilPos = cropPos.below();
		if (!isFarmland(world.getBlockState(soilPos))) {
			return;
		}

		PlotState plot = getOrCreatePlot(world, soilPos);
		if (plot == null) {
			return;
		}

		String cropKey = cropKey(world, cropPos);
		long nowAbsoluteDayTime = resolveAbsoluteDayTime(world);
		String discoveredSeasonId = normalizeSeasonId(MadokuSeason.getCurrentSeasonId(world));
		double requiredGrowthTicks = resolveCropRequiredGrowthTicks(rule, discoveredSeasonId);
		CropState existing = cropsByKey.get(cropKey);
		boolean cropTypeChanged = existing != null && !rule.plantingItemId().equals(existing.cropId);
		double progressBasisTicks = (existing != null && !cropTypeChanged)
			? Math.max(1.0d, existing.requiredGrowthTicks)
			: requiredGrowthTicks;
		double progressFromState = progressFromAge(
			isCropMatureBlock(cropState, rule) ? getCropAgeLimit(cropState) : getCropAge(cropState),
			getCropAgeLimit(cropState)
		) * progressBasisTicks;

		if (existing == null) {
			existing = new CropState(
				levelId(world),
				cropPos.asLong(),
				soilPos.asLong(),
				rule.plantingItemId(),
				discoveredSeasonId,
				requiredGrowthTicks,
				Math.max(0.0d, progressFromState),
				nowAbsoluteDayTime
			);
		} else {
			existing.soilPos = soilPos.asLong();
			if (cropTypeChanged) {
				existing.cropId = rule.plantingItemId();
				existing.discoveredSeasonId = discoveredSeasonId;
				existing.requiredGrowthTicks = Math.max(1.0d, requiredGrowthTicks);
				existing.progressGrowthTicks = Math.max(0.0d, progressFromState);
				existing.lastProcessedAbsoluteDayTime = Math.max(0L, nowAbsoluteDayTime);
			} else {
				double safeRequired = Math.max(1.0d, existing.requiredGrowthTicks);
				double cappedExistingProgress = Math.max(0.0d, Math.min(safeRequired, existing.progressGrowthTicks));
				int ageLimit = Math.max(1, getCropAgeLimit(cropState));
				double oneAgeStepTicks = safeRequired / ageLimit;
				boolean visiblyRegressed = !isCropMatureBlock(cropState, rule)
					&& !isMaxAge(cropState)
					&& progressFromState + Math.max(1.0d, oneAgeStepTicks) < cappedExistingProgress;
				if (visiblyRegressed) {
					// Replanting at the same position can reuse the previous crop key.
					// If the observed age dropped, reset tracked growth to the observed state.
					existing.discoveredSeasonId = discoveredSeasonId;
					existing.requiredGrowthTicks = Math.max(1.0d, requiredGrowthTicks);
					existing.progressGrowthTicks = Math.max(0.0d, progressFromState);
					existing.lastProcessedAbsoluteDayTime = Math.max(0L, nowAbsoluteDayTime);
				} else {
					existing.progressGrowthTicks = Math.max(existing.progressGrowthTicks, progressFromState);
				}
			}
		}

		if (isCropMatureBlock(cropState, rule) || isMaxAge(cropState)) {
			existing.progressGrowthTicks = existing.requiredGrowthTicks;
		}

		putCropState(cropKey, existing);
		plot.cropPos = cropPos.asLong();
		plot.cropId = rule.plantingItemId();
		dirty = true;
	}

	public static void handleBlockBreak(Level world, BlockPos pos, BlockState state, BlockEntity blockEntity) {
		if (!settings.enabled || !(world instanceof ServerLevel serverLevel) || pos == null) {
			return;
		}

		boolean changed = false;

		PlotState plot = findPlot(serverLevel, pos);
		if (plot != null) {
			removePlot(serverLevel, pos);
			changed = true;
		}

		String cropEntryKey = cropKey(serverLevel, pos);
		CropState cropState = removeCropStateByKey(cropEntryKey);
		if (cropState != null) {
			PlotState soilPlot = findPlot(serverLevel, BlockPos.of(cropState.soilPos));
			if (soilPlot != null && soilPlot.cropPos == pos.asLong()) {
				soilPlot.clearCrop();
				if (!soilPlot.fertilized) {
					removePlotStateByKey(soilPlot.key());
				}
			}
			pendingHarvestRulesByKey.remove(cropEntryKey);
			changed = true;
		}

		if (changed) {
			dirty = true;
		}
	}

	public static void applyCropItemMetadata() {
		if (!settings.enabled) {
			return;
		}

		Set<String> processedItemIds = new LinkedHashSet<>();
		for (CropRule rule : cropRulesByPlantingItemId.values()) {
			if (rule == null) {
				continue;
			}
			applyFarmingLore(rule, processedItemIds);
		}

		Item boneMeal = BuiltInRegistries.ITEM.getValue(Identifier.tryParse("minecraft:bone_meal"));
		if (boneMeal != null) {
			applyFertilizerLore(boneMeal);
		}
	}

	public static CropRule resolveHarvestRule(ServerLevel world, BlockPos cropPos, BlockState state) {
		CropRule managedRule = resolveManagedCropRule(world, cropPos, state);
		if (managedRule != null) {
			return managedRule;
		}
		return resolvePendingHarvestRule(world, cropPos);
	}

	public static boolean isCropHarvestReady(ServerLevel world, BlockPos cropPos, BlockState state) {
		if (resolvePendingHarvestRule(world, cropPos) != null) {
			return true;
		}

		CropRule rule = resolveManagedCropRule(world, cropPos, state);
		if (rule == null) {
			return false;
		}
		if (state != null && (isCropMatureBlock(state, rule) || isMaxAge(state))) {
			return true;
		}

		CropState tracked = findCrop(world, cropPos);
		return tracked != null && tracked.progressGrowthTicks + 1e-6d >= tracked.requiredGrowthTicks;
	}

	public static boolean isManagedHarvestState(ServerLevel world, BlockPos cropPos, BlockState state) {
		CropRule pendingRule = resolvePendingHarvestRule(world, cropPos);
		if (pendingRule != null) {
			return true;
		}

		CropRule rule = resolveManagedCropRule(world, cropPos, state);
		if (rule == null || !isCropHarvestReady(world, cropPos, state)) {
			return false;
		}
		return !rule.usesDistinctMatureBlock() || isCropMatureBlock(state, rule);
	}

	public static boolean hasPendingHarvest(ServerLevel world, BlockPos cropPos, BlockState state) {
		return resolvePendingHarvestRule(world, cropPos) != null;
	}

	public static int calculateCropHarvestCount(ServerLevel world, BlockPos cropPos, BlockState state, RandomSource random) {
		CropRule rule = resolveHarvestRule(world, cropPos, state);
		if (!settings.enabled || rule == null || !isCropHarvestReady(world, cropPos, state)) {
			return 0;
		}

		RandomSource safeRandom = random == null ? RandomSource.create() : random;
		int minCount = Math.max(1, rule.minHarvestCount());
		int maxCount = Math.max(minCount, rule.maxHarvestCount());
		int baseCount = minCount + safeRandom.nextInt(maxCount - minCount + 1);
		double multiplier = isHarvestFertilized(world, cropPos, rule) ? (1.0d + settings.fertilizedGrowthBoost) : 1.0d;
		return applyScaledItemCount(baseCount, multiplier, safeRandom);
	}

	public static int calculateCropSecondaryHarvestCount(ServerLevel world, BlockPos cropPos, BlockState state, RandomSource random) {
		CropRule rule = resolveHarvestRule(world, cropPos, state);
		if (!settings.enabled || rule == null || !isCropHarvestReady(world, cropPos, state) || !rule.hasSecondaryHarvestDrop()) {
			return 0;
		}

		RandomSource safeRandom = random == null ? RandomSource.create() : random;
		int minCount = Math.max(1, rule.secondaryMinHarvestCount());
		int maxCount = Math.max(minCount, rule.secondaryMaxHarvestCount());
		return minCount + safeRandom.nextInt(maxCount - minCount + 1);
	}

	public static void prepareCropHarvest(ServerLevel world, BlockPos cropPos, BlockState state) {
		if (!settings.enabled || world == null || cropPos == null || state == null) {
			return;
		}

		CropRule rule = resolveHarvestRule(world, cropPos, state);
		if (rule == null || !isManagedHarvestState(world, cropPos, state)) {
			return;
		}

		boolean fertilized = isFertilized(world, cropPos.below());
		markPendingHarvest(world, cropPos, rule, fertilized);
	}

	public static void completeCropHarvest(ServerLevel world, BlockPos cropPos, BlockState state) {
		if (!settings.enabled || world == null || cropPos == null || state == null) {
			return;
		}

		CropRule rule = resolveHarvestRule(world, cropPos, state);
		if (rule == null) {
			return;
		}

		pendingHarvestRulesByKey.remove(cropKey(world, cropPos));
		CropState removed = removeCropStateByKey(cropKey(world, cropPos));
		BlockPos soilPos = removed == null ? cropPos.below() : BlockPos.of(removed.soilPos);
		PlotState plot = findPlot(world, soilPos);
		if (plot != null && (!plot.hasCrop() || plot.cropPos == cropPos.asLong() || removed != null)) {
			plot.clearCrop();
			plot.fertilized = false;
			plot.fertilizedAtGameplayTick = Long.MIN_VALUE;
			removePlotStateByKey(plot.key());
		}
		dirty = true;
	}

	public static boolean isMaxAge(BlockState state) {
		return getCropAge(state) >= getCropAgeLimit(state);
	}

	public static void emitPendingHarvestUsedDebug(ServerLevel world, BlockPos cropPos, BlockState state, String source) {
		// Intentionally no-op in the rewritten simulation system.
	}

	private static boolean handleBlockBreakBefore(Level world, BlockPos pos, BlockState state, BlockEntity blockEntity) {
		if (!settings.enabled || !(world instanceof ServerLevel serverLevel) || pos == null || state == null) {
			return true;
		}

		if (isManagedHarvestState(serverLevel, pos, state)) {
			prepareCropHarvest(serverLevel, pos, state);
		}
		return true;
	}

	private static void runFarmingDiscoveryTask(MinecraftServer server, SchedulerManagerSystem.TaskContext context, JsonObject payload) {
		if (context != null) {
			farmingDiscoverySchedulerId = context.getSchedulerId();
		}
		// Discovery is now driven by ChunkManagerSystem shared discovery.
	}

	private static void runFarmingProcessTask(MinecraftServer server, SchedulerManagerSystem.TaskContext context, JsonObject payload) {
		if (context != null) {
			farmingProcessSchedulerId = context.getSchedulerId();
		}
		farmingProcessTaskScheduled = false;
		if (server == null || !settings.enabled) {
			return;
		}
		emitFarmingDebug(
			"farming.scheduler_tick",
			server.overworld(),
			"farming_scheduler",
			Map.of(
				"plots", Integer.toString(plotsByKey.size()),
				"crops", Integer.toString(cropsByKey.size()),
				"cursor", Integer.toString(chunkScanCursor)
			)
		);
		purgeExpiredPendingHarvestRules();
		ChunkManagerSystem.runChunkProcessorProcessingStep(server, CHUNK_PROCESSOR_FARMING_DISCOVERY_ID);
		requestFarmingProcessTask(server, resolveFarmingSchedulerInterval(server));
	}

	private static void resetChunkProcessingCycle() {
		chunkScanCursor = 0;
	}

	private static void trackChunkWithState(ChunkRefKey chunkKey) {
		if (chunkKey == null || chunkKey.levelId().isBlank()) {
			return;
		}
		ChunkManagerSystem.trackChunkForProcessor(
			CHUNK_PROCESSOR_FARMING_DISCOVERY_ID,
			chunkKey.levelId(),
			chunkKey.chunkX(),
			chunkKey.chunkZ()
		);
	}

	private static void untrackChunkIfEmpty(ChunkRefKey chunkKey) {
		if (chunkKey == null || hasTrackedEntries(chunkKey)) {
			return;
		}
		ChunkManagerSystem.untrackChunkForProcessor(
			CHUNK_PROCESSOR_FARMING_DISCOVERY_ID,
			chunkKey.levelId(),
			chunkKey.chunkX(),
			chunkKey.chunkZ()
		);
	}

	private static boolean hasTrackedEntries(ChunkRefKey chunkKey) {
		if (chunkKey == null) {
			return false;
		}
		return hasTrackedEntries(plotKeysByChunk, chunkKey)
			|| hasTrackedEntries(cropKeysByChunk, chunkKey);
	}

	private static boolean hasTrackedEntries(Map<ChunkRefKey, Set<String>> indexMap, ChunkRefKey chunkKey) {
		if (indexMap == null || chunkKey == null) {
			return false;
		}
		Set<String> entries = indexMap.get(chunkKey);
		return entries != null && !entries.isEmpty();
	}

	private static void rebuildTrackedChunkStateFromIndexes() {
		for (ChunkRefKey chunkKey : plotKeysByChunk.keySet()) {
			trackChunkWithState(chunkKey);
		}
		for (ChunkRefKey chunkKey : cropKeysByChunk.keySet()) {
			trackChunkWithState(chunkKey);
		}
	}

	private static void processTrackedBlocksInChunk(ServerLevel world, int chunkX, int chunkZ) {
		if (world == null || !ChunkManagerSystem.isChunkLoaded(world, chunkX, chunkZ)) {
			return;
		}
		long currentAbsoluteDayTime = resolveAbsoluteDayTime(world);
		processPlotsInChunk(world, chunkX, chunkZ);
		processCropsInChunk(world, chunkX, chunkZ, currentAbsoluteDayTime);
	}

	private static void discoverTrackableBlocksInChunk(
		ServerLevel world,
		int chunkX,
		int chunkZ,
		ChunkManagerSystem.ChunkDiscoverySnapshot snapshot
	) {
		if (world == null) {
			return;
		}

		int discoveredFarmland = 0;
		int discoveredCrops = 0;
		if (snapshot == null || snapshot.motionColumns().isEmpty()) {
			return;
		}
		for (ChunkManagerSystem.ColumnSample column : snapshot.motionColumns()) {
			if (column == null) {
				continue;
			}
			for (int depth = 0; depth <= 2; depth++) {
				if (!column.hasDepth(depth)) {
					continue;
				}

				BlockPos pos = BlockPos.of(column.posAtDepth(depth));
				BlockState state = column.stateAtDepth(depth);
				if (isFarmland(state)) {
					discoveredFarmland++;
					getOrCreatePlot(world, pos);
				}
				if (isManagedCrop(state)) {
					discoveredCrops++;
					trackCrop(world, pos, state);
				}
			}
		}
		if (shouldEmitChunkDebug("farming.chunk_discover", false)) {
			emitFarmingDebug(
				"farming.chunk_discover",
				world,
				"chunk:" + chunkX + "," + chunkZ,
				Map.of(
					"farmland_hits", Integer.toString(discoveredFarmland),
					"crop_hits", Integer.toString(discoveredCrops)
				)
			);
		}
	}

	private static void processPlotsInChunk(ServerLevel world, int chunkX, int chunkZ) {
		if (world == null) {
			return;
		}
		String worldLevelId = levelId(world);
		ChunkRefKey targetChunkKey = new ChunkRefKey(worldLevelId, chunkX, chunkZ);
		Set<String> chunkPlotKeys = new LinkedHashSet<>(plotKeysByChunk.getOrDefault(targetChunkKey, Set.of()));
		if (chunkPlotKeys.isEmpty()) {
			return;
		}

		List<String> removeKeys = new ArrayList<>();
		for (String plotEntryKey : chunkPlotKeys) {
			PlotState plot = plotsByKey.get(plotEntryKey);
			if (plot == null || !plot.levelId.equals(worldLevelId)) {
				continue;
			}

			BlockPos soilPos = BlockPos.of(plot.soilPos);
			if (!targetChunkKey.equals(chunkRefForPos(worldLevelId, plot.soilPos))) {
				continue;
			}

			BlockState soilState = world.getBlockState(soilPos);
			if (!isFarmland(soilState)) {
				removeKeys.add(plotEntryKey);
				if (plot.hasCrop()) {
					removeCropStateByKey(cropKey(world, BlockPos.of(plot.cropPos)));
				}
				continue;
			}

			if (plot.fertilized) {
				emitFertilizedParticles(world, soilPos, plot);
			}

			if (plot.hasCrop() && !cropsByKey.containsKey(cropKey(world, BlockPos.of(plot.cropPos)))) {
				plot.clearCrop();
				dirty = true;
			}
		}

		for (String key : removeKeys) {
			if (removePlotStateByKey(key) != null) {
				dirty = true;
			}
		}
	}

	private static void processCropsInChunk(ServerLevel world, int chunkX, int chunkZ, long currentAbsoluteDayTime) {
		if (world == null) {
			return;
		}
		String worldLevelId = levelId(world);
		ChunkRefKey targetChunkKey = new ChunkRefKey(worldLevelId, chunkX, chunkZ);
		Set<String> chunkCropKeys = new LinkedHashSet<>(cropKeysByChunk.getOrDefault(targetChunkKey, Set.of()));
		if (chunkCropKeys.isEmpty()) {
			return;
		}

		List<String> removeKeys = new ArrayList<>();
		for (String cropEntryKey : chunkCropKeys) {
			CropState crop = cropsByKey.get(cropEntryKey);
			if (crop == null || !crop.levelId.equals(worldLevelId)) {
				continue;
			}

			BlockPos cropPos = BlockPos.of(crop.cropPos);
			if (!targetChunkKey.equals(chunkRefForPos(worldLevelId, crop.cropPos))) {
				continue;
			}

			CropRule rule = resolveCropRuleByPlantingItemId(crop.cropId);
			if (rule == null) {
				removeKeys.add(cropEntryKey);
				emitFarmingDebug(
					"farming.crop_stall",
					world,
					"crop:" + cropPos.toShortString(),
					Map.of(
						"reason", "rule_missing",
						"crop_id", crop.cropId == null ? "" : crop.cropId
					)
				);
				continue;
			}

			BlockState state = world.getBlockState(cropPos);
			if (!isCropBlock(state, rule)) {
				removeKeys.add(cropEntryKey);
				PlotState plot = findPlot(world, BlockPos.of(crop.soilPos));
				if (plot != null && plot.cropPos == crop.cropPos) {
					plot.clearCrop();
					if (!plot.fertilized) {
						removePlotStateByKey(plot.key());
					}
				}
				emitFarmingDebug(
					"farming.crop_stall",
					world,
					"crop:" + cropPos.toShortString(),
					Map.of(
						"reason", "block_mismatch",
						"expected_crop", rule.cropBlockId(),
						"expected_mature", rule.matureBlockId()
					)
				);
				continue;
			}

			double previousRequiredTicks = Math.max(1.0d, crop.requiredGrowthTicks);
			double recalculatedRequiredTicks = resolveCropRequiredGrowthTicks(rule, crop.discoveredSeasonId);
			if (Math.abs(recalculatedRequiredTicks - previousRequiredTicks) > 1e-6d) {
				crop.progressGrowthTicks = rescaleProgressGrowthTicks(
					crop.progressGrowthTicks,
					previousRequiredTicks,
					recalculatedRequiredTicks
				);
				crop.requiredGrowthTicks = recalculatedRequiredTicks;
				dirty = true;
			}
			double requiredTicks = Math.max(1.0d, crop.requiredGrowthTicks);
			crop.requiredGrowthTicks = requiredTicks;
			int observedAgeLimit = Math.max(1, getCropAgeLimit(state));
			boolean observedMature = isCropMatureBlock(state, rule) || isMaxAge(state);
			int observedAge = isCropMatureBlock(state, rule) ? observedAgeLimit : getCropAge(state);
			double observedProgress = progressFromAge(observedAge, observedAgeLimit) * requiredTicks;
			double cappedTrackedProgress = Math.max(0.0d, Math.min(requiredTicks, crop.progressGrowthTicks));
			double oneAgeStepTicks = requiredTicks / observedAgeLimit;
			boolean replantedRegression = !observedMature
				&& observedProgress + Math.max(1.0d, oneAgeStepTicks) < cappedTrackedProgress;
			if (replantedRegression) {
				String refreshedSeasonId = normalizeSeasonId(MadokuSeason.getCurrentSeasonId(world));
				double refreshedRequiredTicks = Math.max(1.0d, resolveCropRequiredGrowthTicks(rule, refreshedSeasonId));
				double refreshedObservedProgress = progressFromAge(observedAge, observedAgeLimit) * refreshedRequiredTicks;
				crop.discoveredSeasonId = refreshedSeasonId;
				crop.requiredGrowthTicks = refreshedRequiredTicks;
				requiredTicks = refreshedRequiredTicks;
				crop.progressGrowthTicks = Math.max(0.0d, Math.min(requiredTicks, refreshedObservedProgress));
				crop.lastProcessedAbsoluteDayTime = Math.max(0L, currentAbsoluteDayTime);
				dirty = true;
				emitFarmingDebug(
					"farming.crop_progress",
					world,
					"crop:" + cropPos.toShortString(),
					Map.of(
						"result", "replanted_reset",
						"progress_before", Double.toString(cappedTrackedProgress),
						"progress_after", Double.toString(crop.progressGrowthTicks),
						"required_before", Double.toString(Math.max(1.0d, previousRequiredTicks)),
						"required_after", Double.toString(requiredTicks),
						"observed_age", Integer.toString(observedAge),
						"observed_age_limit", Integer.toString(observedAgeLimit)
					)
				);
			}

			PlotState plot = findPlot(world, BlockPos.of(crop.soilPos));
			boolean fertilized = plot != null && plot.fertilized;
			boolean raining = world.isRainingAt(cropPos);
			boolean dryFarmland = isDryFarmland(world.getBlockState(BlockPos.of(crop.soilPos)));
			double speedMultiplier = 1.0d;
			if (fertilized) {
				speedMultiplier += settings.fertilizedGrowthBoost;
			}
			if (raining) {
				speedMultiplier += settings.rainGrowthBoost;
			}
			if (dryFarmland) {
				speedMultiplier *= Math.max(0.0d, 1.0d - settings.dryFarmlandPenalty);
			}
			speedMultiplier = Math.max(0.0d, speedMultiplier);

			long rawPreviousAbsolute = Math.max(0L, crop.lastProcessedAbsoluteDayTime);
			long previousAbsolute = normalizePreviousAbsoluteTick(crop.lastProcessedAbsoluteDayTime, currentAbsoluteDayTime);
			if (previousAbsolute != rawPreviousAbsolute) {
				crop.lastProcessedAbsoluteDayTime = previousAbsolute;
				dirty = true;
				emitFarmingDebug(
					"farming.crop_progress",
					world,
					"crop:" + cropPos.toShortString(),
					Map.of(
						"result", "clock_reset",
						"raw_now_abs", Long.toString(currentAbsoluteDayTime),
						"last_abs", Long.toString(rawPreviousAbsolute),
						"reset_to_abs", Long.toString(previousAbsolute)
					)
				);
			}
			long safeCurrentAbsolute = Math.max(previousAbsolute, currentAbsoluteDayTime);
			long elapsedTicks = safeCurrentAbsolute - previousAbsolute;
			boolean usedElapsedFallback = false;
			double effectiveTicks = elapsedTicks * speedMultiplier;
			if (elapsedTicks <= 0L) {
				// Time mapping can quantize multiple gameplay ticks to the same absolute tick.
				// Keep crops progressing smoothly instead of stalling until the next absolute tick boundary.
				elapsedTicks = 1L;
				effectiveTicks = speedMultiplier;
				usedElapsedFallback = true;
				emitFarmingDebug(
					"farming.crop_progress",
					world,
					"crop:" + cropPos.toShortString(),
					Map.of(
						"result", "no_elapsed_time_fallback",
						"now_abs", Long.toString(safeCurrentAbsolute),
						"raw_now_abs", Long.toString(currentAbsoluteDayTime),
						"last_abs", Long.toString(previousAbsolute),
						"speed", Double.toString(speedMultiplier)
					)
				);
				emitFarmingDebug(
					"farming.crop_stall",
					world,
					"crop:" + cropPos.toShortString(),
					Map.of(
						"reason", "no_elapsed_time_fallback",
						"now_abs", Long.toString(safeCurrentAbsolute),
						"last_abs", Long.toString(previousAbsolute),
						"applied_elapsed_abs_ticks", Long.toString(elapsedTicks)
					)
				);
			}
			if (elapsedTicks > 0L && speedMultiplier <= 0.0d) {
				emitFarmingDebug(
					"farming.crop_stall",
					world,
					"crop:" + cropPos.toShortString(),
					Map.of(
						"reason", "zero_speed",
						"elapsed_abs_ticks", Long.toString(elapsedTicks),
						"speed", Double.toString(speedMultiplier),
						"fertilized", Boolean.toString(fertilized),
						"raining", Boolean.toString(raining),
						"dry_farmland", Boolean.toString(dryFarmland)
					)
				);
			}
			if (effectiveTicks > 0.0d) {
				double before = crop.progressGrowthTicks;
				double updatedProgress = Math.min(requiredTicks, crop.progressGrowthTicks + effectiveTicks);
				if (updatedProgress > crop.progressGrowthTicks) {
					crop.progressGrowthTicks = updatedProgress;
					dirty = true;
					emitFarmingDebug(
						"farming.crop_progress",
						world,
						"crop:" + cropPos.toShortString(),
						Map.of(
							"result", "progressed",
							"elapsed_abs_ticks", Long.toString(elapsedTicks),
							"effective_ticks", Double.toString(effectiveTicks),
							"speed", Double.toString(speedMultiplier),
							"before", Double.toString(before),
							"after", Double.toString(updatedProgress),
							"required", Double.toString(requiredTicks),
							"now_abs", Long.toString(safeCurrentAbsolute),
							"last_abs", Long.toString(previousAbsolute),
							"elapsed_fallback", Boolean.toString(usedElapsedFallback)
						)
					);
				}
				if (updatedProgress <= before + 1e-6d) {
					emitFarmingDebug(
						"farming.crop_stall",
						world,
						"crop:" + cropPos.toShortString(),
						Map.of(
							"reason", "already_complete",
							"progress", Double.toString(before),
							"required", Double.toString(requiredTicks),
							"effective_ticks", Double.toString(effectiveTicks)
						)
					);
				}
			}
			crop.lastProcessedAbsoluteDayTime = safeCurrentAbsolute;

			double observedProgressFromState = progressFromAge(
				isCropMatureBlock(state, rule) ? getCropAgeLimit(state) : getCropAge(state),
				getCropAgeLimit(state)
			) * requiredTicks;
			if (observedProgressFromState > crop.progressGrowthTicks) {
				crop.progressGrowthTicks = Math.min(requiredTicks, observedProgressFromState);
				dirty = true;
			}

			updateCropBlockAge(world, cropPos, state, rule, crop);
		}

		for (String key : removeKeys) {
			if (removeCropStateByKey(key) != null) {
				dirty = true;
			}
		}
	}

	private static void updateCropBlockAge(ServerLevel world, BlockPos cropPos, BlockState state, CropRule rule, CropState crop) {
		if (world == null || cropPos == null || state == null || rule == null || crop == null) {
			return;
		}

		double required = Math.max(1.0d, crop.requiredGrowthTicks);
		double progress = Math.max(0.0d, Math.min(required, crop.progressGrowthTicks));
		int ageLimit = getCropAgeLimit(state);
		int targetAge = ageFromProgress(progress / required, ageLimit);

		if (rule.usesDistinctMatureBlock()) {
			if (progress + 1e-6d >= required) {
				if (!isCropMatureBlock(state, rule) && rule.matureBlock() != null) {
					world.setBlockAndUpdate(cropPos, rule.matureBlock().defaultBlockState());
					dirty = true;
				}
				crop.progressGrowthTicks = required;
				return;
			}
			if (isCropGrowthBlock(state, rule) && targetAge > getCropAge(state)) {
				BlockState updated = setCropAge(state, targetAge);
				if (updated != state) {
					world.setBlockAndUpdate(cropPos, updated);
					dirty = true;
				}
			}
			return;
		}

		if (targetAge > getCropAge(state)) {
			BlockState updated = setCropAge(state, targetAge);
			if (updated != state) {
				world.setBlockAndUpdate(cropPos, updated);
				dirty = true;
			}
		}
		if (targetAge >= ageLimit) {
			crop.progressGrowthTicks = required;
		}
	}

	private static void requestFarmingProcessing(MinecraftServer server, long delayTicks) {
		requestFarmingProcessTask(server, delayTicks);
	}

	private static long resolveFarmingSchedulerInterval(MinecraftServer server) {
		return SchedulerManagerSystem.resolveAdaptiveDelayTicks(
			server,
			FARMING_PROCESS_SCHEDULER_OWNER_ID,
			FARMING_SCHEDULER_MIN_INTERVAL_TICKS,
			FARMING_SCHEDULER_MAX_INTERVAL_TICKS
		);
	}

	private static void requestFarmingProcessTask(MinecraftServer server, long delayTicks) {
		if (server == null || !settings.enabled || farmingProcessTaskScheduled) {
			return;
		}

		String schedulerId = ensureFarmingProcessSchedulerExists();
		if (enqueueFarmingTask(schedulerId, delayTicks, TASK_TYPE_FARMING_PROCESS_TICK)) {
			farmingProcessTaskScheduled = true;
			return;
		}

		farmingProcessSchedulerId = SchedulerManagerSystem.createOrGetScheduler(
			SchedulerManagerSystem.SchedulerBinding.global(FARMING_PROCESS_SCHEDULER_OWNER_ID)
		);
		if (enqueueFarmingTask(farmingProcessSchedulerId, delayTicks, TASK_TYPE_FARMING_PROCESS_TICK)) {
			farmingProcessTaskScheduled = true;
		}
	}

	private static String ensureFarmingProcessSchedulerExists() {
		if (farmingProcessSchedulerId == null || farmingProcessSchedulerId.isBlank()) {
			farmingProcessSchedulerId = SchedulerManagerSystem.createOrGetScheduler(
				SchedulerManagerSystem.SchedulerBinding.global(FARMING_PROCESS_SCHEDULER_OWNER_ID)
			);
		}
		return farmingProcessSchedulerId;
	}

	private static boolean enqueueFarmingTask(String schedulerId, long delayTicks, String taskType) {
		if (schedulerId == null || schedulerId.isBlank()) {
			return false;
		}

		SchedulerManagerSystem.EnqueueStatus status = SchedulerManagerSystem.enqueue(
			schedulerId,
			Math.max(0L, delayTicks),
			taskType,
			new JsonObject(),
			SchedulerManagerSystem.TickDomain.GAMEPLAY
		);
		return status == SchedulerManagerSystem.EnqueueStatus.ACCEPTED
			|| status == SchedulerManagerSystem.EnqueueStatus.QUEUE_FULL;
	}

	private static void onTrackedChunkLoaded(ServerLevel world, int chunkX, int chunkZ) {
		if (!settings.enabled || world == null) {
			return;
		}
		requestFarmingProcessing(world.getServer(), 1L);
	}

	private static void onTrackedChunkUnloaded(ServerLevel world, int chunkX, int chunkZ) {
		// No local loaded-chunk mirrors; ChunkManagerSystem owns tracked loaded state.
	}

	private static String levelId(ServerLevel world) {
		if (world == null) {
			return "";
		}
		return SchedulerManagerSystem.normalizeLevelIdentifier(world.dimension().toString());
	}

	private static PlotState findPlot(ServerLevel world, BlockPos soilPos) {
		if (world == null || soilPos == null) {
			return null;
		}
		return plotsByKey.get(plotKey(world, soilPos));
	}

	private static PlotState getOrCreatePlot(ServerLevel world, BlockPos soilPos) {
		if (world == null || soilPos == null) {
			return null;
		}

		String key = plotKey(world, soilPos);
		PlotState existing = plotsByKey.get(key);
		if (existing != null) {
			return existing;
		}

		PlotState created = new PlotState(levelId(world), soilPos.asLong());
		putPlotState(key, created);
		dirty = true;
		return created;
	}

	private static void removePlot(ServerLevel world, BlockPos soilPos) {
		if (world == null || soilPos == null) {
			return;
		}
		PlotState removed = removePlotStateByKey(plotKey(world, soilPos));
		if (removed == null) {
			return;
		}
		if (removed.hasCrop()) {
			removeCropStateByKey(cropKey(world, BlockPos.of(removed.cropPos)));
		}
		dirty = true;
	}

	private static CropState findCrop(ServerLevel world, BlockPos cropPos) {
		if (world == null || cropPos == null) {
			return null;
		}
		return cropsByKey.get(cropKey(world, cropPos));
	}

	private static PlotState putPlotState(String key, PlotState value) {
		PlotState previous = plotsByKey.put(key, value);
		if (previous != null) {
			removeChunkIndex(plotKeysByChunk, chunkRefForPos(previous.levelId, previous.soilPos), key);
		}
		if (value != null) {
			addChunkIndex(plotKeysByChunk, chunkRefForPos(value.levelId, value.soilPos), key);
		}
		return previous;
	}

	private static PlotState removePlotStateByKey(String key) {
		PlotState removed = plotsByKey.remove(key);
		if (removed != null) {
			removeChunkIndex(plotKeysByChunk, chunkRefForPos(removed.levelId, removed.soilPos), key);
		}
		return removed;
	}

	private static CropState putCropState(String key, CropState value) {
		CropState previous = cropsByKey.put(key, value);
		if (previous != null) {
			removeChunkIndex(cropKeysByChunk, chunkRefForPos(previous.levelId, previous.cropPos), key);
		}
		if (value != null) {
			addChunkIndex(cropKeysByChunk, chunkRefForPos(value.levelId, value.cropPos), key);
		}
		return previous;
	}

	private static CropState removeCropStateByKey(String key) {
		CropState removed = cropsByKey.remove(key);
		if (removed != null) {
			removeChunkIndex(cropKeysByChunk, chunkRefForPos(removed.levelId, removed.cropPos), key);
		}
		return removed;
	}

	private static void addChunkIndex(Map<ChunkRefKey, Set<String>> indexMap, ChunkRefKey chunkKey, String entryKey) {
		if (indexMap == null || chunkKey == null || entryKey == null || entryKey.isBlank()) {
			return;
		}
		indexMap.computeIfAbsent(chunkKey, ignored -> new LinkedHashSet<>()).add(entryKey);
		trackChunkWithState(chunkKey);
	}

	private static void removeChunkIndex(Map<ChunkRefKey, Set<String>> indexMap, ChunkRefKey chunkKey, String entryKey) {
		if (indexMap == null || chunkKey == null || entryKey == null || entryKey.isBlank()) {
			return;
		}
		Set<String> keys = indexMap.get(chunkKey);
		if (keys == null) {
			return;
		}
		keys.remove(entryKey);
		if (keys.isEmpty()) {
			indexMap.remove(chunkKey);
		}
		untrackChunkIfEmpty(chunkKey);
	}

	private static String plotKey(ServerLevel world, BlockPos soilPos) {
		return levelId(world) + "|" + soilPos.asLong();
	}

	private static String cropKey(ServerLevel world, BlockPos cropPos) {
		return levelId(world) + "|" + (cropPos == null ? -1L : cropPos.asLong());
	}

	private static ChunkRefKey chunkRefForPos(String levelId, long packedBlockPos) {
		return new ChunkRefKey(levelId, BlockPos.getX(packedBlockPos) >> 4, BlockPos.getZ(packedBlockPos) >> 4);
	}

	private static boolean shouldEmitChunkDebug(String metricId, boolean force) {
		long now = MadokuTicks.getGameplayTicks();
		if (!force) {
			long last = lastChunkDebugTick(metricId);
			if (last != Long.MIN_VALUE && now - last < CHUNK_DEBUG_INTERVAL_TICKS) {
				return false;
			}
		}
		setLastChunkDebugTick(metricId, now);
		return true;
	}

	private static long lastChunkDebugTick(String metricId) {
		if ("farming.chunk_process".equals(metricId)) {
			return lastChunkProcessDebugTick;
		}
		if ("farming.chunk_pick".equals(metricId)) {
			return lastChunkPickDebugTick;
		}
		if ("farming.chunk_discover".equals(metricId)) {
			return lastChunkDiscoverDebugTick;
		}
		return Long.MIN_VALUE;
	}

	private static void setLastChunkDebugTick(String metricId, long tick) {
		if ("farming.chunk_process".equals(metricId)) {
			lastChunkProcessDebugTick = tick;
			return;
		}
		if ("farming.chunk_pick".equals(metricId)) {
			lastChunkPickDebugTick = tick;
			return;
		}
		if ("farming.chunk_discover".equals(metricId)) {
			lastChunkDiscoverDebugTick = tick;
		}
	}

	private static long resolveAbsoluteDayTime(ServerLevel world) {
		if (world == null) {
			return MadokuTime.getCurrentAbsoluteDayTime();
		}
		return MadokuTime.getCurrentAbsoluteDayTime(world);
	}

	private static long normalizePreviousAbsoluteTick(long previousAbsoluteTick, long currentAbsoluteTick) {
		long safePrevious = Math.max(0L, previousAbsoluteTick);
		long safeCurrent = Math.max(0L, currentAbsoluteTick);
		if (safePrevious > safeCurrent + ABSOLUTE_TIME_ROLLBACK_RESET_TICKS) {
			return safeCurrent;
		}
		return safePrevious;
	}

	private static void applyPersistedData(JsonObject source) {
		plotsByKey.clear();
		cropsByKey.clear();
		plotKeysByChunk.clear();
		cropKeysByChunk.clear();
		pendingHarvestRulesByKey.clear();
		ChunkManagerSystem.resetChunkProcessor(CHUNK_PROCESSOR_FARMING_DISCOVERY_ID);
		resetChunkProcessingCycle();

		if (source == null) {
			return;
		}

		resetChunkProcessingCycle();

		JsonElement plotsElement = source.get(FIELD_PLOTS);
		if (plotsElement != null && plotsElement.isJsonArray()) {
			for (JsonElement element : plotsElement.getAsJsonArray()) {
				PlotState plot = PlotState.fromJson(element);
				if (plot == null) {
					continue;
				}
				putPlotState(plot.key(), plot);
			}
		}

		JsonElement cropsElement = source.get(FIELD_CROPS);
		if (cropsElement != null && cropsElement.isJsonArray()) {
			for (JsonElement element : cropsElement.getAsJsonArray()) {
				CropState crop = CropState.fromJson(element);
				if (crop == null) {
					continue;
				}
				putCropState(crop.key(), crop);
			}
		}

		// Dirt ecosystem tracking moved to MadokuEcosystem.
	}

	private static JsonObject createDefaultData() {
		JsonObject root = new JsonObject();
		root.addProperty(FIELD_CHUNK_CURSOR, 0);
		root.add(FIELD_PLOTS, new JsonArray());
		root.add(FIELD_CROPS, new JsonArray());
		return root;
	}

	private static JsonObject toPersistedData() {
		JsonObject root = new JsonObject();
		root.addProperty(FIELD_CHUNK_CURSOR, Math.max(0, chunkScanCursor));

		JsonArray plots = new JsonArray();
		for (PlotState plot : plotsByKey.values()) {
			if (plot != null) {
				plots.add(plot.toJson());
			}
		}
		root.add(FIELD_PLOTS, plots);

		JsonArray crops = new JsonArray();
		for (CropState crop : cropsByKey.values()) {
			if (crop != null) {
				crops.add(crop.toJson());
			}
		}
		root.add(FIELD_CROPS, crops);
		return root;
	}

	private static void loadStaticConfig() {
		JsonObject defaults = MadokuFarmingConfig.buildFarmingDefaults();
		Settings fallback = Settings.defaults();

		try {
			Path directory = JsonManagerSystem.getOrCreateGlobalSystemDirectory(FARMING_CONFIG_ROOT_FOLDER_NAME);
			Path configFile = resolveJsonFile(directory, FARMING_CONFIG_FILE_NAME);
			JsonObject normalized = JsonStaticSystem.ensureManagedFile(configFile, defaults);
			Settings loaded = Settings.fromJson(normalized);
			JsonStaticSystem.writeManagedFile(configFile, loaded.toConfigJson(), defaults);
			settings = loaded;
		} catch (IOException | RuntimeException exception) {
			settings = fallback;
			LOGGER.error("Failed to load MadokuFarming static config; using defaults.", exception);
		}
	}

	private static void loadCropConfigs() {
		Map<String, CropRule> plantingRules = new LinkedHashMap<>();
		Map<String, CropRule> blockRules = new LinkedHashMap<>();
		Map<String, CropRule> matureRules = new LinkedHashMap<>();
		Map<String, JsonObject> defaultFiles = MadokuCropConfig.buildDefaultCropFileDefaults();

		try {
			Path directory = JsonManagerSystem.getOrCreateGlobalSystemDirectory(CROP_CONFIG_ROOT_FOLDER_NAME);
			for (Map.Entry<String, JsonObject> entry : defaultFiles.entrySet()) {
				String fileKey = entry.getKey();
				JsonObject defaults = entry.getValue();
				Path file = resolveJsonFile(directory, fileKey);
				JsonObject normalized = JsonStaticSystem.ensureManagedFile(file, defaults);
				CropRule rule = CropRule.fromJson(fileKey, normalized);
				if (rule == null) {
					rule = CropRule.defaultRule(fileKey);
				}
				if (rule == null) {
					continue;
				}

				plantingRules.put(rule.plantingItemId(), rule);
				blockRules.put(rule.cropBlockId(), rule);
				if (rule.usesDistinctMatureBlock()) {
					matureRules.put(rule.matureBlockId(), rule);
				}
			}
		} catch (IOException | RuntimeException exception) {
			LOGGER.error("Failed to load MadokuFarming crop configs; using defaults.", exception);
			for (CropRule rule : CropRule.defaultRules()) {
				plantingRules.put(rule.plantingItemId(), rule);
				blockRules.put(rule.cropBlockId(), rule);
				if (rule.usesDistinctMatureBlock()) {
					matureRules.put(rule.matureBlockId(), rule);
				}
			}
		}

		cropRulesByPlantingItemId = Map.copyOf(plantingRules);
		cropRulesByCropBlockId = Map.copyOf(blockRules);
		cropRulesByMatureBlockId = Map.copyOf(matureRules);
	}

	private static Path resolveJsonFile(Path directory, String fileName) {
		String normalized = fileName == null ? "" : fileName.trim();
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("Config file name must not be blank.");
		}
		if (!normalized.endsWith(".json")) {
			normalized = normalized + ".json";
		}
		return directory.resolve(normalized);
	}

	private static CropRule resolveCropRuleByPlantingItem(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		return resolveCropRuleByPlantingItem(stack.getItem());
	}

	private static CropRule resolveCropRuleByPlantingItem(Item item) {
		if (item == null) {
			return null;
		}
		Identifier id = BuiltInRegistries.ITEM.getKey(item);
		return id == null ? null : resolveCropRuleByPlantingItemId(id.toString());
	}

	private static CropRule resolveCropRuleByPlantingItemId(String plantingItemId) {
		String normalized = normalizeRegistryId(plantingItemId);
		if (normalized.isEmpty()) {
			return null;
		}
		return cropRulesByPlantingItemId.get(normalized);
	}

	private static CropRule resolveCropRuleByCropState(BlockState state) {
		if (state == null) {
			return null;
		}
		Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		if (id == null) {
			return null;
		}
		String normalized = normalizeRegistryId(id.toString());
		CropRule rule = cropRulesByCropBlockId.get(normalized);
		if (rule != null) {
			return rule;
		}
		return cropRulesByMatureBlockId.get(normalized);
	}

	private static CropRule resolveManagedCropRule(ServerLevel world, BlockPos cropPos, BlockState state) {
		if (world == null || cropPos == null) {
			return null;
		}

		CropState tracked = findCrop(world, cropPos);
		if (tracked != null) {
			return resolveCropRuleByPlantingItemId(tracked.cropId);
		}

		if (state == null) {
			return null;
		}
		CropRule stateRule = resolveCropRuleByCropState(state);
		if (stateRule == null) {
			return null;
		}
		return stateRule;
	}

	private static void applyFarmingLore(CropRule rule, Set<String> processedItemIds) {
		if (rule == null || processedItemIds == null) {
			return;
		}
		Item item = resolveItemByRegistryId(rule.plantingItemId());
		if (item == null) {
			return;
		}
		Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
		if (itemId == null || !processedItemIds.add(itemId.toString())) {
			return;
		}

		DataComponentMap base = item.components();
		ItemLore currentLore = base.get(DataComponents.LORE);
		List<Component> updatedLines = new ArrayList<>();
		if (currentLore != null) {
			for (Component line : currentLore.lines()) {
				if (line == null) {
					continue;
				}
				String text = line.getString();
				if (text.startsWith(LORE_SEASON_PREFIX)
					|| text.startsWith(LORE_GROWING_TIME_PREFIX)
					|| text.equals(LORE_FERTILIZER_PREFIX)
					|| text.equals(LORE_FERTILIZER_EFFECT_PREFIX)) {
					continue;
				}
				updatedLines.add(line);
			}
		}

		if (MadokuSeason.isEnabled()) {
			updatedLines.add(Component.literal(formatSeasonLoreLine(rule.blockedSeasonIds())).withStyle(ChatFormatting.DARK_GREEN));
		}
		updatedLines.add(Component.literal(formatGrowthTimeLoreLine(rule.growthMinecraftDays())).withStyle(ChatFormatting.GOLD));

		DataComponentMap.Builder builder = DataComponentMap.builder().addAll(base);
		builder.set(DataComponents.LORE, new ItemLore(updatedLines));
		((ItemComponentsAccessor) ((ItemBuiltInRegistryHolderAccessor) item).madokuCraft$getBuiltInRegistryHolder())
			.madokuCraft$bindComponents(builder.build());
	}

	private static Item resolveItemByRegistryId(String itemId) {
		String normalized = normalizeRegistryId(itemId);
		if (normalized.isBlank()) {
			return null;
		}
		Identifier identifier = Identifier.tryParse(normalized);
		return identifier == null ? null : BuiltInRegistries.ITEM.getValue(identifier);
	}

	private static void applyFertilizerLore(Item item) {
		if (item == null) {
			return;
		}

		DataComponentMap base = item.components();
		List<Component> updatedLines = new ArrayList<>();
		ItemLore currentLore = base.get(DataComponents.LORE);
		if (currentLore != null) {
			for (Component line : currentLore.lines()) {
				String text = line == null ? "" : line.getString();
				if (text.startsWith(LORE_FERTILIZER_PREFIX) || text.startsWith(LORE_FERTILIZER_EFFECT_PREFIX)) {
					continue;
				}
				updatedLines.add(line);
			}
		}

		updatedLines.add(Component.literal(LORE_FERTILIZER_PREFIX).withStyle(ChatFormatting.GOLD));
		updatedLines.add(Component.literal(LORE_FERTILIZER_EFFECT_PREFIX).withStyle(ChatFormatting.GOLD));

		DataComponentMap.Builder builder = DataComponentMap.builder().addAll(base);
		builder.set(DataComponents.LORE, new ItemLore(updatedLines));
		((ItemComponentsAccessor) ((ItemBuiltInRegistryHolderAccessor) item).madokuCraft$getBuiltInRegistryHolder())
			.madokuCraft$bindComponents(builder.build());
	}

	private static String formatSeasonLoreLine(Set<String> blockedSeasonIds) {
		return LORE_SEASON_PREFIX + " " + formatSeasonSummary(blockedSeasonIds);
	}

	private static String formatGrowthTimeLoreLine(double growthMinecraftDays) {
		return LORE_GROWING_TIME_PREFIX + " " + formatGrowthDays(growthMinecraftDays) + " Days";
	}

	private static String formatSeasonSummary(Set<String> blockedSeasonIds) {
		boolean[] allowed = new boolean[SEASON_ORDER.length];
		int allowedCount = 0;
		Set<String> blocked = blockedSeasonIds == null ? Set.of() : blockedSeasonIds;

		for (int index = 0; index < SEASON_ORDER.length; index++) {
			allowed[index] = !blocked.contains(SEASON_ORDER[index]);
			if (allowed[index]) {
				allowedCount++;
			}
		}

		if (allowedCount <= 0) {
			return "None";
		}
		if (allowedCount == SEASON_ORDER.length) {
			return "All Year";
		}

		for (int start = 0; start < SEASON_ORDER.length; start++) {
			int previous = (start - 1 + SEASON_ORDER.length) % SEASON_ORDER.length;
			if (!allowed[start] || allowed[previous]) {
				continue;
			}

			int end = start;
			int visited = 1;
			while (visited < allowedCount) {
				int next = (end + 1) % SEASON_ORDER.length;
				if (!allowed[next]) {
					break;
				}
				end = next;
				visited++;
			}

			if (visited == allowedCount) {
				return start == end
					? capitalizeSeasonId(SEASON_ORDER[start])
					: capitalizeSeasonId(SEASON_ORDER[start]) + " - " + capitalizeSeasonId(SEASON_ORDER[end]);
			}
		}

		List<String> seasonNames = new ArrayList<>();
		for (int index = 0; index < SEASON_ORDER.length; index++) {
			if (allowed[index]) {
				seasonNames.add(capitalizeSeasonId(SEASON_ORDER[index]));
			}
		}
		return String.join(", ", seasonNames);
	}

	private static String formatGrowthDays(double growthDays) {
		BigDecimal value = BigDecimal.valueOf(Math.max(0.0d, growthDays)).stripTrailingZeros();
		String formatted = value.toPlainString();
		return formatted.isEmpty() ? "0" : formatted;
	}

	private static String capitalizeSeasonId(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
	}

	private static CropRule resolvePendingHarvestRule(ServerLevel world, BlockPos cropPos) {
		if (world == null || cropPos == null) {
			return null;
		}
		PendingHarvestRule pending = pendingHarvestRulesByKey.get(cropKey(world, cropPos));
		if (pending == null) {
			return null;
		}
		long now = MadokuTicks.getGameplayTicks();
		if (pending.expiresAtGameplayTick < now) {
			pendingHarvestRulesByKey.remove(cropKey(world, cropPos));
			return null;
		}
		return pending.rule;
	}

	private static boolean isCropBlock(BlockState state, CropRule rule) {
		if (state == null || rule == null) {
			return false;
		}
		Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		if (id == null) {
			return false;
		}
		String normalized = normalizeRegistryId(id.toString());
		return normalized.equals(rule.cropBlockId()) || normalized.equals(rule.matureBlockId());
	}

	private static boolean isCropGrowthBlock(BlockState state, CropRule rule) {
		if (state == null || rule == null) {
			return false;
		}
		Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		if (id == null) {
			return false;
		}
		return normalizeRegistryId(id.toString()).equals(rule.cropBlockId());
	}

	private static boolean isCropMatureBlock(BlockState state, CropRule rule) {
		if (state == null || rule == null || !rule.usesDistinctMatureBlock()) {
			return false;
		}
		Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		if (id == null) {
			return false;
		}
		return normalizeRegistryId(id.toString()).equals(rule.matureBlockId());
	}

	private static void markPendingHarvest(ServerLevel world, BlockPos cropPos, CropRule rule, boolean fertilized) {
		if (world == null || cropPos == null || rule == null) {
			return;
		}

		String key = cropKey(world, cropPos);
		PendingHarvestRule existing = pendingHarvestRulesByKey.get(key);
		boolean retainedFertilized = fertilized || (existing != null && existing.fertilized());
		long expiresAtGameplayTick = MadokuTicks.getGameplayTicks() + PENDING_HARVEST_TTL_TICKS;
		pendingHarvestRulesByKey.put(key, new PendingHarvestRule(rule, expiresAtGameplayTick, retainedFertilized));
	}

	private static void purgeExpiredPendingHarvestRules() {
		long now = MadokuTicks.getGameplayTicks();
		Iterator<Map.Entry<String, PendingHarvestRule>> iterator = pendingHarvestRulesByKey.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<String, PendingHarvestRule> entry = iterator.next();
			PendingHarvestRule pending = entry.getValue();
			if (pending == null || pending.expiresAtGameplayTick < now) {
				iterator.remove();
			}
		}
	}

	private static boolean isHarvestFertilized(ServerLevel world, BlockPos cropPos, CropRule rule) {
		if (world == null || cropPos == null) {
			return false;
		}

		PendingHarvestRule pending = pendingHarvestRulesByKey.get(cropKey(world, cropPos));
		if (pending != null && pending.matches(rule)) {
			return pending.fertilized();
		}

		PlotState plot = findPlot(world, cropPos.below());
		return plot != null && plot.fertilized;
	}

	private static void emitFertilizedParticles(ServerLevel world, BlockPos soilPos, PlotState plot) {
		if (world == null || soilPos == null || plot == null || settings.particleCount <= 0) {
			return;
		}

		if (!canEmitFertilizedParticles(plot)) {
			return;
		}

		int particleCount = Math.min(settings.particleCount, MadokuFarmingConfig.MAX_PARTICLE_COUNT);
		if (particleCount <= 0) {
			return;
		}

		double centerX = soilPos.getX() + 0.5d;
		double centerY = soilPos.getY() + 1.0d + settings.particleYOffset;
		double centerZ = soilPos.getZ() + 0.5d;
		world.sendParticles(
			ParticleTypes.HAPPY_VILLAGER,
			centerX,
			centerY,
			centerZ,
			particleCount,
			settings.particleSpread,
			settings.particleSpread * 0.35d,
			settings.particleSpread,
			0.0d
		);
		long gameplayTicks = MadokuTicks.getGameplayTicks();
		plot.lastParticleEmissionTimeTicks = gameplayTicks;
		plot.nextParticleEmissionTimeTicks = gameplayTicks + getRandomParticleCooldownTicks();
	}

	private static boolean canEmitFertilizedParticles(PlotState plot) {
		if (plot == null) {
			return false;
		}

		long gameplayTicks = MadokuTicks.getGameplayTicks();
		if (plot.nextParticleEmissionTimeTicks == Long.MIN_VALUE) {
			long lastEmission = plot.lastParticleEmissionTimeTicks;
			if (lastEmission == Long.MIN_VALUE) {
				return true;
			}
			plot.nextParticleEmissionTimeTicks = lastEmission + getRandomParticleCooldownTicks();
		}

		return gameplayTicks >= plot.nextParticleEmissionTimeTicks;
	}

	private static long getRandomParticleCooldownTicks() {
		return ThreadLocalRandom.current().nextLong(PARTICLE_COOLDOWN_MIN_TICKS, PARTICLE_COOLDOWN_MAX_TICKS + 1L);
	}

	private static boolean isCropGrowingSeason(CropRule rule) {
		if (rule == null) {
			return true;
		}
		return isCropGrowingSeason(rule, MadokuSeason.getCurrentSeasonId());
	}

	private static boolean isCropGrowingSeason(CropRule rule, ServerLevel world) {
		if (rule == null) {
			return true;
		}
		String seasonId = world == null ? MadokuSeason.getCurrentSeasonId() : MadokuSeason.getCurrentSeasonId(world);
		return isCropGrowingSeason(rule, seasonId);
	}

	private static boolean isCropGrowingSeason(CropRule rule, String seasonId) {
		if (rule == null) {
			return true;
		}
		String normalized = normalizeSeasonId(seasonId);
		return !rule.blockedSeasonIds().contains(normalized);
	}

	private static String getCropSeasonBlockedMessage(CropRule rule, String seasonId) {
		if (rule == null) {
			return "Cannot plant this crop right now.";
		}
		String season = normalizeSeasonId(seasonId);
		String displaySeason = season.isBlank() ? "this season" : (Character.toUpperCase(season.charAt(0)) + season.substring(1));
		return "Cannot plant " + rule.displayName() + " during " + displaySeason + ".";
	}

	private static String normalizeRegistryId(String value) {
		if (value == null) {
			return "";
		}
		String trimmed = value.trim();
		if (trimmed.isEmpty()) {
			return "";
		}
		Identifier identifier = Identifier.tryParse(trimmed);
		return identifier == null ? trimmed.toLowerCase(Locale.ROOT) : identifier.toString();
	}

	private static String normalizeSeasonId(String value) {
		return MadokuSeasonConfig.normalizeKey(value);
	}

	private static double resolveCropRequiredGrowthTicks(CropRule rule, String discoveredSeasonId) {
		double baseTicks = Math.max(1.0d, rule.growthMinecraftDays() * MadokuTime.MINECRAFT_TICKS_PER_CYCLE);
		String seasonId = normalizeSeasonId(discoveredSeasonId);
		if (seasonId.isBlank() || !rule.blockedSeasonIds().contains(seasonId)) {
			return baseTicks;
		}

		double multiplier = Math.max(0.01d, settings.outOfSeasonPenalty);
		return baseTicks / multiplier;
	}

	private static double rescaleProgressGrowthTicks(double progressTicks, double previousRequiredTicks, double nextRequiredTicks) {
		double safePrevious = Math.max(1.0d, previousRequiredTicks);
		double safeNext = Math.max(1.0d, nextRequiredTicks);
		double normalizedProgress = Math.max(0.0d, Math.min(1.0d, progressTicks / safePrevious));
		return Math.max(0.0d, Math.min(safeNext, normalizedProgress * safeNext));
	}

	private static int applyScaledItemCount(int count, double multiplier, RandomSource random) {
		if (count <= 0) {
			return 0;
		}

		double scaled = count * Math.max(0.0d, multiplier);
		int whole = (int) Math.floor(scaled);
		double fractional = scaled - whole;
		if (fractional > 0.0d && random != null && random.nextDouble() < fractional) {
			whole++;
		}
		return Math.max(0, whole);
	}

	private static int getCropAge(BlockState state) {
		IntegerProperty ageProperty = findAgeProperty(state);
		if (ageProperty == null) {
			return 0;
		}
		Integer age = state.getValue(ageProperty);
		return age == null ? 0 : Math.max(0, age);
	}

	private static int getCropAgeLimit(BlockState state) {
		IntegerProperty ageProperty = findAgeProperty(state);
		if (ageProperty == null) {
			return CROP_MAX_AGE;
		}

		int maxAge = 0;
		for (Integer value : ageProperty.getPossibleValues()) {
			if (value != null && value > maxAge) {
				maxAge = value;
			}
		}
		return Math.max(1, maxAge);
	}

	private static IntegerProperty findAgeProperty(BlockState state) {
		if (state == null) {
			return null;
		}
		for (Property<?> property : state.getProperties()) {
			if (!(property instanceof IntegerProperty integerProperty)) {
				continue;
			}
			if ("age".equals(integerProperty.getName())) {
				return integerProperty;
			}
		}
		return null;
	}

	private static BlockState setCropAge(BlockState state, int age) {
		if (state == null) {
			return null;
		}

		IntegerProperty ageProperty = findAgeProperty(state);
		if (ageProperty == null) {
			return state;
		}

		int targetAge = Math.max(0, Math.min(getCropAgeLimit(state), age));
		return state.setValue(ageProperty, targetAge);
	}

	private static double progressFromAge(int age, int ageLimit) {
		int safeLimit = Math.max(1, ageLimit);
		int safeAge = Math.max(0, Math.min(safeLimit, age));
		return safeAge / (double) safeLimit;
	}

	private static int ageFromProgress(double progressNormalized, int ageLimit) {
		int safeLimit = Math.max(1, ageLimit);
		double safeProgress = Math.max(0.0d, Math.min(1.0d, progressNormalized));
		return Math.max(0, Math.min(safeLimit, (int) Math.floor(safeProgress * safeLimit + 1e-9d)));
	}

	private static boolean getBoolean(JsonObject object, String key, boolean fallback) {
		if (object == null) {
			return fallback;
		}
		JsonElement element = object.get(key);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
			return fallback;
		}
		return element.getAsBoolean();
	}

	private static int getInt(JsonObject object, String key, int fallback) {
		if (object == null) {
			return fallback;
		}
		JsonElement element = object.get(key);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			return fallback;
		}
		try {
			return element.getAsInt();
		} catch (RuntimeException exception) {
			return fallback;
		}
	}

	private static long getLong(JsonObject object, String key, long fallback) {
		if (object == null) {
			return fallback;
		}
		JsonElement element = object.get(key);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			return fallback;
		}
		try {
			return element.getAsLong();
		} catch (RuntimeException exception) {
			return fallback;
		}
	}

	private static double getDouble(JsonObject object, String key, double fallback) {
		if (object == null) {
			return fallback;
		}
		JsonElement element = object.get(key);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			return fallback;
		}
		try {
			return element.getAsDouble();
		} catch (RuntimeException exception) {
			return fallback;
		}
	}

	private static String getString(JsonObject object, String key, String fallback) {
		if (object == null) {
			return fallback;
		}
		JsonElement element = object.get(key);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			return fallback;
		}
		String value = element.getAsString();
		return value == null ? fallback : value;
	}

	private static int clampInt(int value, int fallback, int min, int max) {
		if (value < min || value > max) {
			return fallback;
		}
		return value;
	}

	private static double clampDouble(double value, double fallback, double min, double max) {
		if (!Double.isFinite(value) || value < min || value > max) {
			return fallback;
		}
		return value;
	}

	private static void emitFarmingDebug(
		String metricId,
		ServerLevel world,
		String subject,
		Map<String, String> fields
	) {
		if (!MadokuDebug.shouldEmit(MadokuDebug.Domain.FARMING, metricId)) {
			return;
		}

		MadokuDebug.EventBuilder builder = MadokuDebug.event(metricId, MadokuDebug.Domain.FARMING)
			.side(MadokuDebug.Side.SERVER)
			.tick(MadokuTicks.getGameplayTicks())
			.world(world == null ? "" : world.dimension().toString())
			.subject(subject == null || subject.isBlank() ? "farming" : subject);
		if (fields != null) {
			for (Map.Entry<String, String> entry : fields.entrySet()) {
				if (entry != null) {
					builder.field(entry.getKey(), entry.getValue());
				}
			}
		}
		builder.log();
	}

	private record ChunkRefKey(String levelId, int chunkX, int chunkZ) {
	}

	private record PendingHarvestRule(CropRule rule, long expiresAtGameplayTick, boolean fertilized) {
		private boolean matches(CropRule other) {
			return rule != null && other != null && rule.cropId().equals(other.cropId());
		}
	}

	private static final class PlotState {
		private final String levelId;
		private final long soilPos;
		private long cropPos;
		private String cropId;
		private boolean fertilized;
		private long fertilizedAtGameplayTick;
		private transient long lastParticleEmissionTimeTicks;
		private transient long nextParticleEmissionTimeTicks;

		private PlotState(String levelId, long soilPos) {
			this.levelId = levelId == null ? "" : levelId;
			this.soilPos = soilPos;
			this.cropPos = -1L;
			this.cropId = "";
			this.fertilized = false;
			this.fertilizedAtGameplayTick = Long.MIN_VALUE;
			this.lastParticleEmissionTimeTicks = Long.MIN_VALUE;
			this.nextParticleEmissionTimeTicks = Long.MIN_VALUE;
		}

		private String key() {
			return levelId + "|" + soilPos;
		}

		private boolean hasCrop() {
			return cropPos != -1L && cropId != null && !cropId.isBlank();
		}

		private void clearCrop() {
			cropPos = -1L;
			cropId = "";
		}

		private JsonObject toJson() {
			JsonObject root = new JsonObject();
			root.addProperty(FIELD_LEVEL_ID, levelId);
			root.addProperty(FIELD_SOIL_POS, soilPos);
			root.addProperty(FIELD_CROP_POS, cropPos);
			root.addProperty(FIELD_CROP_ID, cropId);
			root.addProperty(FIELD_FERTILIZED, fertilized);
			root.addProperty(FIELD_FERTILIZED_AT_GAMEPLAY_TICK,
				fertilizedAtGameplayTick == Long.MIN_VALUE ? -1L : fertilizedAtGameplayTick);
			return root;
		}

		private static PlotState fromJson(JsonElement element) {
			if (element == null || !element.isJsonObject()) {
				return null;
			}
			JsonObject source = element.getAsJsonObject();
			String levelId = getString(source, FIELD_LEVEL_ID, "").trim();
			if (levelId.isEmpty()) {
				return null;
			}
			long soilPos = getLong(source, FIELD_SOIL_POS, Long.MIN_VALUE);
			if (soilPos == Long.MIN_VALUE) {
				return null;
			}
			PlotState plot = new PlotState(levelId, soilPos);
			plot.cropPos = getLong(source, FIELD_CROP_POS, -1L);
			plot.cropId = normalizeRegistryId(getString(source, FIELD_CROP_ID, ""));
			plot.fertilized = getBoolean(source, FIELD_FERTILIZED, false);
			plot.fertilizedAtGameplayTick = getLong(source, FIELD_FERTILIZED_AT_GAMEPLAY_TICK, -1L);
			if (plot.fertilizedAtGameplayTick < 0L) {
				plot.fertilizedAtGameplayTick = Long.MIN_VALUE;
			}
			plot.lastParticleEmissionTimeTicks = Long.MIN_VALUE;
			plot.nextParticleEmissionTimeTicks = Long.MIN_VALUE;
			if (!plot.hasCrop()) {
				plot.clearCrop();
			}
			return plot;
		}
	}

	private static final class CropState {
		private final String levelId;
		private final long cropPos;
		private long soilPos;
		private String cropId;
		private String discoveredSeasonId;
		private double requiredGrowthTicks;
		private double progressGrowthTicks;
		private long lastProcessedAbsoluteDayTime;

		private CropState(
			String levelId,
			long cropPos,
			long soilPos,
			String cropId,
			String discoveredSeasonId,
			double requiredGrowthTicks,
			double progressGrowthTicks,
			long lastProcessedAbsoluteDayTime
		) {
			this.levelId = levelId == null ? "" : levelId;
			this.cropPos = cropPos;
			this.soilPos = soilPos;
			this.cropId = normalizeRegistryId(cropId);
			this.discoveredSeasonId = normalizeSeasonId(discoveredSeasonId);
			this.requiredGrowthTicks = Math.max(1.0d, requiredGrowthTicks);
			this.progressGrowthTicks = Math.max(0.0d, Math.min(this.requiredGrowthTicks, progressGrowthTicks));
			this.lastProcessedAbsoluteDayTime = Math.max(0L, lastProcessedAbsoluteDayTime);
		}

		private String key() {
			return levelId + "|" + cropPos;
		}

		private JsonObject toJson() {
			JsonObject root = new JsonObject();
			root.addProperty(FIELD_LEVEL_ID, levelId);
			root.addProperty(FIELD_CROP_POS, cropPos);
			root.addProperty(FIELD_SOIL_POS, soilPos);
			root.addProperty(FIELD_CROP_ID, cropId);
			root.addProperty(FIELD_DISCOVERED_SEASON_ID, discoveredSeasonId);
			root.addProperty(FIELD_REQUIRED_GROWTH_TICKS, requiredGrowthTicks);
			root.addProperty(FIELD_PROGRESS_GROWTH_TICKS, progressGrowthTicks);
			root.addProperty(FIELD_LAST_PROCESSED_ABSOLUTE_DAY_TIME, lastProcessedAbsoluteDayTime);
			return root;
		}

		private static CropState fromJson(JsonElement element) {
			if (element == null || !element.isJsonObject()) {
				return null;
			}
			JsonObject source = element.getAsJsonObject();
			String levelId = getString(source, FIELD_LEVEL_ID, "").trim();
			if (levelId.isEmpty()) {
				return null;
			}
			long cropPos = getLong(source, FIELD_CROP_POS, Long.MIN_VALUE);
			long soilPos = getLong(source, FIELD_SOIL_POS, Long.MIN_VALUE);
			if (cropPos == Long.MIN_VALUE || soilPos == Long.MIN_VALUE) {
				return null;
			}
			String cropId = normalizeRegistryId(getString(source, FIELD_CROP_ID, ""));
			if (cropId.isBlank()) {
				return null;
			}
			String discoveredSeasonId = normalizeSeasonId(getString(source, FIELD_DISCOVERED_SEASON_ID, ""));
			double requiredGrowthTicks = getDouble(source, FIELD_REQUIRED_GROWTH_TICKS, 1.0d);
			double progressGrowthTicks = getDouble(source, FIELD_PROGRESS_GROWTH_TICKS, 0.0d);
			long lastProcessedAbsoluteDayTime = getLong(source, FIELD_LAST_PROCESSED_ABSOLUTE_DAY_TIME, 0L);
			return new CropState(
				levelId,
				cropPos,
				soilPos,
				cropId,
				discoveredSeasonId,
				requiredGrowthTicks,
				progressGrowthTicks,
				lastProcessedAbsoluteDayTime
			);
		}
	}

	private record Settings(
		boolean enabled,
		double rainGrowthBoost,
		double fertilizedGrowthBoost,
		double outOfSeasonPenalty,
		double dryFarmlandPenalty,
		int particleCount,
		double particleSpread,
		double particleYOffset
	) {
		private static Settings defaults() {
			return new Settings(
				true,
				MadokuFarmingConfig.DEFAULT_RAIN_GROWTH_BOOST,
				MadokuFarmingConfig.DEFAULT_FERTILIZED_GROWTH_BOOST,
				MadokuFarmingConfig.DEFAULT_OUT_OF_SEASON_PENALTY,
				MadokuFarmingConfig.DEFAULT_DRY_FARMLAND_PENALTY,
				MadokuFarmingConfig.DEFAULT_PARTICLE_COUNT,
				MadokuFarmingConfig.DEFAULT_PARTICLE_SPREAD,
				MadokuFarmingConfig.DEFAULT_PARTICLE_Y_OFFSET
			);
		}

		private static Settings fromJson(JsonObject source) {
			boolean enabled = getBoolean(source, MadokuFarmingConfig.FIELD_ENABLED, true);
			double rainBoost = clampDouble(
				getDouble(source, MadokuFarmingConfig.FIELD_RAIN_GROWTH_BOOST, MadokuFarmingConfig.DEFAULT_RAIN_GROWTH_BOOST),
				MadokuFarmingConfig.DEFAULT_RAIN_GROWTH_BOOST,
				0.0d,
				1.0d
			);
			double fertilizedBoost = clampDouble(
				getDouble(source, MadokuFarmingConfig.FIELD_FERTILIZED_GROWTH_BOOST, MadokuFarmingConfig.DEFAULT_FERTILIZED_GROWTH_BOOST),
				MadokuFarmingConfig.DEFAULT_FERTILIZED_GROWTH_BOOST,
				0.0d,
				1.0d
			);
			double outOfSeasonPenalty = clampDouble(
				getDouble(source, MadokuFarmingConfig.FIELD_OUT_OF_SEASON_PENALTY, MadokuFarmingConfig.DEFAULT_OUT_OF_SEASON_PENALTY),
				MadokuFarmingConfig.DEFAULT_OUT_OF_SEASON_PENALTY,
				0.0d,
				1000.0d
			);
			double dryFarmlandPenalty = clampDouble(
				getDouble(source, MadokuFarmingConfig.FIELD_DRY_FARMLAND_PENALTY, MadokuFarmingConfig.DEFAULT_DRY_FARMLAND_PENALTY),
				MadokuFarmingConfig.DEFAULT_DRY_FARMLAND_PENALTY,
				0.0d,
				1.0d
			);
			int particleCount = clampInt(
				getInt(source, MadokuFarmingConfig.FIELD_PARTICLE_COUNT, MadokuFarmingConfig.DEFAULT_PARTICLE_COUNT),
				MadokuFarmingConfig.DEFAULT_PARTICLE_COUNT,
				1,
				MadokuFarmingConfig.MAX_PARTICLE_COUNT
			);
			double particleSpread = clampDouble(
				getDouble(source, MadokuFarmingConfig.FIELD_PARTICLE_SPREAD, MadokuFarmingConfig.DEFAULT_PARTICLE_SPREAD),
				MadokuFarmingConfig.DEFAULT_PARTICLE_SPREAD,
				0.0d,
				3.0d
			);
			double particleYOffset = clampDouble(
				getDouble(source, MadokuFarmingConfig.FIELD_PARTICLE_Y_OFFSET, MadokuFarmingConfig.DEFAULT_PARTICLE_Y_OFFSET),
				MadokuFarmingConfig.DEFAULT_PARTICLE_Y_OFFSET,
				0.0d,
				3.0d
			);
			return new Settings(
				enabled,
				rainBoost,
				fertilizedBoost,
				outOfSeasonPenalty,
				dryFarmlandPenalty,
				particleCount,
				particleSpread,
				particleYOffset
			);
		}

		private JsonObject toConfigJson() {
			JsonObject root = new JsonObject();
			root.addProperty(MadokuFarmingConfig.FIELD_ENABLED, enabled);
			root.addProperty(MadokuFarmingConfig.FIELD_RAIN_GROWTH_BOOST, rainGrowthBoost);
			root.addProperty(MadokuFarmingConfig.FIELD_FERTILIZED_GROWTH_BOOST, fertilizedGrowthBoost);
			root.addProperty(MadokuFarmingConfig.FIELD_OUT_OF_SEASON_PENALTY, outOfSeasonPenalty);
			root.addProperty(MadokuFarmingConfig.FIELD_DRY_FARMLAND_PENALTY, dryFarmlandPenalty);
			return root;
		}
	}

	public static final class CropRule {
		private final String cropId;
		private final String cropBlockId;
		private final String matureBlockId;
		private final String plantingItemId;
		private final String harvestItemId;
		private final String secondaryHarvestItemId;
		private final int secondaryMinHarvestCount;
		private final int secondaryMaxHarvestCount;
		private final String displayName;
		private final double growthMinecraftDays;
		private final int minHarvestCount;
		private final int maxHarvestCount;
		private final Set<String> blockedSeasonIds;
		private final Block matureBlock;
		private final Item harvestItem;
		private final Item secondaryHarvestItem;

		private CropRule(
			String cropId,
			String cropBlockId,
			String matureBlockId,
			String plantingItemId,
			String harvestItemId,
			String secondaryHarvestItemId,
			int secondaryMinHarvestCount,
			int secondaryMaxHarvestCount,
			String displayName,
			double growthMinecraftDays,
			int minHarvestCount,
			int maxHarvestCount,
			Set<String> blockedSeasonIds,
			Block matureBlock,
			Item harvestItem,
			Item secondaryHarvestItem
		) {
			this.cropId = cropId;
			this.cropBlockId = cropBlockId;
			this.matureBlockId = matureBlockId;
			this.plantingItemId = plantingItemId;
			this.harvestItemId = harvestItemId;
			this.secondaryHarvestItemId = secondaryHarvestItemId;
			this.secondaryMinHarvestCount = secondaryMinHarvestCount;
			this.secondaryMaxHarvestCount = secondaryMaxHarvestCount;
			this.displayName = displayName;
			this.growthMinecraftDays = growthMinecraftDays;
			this.minHarvestCount = minHarvestCount;
			this.maxHarvestCount = maxHarvestCount;
			this.blockedSeasonIds = blockedSeasonIds;
			this.matureBlock = matureBlock;
			this.harvestItem = harvestItem;
			this.secondaryHarvestItem = secondaryHarvestItem;
		}

		private static List<CropRule> defaultRules() {
			return List.of(
				defaultPotato(),
				defaultCarrot(),
				defaultBeetroot(),
				defaultMelon(),
				defaultPumpkin(),
				defaultWheat()
			);
		}

		private static CropRule defaultRule(String key) {
			if (key == null) {
				return null;
			}
			String normalized = key.trim().toLowerCase(Locale.ROOT);
			if (normalized.endsWith(".json")) {
				normalized = normalized.substring(0, normalized.length() - 5);
			}
			return switch (normalized) {
				case "potato", "minecraft:potato", "minecraft:potatoes" -> defaultPotato();
				case "carrot", "minecraft:carrot", "minecraft:carrots" -> defaultCarrot();
				case "beetroot", "minecraft:beetroot", "minecraft:beetroots" -> defaultBeetroot();
				case "melon", "minecraft:melon", "minecraft:melon_stem" -> defaultMelon();
				case "pumpkin", "minecraft:pumpkin", "minecraft:pumpkin_stem" -> defaultPumpkin();
				case "wheat", "minecraft:wheat" -> defaultWheat();
				default -> null;
			};
		}

		private static CropRule defaultPotato() {
			return fromValues(
				"potato",
				"minecraft:potatoes",
				"minecraft:potatoes",
				"minecraft:potato",
				"minecraft:potato",
				"",
				0,
				0,
				3.0d,
				7,
				9,
				Set.of("winter")
			);
		}

		private static CropRule defaultCarrot() {
			return fromValues(
				"carrot",
				"minecraft:carrots",
				"minecraft:carrots",
				"minecraft:carrot",
				"minecraft:carrot",
				"",
				0,
				0,
				5.0d,
				5,
				7,
				Set.of()
			);
		}

		private static CropRule defaultBeetroot() {
			return fromValues(
				"beetroot",
				"minecraft:beetroots",
				"minecraft:beetroots",
				"minecraft:beetroot_seeds",
				"minecraft:beetroot",
				"minecraft:beetroot_seeds",
				1,
				3,
				3.0d,
				7,
				9,
				Set.of("spring", "summer")
			);
		}

		private static CropRule defaultMelon() {
			return fromValues(
				"melon",
				"minecraft:melon_stem",
				"minecraft:melon",
				"minecraft:melon_seeds",
				"minecraft:melon_slice",
				"minecraft:melon_seeds",
				1,
				3,
				11.0d,
				15,
				17,
				Set.of("fall", "winter")
			);
		}

		private static CropRule defaultPumpkin() {
			return fromValues(
				"pumpkin",
				"minecraft:pumpkin_stem",
				"minecraft:pumpkin",
				"minecraft:pumpkin_seeds",
				"minecraft:pumpkin",
				"minecraft:pumpkin_seeds",
				1,
				3,
				9.0d,
				3,
				5,
				Set.of("spring", "winter")
			);
		}

		private static CropRule defaultWheat() {
			return fromValues(
				"wheat",
				"minecraft:wheat",
				"minecraft:wheat",
				"minecraft:wheat_seeds",
				"minecraft:wheat",
				"minecraft:wheat_seeds",
				1,
				3,
				7.0d,
				11,
				13,
				Set.of("summer")
			);
		}

		private static CropRule fromJson(String fileKey, JsonObject source) {
			CropRule fallback = defaultRule(fileKey);
			if (fallback == null) {
				return null;
			}

			String cropId = normalizeRegistryId(getString(source, MadokuCropConfig.FIELD_CROP_ID, fallback.cropId));
			if (cropId.isEmpty()) {
				cropId = fallback.cropId;
			}

			double growthDays = clampDouble(
				getDouble(source, MadokuCropConfig.FIELD_GROWTH_TIME, fallback.growthMinecraftDays),
				fallback.growthMinecraftDays,
				0.25d,
				365.0d
			);
			int minHarvestCount = clampInt(
				getInt(source, MadokuCropConfig.FIELD_MIN_HARVEST_COUNT, fallback.minHarvestCount),
				fallback.minHarvestCount,
				1,
				1024
			);
			int maxHarvestCount = clampInt(
				getInt(source, MadokuCropConfig.FIELD_MAX_HARVEST_COUNT, fallback.maxHarvestCount),
				fallback.maxHarvestCount,
				minHarvestCount,
				1024
			);
			int secondaryMinHarvestCount = clampInt(
				getInt(source, MadokuCropConfig.FIELD_MIN_HARVEST_SEEDS, fallback.secondaryMinHarvestCount),
				fallback.secondaryMinHarvestCount,
				0,
				1024
			);
			int secondaryMaxHarvestCount = clampInt(
				getInt(source, MadokuCropConfig.FIELD_MAX_HARVEST_SEEDS, fallback.secondaryMaxHarvestCount),
				fallback.secondaryMaxHarvestCount,
				secondaryMinHarvestCount,
				1024
			);
			Set<String> blockedSeasonIds = parseBlockedSeasonIds(source, fallback.blockedSeasonIds);

			return fromValues(
				cropId,
				fallback.cropBlockId,
				fallback.matureBlockId,
				fallback.plantingItemId,
				fallback.harvestItemId,
				fallback.secondaryHarvestItemId,
				secondaryMinHarvestCount,
				secondaryMaxHarvestCount,
				growthDays,
				minHarvestCount,
				maxHarvestCount,
				blockedSeasonIds
			);
		}

		private static CropRule fromValues(
			String cropId,
			String cropBlockId,
			String matureBlockId,
			String plantingItemId,
			String harvestItemId,
			String secondaryHarvestItemId,
			int secondaryMinHarvestCount,
			int secondaryMaxHarvestCount,
			double growthMinecraftDays,
			int minHarvestCount,
			int maxHarvestCount,
			Set<String> blockedSeasonIds
		) {
			String normalizedCropId = normalizeRegistryId(cropId);
			String normalizedCropBlockId = normalizeRegistryId(cropBlockId);
			String normalizedMatureBlockId = normalizeRegistryId(matureBlockId);
			String normalizedPlantingItemId = normalizeRegistryId(plantingItemId);
			String normalizedHarvestItemId = normalizeRegistryId(harvestItemId);
			String normalizedSecondaryHarvestItemId = normalizeRegistryId(secondaryHarvestItemId);

			Identifier matureBlockIdentifier = Identifier.tryParse(normalizedMatureBlockId);
			Identifier harvestIdentifier = Identifier.tryParse(normalizedHarvestItemId);
			Identifier secondaryHarvestIdentifier = normalizedSecondaryHarvestItemId.isBlank() ? null : Identifier.tryParse(normalizedSecondaryHarvestItemId);

			Block matureBlock = matureBlockIdentifier == null ? null : BuiltInRegistries.BLOCK.getValue(matureBlockIdentifier);
			Item harvestItem = harvestIdentifier == null ? null : BuiltInRegistries.ITEM.getValue(harvestIdentifier);
			Item secondaryHarvestItem = secondaryHarvestIdentifier == null ? null : BuiltInRegistries.ITEM.getValue(secondaryHarvestIdentifier);

			Set<String> normalizedBlockedSeasons = blockedSeasonIds == null || blockedSeasonIds.isEmpty()
				? Set.of()
				: blockedSeasonIds.stream()
					.map(MadokuCropConfig::normalizeSeasonId)
					.filter(value -> !value.isBlank())
					.collect(Collectors.toUnmodifiableSet());

			String displayName = normalizeDisplayName(normalizedCropId);
			return new CropRule(
				normalizedCropId,
				normalizedCropBlockId,
				normalizedMatureBlockId,
				normalizedPlantingItemId,
				normalizedHarvestItemId,
				normalizedSecondaryHarvestItemId,
				Math.max(0, secondaryMinHarvestCount),
				Math.max(Math.max(0, secondaryMinHarvestCount), secondaryMaxHarvestCount),
				displayName,
				growthMinecraftDays,
				Math.max(1, minHarvestCount),
				Math.max(Math.max(1, minHarvestCount), maxHarvestCount),
				normalizedBlockedSeasons,
				matureBlock,
				harvestItem,
				secondaryHarvestItem
			);
		}

		private static Set<String> parseBlockedSeasonIds(JsonObject source, Set<String> fallback) {
			if (source == null) {
				return fallback == null ? Set.of() : fallback;
			}

			JsonElement element = source.get(MadokuCropConfig.FIELD_PLANTING_BLOCKED_SEASONS);
			if (element == null || !element.isJsonArray()) {
				return fallback == null ? Set.of() : fallback;
			}

			java.util.LinkedHashSet<String> seasons = new java.util.LinkedHashSet<>();
			for (JsonElement child : element.getAsJsonArray()) {
				if (child == null || !child.isJsonPrimitive() || !child.getAsJsonPrimitive().isString()) {
					continue;
				}
				String normalized = MadokuCropConfig.normalizeSeasonId(child.getAsString());
				if (!normalized.isBlank()) {
					seasons.add(normalized);
				}
			}
			return seasons.isEmpty() ? (fallback == null ? Set.of() : fallback) : Set.copyOf(seasons);
		}

		private static String normalizeDisplayName(String value) {
			if (value == null || value.isBlank()) {
				return "Crop";
			}
			String normalized = value;
			int namespaceSeparator = normalized.indexOf(':');
			if (namespaceSeparator >= 0 && namespaceSeparator < normalized.length() - 1) {
				normalized = normalized.substring(namespaceSeparator + 1);
			}
			normalized = normalized.replace('_', ' ').replace('-', ' ');
			String[] parts = normalized.split("\\s+");
			StringBuilder builder = new StringBuilder();
			for (String part : parts) {
				if (part == null || part.isBlank()) {
					continue;
				}
				if (builder.length() > 0) {
					builder.append(' ');
				}
				builder.append(Character.toUpperCase(part.charAt(0)));
				if (part.length() > 1) {
					builder.append(part.substring(1));
				}
			}
			String display = builder.toString().trim();
			return display.isEmpty() ? "Crop" : display;
		}

		private boolean usesDistinctMatureBlock() {
			return matureBlockId != null && !matureBlockId.isBlank() && !matureBlockId.equals(cropBlockId);
		}

		private boolean hasSecondaryHarvestDrop() {
			return secondaryHarvestItem != null && secondaryMinHarvestCount > 0 && secondaryMaxHarvestCount >= secondaryMinHarvestCount;
		}

		public String cropId() {
			return cropId;
		}

		public String cropBlockId() {
			return cropBlockId;
		}

		public String matureBlockId() {
			return matureBlockId;
		}

		public String plantingItemId() {
			return plantingItemId;
		}

		public Item harvestItem() {
			return harvestItem;
		}

		public Item secondaryHarvestItem() {
			return secondaryHarvestItem;
		}

		public int secondaryMinHarvestCount() {
			return secondaryMinHarvestCount;
		}

		public int secondaryMaxHarvestCount() {
			return secondaryMaxHarvestCount;
		}

		public double growthMinecraftDays() {
			return growthMinecraftDays;
		}

		public int minHarvestCount() {
			return minHarvestCount;
		}

		public int maxHarvestCount() {
			return maxHarvestCount;
		}

		public Set<String> blockedSeasonIds() {
			return blockedSeasonIds;
		}

		public Block matureBlock() {
			return matureBlock;
		}

		public String displayName() {
			return displayName;
		}
	}
}

