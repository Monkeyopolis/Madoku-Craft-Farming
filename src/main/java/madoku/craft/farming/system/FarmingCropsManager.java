package madoku.craft.farming.system;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import madoku.craft.api.data.DataSystemsManager;
import madoku.craft.api.data.DataWorldManager;
import madoku.craft.api.data.MadokuChunkDataManager;
import madoku.craft.api.time.MadokuTimeManager;
import madoku.craft.api.json.JSONFormatManager;
import madoku.craft.api.json.MadokuJSONManager;
import madoku.craft.farming.mixin.ItemBuiltInRegistryHolderAccessor;
import madoku.craft.farming.mixin.ItemComponentsAccessor;
import madoku.craft.api.scheduler.MadokuSchedulerManager;
import madoku.craft.api.season.MadokuSeasonManager;
import madoku.craft.api.season.SeasonBiomeClimateManager;
import madoku.craft.api.chunk.MadokuChunkManager;
import madoku.craft.api.loot.LootTableCropsManager;
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
import net.minecraft.world.level.chunk.LevelChunk;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class FarmingCropsManager {
	private static final Logger LOGGER = LoggerFactory.getLogger(FarmingCropsManager.class);
	private static final String LORE_GROWING_TIME_PREFIX = "Growing Time:";
	private static final String LORE_TEMPERATURE_PREFIX = "Ideal Temperature:";
	private static final String LORE_HUMIDITY_PREFIX = "Ideal Humidity:";
	private static final String LORE_FERTILIZER_PREFIX = "Farmland fertilizer.";
	private static final String LORE_FERTILIZER_EFFECT_PREFIX = "Increases crop growth speed and yield.";

	private static final String DATA_SYSTEM_ID = "farming";

	private static final String FIELD_PLOTS = "plots";
	private static final String FIELD_CROPS = "crops";

	private static final String FIELD_LEVEL_ID = "level-id";
	private static final String FIELD_SOIL_POS = "soil-pos";
	private static final String FIELD_CROP_POS = "crop-pos";
	private static final String FIELD_CROP_ID = "crop-id";
	private static final String FIELD_FERTILIZED = "fertilized";
	private static final String FIELD_FERTILIZED_AT_GAMEPLAY_TICK = "fertilized-at-gameplay-tick";
	private static final String FIELD_REQUIRED_GROWTH_TICKS = "required-growth-ticks";
	private static final String FIELD_PROGRESS_GROWTH_TICKS = "progress-growth-ticks";
	private static final String FIELD_LAST_PROCESSED_ABSOLUTE_DAY_TIME = "last-processed-absolute-day-time";

	private static final int CROP_MAX_AGE = 7;
	private static final long PENDING_HARVEST_TTL_TICKS = 2L;
	private static final long ABSOLUTE_TIME_ROLLBACK_RESET_TICKS = 20L;
	private static final int FERTILIZER_PARTICLE_MIN_COUNT = 4;
	private static final int FERTILIZER_PARTICLE_MAX_COUNT = 8;
	private static final double FERTILIZER_PARTICLE_SPREAD = 0.12d;
	private static final double FERTILIZER_PARTICLE_Y_OFFSET = 0.1d;

	private static volatile Settings settings = Settings.defaults();
	private static volatile boolean dirty = false;

	private static volatile Map<String, CropRule> cropRulesByPlantingItemId = new LinkedHashMap<>();
	private static volatile Map<Item, CropRule> cropRulesByPlantingItem = Map.of();
	private static volatile Map<Block, CropRule> cropRulesByBlock = Map.of();
	private static volatile Map<Block, CropRule> cropRulesByGrowthBlock = Map.of();
	private static volatile Map<Block, CropRule> cropRulesByMatureBlock = Map.of();
	private static final Map<Block, AgeMetadata> AGE_METADATA_BY_BLOCK = new HashMap<>();

	private static final Map<String, PlotState> plotsByKey = new HashMap<>();
	private static final Map<String, CropState> cropsByKey = new HashMap<>();
	private static final Map<String, Set<String>> cropKeysByChunk = new HashMap<>();
	private static final Map<String, PendingHarvestRule> pendingHarvestRulesByKey = new HashMap<>();
	private static final Map<String, JsonObject> serializedPlotsByKey = new HashMap<>();
	private static final Map<String, Long> serializedPlotFingerprintsByKey = new HashMap<>();
	private static final Map<String, JsonObject> serializedCropsByKey = new HashMap<>();
	private static final Map<String, Long> serializedCropFingerprintsByKey = new HashMap<>();

	private static final MadokuChunkManager.ChunkLifecycleListener CHUNK_LISTENER = new MadokuChunkManager.ChunkLifecycleListener() {
		@Override
		public void onChunkLoaded(ServerLevel level, int chunkX, int chunkZ) {
			processCropsInChunk(level, chunkX, chunkZ);
		}

		@Override
		public void onChunkUnloaded(ServerLevel level, int chunkX, int chunkZ) {
		}
	};

	private FarmingCropsManager() {
	}

	public static void initialize() {
		DataSystemsManager.registerSystem(DATA_SYSTEM_ID);
		loadStaticConfig();
		loadCropConfigs();
		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) ->
			handleBlockBreakBefore(world, pos, state, blockEntity)
		);
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) ->
			handleBlockBreak(world, pos, state, blockEntity)
		);
		MadokuChunkManager.registerChunkLifecycleListener(CHUNK_LISTENER);
	}

	public static void reset() {
		plotsByKey.clear();
		cropsByKey.clear();
		cropKeysByChunk.clear();
		pendingHarvestRulesByKey.clear();
		serializedPlotsByKey.clear();
		serializedPlotFingerprintsByKey.clear();
		serializedCropsByKey.clear();
		serializedCropFingerprintsByKey.clear();
		dirty = false;
	}

	public static void onServerStarted(MinecraftServer server) {
		if (server == null) {
			return;
		}
		DataSystemsManager.registerSystem(DATA_SYSTEM_ID);
		applyCropItemMetadata();
		for (ServerLevel level : server.getAllLevels()) {
			level.getChunkSource().chunkMap.forEachReadyToSendChunk((LevelChunk chunk) -> {
				if (chunk != null) processCropsInChunk(level, chunk.getPos().x(), chunk.getPos().z());
			});
		}
	}

	public static void loadPersistedData(MinecraftServer server) {
		if (server == null) {
			return;
		}

		loadStaticConfig();
		loadCropConfigs();
		JsonObject data = DataWorldManager.getSystemData(DATA_SYSTEM_ID);
		applyPersistedData(data);
		dirty = false;
	}

	public static void autosavePersistedData(MinecraftServer server) {
		if (server == null) {
			return;
		}

		if (dirty) {
			savePersistedData(server);
		}
	}

	public static void savePersistedData(MinecraftServer server) {
		if (server == null) {
			return;
		}

		DataWorldManager.setSystemData(DATA_SYSTEM_ID, toPersistedData());
		dirty = false;
	}

	public static boolean isEnabled() {
		return settings.enabled;
	}

	public static boolean applyExternalGrowthPercent(ServerLevel world, BlockPos cropPos, double growthPercent, String source) {
		if (!settings.enabled || world == null || cropPos == null || growthPercent <= 0.0d) {
			return false;
		}
		BlockState state = world.getBlockState(cropPos);
		CropRule rule = resolveCropRuleByCropState(state);
		if (rule == null) {
			return false;
		}

		trackCrop(world, cropPos, state);
		CropState crop = cropsByKey.get(cropKey(world, cropPos));
		if (crop == null) {
			return false;
		}

		double requiredTicks = Math.max(1.0d, crop.requiredGrowthTicks);
		double deltaTicks = requiredTicks * (growthPercent / 100.0d);
		if (!(deltaTicks > 0.0d)) {
			return false;
		}
		double before = Math.max(0.0d, Math.min(requiredTicks, crop.progressGrowthTicks));
		double updated = Math.min(requiredTicks, before + deltaTicks);
		if (updated <= before + 1.0e-6d) {
			return false;
		}

		crop.progressGrowthTicks = updated;
		crop.lastProcessedAbsoluteDayTime = Math.max(crop.lastProcessedAbsoluteDayTime, resolveAbsoluteDayTime(world));
		dirty = true;
		updateCropBlockAge(world, cropPos, world.getBlockState(cropPos), rule, crop);

		return true;
	}

	public static boolean isCropPlantItem(ItemStack stack) {
		return resolveCropRuleByPlantingItem(stack) != null;
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

		long gameplayTick = MadokuTimeManager.getGameplayTicks();
		if (!plot.fertilized) {
			plot.fertilized = true;
			plot.fertilizedAtGameplayTick = gameplayTick;
			dirty = true;
		}
		emitFertilizedParticles(world, soilPos);
	}

	public static void handleFarmlandRandomTick(ServerLevel world, BlockPos soilPos) {
		if (!settings.enabled || world == null || soilPos == null || !isFarmland(world.getBlockState(soilPos))) {
			return;
		}

		PlotState plot = findPlot(world, soilPos);
		if (plot != null && plot.fertilized) {
			emitFertilizedParticles(world, soilPos);
		}
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
			plot.fertilizedAtGameplayTick = MadokuTimeManager.getGameplayTicks();
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
		}
		if (fertilized && changed) {
			emitFertilizedParticles(world, soilPos);
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
	}

	public static void trackCrop(ServerLevel world, BlockPos cropPos, BlockState cropState) {
		if (!settings.enabled || world == null || cropPos == null || cropState == null) {
			return;
		}

		CropRule rule = resolveCropRuleByCropState(cropState);
		if (rule == null) {
			return;
		}
		// A mature pumpkin/melon placed as a block is not a farming harvest.
		// Do not let a farmland scan turn it into tracked crop state.
		if (isCropMatureBlock(cropState, rule)
			&& MadokuChunkDataManager.isPlayerPlacedBlock(world, cropPos)) {
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
		double requiredGrowthTicks = resolveCropRequiredGrowthTicks(rule);
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
				requiredGrowthTicks,
				Math.max(0.0d, progressFromState),
				nowAbsoluteDayTime
			);
		} else {
			existing.soilPos = soilPos.asLong();
			if (cropTypeChanged) {
				existing.cropId = rule.plantingItemId();
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

	/**
	 * Updates a managed crop when Minecraft gives its block a random tick.
	 * Random ticks are the trigger; the persisted elapsed-time value preserves
	 * the configured growth duration instead of making growth depend on tick count.
	 */
	public static boolean handleCropRandomTick(ServerLevel world, BlockPos cropPos, BlockState cropState, RandomSource random) {
		if (!settings.enabled || world == null || cropPos == null || cropState == null) {
			return false;
		}

		CropRule rule = resolveCropRuleByCropState(cropState);
		BlockState soilState = world.getBlockState(cropPos.below());
		if (rule == null || !isCropBlock(cropState, rule) || !isFarmland(soilState)) {
			return false;
		}

		trackCrop(world, cropPos, cropState);
		CropState crop = findCrop(world, cropPos);
		if (crop == null) {
			return false;
		}

		applyElapsedCropProgress(world, cropPos, cropState, rule, crop, resolveAbsoluteDayTime(world));
		return true;
	}

	private static void processCropsInChunk(ServerLevel world, int chunkX, int chunkZ) {
		if (!settings.enabled || world == null) {
			return;
		}

		Set<String> keys = cropKeysByChunk.get(cropChunkKey(world, chunkX, chunkZ));
		if (keys == null || keys.isEmpty()) {
			return;
		}

		long currentAbsoluteDayTime = resolveAbsoluteDayTime(world);
		List<String> staleKeys = new ArrayList<>();
		for (String key : keys) {
			CropState crop = cropsByKey.get(key);
			if (crop == null) {
				staleKeys.add(key);
				continue;
			}

			BlockPos cropPos = BlockPos.of(crop.cropPos);
			BlockState cropState = world.getBlockState(cropPos);
			BlockState soilState = world.getBlockState(cropPos.below());
			CropRule rule = resolveCropRuleByCropState(cropState);
			if (rule == null || !isCropBlock(cropState, rule) || !isFarmland(soilState)) {
				staleKeys.add(key);
				continue;
			}

			applyElapsedCropProgress(world, cropPos, cropState, rule, crop, currentAbsoluteDayTime);
		}

		for (String staleKey : staleKeys) {
			if (removeCropStateByKey(staleKey) != null) {
				dirty = true;
			} else {
				// Repair an orphaned secondary-index entry left by an older state or migration.
				keys.remove(staleKey);
			}
		}
	}

	private static void applyElapsedCropProgress(
		ServerLevel world,
		BlockPos cropPos,
		BlockState state,
		CropRule rule,
		CropState crop,
		long currentAbsoluteDayTime
	) {
		if (world == null || cropPos == null || state == null || rule == null || crop == null) {
			return;
		}

		double requiredTicks = Math.max(1.0d, crop.requiredGrowthTicks);
		crop.requiredGrowthTicks = requiredTicks;
		int ageLimit = Math.max(1, getCropAgeLimit(state));
		boolean observedMature = isCropMatureBlock(state, rule) || isMaxAge(state);
		int observedAge = isCropMatureBlock(state, rule) ? ageLimit : getCropAge(state);
		double observedProgress = progressFromAge(observedAge, ageLimit) * requiredTicks;
		double cappedTrackedProgress = Math.max(0.0d, Math.min(requiredTicks, crop.progressGrowthTicks));
		double oneAgeStepTicks = requiredTicks / ageLimit;
		if (!observedMature && observedProgress + Math.max(1.0d, oneAgeStepTicks) < cappedTrackedProgress) {
			crop.progressGrowthTicks = Math.max(0.0d, Math.min(requiredTicks, observedProgress));
		}

		BlockPos soilPos = cropPos.below();
		BlockState soilState = world.getBlockState(soilPos);
		PlotState plot = findPlot(world, soilPos);
		boolean fertilized = plot != null && plot.fertilized;
		boolean raining = world.isRainingAt(cropPos);
		boolean dryFarmland = isDryFarmland(soilState);
		double speedMultiplier = 1.0d + resolveGrowingConditionsSpeedModifier(rule, world, cropPos);
		if (fertilized) speedMultiplier += settings.fertilizedGrowthBoost;
		if (raining) speedMultiplier += settings.rainGrowthBoost;
		if (dryFarmland) speedMultiplier *= Math.max(0.0d, 1.0d - settings.dryFarmlandPenalty);
		speedMultiplier = Math.max(0.0d, speedMultiplier);

		long rawPreviousAbsolute = Math.max(0L, crop.lastProcessedAbsoluteDayTime);
		long previousAbsolute = normalizePreviousAbsoluteTick(crop.lastProcessedAbsoluteDayTime, currentAbsoluteDayTime);
		if (previousAbsolute != rawPreviousAbsolute) {
			crop.lastProcessedAbsoluteDayTime = previousAbsolute;
			dirty = true;
		}
		long safeCurrentAbsolute = Math.max(previousAbsolute, currentAbsoluteDayTime);
		long elapsedTicks = safeCurrentAbsolute - previousAbsolute;
		if (elapsedTicks > 0L && speedMultiplier > 0.0d) {
			double updatedProgress = Math.min(requiredTicks, crop.progressGrowthTicks + elapsedTicks * speedMultiplier);
			if (updatedProgress > crop.progressGrowthTicks) {
				crop.progressGrowthTicks = updatedProgress;
				dirty = true;
			}
		}
		crop.lastProcessedAbsoluteDayTime = safeCurrentAbsolute;

		double observedProgressFromState = progressFromAge(
			isCropMatureBlock(state, rule) ? ageLimit : getCropAge(state),
			ageLimit
		) * requiredTicks;
		if (observedProgressFromState > crop.progressGrowthTicks) {
			crop.progressGrowthTicks = Math.min(requiredTicks, observedProgressFromState);
			dirty = true;
		}

		updateCropBlockAge(world, cropPos, state, rule, crop);
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

	public static List<ItemStack> calculateCropHarvestDrops(ServerLevel world, BlockPos cropPos, BlockState state, RandomSource random) {
		purgeExpiredPendingHarvestRules();
		CropRule rule = resolveHarvestRule(world, cropPos, state);
		if (!settings.enabled || rule == null || !isCropHarvestReady(world, cropPos, state)) {
			return List.of();
		}

		RandomSource safeRandom = random == null ? RandomSource.create() : random;
		List<ItemStack> drops = LootTableCropsManager.generateManagedLootForTable(rule.yieldTableId(), safeRandom);
		if (isHarvestFertilized(world, cropPos, rule) && !drops.isEmpty()) {
			ItemStack first = drops.getFirst();
			if (first != null && !first.isEmpty()) {
				first.setCount(applyScaledItemCount(first.getCount(), 1.0d + settings.fertilizedYieldBoost, safeRandom));
			}
		}
		return drops;
	}

	public static boolean hasCropHarvestLootTable(ServerLevel world, BlockPos cropPos, BlockState state) {
		CropRule rule = resolveHarvestRule(world, cropPos, state);
		return rule != null && LootTableCropsManager.hasManagedLootTable(rule.yieldTableId());
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


	private static boolean handleBlockBreakBefore(Level world, BlockPos pos, BlockState state, BlockEntity blockEntity) {
		if (!settings.enabled || !(world instanceof ServerLevel serverLevel) || pos == null || state == null) {
			return true;
		}

		if (isManagedHarvestState(serverLevel, pos, state)) {
			prepareCropHarvest(serverLevel, pos, state);
		}
		return true;
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
					// The stem was player-planted, but the mature block is generated
					// by farming and must use the managed harvest path.
					MadokuChunkDataManager.removePlayerPlacedBlock(world, cropPos);
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

	private static String levelId(ServerLevel world) {
		if (world == null) {
			return "";
		}
		return MadokuSchedulerManager.normalizeLevelIdentifier(world.dimension().toString());
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
		return plotsByKey.put(key, value);
	}

	private static PlotState removePlotStateByKey(String key) {
		return plotsByKey.remove(key);
	}

	private static CropState putCropState(String key, CropState value) {
		CropState previous = cropsByKey.put(key, value);
		if (previous != null && (value == null || !cropChunkKey(previous).equals(cropChunkKey(value)))) {
			removeCropKeyFromChunk(previous);
		}
		if (value != null) {
			cropKeysByChunk.computeIfAbsent(cropChunkKey(value), ignored -> new HashSet<>()).add(key);
		}
		return previous;
	}

	private static CropState removeCropStateByKey(String key) {
		CropState removed = cropsByKey.remove(key);
		serializedCropsByKey.remove(key);
		serializedCropFingerprintsByKey.remove(key);
		if (removed != null) {
			removeCropKeyFromChunk(removed);
		}
		return removed;
	}

	private static void removeCropKeyFromChunk(CropState crop) {
		if (crop == null) {
			return;
		}
		String chunkKey = cropChunkKey(crop);
		Set<String> keys = cropKeysByChunk.get(chunkKey);
		if (keys == null || !keys.remove(crop.key())) {
			return;
		}
		if (keys.isEmpty()) {
			cropKeysByChunk.remove(chunkKey);
		}
	}

	private static String cropChunkKey(CropState crop) {
		return crop == null ? "" : cropChunkKey(crop.levelId, BlockPos.getX(crop.cropPos) >> 4, BlockPos.getZ(crop.cropPos) >> 4);
	}

	private static String cropChunkKey(ServerLevel world, int chunkX, int chunkZ) {
		return cropChunkKey(levelId(world), chunkX, chunkZ);
	}

	private static String cropChunkKey(String levelId, int chunkX, int chunkZ) {
		return (levelId == null ? "" : levelId) + "|" + chunkX + "|" + chunkZ;
	}

	private static String plotKey(ServerLevel world, BlockPos soilPos) {
		return levelId(world) + "|" + soilPos.asLong();
	}

	private static String cropKey(ServerLevel world, BlockPos cropPos) {
		return levelId(world) + "|" + (cropPos == null ? -1L : cropPos.asLong());
	}

	private static long resolveAbsoluteDayTime(ServerLevel world) {
		if (world == null) {
			return MadokuTimeManager.getCurrentAbsoluteDayTime();
		}
		return MadokuTimeManager.getCurrentAbsoluteDayTime(world);
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
		cropKeysByChunk.clear();
		pendingHarvestRulesByKey.clear();
		serializedPlotsByKey.clear();
		serializedPlotFingerprintsByKey.clear();
		serializedCropsByKey.clear();
		serializedCropFingerprintsByKey.clear();

		if (source == null) {
			return;
		}

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

	private static JsonObject toPersistedData() {
		JSONFormatManager.ArrayBuilder plots = JSONFormatManager.array();
		for (PlotState plot : plotsByKey.values()) {
			if (plot != null) {
				String key = plot.key();
				long fingerprint = plot.persistenceFingerprint();
				JsonObject serialized = serializedPlotsByKey.get(key);
				if (serialized == null || !Long.valueOf(fingerprint).equals(serializedPlotFingerprintsByKey.get(key))) {
					serialized = plot.toJson();
					serializedPlotsByKey.put(key, serialized);
					serializedPlotFingerprintsByKey.put(key, fingerprint);
				}
				plots.add(serialized);
			}
		}
		serializedPlotsByKey.keySet().retainAll(plotsByKey.keySet());
		serializedPlotFingerprintsByKey.keySet().retainAll(plotsByKey.keySet());
		JSONFormatManager.ArrayBuilder crops = JSONFormatManager.array();
		for (CropState crop : cropsByKey.values()) {
			if (crop != null) {
				String key = crop.key();
				long fingerprint = crop.persistenceFingerprint();
				JsonObject serialized = serializedCropsByKey.get(key);
				if (serialized == null || !Long.valueOf(fingerprint).equals(serializedCropFingerprintsByKey.get(key))) {
					serialized = crop.toJson();
					serializedCropsByKey.put(key, serialized);
					serializedCropFingerprintsByKey.put(key, fingerprint);
				}
				crops.add(serialized);
			}
		}
		serializedCropsByKey.keySet().retainAll(cropsByKey.keySet());
		serializedCropFingerprintsByKey.keySet().retainAll(cropsByKey.keySet());
		return JSONFormatManager.object()
			.put(FIELD_PLOTS, plots.build())
			.put(FIELD_CROPS, crops.build())
			.build();
	}

	private static void loadStaticConfig() {
		Settings fallback = Settings.defaults();

		try {
			JsonObject normalized = FarmingConfigManager.loadFarmingSettings();
			Settings loaded = Settings.fromJson(normalized);
			settings = loaded;
		} catch (IOException | RuntimeException exception) {
			settings = fallback;
			LOGGER.error("Failed to load FarmingCropsManager static config; using defaults.", exception);
		}
	}

	private static void loadCropConfigs() {
		Map<String, CropRule> plantingRules = new LinkedHashMap<>();
		Map<String, JsonObject> defaultFiles = CropsConfigManager.buildDefaultCropFileDefaults();

		try {
			Path directory = FarmingConfigManager.resolveCropsConfigDirectory();
			for (Map.Entry<String, JsonObject> entry : defaultFiles.entrySet()) {
				String fileKey = entry.getKey();
				JsonObject defaults = entry.getValue();
				Path file = resolveJsonFile(directory, fileKey);
				JsonObject normalized = JSONFormatManager.ensureManagedFile(file, defaults);
				CropRule rule = CropRule.fromJson(fileKey, normalized);
				if (rule == null) {
					rule = CropRule.defaultRule(fileKey);
				}
				if (rule == null) {
					continue;
				}

				plantingRules.put(rule.plantingItemId(), rule);
			}
		} catch (IOException | RuntimeException exception) {
			LOGGER.error("Failed to load FarmingCropsManager crop configs; using defaults.", exception);
			for (CropRule rule : CropRule.defaultRules()) {
				plantingRules.put(rule.plantingItemId(), rule);
			}
		}

		Map<Item, CropRule> plantingItems = new HashMap<>();
		Map<Block, CropRule> blocks = new HashMap<>();
		Map<Block, CropRule> growthBlocks = new HashMap<>();
		Map<Block, CropRule> matureBlocks = new HashMap<>();
		for (CropRule rule : plantingRules.values()) {
			Item item = resolveItemByRegistryId(rule.plantingItemId());
			if (item != null) {
				plantingItems.put(item, rule);
			}
			Block cropBlock = resolveBlockByRegistryId(rule.cropBlockId());
			if (cropBlock != null) {
				blocks.put(cropBlock, rule);
				growthBlocks.put(cropBlock, rule);
			}
			if (rule.usesDistinctMatureBlock()) {
				Block matureBlock = resolveBlockByRegistryId(rule.matureBlockId());
				if (matureBlock != null) {
					blocks.put(matureBlock, rule);
					matureBlocks.put(matureBlock, rule);
				}
			}
		}

		cropRulesByPlantingItemId = Map.copyOf(plantingRules);
		cropRulesByPlantingItem = Map.copyOf(plantingItems);
		cropRulesByBlock = Map.copyOf(blocks);
		cropRulesByGrowthBlock = Map.copyOf(growthBlocks);
		cropRulesByMatureBlock = Map.copyOf(matureBlocks);
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
		return cropRulesByPlantingItem.get(item);
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
		return cropRulesByBlock.get(state.getBlock());
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
				if (text.startsWith("Season:")
					|| text.startsWith(LORE_GROWING_TIME_PREFIX)
					|| text.startsWith(LORE_TEMPERATURE_PREFIX)
					|| text.startsWith(LORE_HUMIDITY_PREFIX)
					|| text.equals(LORE_FERTILIZER_PREFIX)
					|| text.equals(LORE_FERTILIZER_EFFECT_PREFIX)) {
					continue;
				}
				updatedLines.add(line);
			}
		}

		updatedLines.add(Component.literal(formatGrowthTimeLoreLine(rule.growthMinecraftDays())).withStyle(ChatFormatting.GOLD));
		GrowingConditions conditions = rule.growingConditions();
		if (conditions != null) {
			updatedLines.add(Component.literal(formatTemperatureLoreLine(conditions)).withStyle(ChatFormatting.DARK_GREEN));
			updatedLines.add(Component.literal(formatHumidityLoreLine(conditions)).withStyle(ChatFormatting.DARK_GREEN));
		}

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

	private static String formatGrowthTimeLoreLine(double growthMinecraftDays) {
		return LORE_GROWING_TIME_PREFIX + " " + formatGrowthDays(growthMinecraftDays) + " Days";
	}

	private static String formatTemperatureLoreLine(GrowingConditions conditions) {
		return LORE_TEMPERATURE_PREFIX + " " + formatRange(conditions.minimumTemperature(), conditions.maximumTemperature());
	}

	private static String formatHumidityLoreLine(GrowingConditions conditions) {
		return LORE_HUMIDITY_PREFIX + " " + formatRange(conditions.minimumHumidity(), conditions.maximumHumidity());
	}

	private static String formatRange(double minimum, double maximum) {
		return formatGrowthDays(minimum) + " - " + formatGrowthDays(maximum);
	}

	private static String formatGrowthDays(double growthDays) {
		BigDecimal value = BigDecimal.valueOf(Math.max(0.0d, growthDays)).stripTrailingZeros();
		String formatted = value.toPlainString();
		return formatted.isEmpty() ? "0" : formatted;
	}

	private static CropRule resolvePendingHarvestRule(ServerLevel world, BlockPos cropPos) {
		if (world == null || cropPos == null) {
			return null;
		}
		PendingHarvestRule pending = pendingHarvestRulesByKey.get(cropKey(world, cropPos));
		if (pending == null) {
			return null;
		}
		long now = MadokuTimeManager.getGameplayTicks();
		if (pending.expiresAtGameplayTick < now) {
			pendingHarvestRulesByKey.remove(cropKey(world, cropPos));
			return null;
		}
		return pending.rule;
	}

	private static boolean isCropBlock(BlockState state, CropRule rule) {
		return state != null && rule != null && cropRulesByBlock.get(state.getBlock()) == rule;
	}

	private static boolean isCropGrowthBlock(BlockState state, CropRule rule) {
		return state != null && rule != null && cropRulesByGrowthBlock.get(state.getBlock()) == rule;
	}

	private static boolean isCropMatureBlock(BlockState state, CropRule rule) {
		return state != null && rule != null && rule.usesDistinctMatureBlock()
			&& cropRulesByMatureBlock.get(state.getBlock()) == rule;
	}

	private static void markPendingHarvest(ServerLevel world, BlockPos cropPos, CropRule rule, boolean fertilized) {
		if (world == null || cropPos == null || rule == null) {
			return;
		}

		String key = cropKey(world, cropPos);
		PendingHarvestRule existing = pendingHarvestRulesByKey.get(key);
		boolean retainedFertilized = fertilized || (existing != null && existing.fertilized());
		long expiresAtGameplayTick = MadokuTimeManager.getGameplayTicks() + PENDING_HARVEST_TTL_TICKS;
		pendingHarvestRulesByKey.put(key, new PendingHarvestRule(rule, expiresAtGameplayTick, retainedFertilized));
	}

	private static void purgeExpiredPendingHarvestRules() {
		long now = MadokuTimeManager.getGameplayTicks();
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

	private static void emitFertilizedParticles(ServerLevel world, BlockPos soilPos) {
		if (world == null || soilPos == null) {
			return;
		}

		double centerX = soilPos.getX() + 0.5d;
		double centerY = soilPos.getY() + 1.0d + FERTILIZER_PARTICLE_Y_OFFSET;
		double centerZ = soilPos.getZ() + 0.5d;
		int particleCount = ThreadLocalRandom.current().nextInt(
			FERTILIZER_PARTICLE_MIN_COUNT,
			FERTILIZER_PARTICLE_MAX_COUNT + 1
		);
		world.sendParticles(
			ParticleTypes.HAPPY_VILLAGER,
			centerX,
			centerY,
			centerZ,
			particleCount,
			FERTILIZER_PARTICLE_SPREAD,
			FERTILIZER_PARTICLE_SPREAD * 0.35d,
			FERTILIZER_PARTICLE_SPREAD,
			0.0d
		);
	}

	private static String normalizeRegistryId(String value) {
		return MadokuJSONManager.normalizeRegistryIdentifierForLookup(value);
	}

	private static double resolveCropRequiredGrowthTicks(CropRule rule) {
		return Math.max(1.0d, rule.growthMinecraftDays() * MadokuTimeManager.MINECRAFT_TICKS_PER_CYCLE);
	}

	private static double resolveGrowingConditionsSpeedModifier(CropRule rule, ServerLevel world, BlockPos cropPos) {
		if (rule == null || world == null || cropPos == null || rule.growingConditions() == null) {
			return 0.0d;
		}

		SeasonBiomeClimateManager.Climate climate = MadokuSeasonManager.resolveBiomeClimate(world, cropPos);
		GrowingConditions conditions = rule.growingConditions();
		return resolveConditionSpeedModifier(
			climate.temperature(),
			conditions.minimumTemperature(),
			conditions.maximumTemperature()
		) + resolveConditionSpeedModifier(
			climate.humidity(),
			conditions.minimumHumidity(),
			conditions.maximumHumidity()
		);
	}

	/** Returns one condition's speed modifier in the inclusive range [-0.25, 0.25]. */
	private static double resolveConditionSpeedModifier(double actual, double minimumIdeal, double maximumIdeal) {
		if (!Double.isFinite(actual) || !Double.isFinite(minimumIdeal) || !Double.isFinite(maximumIdeal)
			|| maximumIdeal < minimumIdeal) {
			return 0.0d;
		}
		if (actual >= minimumIdeal && actual <= maximumIdeal) {
			return 0.25d;
		}

		double ratio;
		if (actual < minimumIdeal) {
			ratio = minimumIdeal <= 0.0d ? 0.0d : actual / minimumIdeal;
		} else {
			ratio = actual <= 0.0d ? 0.0d : maximumIdeal / actual;
		}
		ratio = Math.max(0.0d, Math.min(1.0d, ratio));

		if (ratio <= 0.20d) {
			return -0.25d;
		}
		if (ratio < 0.71d) {
			return -0.25d + ((ratio - 0.20d) / 0.51d) * 0.25d;
		}
		if (ratio <= 0.79d) {
			return 0.0d;
		}
		return Math.min(0.25d, ((ratio - 0.79d) / 0.21d) * 0.25d);
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
		return ageMetadata(state).ageLimit();
	}

	private static Block resolveBlockByRegistryId(String blockId) {
		Identifier identifier = Identifier.tryParse(normalizeRegistryId(blockId));
		return identifier == null || !BuiltInRegistries.BLOCK.containsKey(identifier)
			? null
			: BuiltInRegistries.BLOCK.getValue(identifier);
	}

	private static IntegerProperty findAgeProperty(BlockState state) {
		return ageMetadata(state).property();
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

	private static AgeMetadata ageMetadata(BlockState state) {
		if (state == null) {
			return AgeMetadata.NONE;
		}
		return AGE_METADATA_BY_BLOCK.computeIfAbsent(state.getBlock(), block -> {
			for (Property<?> property : block.defaultBlockState().getProperties()) {
				if (property instanceof IntegerProperty integerProperty && "age".equals(property.getName())) {
					int maxAge = integerProperty.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(CROP_MAX_AGE);
					return new AgeMetadata(integerProperty, Math.max(1, maxAge));
				}
			}
			return AgeMetadata.NONE;
		});
	}

	private record AgeMetadata(IntegerProperty property, int ageLimit) {
		private static final AgeMetadata NONE = new AgeMetadata(null, CROP_MAX_AGE);
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

	private static double clampDouble(double value, double fallback, double min, double max) {
		if (!Double.isFinite(value) || value < min || value > max) {
			return fallback;
		}
		return value;
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

		private PlotState(String levelId, long soilPos) {
			this.levelId = levelId == null ? "" : levelId;
			this.soilPos = soilPos;
			this.cropPos = -1L;
			this.cropId = "";
			this.fertilized = false;
			this.fertilizedAtGameplayTick = Long.MIN_VALUE;
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
			return JSONFormatManager.object()
				.put(FIELD_LEVEL_ID, levelId)
				.put(FIELD_SOIL_POS, soilPos)
				.put(FIELD_CROP_POS, cropPos)
				.put(FIELD_CROP_ID, cropId)
				.put(FIELD_FERTILIZED, fertilized)
				.put(FIELD_FERTILIZED_AT_GAMEPLAY_TICK,
					fertilizedAtGameplayTick == Long.MIN_VALUE ? -1L : fertilizedAtGameplayTick)
				.build();
		}

		private long persistenceFingerprint() {
			long result = 17L;
			result = 31L * result + levelId.hashCode();
			result = 31L * result + soilPos;
			result = 31L * result + cropPos;
			result = 31L * result + (cropId == null ? 0 : cropId.hashCode());
			result = 31L * result + (fertilized ? 1L : 0L);
			return 31L * result + fertilizedAtGameplayTick;
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
		private double requiredGrowthTicks;
		private double progressGrowthTicks;
		private long lastProcessedAbsoluteDayTime;

		private CropState(
			String levelId,
			long cropPos,
			long soilPos,
			String cropId,
			double requiredGrowthTicks,
			double progressGrowthTicks,
			long lastProcessedAbsoluteDayTime
		) {
			this.levelId = levelId == null ? "" : levelId;
			this.cropPos = cropPos;
			this.soilPos = soilPos;
			this.cropId = normalizeRegistryId(cropId);
			this.requiredGrowthTicks = Math.max(1.0d, requiredGrowthTicks);
			this.progressGrowthTicks = Math.max(0.0d, Math.min(this.requiredGrowthTicks, progressGrowthTicks));
			this.lastProcessedAbsoluteDayTime = Math.max(0L, lastProcessedAbsoluteDayTime);
		}

		private String key() {
			return levelId + "|" + cropPos;
		}

		private JsonObject toJson() {
			return JSONFormatManager.object()
				.put(FIELD_LEVEL_ID, levelId)
				.put(FIELD_CROP_POS, cropPos)
				.put(FIELD_SOIL_POS, soilPos)
				.put(FIELD_CROP_ID, cropId)
				.put(FIELD_REQUIRED_GROWTH_TICKS, requiredGrowthTicks)
				.put(FIELD_PROGRESS_GROWTH_TICKS, progressGrowthTicks)
				.put(FIELD_LAST_PROCESSED_ABSOLUTE_DAY_TIME, lastProcessedAbsoluteDayTime)
				.build();
		}

		private long persistenceFingerprint() {
			long result = 17L;
			result = 31L * result + levelId.hashCode();
			result = 31L * result + cropPos;
			result = 31L * result + soilPos;
			result = 31L * result + (cropId == null ? 0 : cropId.hashCode());
			result = 31L * result + Double.doubleToLongBits(requiredGrowthTicks);
			result = 31L * result + Double.doubleToLongBits(progressGrowthTicks);
			return 31L * result + lastProcessedAbsoluteDayTime;
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
			double requiredGrowthTicks = getDouble(source, FIELD_REQUIRED_GROWTH_TICKS, 1.0d);
			double progressGrowthTicks = getDouble(source, FIELD_PROGRESS_GROWTH_TICKS, 0.0d);
			long lastProcessedAbsoluteDayTime = getLong(source, FIELD_LAST_PROCESSED_ABSOLUTE_DAY_TIME, 0L);
			return new CropState(
				levelId,
				cropPos,
				soilPos,
				cropId,
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
		double fertilizedYieldBoost,
		double dryFarmlandPenalty
	) {
		private static Settings defaults() {
			return new Settings(
				true,
				FarmingConfigManager.DEFAULT_RAIN_GROWTH_BOOST,
				FarmingConfigManager.DEFAULT_FERTILIZED_GROWTH_BOOST,
				FarmingConfigManager.DEFAULT_FERTILIZED_YIELD_BOOST,
				FarmingConfigManager.DEFAULT_DRY_FARMLAND_PENALTY
			);
		}

		private static Settings fromJson(JsonObject source) {
			boolean enabled = getBoolean(source, FarmingConfigManager.FIELD_ENABLED, true);
			double rainBoost = clampDouble(
				getDouble(source, FarmingConfigManager.FIELD_RAIN_GROWTH_BOOST, FarmingConfigManager.DEFAULT_RAIN_GROWTH_BOOST),
				FarmingConfigManager.DEFAULT_RAIN_GROWTH_BOOST,
				0.0d,
				1.0d
			);
			double fertilizedBoost = clampDouble(
				getDouble(source, FarmingConfigManager.FIELD_FERTILIZED_GROWTH_BOOST, FarmingConfigManager.DEFAULT_FERTILIZED_GROWTH_BOOST),
				FarmingConfigManager.DEFAULT_FERTILIZED_GROWTH_BOOST,
				0.0d,
				1.0d
			);
			double fertilizedYieldBoost = clampDouble(
				getDouble(source, FarmingConfigManager.FIELD_FERTILIZED_YIELD_BOOST, FarmingConfigManager.DEFAULT_FERTILIZED_YIELD_BOOST),
				FarmingConfigManager.DEFAULT_FERTILIZED_YIELD_BOOST,
				0.0d,
				1.0d
			);
			double dryFarmlandPenalty = clampDouble(
				getDouble(source, FarmingConfigManager.FIELD_DRY_FARMLAND_PENALTY, FarmingConfigManager.DEFAULT_DRY_FARMLAND_PENALTY),
				FarmingConfigManager.DEFAULT_DRY_FARMLAND_PENALTY,
				0.0d,
				1.0d
			);
			return new Settings(
				enabled,
				rainBoost,
				fertilizedBoost,
				fertilizedYieldBoost,
				dryFarmlandPenalty
			);
		}

	}

	public static final class CropRule {
		private final String cropId;
		private final String cropBlockId;
		private final String matureBlockId;
		private final String plantingItemId;
		private final String yieldTableId;
		private final String displayName;
		private final double growthMinecraftDays;
		private final GrowingConditions growingConditions;
		private final Block matureBlock;

		private CropRule(
			String cropId,
			String cropBlockId,
			String matureBlockId,
			String plantingItemId,
			String yieldTableId,
			String displayName,
			double growthMinecraftDays,
			GrowingConditions growingConditions,
			Block matureBlock
		) {
			this.cropId = cropId;
			this.cropBlockId = cropBlockId;
			this.matureBlockId = matureBlockId;
			this.plantingItemId = plantingItemId;
			this.yieldTableId = yieldTableId == null ? "" : yieldTableId;
			this.displayName = displayName;
			this.growthMinecraftDays = growthMinecraftDays;
			this.growingConditions = growingConditions;
			this.matureBlock = matureBlock;
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
				3.0d,
				"minecraft:blocks/potatoes",
				new GrowingConditions(35, 55, 50, 70)
			);
		}

		private static CropRule defaultCarrot() {
			return fromValues(
				"carrot",
				"minecraft:carrots",
				"minecraft:carrots",
				"minecraft:carrot",
				5.0d,
				"minecraft:blocks/carrots",
				new GrowingConditions(40, 60, 45, 65)
			);
		}

		private static CropRule defaultBeetroot() {
			return fromValues(
				"beetroot",
				"minecraft:beetroots",
				"minecraft:beetroots",
				"minecraft:beetroot_seeds",
				3.0d,
				"minecraft:blocks/beetroots",
				new GrowingConditions(45, 65, 50, 70)
			);
		}

		private static CropRule defaultMelon() {
			return fromValues(
				"melon",
				"minecraft:melon_stem",
				"minecraft:melon",
				"minecraft:melon_seeds",
				11.0d,
				"minecraft:blocks/melon",
				new GrowingConditions(50, 80, 35, 55)
			);
		}

		private static CropRule defaultPumpkin() {
			return fromValues(
				"pumpkin",
				"minecraft:pumpkin_stem",
				"minecraft:pumpkin",
				"minecraft:pumpkin_seeds",
				9.0d,
				"minecraft:blocks/pumpkin",
				new GrowingConditions(45, 70, 40, 60)
			);
		}

		private static CropRule defaultWheat() {
			return fromValues(
				"wheat",
				"minecraft:wheat",
				"minecraft:wheat",
				"minecraft:wheat_seeds",
				7.0d,
				"minecraft:blocks/wheat",
				new GrowingConditions(40, 60, 45, 55)
			);
		}

		private static CropRule fromJson(String fileKey, JsonObject source) {
			CropRule fallback = defaultRule(fileKey);
			if (fallback == null) {
				return null;
			}

			String cropId = normalizeRegistryId(getString(source, CropsConfigManager.FIELD_CROP_ID, fallback.cropId));
			if (cropId.isEmpty()) {
				cropId = fallback.cropId;
			}

			double growthDays = clampDouble(
				getDouble(source, CropsConfigManager.FIELD_GROWTH_TIME, fallback.growthMinecraftDays),
				fallback.growthMinecraftDays,
				0.25d,
				365.0d
			);
			return fromValues(
				cropId,
				fallback.cropBlockId,
				fallback.matureBlockId,
				fallback.plantingItemId,
				growthDays,
				parseYieldTableId(source, fallback.yieldTableId),
				parseGrowingConditions(source, fallback.growingConditions)
			);
		}

		private static CropRule fromValues(
			String cropId,
			String cropBlockId,
			String matureBlockId,
			String plantingItemId,
			double growthMinecraftDays,
			String yieldTableId,
			GrowingConditions growingConditions
		) {
			String normalizedCropId = normalizeRegistryId(cropId);
			String normalizedCropBlockId = normalizeRegistryId(cropBlockId);
			String normalizedMatureBlockId = normalizeRegistryId(matureBlockId);
			String normalizedPlantingItemId = normalizeRegistryId(plantingItemId);
			Identifier matureBlockIdentifier = Identifier.tryParse(normalizedMatureBlockId);
			Block matureBlock = matureBlockIdentifier == null ? null : BuiltInRegistries.BLOCK.getValue(matureBlockIdentifier);
			String displayName = normalizeDisplayName(normalizedCropId);
			return new CropRule(
					normalizedCropId,
				normalizedCropBlockId,
				normalizedMatureBlockId,
				normalizedPlantingItemId,
				normalizeRegistryId(yieldTableId),
				displayName,
				growthMinecraftDays,
				growingConditions,
				matureBlock
			);
		}

		private static String parseYieldTableId(JsonObject source, String fallback) {
			String value = getString(source, CropsConfigManager.FIELD_YIELD_ID, fallback);
			String normalized = normalizeRegistryId(value);
			return normalized.isBlank() ? fallback : normalized;
		}

		private static GrowingConditions parseGrowingConditions(JsonObject source, GrowingConditions fallback) {
			GrowingConditions safeFallback = fallback == null ? new GrowingConditions(40, 60, 40, 60) : fallback;
			JsonObject conditions = object(source, CropsConfigManager.FIELD_GROWING_CONDITIONS);
			JsonObject temperature = object(conditions, CropsConfigManager.FIELD_IDEAL_TEMPERATURE);
			JsonObject humidity = object(conditions, CropsConfigManager.FIELD_IDEAL_HUMIDITY);
			double minimumTemperature = clampDouble(getDouble(temperature, CropsConfigManager.FIELD_MINIMUM_TEMPERATURE, safeFallback.minimumTemperature()), safeFallback.minimumTemperature(), -100.0d, 100.0d);
			double maximumTemperature = clampDouble(getDouble(temperature, CropsConfigManager.FIELD_MAXIMUM_TEMPERATURE, safeFallback.maximumTemperature()), safeFallback.maximumTemperature(), minimumTemperature, 100.0d);
			double minimumHumidity = clampDouble(getDouble(humidity, CropsConfigManager.FIELD_MINIMUM_HUMIDITY, safeFallback.minimumHumidity()), safeFallback.minimumHumidity(), 0.0d, 100.0d);
			double maximumHumidity = clampDouble(getDouble(humidity, CropsConfigManager.FIELD_MAXIMUM_HUMIDITY, safeFallback.maximumHumidity()), safeFallback.maximumHumidity(), minimumHumidity, 100.0d);
			return new GrowingConditions(minimumTemperature, maximumTemperature, minimumHumidity, maximumHumidity);
		}

		private static JsonObject object(JsonObject source, String key) {
			if (source == null) return null;
			JsonElement element = source.get(key);
			return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
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

		public String yieldTableId() {
			return yieldTableId;
		}

		public double growthMinecraftDays() {
			return growthMinecraftDays;
		}

		public GrowingConditions growingConditions() {
			return growingConditions;
		}

		public Block matureBlock() {
			return matureBlock;
		}

		public String displayName() {
			return displayName;
		}
	}

	public record GrowingConditions(
		double minimumTemperature,
		double maximumTemperature,
		double minimumHumidity,
		double maximumHumidity
	) { }

}
