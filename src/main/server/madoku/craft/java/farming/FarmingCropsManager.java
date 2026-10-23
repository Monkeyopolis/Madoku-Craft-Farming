package madoku.craft.java.farming;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import madoku.craft.java.core.data.DataSystemsAPIManager;
import madoku.craft.java.core.data.ChunkDataAPIManager;
import madoku.craft.java.core.data.WorldChunkDataAPIManager;
import madoku.craft.java.core.data.WorldChunkDataKey;
import madoku.craft.java.core.chunk.ActiveChunkTickAPIManager;
import madoku.craft.java.core.chunk.ActiveChunkTickEvent;
import madoku.craft.java.core.chunk.ActiveChunkTickListener;
import madoku.craft.java.core.chunk.ChunkAPIManager;
import madoku.craft.java.core.json.JSONFormatAPIManager;
import madoku.craft.java.core.json.JSONAPIManager;
import madoku.craft.java.core.season.SeasonAPIManager;
import madoku.craft.java.core.season.SeasonBiomeClimateAPIManager;
import madoku.craft.java.core.sync.SyncConfigAPIManager;
import madoku.craft.java.core.time.TimeAPIManager;
import madoku.craft.mixin.item.ItemBuiltInRegistryHolderAccessor;
import madoku.craft.mixin.item.ItemComponentsAccessor;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.core.registries.Registries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class FarmingCropsManager {
	private static final Logger LOGGER = LoggerFactory.getLogger(FarmingCropsManager.class);
	private static final String LORE_GROWING_TIME_PREFIX = "Growing Time:";
	private static final String LORE_TEMPERATURE_PREFIX = "Ideal Temperature:";
	private static final String LORE_HUMIDITY_PREFIX = "Ideal Humidity:";
	private static final String LORE_FERTILIZER_PREFIX = "Soil fertilizer.";
	private static final String LORE_FERTILIZER_EFFECT_PREFIX = "Increases crop growth speed.";

	private static final String DATA_SYSTEM_ID = "farming";

	private static final String FIELD_PLOTS = "plots";
	private static final String FIELD_CROPS = "crops";

	private static final String FIELD_LEVEL_ID = "level-id";
	private static final String FIELD_SOIL_POS = "soil-pos";
	private static final String FIELD_CROP_POS = "crop-pos";
	private static final String FIELD_CROP_ID = "crop-id";
	private static final String FIELD_FERTILIZED = "fertilized";
	private static final String FIELD_FERTILIZED_AT_ABSOLUTE_DAY_TIME = "fertilized-at-absolute-day-time";
	private static final String FIELD_REQUIRED_GROWTH_TICKS = "required-growth-ticks";
	private static final String FIELD_PROGRESS_GROWTH_TICKS = "progress-growth-ticks";
	private static final String FIELD_LAST_PROCESSED_ABSOLUTE_DAY_TIME = "last-processed-absolute-day-time";
	private static final String FIELD_MATURITY_YIELD_RECORDED = "maturity-yield-recorded";
	private static final String FIELD_MATURITY_YIELD_MULTIPLIER = "maturity-yield-multiplier";
	private static final String FIELD_RAIN_BOOST_EXPIRES_AT_ABSOLUTE_DAY_TIME = "rain-boost-expires-at-absolute-day-time";
	private static final String FIELD_MATURE_STEM_READY_AT_ABSOLUTE_DAY_TIME = "mature-stem-ready-at-absolute-day-time";

	private static final int CROP_MAX_AGE = 7;
	private static final long PENDING_HARVEST_TTL_TICKS = 2L;
	private static final long ABSOLUTE_TIME_ROLLBACK_RESET_TICKS = 20L;
	private static final int FERTILIZER_PARTICLE_MIN_COUNT = 4;
	private static final int FERTILIZER_PARTICLE_MAX_COUNT = 8;
	private static final double FERTILIZER_PARTICLE_SPREAD = 0.12d;
	private static final double FERTILIZER_PARTICLE_Y_OFFSET = 0.1d;
	private static final int MAX_CROP_OPERATIONS_PER_HEARTBEAT = 4;
	private static final long ACTIVE_CROP_UPDATE_INTERVAL_TICKS = 1L;
	private static final long ZERO_SPEED_RECHECK_INTERVAL_TICKS = 20L;
	private static final long RAIN_BOOST_DURATION_TICKS = TimeAPIManager.MINECRAFT_TICKS_PER_CYCLE;
	private static final long RAIN_BOOST_SCAN_INTERVAL_TICKS = 20L;
	private static final double CROP_DUE_EPSILON = 1.0e-6d;
	private static final double GROWTH_SPEED_STEP = 0.01d;

	private static volatile Settings settings = Settings.defaults();
	private static volatile boolean dirty = false;

	private static volatile Map<String, CropRule> cropRulesByPlantingItemId = new LinkedHashMap<>();
	private static volatile Map<Item, CropRule> cropRulesByPlantingItem = Map.of();
	private static volatile Map<Block, CropRule> cropRulesByBlock = Map.of();
	private static volatile Map<Block, CropRule> cropRulesByGrowthBlock = Map.of();
	private static volatile Map<Block, CropRule> cropRulesByMatureBlock = Map.of();
	private static volatile boolean clientSynchronized;
	private static Settings savedClientSettings;
	private static Map<String, CropRule> savedClientCropRules = Map.of();
	private static Map<Item, DataComponentMap> savedClientComponents = Map.of();
	private static final Map<Block, AgeMetadata> AGE_METADATA_BY_BLOCK = new HashMap<>();

	private static final Map<String, PlotState> plotsByKey = new HashMap<>();
	private static final Map<String, CropState> cropsByKey = new HashMap<>();
	private static final Map<String, Set<String>> plotKeysByChunk = new HashMap<>();
	private static final Map<String, Set<String>> cropKeysByChunk = new HashMap<>();
	private static final Map<String, PendingHarvestRule> pendingHarvestRulesByKey = new HashMap<>();
	private static final Set<String> LOADED_CHUNK_KEYS = new LinkedHashSet<>();
	private static final Map<String, PriorityQueue<CropDueEntry>> cropDueQueuesByChunk = new HashMap<>();
	private static final Map<String, Map<String, Long>> cropDueVersionsByChunk = new HashMap<>();
	private static final Map<String, Long> rainBoostScanAtAbsoluteDayTimeByChunk = new HashMap<>();
	private static final Map<String, Long> lastObservedAbsoluteDayTimeByLevel = new HashMap<>();
	private static final ActiveChunkTickListener ACTIVE_CHUNK_TICK_LISTENER = FarmingCropsManager::onActiveChunkTick;
	private static final ChunkAPIManager.ChunkLifecycleListener CHUNK_LISTENER = new ChunkAPIManager.ChunkLifecycleListener() {
		@Override public void onChunkLoaded(ServerLevel level, int chunkX, int chunkZ) {
			loadChunkData(level, chunkX, chunkZ);
			processCropsInChunk(level, chunkX, chunkZ);
	}

		@Override public void onChunkUnloaded(ServerLevel level, int chunkX, int chunkZ) {
			saveChunkData(level, chunkX, chunkZ);
			clearCropDueSchedule(cropChunkKey(level, chunkX, chunkZ));
			markChunkUnloaded(level, chunkX, chunkZ);
		}
	};

	private FarmingCropsManager() {
	}

	public static void initialize() {
		DataSystemsAPIManager.registerSystem(DATA_SYSTEM_ID);
		ChunkAPIManager.registerChunkLifecycleListener(CHUNK_LISTENER);
		ActiveChunkTickAPIManager.registerListener(ACTIVE_CHUNK_TICK_LISTENER);
		loadStaticConfig();
		loadCropConfigs();
		SyncConfigAPIManager.register(
			"farming",
			FarmingCropsManager::createClientSyncSnapshot,
			FarmingCropsManager::applyClientSyncSnapshot,
			FarmingCropsManager::resetClientSyncState
		);
		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) ->
			handleBlockBreakBefore(world, pos, state, blockEntity)
		);
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) ->
			handleBlockBreak(world, pos, state, blockEntity)
		);
	}

	public static void reset() {
		ActiveChunkTickAPIManager.unregisterListener(ACTIVE_CHUNK_TICK_LISTENER);
		plotsByKey.clear();
		cropsByKey.clear();
		plotKeysByChunk.clear();
		cropKeysByChunk.clear();
		pendingHarvestRulesByKey.clear();
		LOADED_CHUNK_KEYS.clear();
		cropDueQueuesByChunk.clear();
		cropDueVersionsByChunk.clear();
		rainBoostScanAtAbsoluteDayTimeByChunk.clear();
		lastObservedAbsoluteDayTimeByLevel.clear();
		dirty = false;
		clientSynchronized = false;
		savedClientSettings = null;
		savedClientCropRules = Map.of();
		savedClientComponents = Map.of();
	}

	public static void onServerStarted(MinecraftServer server) {
		if (server == null) {
			return;
		}
		DataSystemsAPIManager.registerSystem(DATA_SYSTEM_ID);
		ActiveChunkTickAPIManager.registerListener(ACTIVE_CHUNK_TICK_LISTENER);
		applyCropItemMetadata();
		for (ServerLevel level : server.getAllLevels()) {
			level.getChunkSource().chunkMap.forEachReadyToSendChunk((LevelChunk chunk) -> {
				if (chunk != null) {
					loadChunkData(level, chunk.getPos().x(), chunk.getPos().z());
					processCropsInChunk(level, chunk.getPos().x(), chunk.getPos().z());
				}
			});
		}
	}

	public static void loadPersistedData(MinecraftServer server) {
		if (server == null) {
			return;
		}

		loadStaticConfig();
		loadCropConfigs();
		plotsByKey.clear();
		cropsByKey.clear();
		plotKeysByChunk.clear();
		cropKeysByChunk.clear();
		pendingHarvestRulesByKey.clear();
		LOADED_CHUNK_KEYS.clear();
		cropDueQueuesByChunk.clear();
		cropDueVersionsByChunk.clear();
		rainBoostScanAtAbsoluteDayTimeByChunk.clear();
		lastObservedAbsoluteDayTimeByLevel.clear();
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

		for (ServerLevel level : server.getAllLevels()) {
			for (String key : Set.copyOf(LOADED_CHUNK_KEYS)) {
				int[] coordinates = chunkCoordinates(key, level);
				if (coordinates != null) saveChunkData(level, coordinates[0], coordinates[1]);
			}
		}
		dirty = false;
	}

	public static boolean isEnabled() {
		return settings.enabled;
	}

	public static boolean isPlotIndicatorEnabled() {
		return settings.enabled && settings.farmIndicatorEnabled;
	}

	public static String createClientSyncSnapshot() {
		JsonObject snapshot = JSONFormatAPIManager.object()
			.put("settings", settings.toConfigJson())
			.object("crops", crops -> {
				for (CropRule rule : cropRulesByPlantingItemId.values()) {
					if (rule != null) crops.put(rule.cropId(), rule.toConfigJson());
				}
			})
			.build();
		return snapshot.toString();
	}

	public static void applyClientSyncSnapshot(String snapshot) {
		JsonElement parsed = JsonParser.parseString(snapshot == null ? "" : snapshot);
		if (!parsed.isJsonObject()) return;
		JsonObject root = parsed.getAsJsonObject();
		// A client may have applied its local metadata before this join snapshot
		// arrived. Restore that baseline before capturing the synchronized state.
		restoreClientComponents();
		captureClientSyncState();
		settings = Settings.fromJson(readObject(root, "settings"));
		Map<String, CropRule> synchronizedRules = new LinkedHashMap<>();
		JsonObject crops = readObject(root, "crops");
		for (Map.Entry<String, JsonElement> entry : crops.entrySet()) {
			if (!entry.getValue().isJsonObject()) continue;
			CropRule rule = CropRule.fromJson(entry.getKey(), entry.getValue().getAsJsonObject());
			if (rule != null) synchronizedRules.put(rule.plantingItemId(), rule);
		}
		// Replace the complete client rule set, including the empty case. Otherwise a
		// server with no configured crops leaves stale rules from the previous server.
		installCropRules(synchronizedRules);
		captureClientComponents(synchronizedRules.values());
		clientSynchronized = true;
		applyCropItemMetadata();
	}

	public static void resetClientSyncState() {
		if (!clientSynchronized) {
			savedClientComponents = Map.of();
			return;
		}
		restoreClientComponents();
		settings = savedClientSettings == null ? Settings.defaults() : savedClientSettings;
		installCropRules(savedClientCropRules);
		clientSynchronized = false;
		savedClientSettings = null;
		savedClientCropRules = Map.of();
		savedClientComponents = Map.of();
	}

	public static boolean applyExternalGrowthPercent(ServerLevel world, BlockPos cropPos, double growthPercent, String source) {
		if (!settings.enabled || world == null || cropPos == null || growthPercent <= 0.0d) {
			return false;
		}
		BlockState state = world.getBlockState(cropPos);
		CropRule rule = resolveCropRuleByCropState(state);
		if (rule == null || rule.usesVanillaGrowth()) {
			return false;
		}

		trackCrop(world, cropPos, state);
		BlockPos trackedCropPos = resolveTrackedCropPosition(world, cropPos, rule);
		CropState crop = cropsByKey.get(cropKey(world, trackedCropPos));
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
		updateCropBlockAge(world, trackedCropPos, world.getBlockState(trackedCropPos), rule, crop, null);
		scheduleCropDue(world, crop);
		return true;
	}

	public static boolean isCropPlantItem(ItemStack stack) {
		return resolveCropRuleByPlantingItem(stack) != null;
	}

	public static boolean usesVanillaGrowth(BlockState state) {
		CropRule rule = resolveCropRuleByCropState(state);
		return rule != null && rule.usesVanillaGrowth();
	}

	private static boolean isVerticalCrop(CropRule rule) {
		return rule != null && rule.maxHeight() > 1;
	}

	private static Block resolveAttachedStemBlock(CropRule rule) {
		if (rule == null) {
			return null;
		}
		return switch (normalizeRegistryId(rule.cropBlockId())) {
			case "minecraft:melon_stem" -> Blocks.ATTACHED_MELON_STEM;
			case "minecraft:pumpkin_stem" -> Blocks.ATTACHED_PUMPKIN_STEM;
			default -> null;
		};
	}

	private static boolean isCropAttachedStemBlock(BlockState state, CropRule rule) {
		Block attachedStemBlock = resolveAttachedStemBlock(rule);
		return state != null && attachedStemBlock != null && state.getBlock() == attachedStemBlock;
	}

	private static BlockPos resolveAdjacentStemFruitPosition(ServerLevel world, BlockPos stemPos, CropRule rule) {
		if (world == null || stemPos == null) {
			return null;
		}

		int availableDirections = 0;
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos fruitPos = stemPos.relative(direction);
			if (world.getBlockState(fruitPos).isAir()
				&& isStemFruitSupport(world.getBlockState(fruitPos.below()), rule)) {
				availableDirections++;
			}
		}
		if (availableDirections == 0) {
			return null;
		}

		int selectedDirection = world.getRandom().nextInt(availableDirections);
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos fruitPos = stemPos.relative(direction);
			if (!world.getBlockState(fruitPos).isAir()
				|| !isStemFruitSupport(world.getBlockState(fruitPos.below()), rule)) {
				continue;
			}
			if (selectedDirection-- == 0) {
				return fruitPos;
			}
		}
		return null;
	}

	private static boolean isStemFruitSupport(BlockState state, CropRule rule) {
		return state != null
			&& (state.is(BlockTags.SUPPORTS_STEM_FRUIT) || matchesCropSupport(state, rule));
	}

	private static void rescheduleAdjacentMatureStemAttempts(
		ServerLevel world,
		BlockPos producePos,
		CropRule rule
	) {
		if (world == null || producePos == null || rule == null || !rule.usesDistinctMatureBlock()) {
			return;
		}

		long currentAbsoluteDayTime = resolveAbsoluteDayTime(world);
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos stemPos = producePos.relative(direction);
			BlockState stemState = world.getBlockState(stemPos);
			if (!isCropGrowthBlock(stemState, rule)
				|| !isMaxAge(stemState)
				|| hasAdjacentCropProduce(world, stemPos, rule)) {
				continue;
			}

			CropState crop = findCrop(world, stemPos);
			if (crop == null) {
				continue;
			}
			if (crop.matureStemReadyAtAbsoluteDayTime == Long.MIN_VALUE
				|| crop.matureStemReadyAtAbsoluteDayTime <= currentAbsoluteDayTime) {
				crop.matureStemReadyAtAbsoluteDayTime = currentAbsoluteDayTime;
				scheduleCropDue(world, crop);
				dirty = true;
			}
		}
	}

	private static boolean isCropAttachedStemFacingProduce(
		ServerLevel world,
		BlockPos stemPos,
		BlockState stemState,
		BlockPos producePos,
		BlockState produceState,
		CropRule rule
	) {
		if (world == null || stemPos == null || stemState == null || producePos == null || produceState == null || rule == null
			|| !isCropAttachedStemBlock(stemState, rule)
			|| !stemState.hasProperty(HorizontalDirectionalBlock.FACING)) {
			return false;
		}
		Direction direction = stemState.getValue(HorizontalDirectionalBlock.FACING);
		return stemPos.relative(direction).equals(producePos)
			&& isCropMatureBlock(produceState, rule);
	}

	private static boolean hasAdjacentCropProduce(
		ServerLevel world,
		BlockPos stemPos,
		CropRule rule,
		BlockState stemState
	) {
		if (world == null || stemPos == null || stemState == null || rule == null
			|| !isCropAttachedStemBlock(stemState, rule)
			|| !stemState.hasProperty(HorizontalDirectionalBlock.FACING)) {
			return false;
		}
		return isCropMatureBlock(
			world.getBlockState(stemPos.relative(stemState.getValue(HorizontalDirectionalBlock.FACING))),
			rule
		);
	}

	private static BlockPos resolveCropBasePosition(ServerLevel world, BlockPos cropPos, CropRule rule) {
		if (world == null || cropPos == null || !isVerticalCrop(rule)) {
			return cropPos;
		}

		BlockPos base = cropPos;
		// A managed vertical crop can never be taller than its configured maximum.
		// Bound this search so manually stacked columns cannot turn random-tick or
		// tracking work into an unbounded downward scan.
		int maximumBaseSearchSteps = Math.max(0, rule.maxHeight() - 1);
		for (int step = 0; step < maximumBaseSearchSteps; step++) {
			BlockPos below = base.below();
			if (!isCropBlock(world.getBlockState(below), rule)) {
				break;
			}
			base = below;
		}
		return base;
	}

	private static BlockPos resolveTrackedCropPosition(ServerLevel world, BlockPos cropPos, CropRule rule) {
		return resolveTrackedCropPosition(world, cropPos, rule, null);
	}

	private static BlockPos resolveTrackedCropPosition(
		ServerLevel world,
		BlockPos cropPos,
		CropRule rule,
		BlockState observedState
	) {
		BlockPos basePos = resolveCropBasePosition(world, cropPos, rule);
		if (world == null || basePos == null || rule == null || !rule.usesDistinctMatureBlock()) {
			return basePos;
		}

		BlockState state = observedState != null && basePos.equals(cropPos)
			? observedState
			: world.getBlockState(basePos);
		if (!isCropMatureBlock(state, rule)) {
			return basePos;
		}
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos candidate = basePos.relative(direction);
			BlockState candidateState = world.getBlockState(candidate);
			if (isCropAttachedStemFacingProduce(world, candidate, candidateState, basePos, state, rule)) {
				return candidate;
			}
		}
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos candidate = basePos.relative(direction);
			if (isCropGrowthBlock(world.getBlockState(candidate), rule)) {
				return candidate;
			}
		}
		return basePos;
	}

	private static boolean isCropSupport(ServerLevel world, BlockPos cropPos, CropRule rule) {
		if (world == null || cropPos == null || rule == null) {
			return false;
		}
		if (isVerticalCrop(rule)) {
			BlockPos basePos = resolveCropBasePosition(world, cropPos, rule);
			BlockState cropState = world.getBlockState(basePos);
			return isCropBlock(cropState, rule)
				&& cropState.canSurvive(world, basePos)
				&& matchesCropSupport(world.getBlockState(basePos.below()), rule);
		}
		return matchesCropSupport(world.getBlockState(cropPos.below()), rule);
	}

	private static boolean matchesCropSupport(BlockState supportState, CropRule rule) {
		if (supportState == null || rule == null) {
			return false;
		}
		if (rule.supportBlocks().contains(supportState.getBlock())) {
			return true;
		}
		for (TagKey<Block> supportTag : rule.supportTags()) {
			if (supportState.is(supportTag)) {
				return true;
			}
		}
		return false;
	}

	private static int getVerticalCropHeight(ServerLevel world, BlockPos basePos, CropRule rule) {
		if (world == null || basePos == null || !isVerticalCrop(rule)) {
			return 1;
		}

		int height = 0;
		int maxHeight = Math.max(1, rule.maxHeight());
		while (height < maxHeight && isCropBlock(world.getBlockState(basePos.above(height)), rule)) {
			height++;
		}
		return Math.max(1, height);
	}

	private static VerticalCropGeometry resolveVerticalCropGeometry(
		ServerLevel world,
		BlockPos cropPos,
		CropRule rule
	) {
		if (world == null || cropPos == null || !isVerticalCrop(rule)) {
			return null;
		}

		BlockPos basePos = resolveCropBasePosition(world, cropPos, rule);
		int currentHeight = getVerticalCropHeight(world, basePos, rule);
		boolean growthBlocked = currentHeight < rule.maxHeight()
			&& !world.getBlockState(basePos.above(currentHeight)).isAir();
		return new VerticalCropGeometry(basePos, currentHeight, growthBlocked);
	}

	private static double progressFromCropState(
		ServerLevel world,
		BlockPos cropPos,
		BlockState state,
		CropRule rule,
		double requiredTicks
	) {
		return progressFromCropState(
			world,
			cropPos,
			state,
			rule,
			requiredTicks,
			isVerticalCrop(rule) ? resolveVerticalCropGeometry(world, cropPos, rule) : null
		);
	}

	private static double progressFromCropState(
		ServerLevel world,
		BlockPos cropPos,
		BlockState state,
		CropRule rule,
		double requiredTicks,
		VerticalCropGeometry geometry
	) {
		if (isVerticalCrop(rule)) {
			VerticalCropGeometry safeGeometry = geometry == null
				? resolveVerticalCropGeometry(world, cropPos, rule)
				: geometry;
			int height = safeGeometry == null ? 1 : safeGeometry.height();
			int maxHeight = Math.max(2, rule.maxHeight());
			return ((height - 1) / (double) (maxHeight - 1)) * requiredTicks;
		}

		int ageLimit = Math.max(1, getCropAgeLimit(state));
		if (rule.usesDistinctMatureBlock()
			&& (isCropMatureBlock(state, rule) || hasAdjacentCropProduce(world, cropPos, rule))) {
			return requiredTicks;
		}
		return progressFromAge(
			isCropMatureBlock(state, rule) ? ageLimit : getCropAge(state),
			ageLimit
		) * requiredTicks;
	}

	private static int verticalHeightFromProgress(double progressNormalized, CropRule rule) {
		int maxHeight = Math.max(1, rule == null ? 1 : rule.maxHeight());
		if (maxHeight <= 1) {
			return 1;
		}
		double safeProgress = Math.max(0.0d, Math.min(1.0d, progressNormalized));
		return Math.max(1, Math.min(maxHeight, 1 + (int) Math.floor(safeProgress * (maxHeight - 1) + 1.0e-9d)));
	}

	private static boolean isCropGrowthComplete(ServerLevel world, BlockPos cropPos, BlockState state, CropRule rule, CropState crop) {
		return isCropGrowthComplete(
			world,
			cropPos,
			state,
			rule,
			crop,
			isVerticalCrop(rule) ? resolveVerticalCropGeometry(world, cropPos, rule) : null
		);
	}

	private static boolean isCropGrowthComplete(
		ServerLevel world,
		BlockPos cropPos,
		BlockState state,
		CropRule rule,
		CropState crop,
		VerticalCropGeometry geometry
	) {
		if (isVerticalCrop(rule)) {
			VerticalCropGeometry safeGeometry = geometry == null
				? resolveVerticalCropGeometry(world, cropPos, rule)
				: geometry;
			return safeGeometry != null
				&& safeGeometry.height() >= rule.maxHeight()
				&& crop != null
				&& crop.progressGrowthTicks + CROP_DUE_EPSILON >= Math.max(1.0d, crop.requiredGrowthTicks);
		}
		if (rule != null && rule.usesDistinctMatureBlock()) {
			boolean producePresent = isCropMatureBlock(state, rule)
				|| hasAdjacentCropProduce(world, cropPos, rule);
			return producePresent
				&& (isCropMatureBlock(state, rule)
					|| (crop != null && crop.progressGrowthTicks + CROP_DUE_EPSILON >= Math.max(1.0d, crop.requiredGrowthTicks)));
		}
		return (state != null && (isCropMatureBlock(state, rule) || isMaxAge(state)))
			|| (crop != null && crop.progressGrowthTicks + CROP_DUE_EPSILON >= Math.max(1.0d, crop.requiredGrowthTicks));
	}

	private static boolean hasAdjacentCropProduce(ServerLevel world, BlockPos stemPos, CropRule rule) {
		return world != null && stemPos != null
			&& hasAdjacentCropProduce(world, stemPos, rule, world.getBlockState(stemPos));
	}

	private static boolean isCropMatureForYield(
		ServerLevel world,
		BlockPos cropPos,
		BlockState state,
		CropRule rule,
		CropState crop
	) {
		if (rule == null || crop == null) {
			return false;
		}
		if (rule.usesVanillaGrowth()) {
			return hasAdjacentCropProduce(world, cropPos, rule);
		}
		return isCropGrowthComplete(world, cropPos, state, rule, crop);
	}

	private static void recordCropMaturityIfReady(
		ServerLevel world,
		BlockPos cropPos,
		BlockState state,
		CropRule rule,
		CropState crop
	) {
		if (world == null || cropPos == null || state == null || rule == null || crop == null
			|| crop.maturityYieldRecorded
			|| !isCropMatureForYield(world, cropPos, state, rule, crop)) {
			return;
		}

		// The speed multiplier is also the yield multiplier: 0.0 is -100% yield,
		// 1.0 is normal yield, and 2.0 is +100% yield. Capture it once so later
		// weather, fertilizer expiry, or chunk unloads cannot change a mature crop.
		double growthSpeedMultiplier = resolveGrowthSpeedMultiplier(rule, world, cropPos, crop);
		crop.recordMaturityYield(growthSpeedMultiplier);
		dirty = true;
	}

	private static boolean refreshCropRainBoost(ServerLevel world, BlockPos cropPos, CropState crop) {
		if (world == null) {
			return false;
		}
		return refreshCropRainBoost(world, cropPos, crop, resolveAbsoluteDayTime(world));
	}

	private static boolean refreshCropRainBoost(
		ServerLevel world,
		BlockPos cropPos,
		CropState crop,
		long currentAbsoluteDayTime
	) {
		if (world == null || cropPos == null || crop == null) {
			return false;
		}

		// An active buff already represents a full day of rain protection. Avoid
		// checking precipitation or dirtying persistent state every server tick.
		if (crop.rainBoostExpiresAtAbsoluteDayTime > currentAbsoluteDayTime) {
			return true;
		}

		if (world.isRainingAt(cropPos)) {
			long expiresAt = currentAbsoluteDayTime > Long.MAX_VALUE - RAIN_BOOST_DURATION_TICKS
				? Long.MAX_VALUE
				: currentAbsoluteDayTime + RAIN_BOOST_DURATION_TICKS;
			crop.rainBoostExpiresAtAbsoluteDayTime = expiresAt;
			dirty = true;
		}
		return crop.rainBoostExpiresAtAbsoluteDayTime > currentAbsoluteDayTime;
	}

	public static boolean isFarmland(BlockState state) {
		return state != null && state.getBlock() == Blocks.FARMLAND;
	}

	/** Returns whether a block can hold a farming plot or fertilizer state. */
	public static boolean isFarmingSoil(BlockState state) {
		return FarmingSoilRules.isFarmingSoil(state);
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
		return isPlotFertilized(world, plot);
	}

	public static boolean isManagedPlot(ServerLevel world, BlockPos soilPos) {
		PlotState plot = findPlot(world, soilPos);
		return plot != null && (isPlotFertilized(world, plot) || plot.hasCrop());
	}

	static boolean isPlotGrowthComplete(ServerLevel world, long soilPos) {
		if (!settings.enabled || world == null) {
			return false;
		}

		PlotState plot = findPlot(world, BlockPos.of(soilPos));
		if (plot == null || !plot.hasCrop()) {
			return false;
		}
		CropState crop = cropsByKey.get(plot.levelId + "|" + plot.cropPos);
		if (crop == null) {
			return false;
		}
		CropRule rule = resolveCropRuleByPlantingItemId(crop.cropId);
		if (rule == null) {
			return false;
		}

		BlockPos cropPos = BlockPos.of(crop.cropPos);
		BlockState cropState = world.getBlockState(cropPos);
		if (isCropGrowthComplete(world, cropPos, cropState, rule, crop)) {
			return true;
		}

		double requiredGrowthTicks = Math.max(1.0d, crop.requiredGrowthTicks);
		double progressGrowthTicks = Math.max(
			0.0d,
			Math.min(requiredGrowthTicks, crop.progressGrowthTicks)
		);
		long currentAbsoluteDayTime = resolveAbsoluteDayTime(world);
		long previousAbsoluteDayTime = normalizePreviousAbsoluteTick(
			crop.lastProcessedAbsoluteDayTime,
			currentAbsoluteDayTime
		);
		long elapsedTicks = Math.max(0L, currentAbsoluteDayTime - previousAbsoluteDayTime);
		double speedMultiplier = resolveGrowthSpeedMultiplier(rule, world, cropPos, crop);
		return progressGrowthTicks + elapsedTicks * speedMultiplier
			+ CROP_DUE_EPSILON >= requiredGrowthTicks;
	}

	/**
	 * Returns server-authoritative indicator values from the chunk-indexed plot
	 * registry. This checks tracked plots only; it never searches nearby blocks.
	 * The client remains responsible for final distance and visibility culling.
	 */
	static List<PlotIndicatorState> getPlotIndicatorStates(ServerLevel world, BlockPos center, int radius) {
		if (!settings.enabled || world == null || center == null || radius < 0) {
			return List.of();
		}

		String currentLevelId = levelId(world);
		long currentAbsoluteDayTime = resolveAbsoluteDayTime(world);
		float worldClockRate = world.getServer() == null
			? 1.0F
			: Math.max(0.0F, TimeAPIManager.resolveWorldClockRate(world.getServer()));
		int radiusSquared = radius * radius;
		int minChunkX = (center.getX() - radius) >> 4;
		int maxChunkX = (center.getX() + radius) >> 4;
		int minChunkZ = (center.getZ() - radius) >> 4;
		int maxChunkZ = (center.getZ() + radius) >> 4;
		List<PlotIndicatorState> indicators = new ArrayList<>();

		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				if (!isRuntimeChunkLoaded(world, chunkX, chunkZ)) {
					continue;
				}
				Set<String> plotKeys = plotKeysByChunk.get(plotChunkKey(world, chunkX, chunkZ));
				if (plotKeys == null || plotKeys.isEmpty()) {
					continue;
				}

				for (String plotKey : plotKeys) {
					PlotState plot = plotsByKey.get(plotKey);
					if (plot == null || !currentLevelId.equals(plot.levelId) || !plot.hasCrop()) {
						continue;
					}
					BlockPos soilPos = BlockPos.of(plot.soilPos);
					int dx = soilPos.getX() - center.getX();
					int dy = soilPos.getY() - center.getY();
					int dz = soilPos.getZ() - center.getZ();
					if (dx * dx + dy * dy + dz * dz > radiusSquared) {
						continue;
					}

					CropState crop = cropsByKey.get(plot.levelId + "|" + plot.cropPos);
					if (crop == null) {
						continue;
					}

					BlockPos cropPos = BlockPos.of(plot.cropPos);
					CropRule rule = resolveCropRuleByPlantingItemId(crop.cropId);
					BlockState cropState = world.getBlockState(cropPos);
					boolean validCropState = rule != null
						&& (isCropBlock(cropState, rule) || isCropMatureBlock(cropState, rule));
					boolean validSoilState = rule != null && isCropSupport(world, cropPos, rule);
					if (!validCropState || !validSoilState) {
						continue;
					}

					VerticalCropGeometry geometry = isVerticalCrop(rule)
						? resolveVerticalCropGeometry(world, cropPos, rule)
						: null;
					if (geometry != null && geometry.growthBlocked()) {
						continue;
					}

					double requiredGrowthTicks = Math.max(1.0d, crop.requiredGrowthTicks);
					double progressGrowthTicks = Math.max(0.0d,
						Math.min(requiredGrowthTicks, crop.progressGrowthTicks));
					double growthSpeedMultiplier = resolveGrowthSpeedMultiplier(rule, world, cropPos, geometry, crop);
					long previousAbsoluteDayTime = normalizePreviousAbsoluteTick(
						crop.lastProcessedAbsoluteDayTime,
						currentAbsoluteDayTime
					);
					long elapsedTicks = Math.max(0L, currentAbsoluteDayTime - previousAbsoluteDayTime);
					progressGrowthTicks = Math.min(
						requiredGrowthTicks,
						progressGrowthTicks + (elapsedTicks * growthSpeedMultiplier)
					);
					double remainingGrowthTicks = Math.max(0.0d, requiredGrowthTicks - progressGrowthTicks);
				boolean distinctProduceCrop = rule.usesDistinctMatureBlock();
				boolean producePresent = distinctProduceCrop
					&& (isCropMatureBlock(cropState, rule) || hasAdjacentCropProduce(world, cropPos, rule));
				if (producePresent) {
					continue;
				}

				if (distinctProduceCrop) {
					double matureStemGrowthSpeed = growthSpeedMultiplier;
					long matureStemCooldownTicks = resolveMatureStemCooldownTicks(crop, rule, matureStemGrowthSpeed);
					double matureStemCooldownIndicatorTicks = growthSpeedMultiplier > 0.0d
						? matureStemCooldownTicks * growthSpeedMultiplier
						: matureStemCooldownTicks;
					double indicatorRemainingTicks;
					if (isCropGrowthBlock(cropState, rule) && isMaxAge(cropState)) {
						long remainingStemCooldown = crop.matureStemReadyAtAbsoluteDayTime == Long.MIN_VALUE
							? matureStemCooldownTicks
							: Math.max(0L, crop.matureStemReadyAtAbsoluteDayTime - currentAbsoluteDayTime);
						indicatorRemainingTicks = growthSpeedMultiplier > 0.0d
							? remainingStemCooldown * growthSpeedMultiplier
							: remainingStemCooldown;
					} else {
						// The indicator represents the complete sequence: remaining
						// stem growth plus the post-maturity fruit cooldown. This
						// prevents it from reaching zero and then increasing when the
						// stem changes to its mature age.
						indicatorRemainingTicks = remainingGrowthTicks + matureStemCooldownIndicatorTicks;
					}
					if (indicatorRemainingTicks > CROP_DUE_EPSILON) {
						indicators.add(new PlotIndicatorState(
							plot.soilPos,
							plot.cropPos,
							indicatorRemainingTicks,
							growthSpeedMultiplier,
							worldClockRate
						));
					}
					continue;
				}

				if (remainingGrowthTicks <= CROP_DUE_EPSILON || isCropMatureBlock(cropState, rule) || isMaxAge(cropState)) {
					continue;
				}
					indicators.add(new PlotIndicatorState(
						plot.soilPos,
						plot.cropPos,
						remainingGrowthTicks,
						growthSpeedMultiplier,
						worldClockRate
					));
				}
			}
		}
		return indicators;
	}

	static boolean isPlotIndicatorSuppressed(ServerLevel world, long soilPos) {
		if (!settings.enabled || world == null) {
			return false;
		}
		PlotState plot = plotsByKey.get(levelId(world) + "|" + soilPos);
		if (plot == null || !plot.hasCrop()) {
			return false;
		}
		CropState crop = cropsByKey.get(plot.levelId + "|" + plot.cropPos);
		if (crop == null) {
			return false;
		}
		CropRule rule = resolveCropRuleByPlantingItemId(crop.cropId);
		if (!isVerticalCrop(rule)) {
			return false;
		}
		VerticalCropGeometry geometry = resolveVerticalCropGeometry(world, BlockPos.of(crop.cropPos), rule);
		return geometry != null && geometry.growthBlocked();
	}

	public record PlotIndicatorState(
		long soilPos,
		long cropPos,
		double remainingGrowthTicks,
		double growthSpeedMultiplier,
		float worldClockRate
	) {
		public PlotIndicatorState {
			remainingGrowthTicks = Math.max(0.0d, remainingGrowthTicks);
			growthSpeedMultiplier = Math.max(0.0d, growthSpeedMultiplier);
			worldClockRate = Math.max(0.0F, worldClockRate);
		}
	}

	public static void fertilizeSoil(ServerLevel world, BlockPos soilPos) {
		if (!settings.enabled || world == null || soilPos == null) {
			return;
		}

		BlockState soilState = world.getBlockState(soilPos);
		if (!isFarmingSoil(soilState)) {
			return;
		}

		PlotState plot = getOrCreatePlot(world, soilPos);
		if (plot == null) {
			return;
		}

		isPlotFertilized(world, plot);
		if (findPlot(world, soilPos) == null) {
			plot = getOrCreatePlot(world, soilPos);
		}
		long currentAbsoluteDayTime = resolveAbsoluteDayTime(world);
		if (!plot.fertilized) {
			plot.fertilized = true;
			plot.fertilizedAtAbsoluteDayTime = currentAbsoluteDayTime;
			dirty = true;
		}
		emitFertilizedParticles(world, soilPos);
	}

	public static void handleFarmlandRandomTick(ServerLevel world, BlockPos soilPos) {
		if (!settings.enabled || world == null || soilPos == null || !isFarmland(world.getBlockState(soilPos))) {
			return;
		}

		PlotState plot = findPlot(world, soilPos);
		if (plot != null && isPlotFertilized(world, plot)) {
			emitFertilizedParticles(world, soilPos);
		}
	}

	public static void syncPlotFromSoil(ServerLevel world, BlockPos soilPos, boolean fertilized) {
		if (!settings.enabled || world == null || soilPos == null) {
			return;
		}

		BlockState soilState = world.getBlockState(soilPos);
		if (!isFarmingSoil(soilState)) {
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

		isPlotFertilized(world, plot);
		if (findPlot(world, soilPos) == null) {
			plot = getOrCreatePlot(world, soilPos);
		}
		boolean changed = false;
		if (fertilized && !plot.fertilized) {
			plot.fertilized = true;
			plot.fertilizedAtAbsoluteDayTime = resolveAbsoluteDayTime(world);
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

		BlockPos cropPos = soilPos.above();
		BlockState cropState = world.getBlockState(cropPos);
		if (!isCropBlock(cropState, rule) || !isCropSupport(world, cropPos, rule)) {
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
		BlockPos trackedCropPos = resolveCropBasePosition(world, cropPos, rule);
		BlockState trackedCropState = world.getBlockState(trackedCropPos);
		if (!isCropBlock(trackedCropState, rule) || !isCropSupport(world, trackedCropPos, rule)) {
			return;
		}
		LOADED_CHUNK_KEYS.add(cropChunkKey(world, trackedCropPos.getX() >> 4, trackedCropPos.getZ() >> 4));
		// A mature pumpkin/melon placed as a block is not a farming harvest.
		// Do not let a farmland scan turn it into tracked crop state.
		if (isCropMatureBlock(trackedCropState, rule)
			&& ChunkDataAPIManager.isPlayerPlacedBlock(world, trackedCropPos)) {
			return;
		}

		BlockPos soilPos = trackedCropPos.below();

		PlotState plot = getOrCreatePlot(world, soilPos);
		if (plot == null) {
			return;
		}

		String cropKey = cropKey(world, trackedCropPos);
		long nowAbsoluteDayTime = resolveAbsoluteDayTime(world);
		double requiredGrowthTicks = resolveCropRequiredGrowthTicks(rule);
		CropState existing = cropsByKey.get(cropKey);
		boolean cropTypeChanged = existing != null && !rule.plantingItemId().equals(existing.cropId);
		boolean scheduleDue = existing == null || cropTypeChanged;
		double progressBasisTicks = (existing != null && !cropTypeChanged)
			? Math.max(1.0d, existing.requiredGrowthTicks)
			: requiredGrowthTicks;
		double progressFromState = progressFromCropState(world, trackedCropPos, trackedCropState, rule, progressBasisTicks);

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
			putCropState(cropKey, existing);
		} else {
			existing.soilPos = soilPos.asLong();
			if (cropTypeChanged) {
				existing.cropId = rule.plantingItemId();
				existing.requiredGrowthTicks = Math.max(1.0d, requiredGrowthTicks);
				existing.progressGrowthTicks = Math.max(0.0d, progressFromState);
				existing.lastProcessedAbsoluteDayTime = Math.max(0L, nowAbsoluteDayTime);
				existing.clearMaturityYield();
				existing.clearRainBoost();
				existing.clearMatureStemCooldown();
			} else {
				double safeRequired = Math.max(1.0d, existing.requiredGrowthTicks);
				double cappedExistingProgress = Math.max(0.0d, Math.min(safeRequired, existing.progressGrowthTicks));
				int growthStages = isVerticalCrop(rule)
					? Math.max(1, rule.maxHeight() - 1)
					: Math.max(1, getCropAgeLimit(trackedCropState));
				double oneGrowthStageTicks = safeRequired / growthStages;
				boolean visiblyComplete = isVerticalCrop(rule)
					? getVerticalCropHeight(world, trackedCropPos, rule) >= rule.maxHeight()
					: isCropMatureBlock(trackedCropState, rule) || isMaxAge(trackedCropState);
				boolean visiblyRegressed = !visiblyComplete
					&& progressFromState + Math.max(1.0d, oneGrowthStageTicks) < cappedExistingProgress;
				if (visiblyRegressed) {
					scheduleDue = true;
					// Replanting at the same position can reuse the previous crop key.
					// If the observed age dropped, reset tracked growth to the observed state.
					existing.requiredGrowthTicks = Math.max(1.0d, requiredGrowthTicks);
					existing.progressGrowthTicks = Math.max(0.0d, progressFromState);
					existing.lastProcessedAbsoluteDayTime = Math.max(0L, nowAbsoluteDayTime);
					existing.clearMaturityYield();
					existing.clearRainBoost();
					existing.clearMatureStemCooldown();
				} else {
					existing.progressGrowthTicks = Math.max(existing.progressGrowthTicks, progressFromState);
				}
			}
		}

		boolean visiblyComplete = isVerticalCrop(rule)
			? getVerticalCropHeight(world, trackedCropPos, rule) >= rule.maxHeight()
			: isCropMatureBlock(trackedCropState, rule) || isMaxAge(trackedCropState);
		if (visiblyComplete) {
			existing.progressGrowthTicks = existing.requiredGrowthTicks;
		}
		refreshCropRainBoost(world, trackedCropPos, existing);
		recordCropMaturityIfReady(world, trackedCropPos, trackedCropState, rule, existing);

		if (scheduleDue && !rule.usesVanillaGrowth()) {
			scheduleCropDue(world, existing);
		}
		plot.cropPos = trackedCropPos.asLong();
		plot.cropId = rule.plantingItemId();
		dirty = true;
	}

	/**
	 * Prevents vanilla random ticks from bypassing the active-chunk growth
	 * scheduler. A random tick may discover a crop that was placed outside the
	 * normal planting path, but it never advances the crop timer.
	 */
	public static boolean handleCropRandomTick(
		ServerLevel world,
		BlockPos cropPos,
		BlockState cropState,
		RandomSource ignoredRandom
	) {
		if (!settings.enabled || world == null || cropPos == null || cropState == null) {
			return false;
		}

		CropRule rule = resolveCropRuleByCropState(cropState);
		if (rule == null || !isCropBlock(cropState, rule) || !isCropSupport(world, cropPos, rule)) {
			return false;
		}
		if (rule.usesVanillaGrowth()) {
			if (findCrop(world, cropPos) == null) {
				trackCrop(world, cropPos, cropState);
			}
			// Vanilla stems own their random-tick growth and fruit placement.
			return false;
		}

		CropState crop = findCrop(world, cropPos);
		if (crop == null) {
			trackCrop(world, cropPos, cropState);
		}

		// Managed crops are advanced only by onActiveChunkTick. Cancelling here
		// prevents vanilla's random-growth formula from competing with that timer.
		return true;
	}

	/** Handles a managed crop whose vanilla harvest is performed without breaking the block. */
	public static boolean handleCropUseWithoutItem(ServerLevel world, BlockPos cropPos, BlockState state) {
		if (!settings.enabled || world == null || cropPos == null || state == null) {
			return false;
		}

		if (!isCropHarvestReady(world, cropPos, state)
			|| !hasCropHarvestLootTable(world, cropPos, state)) {
			return false;
		}

		trackCrop(world, cropPos, state);
		prepareCropHarvest(world, cropPos, state);
		List<ItemStack> drops = calculateCropHarvestDrops(world, cropPos, state, world.getRandom());
		if (drops.isEmpty()) {
			return false;
		}

		for (ItemStack drop : drops) {
			if (drop != null && !drop.isEmpty()) {
				Block.popResource(world, cropPos, drop);
			}
		}
		completeCropHarvest(world, cropPos, state);
		return true;
	}

	/** Records native stem maturity after Minecraft has finished its random tick. */
	public static void handleCropRandomTickAfter(ServerLevel world, BlockPos cropPos, BlockState cropState) {
		if (!settings.enabled || world == null || cropPos == null || cropState == null) {
			return;
		}

		CropRule rule = resolveCropRuleByCropState(cropState);
		if (rule == null || !rule.usesVanillaGrowth()) {
			return;
		}

		CropState crop = findCrop(world, cropPos);
		if (crop == null) {
			trackCrop(world, cropPos, world.getBlockState(cropPos));
			crop = findCrop(world, cropPos);
		}
		recordCropMaturityIfReady(world, cropPos, world.getBlockState(cropPos), rule, crop);
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
			CropRule rule = resolveCropRuleByCropState(cropState);
			if (rule == null || !isCropBlock(cropState, rule) || !isCropSupport(world, cropPos, rule)) {
				staleKeys.add(key);
				continue;
			}

			if (normalizeCropAbsoluteTimers(crop, currentAbsoluteDayTime)) {
				dirty = true;
			}
			refreshCropRainBoost(world, cropPos, crop);
			if (!rule.usesVanillaGrowth()) {
				applyElapsedCropProgress(world, cropPos, cropState, rule, crop, currentAbsoluteDayTime);
				scheduleCropDue(world, crop);
			} else {
				recordCropMaturityIfReady(world, cropPos, cropState, rule, crop);
			}
		}

		for (String staleKey : staleKeys) {
			CropState staleCrop = cropsByKey.get(staleKey);
			if (staleCrop != null) {
				FarmingPlotIndicatorManager.invalidatePlot(world, staleCrop.soilPos);
			}
			if (removeCropStateByKey(staleKey) != null) {
				dirty = true;
			} else {
				// Repair an orphaned secondary-index entry left by an older state or migration.
				keys.remove(staleKey);
			}
		}
		rebuildCropDueSchedule(world, chunkX, chunkZ);
	}

	/**
	 * Advances a bounded number of crops from an active chunk on every heartbeat.
	 * Positive-speed crops are scheduled for the next heartbeat so their block
	 * age can advance continuously; zero-speed crops are rechecked less often.
	 */
	private static void onActiveChunkTick(ActiveChunkTickEvent event) {
		if (event == null || !settings.enabled) {
			return;
		}

		ServerLevel world = event.level();
		LevelChunk chunk = event.chunk();
		if (world == null || chunk == null) {
			return;
		}

		int chunkX = chunk.getPos().x();
		int chunkZ = chunk.getPos().z();
		String chunkKey = cropChunkKey(world, chunkX, chunkZ);
		Set<String> cropKeys = cropKeysByChunk.get(chunkKey);
		PriorityQueue<CropDueEntry> queue = cropDueQueuesByChunk.get(chunkKey);
		if (cropKeys == null || cropKeys.isEmpty()) {
			clearCropDueSchedule(chunkKey);
			return;
		}

		long currentAbsoluteDayTime = resolveAbsoluteDayTime(world);
		String worldLevelId = levelId(world);
		Long previousObservedAbsoluteDayTime = lastObservedAbsoluteDayTimeByLevel.put(
			worldLevelId,
			currentAbsoluteDayTime
		);
		if (previousObservedAbsoluteDayTime != null
			&& isAbsoluteTimeRollback(previousObservedAbsoluteDayTime, currentAbsoluteDayTime)) {
			rebaseAbsoluteTimersAfterRollback(
				world,
				previousObservedAbsoluteDayTime,
				currentAbsoluteDayTime
			);
			queue = cropDueQueuesByChunk.get(chunkKey);
		}
		Long lastRainBoostScan = rainBoostScanAtAbsoluteDayTimeByChunk.get(chunkKey);
		boolean rainScanDue = lastRainBoostScan == null
			|| currentAbsoluteDayTime < lastRainBoostScan
			|| currentAbsoluteDayTime - lastRainBoostScan >= RAIN_BOOST_SCAN_INTERVAL_TICKS;
		if (world.isRaining() && rainScanDue) {
			rainBoostScanAtAbsoluteDayTimeByChunk.put(chunkKey, currentAbsoluteDayTime);
			for (String cropKey : cropKeys) {
				CropState crop = cropsByKey.get(cropKey);
				CropRule rule = crop == null ? null : resolveCropRuleByPlantingItemId(crop.cropId);
				if (crop != null && rule != null && rule.usesVanillaGrowth()) {
					refreshCropRainBoost(world, BlockPos.of(crop.cropPos), crop, currentAbsoluteDayTime);
				}
			}
		}

		if (queue == null || (queue.isEmpty() && hasPendingCropGrowth(world, cropKeys))) {
			rebuildCropDueSchedule(world, chunkX, chunkZ);
			queue = cropDueQueuesByChunk.get(chunkKey);
			if (queue == null) {
				return;
			}
		}

		pruneCropDueQueue(chunkKey, queue);
		int operations = 0;
		while (operations < MAX_CROP_OPERATIONS_PER_HEARTBEAT) {
			CropDueEntry entry = queue.peek();
			if (entry == null) {
				break;
			}
			if (!isCurrentCropDueEntry(chunkKey, entry)) {
				queue.poll();
				continue;
			}
			if (entry.nextDue() > currentAbsoluteDayTime + CROP_DUE_EPSILON) {
				break;
			}

			queue.poll();
			CropState crop = cropsByKey.get(entry.cropKey());
			if (crop == null) {
				continue;
			}

			BlockPos cropPos = BlockPos.of(crop.cropPos);
			BlockState cropState = world.getBlockState(cropPos);
			CropRule rule = resolveCropRuleByPlantingItemId(crop.cropId);
			if (rule == null || !isCropBlock(cropState, rule) || !isCropSupport(world, cropPos, rule)) {
				FarmingPlotIndicatorManager.invalidatePlot(world, crop.soilPos);
				if (removeCropStateByKey(crop.key()) != null) {
					dirty = true;
				}
				continue;
			}
			if (rule.usesVanillaGrowth()) {
				// Vanilla-growth crops remain tracked for plots and harvests, but
				// their age and produce placement belong to Minecraft.
				continue;
			}

			applyElapsedCropProgress(world, cropPos, cropState, rule, crop, currentAbsoluteDayTime);
			operations++;
			scheduleCropDue(world, crop);
		}

		pruneCropDueQueue(chunkKey, queue);
		if (queue.size() > cropKeys.size() * 2 + 16) {
			rebuildCropDueSchedule(world, chunkX, chunkZ);
		}
	}

	private static boolean hasPendingCropGrowth(ServerLevel world, Set<String> cropKeys) {
		if (cropKeys == null || cropKeys.isEmpty()) {
			return false;
		}
		for (String cropKey : cropKeys) {
			CropState crop = cropsByKey.get(cropKey);
			CropRule rule = crop == null ? null : resolveCropRuleByPlantingItemId(crop.cropId);
			if (crop == null || (rule != null && rule.usesVanillaGrowth())) {
				continue;
			}
			boolean progressPending = crop.progressGrowthTicks + CROP_DUE_EPSILON
				< Math.max(1.0d, crop.requiredGrowthTicks);
			boolean blockedStemFruitPlacement = rule != null
				&& rule.usesDistinctMatureBlock()
				&& crop.progressGrowthTicks + CROP_DUE_EPSILON >= Math.max(1.0d, crop.requiredGrowthTicks)
				&& !isCropMatureBlock(world.getBlockState(BlockPos.of(crop.cropPos)), rule)
				&& !hasAdjacentCropProduce(world, BlockPos.of(crop.cropPos), rule);
			if (rule == null || progressPending || blockedStemFruitPlacement) {
				return true;
			}
		}
		return false;
	}

	private static void rebuildCropDueSchedule(ServerLevel world, int chunkX, int chunkZ) {
		if (world == null) {
			return;
		}
		String chunkKey = cropChunkKey(world, chunkX, chunkZ);
		clearCropDueSchedule(chunkKey);
		Set<String> cropKeys = cropKeysByChunk.get(chunkKey);
		if (cropKeys == null || cropKeys.isEmpty()) {
			return;
		}
		for (String cropKey : List.copyOf(cropKeys)) {
			CropState crop = cropsByKey.get(cropKey);
			if (crop != null) {
				scheduleCropDue(world, crop);
			}
		}
	}

	private static void scheduleCropDue(ServerLevel world, CropState crop) {
		if (world == null || crop == null) {
			return;
		}
		String chunkKey = cropChunkKey(crop);
		Map<String, Long> versions = cropDueVersionsByChunk.computeIfAbsent(chunkKey, ignored -> new HashMap<>());
		long version = versions.getOrDefault(crop.key(), 0L) + 1L;
		versions.put(crop.key(), version);
		double nextDue = calculateCropNextDue(world, crop, resolveAbsoluteDayTime(world));
		if (!Double.isFinite(nextDue)) {
			return;
		}
		cropDueQueuesByChunk.computeIfAbsent(chunkKey, ignored -> new PriorityQueue<>(
			Comparator.comparingDouble(CropDueEntry::nextDue).thenComparing(CropDueEntry::cropKey)
		)).add(new CropDueEntry(crop.key(), nextDue, version));
	}

	private static double calculateCropNextDue(ServerLevel world, CropState crop, long currentAbsoluteDayTime) {
		if (world == null || crop == null) {
			return Double.POSITIVE_INFINITY;
		}
		double requiredTicks = Math.max(1.0d, crop.requiredGrowthTicks);
		double progress = Math.max(0.0d, Math.min(requiredTicks, crop.progressGrowthTicks));

		CropRule rule = resolveCropRuleByPlantingItemId(crop.cropId);
		if (rule == null || rule.usesVanillaGrowth()) {
			return Double.POSITIVE_INFINITY;
		}
		BlockPos cropPos = BlockPos.of(crop.cropPos);
		BlockState cropState = world.getBlockState(cropPos);
		VerticalCropGeometry geometry = isVerticalCrop(rule)
			? resolveVerticalCropGeometry(world, cropPos, rule)
			: null;
		boolean matureProducePresent = !rule.usesDistinctMatureBlock()
			|| isCropMatureBlock(cropState, rule)
			|| hasAdjacentCropProduce(world, cropPos, rule);
		if (progress + CROP_DUE_EPSILON >= requiredTicks && rule.usesDistinctMatureBlock()) {
			if (matureProducePresent && (geometry == null || !geometry.growthBlocked())) {
				return Double.POSITIVE_INFINITY;
			}
			if (crop.matureStemReadyAtAbsoluteDayTime > currentAbsoluteDayTime) {
				return crop.matureStemReadyAtAbsoluteDayTime;
			}
			long baseline = Math.max(0L, crop.lastProcessedAbsoluteDayTime);
			return Math.max(currentAbsoluteDayTime, baseline) + ZERO_SPEED_RECHECK_INTERVAL_TICKS;
		}
		if (progress + CROP_DUE_EPSILON >= requiredTicks
			&& (geometry == null || !geometry.growthBlocked())
			&& matureProducePresent) {
			return Double.POSITIVE_INFINITY;
		}

		double speedMultiplier = resolveGrowthSpeedMultiplier(rule, world, cropPos, geometry, crop);
		long baseline = Math.max(0L, crop.lastProcessedAbsoluteDayTime);
		if (!(speedMultiplier > 0.0d)) {
			return Math.max(currentAbsoluteDayTime, baseline) + ZERO_SPEED_RECHECK_INTERVAL_TICKS;
		}
		return Math.max(currentAbsoluteDayTime, baseline) + ACTIVE_CROP_UPDATE_INTERVAL_TICKS;
	}

	private static boolean isCurrentCropDueEntry(String chunkKey, CropDueEntry entry) {
		Map<String, Long> versions = cropDueVersionsByChunk.get(chunkKey);
		return versions != null && Long.valueOf(entry.version()).equals(versions.get(entry.cropKey()));
	}

	private static void pruneCropDueQueue(String chunkKey, PriorityQueue<CropDueEntry> queue) {
		if (queue == null) {
			return;
		}
		while (!queue.isEmpty()) {
			CropDueEntry entry = queue.peek();
			if (isCurrentCropDueEntry(chunkKey, entry) && cropsByKey.containsKey(entry.cropKey())) {
				return;
			}
			queue.poll();
		}
	}

	private static void clearCropDueSchedule(String chunkKey) {
		if (chunkKey == null) {
			return;
		}
		cropDueQueuesByChunk.remove(chunkKey);
		cropDueVersionsByChunk.remove(chunkKey);
	}

	private record CropDueEntry(String cropKey, double nextDue, long version) { }

	private static void loadChunkData(ServerLevel world, int chunkX, int chunkZ) {
		if (world == null) return;
		String chunkKey = cropChunkKey(world, chunkX, chunkZ);
		LOADED_CHUNK_KEYS.add(chunkKey);
		CompoundTag source = WorldChunkDataAPIManager.getChunkSystemData(world, chunkX, chunkZ, DATA_SYSTEM_ID);
		boolean hasPersistedFarmingData = source.contains(FIELD_PLOTS) || source.contains(FIELD_CROPS);
		if (hasPersistedFarmingData) {
			// A duplicate load callback can arrive after runtime state was already
			// restored. Replace that chunk from durable data only when data exists;
			// an empty attachment must not erase a valid in-memory state.
			evictChunkRuntime(world, chunkX, chunkZ);
		}
		if (source.get(FIELD_PLOTS) instanceof ListTag plots) {
			for (Tag element : plots) {
				PlotState plot = element instanceof CompoundTag compound ? PlotState.fromNbt(compound) : null;
				if (plot != null) {
					putPlotState(plot.key(), plot);
				}
			}
		}
		if (source.get(FIELD_CROPS) instanceof ListTag crops) {
			long currentAbsoluteDayTime = resolveAbsoluteDayTime(world);
			for (Tag element : crops) {
				CropState crop = element instanceof CompoundTag compound ? CropState.fromNbt(compound) : null;
				if (crop != null) {
					if (normalizeCropAbsoluteTimers(crop, currentAbsoluteDayTime)) {
						dirty = true;
					}
					putCropState(crop.key(), crop);
				}
			}
		}
	}

	private static void saveChunkData(ServerLevel world, int chunkX, int chunkZ) {
		if (world == null) return;
		String chunkKey = cropChunkKey(world, chunkX, chunkZ);
		LOADED_CHUNK_KEYS.add(chunkKey);
		CompoundTag data = toChunkPersistedData(world, chunkX, chunkZ);
		ListTag plots = data.getList(FIELD_PLOTS).orElse(new ListTag());
		ListTag crops = data.getList(FIELD_CROPS).orElse(new ListTag());
		if (plots.isEmpty() && crops.isEmpty()) {
			WorldChunkDataAPIManager.removeChunkSystemData(
				new WorldChunkDataKey(levelId(world), chunkX, chunkZ), DATA_SYSTEM_ID
			);
		} else {
			WorldChunkDataAPIManager.setChunkSystemData(
				new WorldChunkDataKey(levelId(world), chunkX, chunkZ), DATA_SYSTEM_ID, data
			);
		}
	}

	private static void markChunkUnloaded(ServerLevel world, int chunkX, int chunkZ) {
		String chunkKey = cropChunkKey(world, chunkX, chunkZ);
		LOADED_CHUNK_KEYS.remove(chunkKey);
		rainBoostScanAtAbsoluteDayTimeByChunk.remove(chunkKey);
	}

	private static boolean isRuntimeChunkLoaded(ServerLevel world, int chunkX, int chunkZ) {
		if (world == null) {
			return false;
		}
		String chunkKey = cropChunkKey(world, chunkX, chunkZ);
		if (LOADED_CHUNK_KEYS.contains(chunkKey)) {
			return true;
		}
		if (!ChunkAPIManager.isChunkLoaded(world, chunkX, chunkZ)) {
			return false;
		}
		LOADED_CHUNK_KEYS.add(chunkKey);
		return true;
	}

	private static void evictChunkRuntime(ServerLevel world, int chunkX, int chunkZ) {
		if (world == null) return;
		String levelId = levelId(world);
		String chunkKey = cropChunkKey(world, chunkX, chunkZ);
		clearCropDueSchedule(chunkKey);
		Set<String> plotKeys = plotKeysByChunk.get(chunkKey);
		if (plotKeys != null) {
			for (String plotKey : List.copyOf(plotKeys)) {
				removePlotStateByKey(plotKey);
			}
		}
		Set<String> cropKeys = cropKeysByChunk.get(chunkKey);
		if (cropKeys != null) {
			for (String cropKey : List.copyOf(cropKeys)) {
				removeCropStateByKey(cropKey);
			}
		}
		pendingHarvestRulesByKey.keySet().removeIf(key -> keyBelongsToChunk(key, levelId, chunkX, chunkZ));
	}

	private static CompoundTag toChunkPersistedData(ServerLevel world, int chunkX, int chunkZ) {
		ListTag plots = new ListTag();
		String chunkKey = plotChunkKey(world, chunkX, chunkZ);
		Set<String> plotKeys = plotKeysByChunk.get(chunkKey);
		if (plotKeys != null) {
			for (String plotKey : plotKeys) {
				PlotState plot = plotsByKey.get(plotKey);
				if (plot != null) {
					plots.add(plot.toNbt());
				}
			}
		}
		ListTag crops = new ListTag();
		Set<String> cropKeys = cropKeysByChunk.get(chunkKey);
		if (cropKeys != null) {
			for (String cropKey : cropKeys) {
				CropState crop = cropsByKey.get(cropKey);
				if (crop != null) {
					crops.add(crop.toNbt());
				}
			}
		}
		CompoundTag data = new CompoundTag();
		data.put(FIELD_PLOTS, plots);
		data.put(FIELD_CROPS, crops);
		return data;
	}

	private static int[] chunkCoordinates(String key, ServerLevel world) {
		if (key == null || world == null) return null;
		String prefix = levelId(world) + "|";
		if (!key.startsWith(prefix)) return null;
		String[] parts = key.substring(prefix.length()).split("\\|", 2);
		if (parts.length != 2) return null;
		try { return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) }; }
		catch (NumberFormatException ignored) { return null; }
	}

	private static boolean keyBelongsToChunk(String key, String levelId, int chunkX, int chunkZ) {
		if (key == null || levelId == null || !key.startsWith(levelId + "|")) return false;
		try {
			long position = Long.parseLong(key.substring(levelId.length() + 1));
			return (BlockPos.getX(position) >> 4) == chunkX && (BlockPos.getZ(position) >> 4) == chunkZ;
		} catch (NumberFormatException ignored) { return false; }
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
		VerticalCropGeometry geometry = isVerticalCrop(rule)
			? resolveVerticalCropGeometry(world, cropPos, rule)
			: null;
		boolean observedMature = geometry != null
			? geometry.height() >= rule.maxHeight()
			: isCropMatureBlock(state, rule) || isMaxAge(state);
		boolean wasGrowthComplete = observedMature
			&& crop.progressGrowthTicks + CROP_DUE_EPSILON >= requiredTicks;
		double observedProgress = progressFromCropState(world, cropPos, state, rule, requiredTicks, geometry);
		double cappedTrackedProgress = Math.max(0.0d, Math.min(requiredTicks, crop.progressGrowthTicks));
		double oneGrowthStageTicks = requiredTicks / (isVerticalCrop(rule)
			? Math.max(1, rule.maxHeight() - 1)
			: ageLimit);
		if (!observedMature && observedProgress + Math.max(1.0d, oneGrowthStageTicks) < cappedTrackedProgress) {
			crop.progressGrowthTicks = Math.max(0.0d, Math.min(requiredTicks, observedProgress));
		}

		double speedMultiplier = resolveGrowthSpeedMultiplier(rule, world, cropPos, geometry, crop);

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

		double observedProgressFromState = observedProgress;
		if (observedProgressFromState > crop.progressGrowthTicks) {
			crop.progressGrowthTicks = Math.min(requiredTicks, observedProgressFromState);
			dirty = true;
		}

		VerticalCropGeometry updatedGeometry = updateCropBlockAge(world, cropPos, state, rule, crop, geometry);
		BlockState updatedState = world.getBlockState(cropPos);
		boolean isGrowthComplete = isCropGrowthComplete(world, cropPos, updatedState, rule, crop, updatedGeometry);
		recordCropMaturityIfReady(world, cropPos, updatedState, rule, crop);
		if (!wasGrowthComplete && isGrowthComplete) {
			FarmingPlotIndicatorManager.invalidatePlot(world, crop.soilPos);
		}
	}

	public static void handleBlockBreak(Level world, BlockPos pos, BlockState state, BlockEntity blockEntity) {
		if (!settings.enabled || !(world instanceof ServerLevel serverLevel) || pos == null) {
			return;
		}
		// The optional loot/drop bridge normally completes managed harvests. If
		// it is absent, finish the pending player harvest here so post-harvest
		// mechanics such as stem wilting still run.
		if (hasPendingHarvest(serverLevel, pos, state)) {
			completeCropHarvest(serverLevel, pos, state);
		}

		CropRule brokenRule = resolveCropRuleByCropState(state);
		if (isVerticalCrop(brokenRule)) {
			BlockPos trackedCropPos = resolveCropBasePosition(serverLevel, pos, brokenRule);
			String trackedCropKey = cropKey(serverLevel, trackedCropPos);
			CropState removed = removeCropStateByKey(trackedCropKey);
			String pendingHarvestKey = resolvePendingHarvestKey(serverLevel, pos);
			PendingHarvestRule pendingHarvest = pendingHarvestKey == null
				? null
				: pendingHarvestRulesByKey.get(pendingHarvestKey);
			if (pendingHarvest == null || !pendingHarvest.completed) {
				if (pendingHarvestKey != null) {
					pendingHarvestRulesByKey.remove(pendingHarvestKey);
				}
			}
			BlockState remainingBaseState = serverLevel.getBlockState(trackedCropPos);
			if (isCropBlock(remainingBaseState, brokenRule) && isCropSupport(serverLevel, trackedCropPos, brokenRule)) {
				trackCrop(serverLevel, trackedCropPos, remainingBaseState);
				FarmingPlotIndicatorManager.invalidatePlot(serverLevel, trackedCropPos.below().asLong());
			} else if (removed != null) {
				FarmingPlotIndicatorManager.invalidatePlot(serverLevel, removed.soilPos);
				PlotState soilPlot = findPlot(serverLevel, BlockPos.of(removed.soilPos));
				if (soilPlot != null && soilPlot.cropPos == trackedCropPos.asLong()) {
					boolean fertilizerActive = isPlotFertilized(serverLevel, soilPlot);
					soilPlot.clearCrop();
					if (!fertilizerActive) {
						removePlotStateByKey(soilPlot.key());
					}
				}
			}
			dirty = removed != null || isCropBlock(remainingBaseState, brokenRule);
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
		String pendingHarvestKey = resolvePendingHarvestKey(serverLevel, pos);
		PendingHarvestRule pendingHarvest = pendingHarvestKey == null
			? null
			: pendingHarvestRulesByKey.get(pendingHarvestKey);
		if (cropState != null) {
			FarmingPlotIndicatorManager.invalidatePlot(serverLevel, cropState.soilPos);
			PlotState soilPlot = findPlot(serverLevel, BlockPos.of(cropState.soilPos));
			if (soilPlot != null && soilPlot.cropPos == pos.asLong()) {
				boolean fertilizerActive = isPlotFertilized(serverLevel, soilPlot);
				soilPlot.clearCrop();
				if (!fertilizerActive) {
					removePlotStateByKey(soilPlot.key());
				}
			}
			if (pendingHarvest == null || !pendingHarvest.completed) {
				if (pendingHarvestKey != null) {
					pendingHarvestRulesByKey.remove(pendingHarvestKey);
				}
			}
			changed = true;
		}
		if (isCropMatureBlock(state, brokenRule)) {
			rescheduleAdjacentMatureStemAttempts(serverLevel, pos, brokenRule);
		}

		if (changed) {
			dirty = true;
		}
	}

	public static void applyCropItemMetadata() {
		if (!settings.enabled) {
			return;
		}
		if (!clientSynchronized && FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
			captureClientComponents(cropRulesByPlantingItemId.values());
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
		if (isDirectlyPlacedMatureCrop(world, cropPos, state)) {
			return false;
		}
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
		return tracked != null && isCropGrowthComplete(world, cropPos, state, rule, tracked);
	}

	public static boolean isManagedHarvestState(ServerLevel world, BlockPos cropPos, BlockState state) {
		if (isDirectlyPlacedMatureCrop(world, cropPos, state)) {
			return false;
		}
		CropRule pendingRule = resolvePendingHarvestRule(world, cropPos);
		if (pendingRule != null) {
			return true;
		}

		CropRule rule = resolveManagedCropRule(world, cropPos, state);
		if (rule == null || !isCropHarvestReady(world, cropPos, state)) {
			return false;
		}
		return isVerticalCrop(rule) || !rule.usesDistinctMatureBlock() || isCropMatureBlock(state, rule);
	}


	public static boolean hasPendingHarvest(ServerLevel world, BlockPos cropPos, BlockState state) {
		PendingHarvestRule pendingHarvest = resolvePendingHarvest(world, cropPos);
		return pendingHarvest != null && !pendingHarvest.completed;
	}

	public static List<ItemStack> calculateCropHarvestDrops(ServerLevel world, BlockPos cropPos, BlockState state, RandomSource random) {
		purgeExpiredPendingHarvestRules();
		CropRule rule = resolveHarvestRule(world, cropPos, state);
		if (!settings.enabled || rule == null || !isCropHarvestReady(world, cropPos, state)) {
			return List.of();
		}

		PendingHarvestRule pendingHarvest = resolvePendingHarvest(world, cropPos);
		if (pendingHarvest != null && (pendingHarvest.yieldApplied || pendingHarvest.completed)) {
			return List.of();
		}
		RandomSource safeRandom = random == null ? RandomSource.create() : random;
		List<ItemStack> drops = FarmingAPIManager.generateManagedLootForTable(rule.yieldTableId(), safeRandom);
		BlockPos trackedCropPos = pendingHarvest == null
			? resolveTrackedCropPosition(world, cropPos, rule, state)
			: BlockPos.of(pendingHarvest.trackedCropPos);
		CropState crop = findCrop(world, trackedCropPos);
		boolean completedYieldRecorded = pendingHarvest != null && pendingHarvest.completed;
		if (!completedYieldRecorded && crop != null && !crop.maturityYieldRecorded) {
			// The harvested fruit may already be cleared from the world by the
			// time loot is calculated. Use the observed harvested state so a
			// melon/pumpkin still records the speed at which that fruit matured.
			recordCropMaturityIfReady(world, trackedCropPos, state, rule, crop);
		}

		double yieldMultiplier = completedYieldRecorded
			? pendingHarvest.completedYieldMultiplier
			: crop != null && crop.maturityYieldRecorded
				? crop.maturityYieldMultiplier
				: 1.0d;
		if (!drops.isEmpty()) {
			ItemStack first = drops.getFirst();
			if (first != null && !first.isEmpty()) {
				first.setCount(applyScaledItemCount(first.getCount(), yieldMultiplier, safeRandom));
			}
		}
		if (!drops.isEmpty()) {
			markPendingHarvestYieldApplied(world, cropPos);
		}
		return drops;
	}

	public static boolean hasCropHarvestLootTable(ServerLevel world, BlockPos cropPos, BlockState state) {
		CropRule rule = resolveHarvestRule(world, cropPos, state);
		return rule != null && FarmingAPIManager.hasManagedLootTable(rule.yieldTableId());
	}

	public static void prepareCropHarvest(ServerLevel world, BlockPos cropPos, BlockState state) {
		if (!settings.enabled || world == null || cropPos == null || state == null) {
			return;
		}

		CropRule rule = resolveHarvestRule(world, cropPos, state);
		if (rule == null || !isManagedHarvestState(world, cropPos, state)) {
			return;
		}

		markPendingHarvest(world, cropPos, rule);
	}

	public static void completeCropHarvest(ServerLevel world, BlockPos cropPos, BlockState state) {
		if (!settings.enabled || world == null || cropPos == null || state == null) {
			return;
		}

		CropRule rule = resolveHarvestRule(world, cropPos, state);
		if (rule == null) {
			return;
		}

		PendingHarvestRule pendingHarvest = resolvePendingHarvest(world, cropPos);
		if (pendingHarvest != null && pendingHarvest.completed) {
			return;
		}
		if (pendingHarvest != null && !pendingHarvest.yieldApplied) {
			BlockState harvestState = pendingHarvest.harvestState == null ? state : pendingHarvest.harvestState;
			List<ItemStack> fallbackDrops = calculateCropHarvestDrops(world, cropPos, harvestState, world.getRandom());
			for (ItemStack drop : fallbackDrops) {
				if (drop != null && !drop.isEmpty()) {
					Block.popResource(world, cropPos, drop);
				}
			}
			pendingHarvest = resolvePendingHarvest(world, cropPos);
		}
		BlockState harvestState = pendingHarvest == null || pendingHarvest.harvestState == null
			? state
			: pendingHarvest.harvestState;
		BlockPos trackedCropPos = pendingHarvest == null
			? resolveTrackedCropPosition(world, cropPos, rule, state)
			: BlockPos.of(pendingHarvest.trackedCropPos);
		String trackedCropKey = cropKey(world, trackedCropPos);
		CropState trackedCrop = cropsByKey.get(trackedCropKey);
		if (trackedCrop != null && !trackedCrop.maturityYieldRecorded) {
			recordCropMaturityIfReady(world, trackedCropPos, harvestState, rule, trackedCrop);
		}
		double completedYieldMultiplier = trackedCrop != null && trackedCrop.maturityYieldRecorded
			? trackedCrop.maturityYieldMultiplier
			: pendingHarvest == null ? 1.0d : pendingHarvest.completedYieldMultiplier;
		BlockPos expectedStemPos = pendingHarvest == null ? null : trackedCropPos;
		BlockPos wiltedStemPos = wiltStemOnHarvest(world, cropPos, state, rule, expectedStemPos);
		BlockState survivingStemState = world.getBlockState(trackedCropPos);
		boolean stemSurvived = wiltedStemPos == null
			&& isCropGrowthBlock(survivingStemState, rule)
			&& isCropSupport(world, trackedCropPos, rule);
		if (stemSurvived) {
			if (trackedCrop == null) {
				trackCrop(world, trackedCropPos, survivingStemState);
				trackedCrop = cropsByKey.get(trackedCropKey);
			}
			if (trackedCrop != null) {
				long currentAbsoluteDayTime = resolveAbsoluteDayTime(world);
				if (rule.harvestResetAge() >= 0) {
					BlockState resetState = setCropAge(survivingStemState, rule.harvestResetAge());
					if (resetState != null && !resetState.equals(survivingStemState)) {
						world.setBlockAndUpdate(trackedCropPos, resetState);
					}
					BlockState observedResetState = world.getBlockState(trackedCropPos);
					trackedCrop.progressGrowthTicks = Math.max(
						0.0d,
						Math.min(
							trackedCrop.requiredGrowthTicks,
							progressFromCropState(
								world,
								trackedCropPos,
								observedResetState,
								rule,
								trackedCrop.requiredGrowthTicks
							)
						)
					);
					trackedCrop.clearMatureStemCooldown();
				} else {
					double matureStemGrowthSpeed = resolveGrowthSpeedMultiplier(rule, world, trackedCropPos, trackedCrop);
					long matureStemCooldownTicks = resolveMatureStemCooldownTicks(
						trackedCrop,
						rule,
						matureStemGrowthSpeed
					);
					BlockState matureStemState = resolveMatureStemState(rule);
					if (matureStemState != null && !matureStemState.equals(survivingStemState)) {
						world.setBlockAndUpdate(trackedCropPos, matureStemState);
					}
					trackedCrop.progressGrowthTicks = trackedCrop.requiredGrowthTicks;
					trackedCrop.matureStemReadyAtAbsoluteDayTime = addAbsoluteDayTime(
						currentAbsoluteDayTime,
						matureStemCooldownTicks
					);
				}
				trackedCrop.lastProcessedAbsoluteDayTime = currentAbsoluteDayTime;
				trackedCrop.clearMaturityYield();
				dirty = true;
				scheduleCropDue(world, trackedCrop);
			}
		}
		CropState removed = stemSurvived ? null : removeCropStateByKey(trackedCropKey);
		if (wiltedStemPos != null && !wiltedStemPos.equals(trackedCropPos)) {
			removeWiltedStemTracking(world, wiltedStemPos);
		}
		BlockPos soilPos = removed == null ? trackedCropPos.below() : BlockPos.of(removed.soilPos);
		if (stemSurvived) {
			FarmingPlotIndicatorManager.refreshPlot(world, soilPos.asLong());
		} else {
			FarmingPlotIndicatorManager.invalidatePlot(world, soilPos.asLong());
		}
		PlotState plot = findPlot(world, soilPos);
		if (!stemSurvived && plot != null && (!plot.hasCrop() || plot.cropPos == cropPos.asLong() || removed != null)) {
			boolean fertilizerActive = isPlotFertilized(world, plot);
			plot.clearCrop();
			if (!fertilizerActive) {
				removePlotStateByKey(plot.key());
			}
		}
		dirty = true;
		if (pendingHarvest != null) {
			String pendingHarvestKey = resolvePendingHarvestKey(world, cropPos);
			if (pendingHarvestKey != null) {
				pendingHarvestRulesByKey.put(
					pendingHarvestKey,
					new PendingHarvestRule(
						pendingHarvest.rule,
						pendingHarvest.expiresAtGameplayTick,
						pendingHarvest.produceCropPos,
						pendingHarvest.trackedCropPos,
						pendingHarvest.harvestState,
						pendingHarvest.yieldApplied,
						true,
						completedYieldMultiplier
					)
				);
			}
		}
	}

	/** Applies a crop rule's standardized post-harvest stem-wilt mechanic. */
	private static BlockPos wiltStemOnHarvest(
		ServerLevel world,
		BlockPos producePos,
		BlockState produceState,
		CropRule rule,
		BlockPos expectedStemPos
	) {
		if (world == null || producePos == null || rule == null || rule.stemWiltChance() <= 0.0d) {
			return null;
		}

		BlockPos stemPos = expectedStemPos;
		if (stemPos != null) {
			if (!isCropGrowthBlock(world.getBlockState(stemPos), rule)) {
				return null;
			}
		} else if (isCropGrowthBlock(produceState, rule)) {
			stemPos = producePos;
		} else if (rule.usesDistinctMatureBlock() && isCropMatureBlock(produceState, rule)) {
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos candidate = producePos.relative(direction);
				if (isCropAttachedStemFacingProduce(
					world,
					candidate,
					world.getBlockState(candidate),
					producePos,
					produceState,
					rule
				)) {
					stemPos = candidate;
					break;
				}
			}
			if (stemPos == null) {
				for (Direction direction : Direction.Plane.HORIZONTAL) {
					BlockPos candidate = producePos.relative(direction);
					if (isCropGrowthBlock(world.getBlockState(candidate), rule)) {
						stemPos = candidate;
						break;
					}
				}
			}
		}
		if (stemPos == null) {
			return null;
		}

		double roll = world.getRandom().nextDouble();
		boolean wilted = roll < rule.stemWiltChance();
		if (!wilted) {
			return null;
		}

		world.setBlockAndUpdate(stemPos, Blocks.AIR.defaultBlockState());
		return stemPos;
	}

	private static void removeWiltedStemTracking(ServerLevel world, BlockPos stemPos) {
		if (world == null || stemPos == null) {
			return;
		}

		CropState removed = removeCropStateByKey(cropKey(world, stemPos));
		if (removed == null) {
			return;
		}
		FarmingPlotIndicatorManager.invalidatePlot(world, removed.soilPos);
		PlotState plot = findPlot(world, BlockPos.of(removed.soilPos));
		if (plot != null && plot.cropPos == stemPos.asLong()) {
			boolean fertilizerActive = isPlotFertilized(world, plot);
			plot.clearCrop();
			if (!fertilizerActive) {
				removePlotStateByKey(plot.key());
			}
		}
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

	private static BlockState resolveMatureStemState(CropRule rule) {
		Block stemBlock = rule == null ? null : resolveBlockByRegistryId(rule.cropBlockId());
		if (stemBlock == null) {
			return null;
		}
		BlockState stemState = stemBlock.defaultBlockState();
		return setCropAge(stemState, getCropAgeLimit(stemState));
	}

	private static VerticalCropGeometry updateCropBlockAge(
		ServerLevel world,
		BlockPos cropPos,
		BlockState state,
		CropRule rule,
		CropState crop,
		VerticalCropGeometry geometry
	) {
		if (world == null || cropPos == null || state == null || rule == null || crop == null) {
			return geometry;
		}

		double required = Math.max(1.0d, crop.requiredGrowthTicks);
		double progress = Math.max(0.0d, Math.min(required, crop.progressGrowthTicks));
		if (isVerticalCrop(rule)) {
			VerticalCropGeometry safeGeometry = geometry == null
				? resolveVerticalCropGeometry(world, cropPos, rule)
				: geometry;
			BlockPos basePos = safeGeometry == null ? cropPos : safeGeometry.basePos();
			int currentHeight = safeGeometry == null ? getVerticalCropHeight(world, basePos, rule) : safeGeometry.height();
			int targetHeight = verticalHeightFromProgress(progress / required, rule);
			Block cropBlock = resolveBlockByRegistryId(rule.cropBlockId());
			if (cropBlock == null) {
				return safeGeometry;
			}

			int finalHeight = currentHeight;
			for (int height = currentHeight; height < targetHeight; height++) {
				BlockPos nextPos = basePos.above(height);
				BlockState nextState = world.getBlockState(nextPos);
				if (!nextState.isAir()) {
					break;
				}
				world.setBlockAndUpdate(nextPos, cropBlock.defaultBlockState());
				dirty = true;
				finalHeight = height + 1;
			}
			if (finalHeight >= rule.maxHeight()) {
				crop.progressGrowthTicks = required;
			}
			return new VerticalCropGeometry(
				basePos,
				finalHeight,
				finalHeight < rule.maxHeight() && !world.getBlockState(basePos.above(finalHeight)).isAir()
			);
		}

		int ageLimit = getCropAgeLimit(state);
		int targetAge = ageFromProgress(progress / required, ageLimit);

		if (rule.usesDistinctMatureBlock()) {
			if (progress + 1e-6d >= required) {
				if (isCropMatureBlock(state, rule) || hasAdjacentCropProduce(world, cropPos, rule)) {
					crop.progressGrowthTicks = required;
					crop.clearMatureStemCooldown();
					return null;
				}

				long currentAbsoluteDayTime = resolveAbsoluteDayTime(world);
				if (crop.matureStemReadyAtAbsoluteDayTime == Long.MIN_VALUE) {
					double matureStemGrowthSpeed = resolveGrowthSpeedMultiplier(rule, world, cropPos, crop);
					long matureStemCooldownTicks = resolveMatureStemCooldownTicks(
						crop,
						rule,
						matureStemGrowthSpeed
					);
					crop.matureStemReadyAtAbsoluteDayTime = addAbsoluteDayTime(
						currentAbsoluteDayTime,
						matureStemCooldownTicks
					);
					crop.lastProcessedAbsoluteDayTime = Math.max(crop.lastProcessedAbsoluteDayTime, currentAbsoluteDayTime);
					BlockState matureStemState = resolveMatureStemState(rule);
					if (matureStemState != null && isCropGrowthBlock(state, rule) && !matureStemState.equals(state)) {
						world.setBlockAndUpdate(cropPos, matureStemState);
						dirty = true;
					}
					dirty = true;
					return null;
				}
				if (currentAbsoluteDayTime < crop.matureStemReadyAtAbsoluteDayTime) {
					return null;
				}

				BlockPos fruitPos = resolveAdjacentStemFruitPosition(world, cropPos, rule);
				Block attachedStemBlock = resolveAttachedStemBlock(rule);
				if (fruitPos != null && rule.matureBlock() != null && attachedStemBlock != null) {
					world.setBlockAndUpdate(fruitPos, rule.matureBlock().defaultBlockState());
					BlockState attachedStemState = attachedStemBlock.defaultBlockState();
					if (attachedStemState.hasProperty(HorizontalDirectionalBlock.FACING)) {
						attachedStemState = attachedStemState.setValue(
							HorizontalDirectionalBlock.FACING,
							fruitPos.getX() > cropPos.getX()
								? Direction.EAST
								: fruitPos.getX() < cropPos.getX()
									? Direction.WEST
									: fruitPos.getZ() > cropPos.getZ()
										? Direction.SOUTH
										: Direction.NORTH
						);
					}
					world.setBlockAndUpdate(cropPos, attachedStemState);
					crop.clearMatureStemCooldown();
					dirty = true;
				} else if (fruitPos == null) {
					// Keep the stem due for a cheap retry. Another stem may have
					// claimed one shared space, while another valid space remains
					// available after the next block update.
					crop.matureStemReadyAtAbsoluteDayTime = currentAbsoluteDayTime;
				} else if (attachedStemBlock == null && !isCropMatureBlock(state, rule) && rule.matureBlock() != null) {
					// Preserve the existing fallback for distinct mature blocks that do
					// not have a vanilla attached-stem block.
					ChunkDataAPIManager.removePlayerPlacedBlock(world, cropPos);
					world.setBlockAndUpdate(cropPos, rule.matureBlock().defaultBlockState());
					dirty = true;
				}
				crop.progressGrowthTicks = required;
				return null;
			}
			if (isCropGrowthBlock(state, rule) && targetAge > getCropAge(state)) {
				BlockState updated = setCropAge(state, targetAge);
				if (updated != state) {
					world.setBlockAndUpdate(cropPos, updated);
					dirty = true;
				}
			}
			return null;
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
		return null;
	}

	private static String levelId(ServerLevel world) {
		if (world == null) {
			return "";
		}
		return ChunkAPIManager.normalizeLevelIdentifier(world.dimension().toString());
	}

	private static PlotState findPlot(ServerLevel world, BlockPos soilPos) {
		if (world == null || soilPos == null) {
			return null;
		}
		return plotsByKey.get(plotKey(world, soilPos));
	}

	private static boolean isPlotFertilized(ServerLevel world, PlotState plot) {
		long currentAbsoluteDayTime = resolveAbsoluteDayTime(world);
		long durationTicks = resolveFertilizerDurationTicks();
		if (plot == null || !plot.fertilized) {
			return false;
		}

		long elapsedTicks = currentAbsoluteDayTime - plot.fertilizedAtAbsoluteDayTime;
		if (currentAbsoluteDayTime < plot.fertilizedAtAbsoluteDayTime) {
			plot.fertilizedAtAbsoluteDayTime = currentAbsoluteDayTime;
			dirty = true;
			return true;
		}
		if (durationTicks > 0L && elapsedTicks < durationTicks) {
			return true;
		}

		plot.fertilized = false;
		plot.fertilizedAtAbsoluteDayTime = Long.MIN_VALUE;
		dirty = true;
		if (world != null) {
			FarmingPlotIndicatorManager.invalidatePlot(world, plot.soilPos);
		}
		if (!plot.hasCrop()) {
			removePlotStateByKey(plot.key());
		}
		return false;
	}

	private static PlotState getOrCreatePlot(ServerLevel world, BlockPos soilPos) {
		if (world == null || soilPos == null) {
			return null;
		}
		LOADED_CHUNK_KEYS.add(cropChunkKey(world, soilPos.getX() >> 4, soilPos.getZ() >> 4));

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
		FarmingPlotIndicatorManager.invalidatePlot(world, soilPos.asLong());
		if (removed.hasCrop()) {
			removeCropStateByKey(cropKey(world, BlockPos.of(removed.cropPos)));
		}
		dirty = true;
	}

	private static CropState findCrop(ServerLevel world, BlockPos cropPos) {
		if (world == null || cropPos == null) {
			return null;
		}
		CropState direct = cropsByKey.get(cropKey(world, cropPos));
		if (direct != null) {
			return direct;
		}
		CropRule rule = resolveCropRuleByCropState(world.getBlockState(cropPos));
		BlockPos trackedCropPos = resolveTrackedCropPosition(world, cropPos, rule);
		return cropsByKey.get(cropKey(world, trackedCropPos));
	}

	private static PlotState putPlotState(String key, PlotState value) {
		PlotState previous = plotsByKey.put(key, value);
		if (previous != null && (value == null || !plotChunkKey(previous).equals(plotChunkKey(value)))) {
			removePlotKeyFromChunk(previous);
		}
		if (value != null) {
			plotKeysByChunk.computeIfAbsent(plotChunkKey(value), ignored -> new HashSet<>()).add(key);
		}
		return previous;
	}

	private static PlotState removePlotStateByKey(String key) {
		PlotState removed = plotsByKey.remove(key);
		if (removed != null) {
			removePlotKeyFromChunk(removed);
		}
		return removed;
	}

	private static void removePlotKeyFromChunk(PlotState plot) {
		if (plot == null) {
			return;
		}
		String chunkKey = plotChunkKey(plot);
		Set<String> keys = plotKeysByChunk.get(chunkKey);
		if (keys == null || !keys.remove(plot.key())) {
			return;
		}
		if (keys.isEmpty()) {
			plotKeysByChunk.remove(chunkKey);
		}
	}

	private static CropState putCropState(String key, CropState value) {
		CropState previous = cropsByKey.put(key, value);
		if (previous != null && (value == null || !cropChunkKey(previous).equals(cropChunkKey(value)))) {
			invalidateCropDueEntry(previous);
			removeCropKeyFromChunk(previous);
		}
		if (value != null) {
			cropKeysByChunk.computeIfAbsent(cropChunkKey(value), ignored -> new HashSet<>()).add(key);
		}
		return previous;
	}

	private static CropState removeCropStateByKey(String key) {
		CropState removed = cropsByKey.remove(key);
		if (removed != null) {
			invalidateCropDueEntry(removed);
			removeCropKeyFromChunk(removed);
		}
		return removed;
	}

	private static void invalidateCropDueEntry(CropState crop) {
		if (crop == null) {
			return;
		}
		Map<String, Long> versions = cropDueVersionsByChunk.get(cropChunkKey(crop));
		if (versions != null) {
			versions.remove(crop.key());
		}
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

	private static String plotChunkKey(PlotState plot) {
		return plot == null ? "" : plotChunkKey(plot.levelId, BlockPos.getX(plot.soilPos) >> 4, BlockPos.getZ(plot.soilPos) >> 4);
	}

	private static String plotChunkKey(ServerLevel world, int chunkX, int chunkZ) {
		return plotChunkKey(levelId(world), chunkX, chunkZ);
	}

	private static String plotChunkKey(String levelId, int chunkX, int chunkZ) {
		return (levelId == null ? "" : levelId) + "|" + chunkX + "|" + chunkZ;
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
			return TimeAPIManager.getCurrentAbsoluteDayTime();
		}
		return TimeAPIManager.getCurrentAbsoluteDayTime(world);
	}

	private static boolean isAbsoluteTimeRollback(long previousAbsoluteDayTime, long currentAbsoluteDayTime) {
		long previous = Math.max(0L, previousAbsoluteDayTime);
		long current = Math.max(0L, currentAbsoluteDayTime);
		return previous >= current && previous - current > ABSOLUTE_TIME_ROLLBACK_RESET_TICKS;
	}

	private static boolean normalizeCropAbsoluteTimers(CropState crop, long currentAbsoluteDayTime) {
		if (crop == null || !isAbsoluteTimeRollback(crop.lastProcessedAbsoluteDayTime, currentAbsoluteDayTime)) {
			return false;
		}
		rebaseCropAbsoluteTimers(
			crop,
			crop.lastProcessedAbsoluteDayTime,
			currentAbsoluteDayTime
		);
		return true;
	}

	private static void rebaseAbsoluteTimersAfterRollback(
		ServerLevel world,
		long previousAbsoluteDayTime,
		long currentAbsoluteDayTime
	) {
		if (world == null || !isAbsoluteTimeRollback(previousAbsoluteDayTime, currentAbsoluteDayTime)) {
			return;
		}

		String currentLevelId = levelId(world);
		for (CropState crop : cropsByKey.values()) {
			if (crop == null || !currentLevelId.equals(crop.levelId)) {
				continue;
			}
			rebaseCropAbsoluteTimers(crop, previousAbsoluteDayTime, currentAbsoluteDayTime);
			FarmingPlotIndicatorManager.invalidatePlot(world, crop.soilPos);
		}

		String levelPrefix = currentLevelId + "|";
		cropDueQueuesByChunk.keySet().removeIf(key -> key != null && key.startsWith(levelPrefix));
		cropDueVersionsByChunk.keySet().removeIf(key -> key != null && key.startsWith(levelPrefix));
		for (CropState crop : cropsByKey.values()) {
			if (crop != null && currentLevelId.equals(crop.levelId)) {
				scheduleCropDue(world, crop);
			}
		}

		dirty = true;
	}

	private static void rebaseCropAbsoluteTimers(
		CropState crop,
		long previousAbsoluteDayTime,
		long currentAbsoluteDayTime
	) {
		if (crop == null) {
			return;
		}

		long previous = Math.max(0L, previousAbsoluteDayTime);
		long current = Math.max(0L, currentAbsoluteDayTime);
		crop.matureStemReadyAtAbsoluteDayTime = rebaseAbsoluteDeadline(
			crop.matureStemReadyAtAbsoluteDayTime,
			previous,
			current,
			current
		);
		crop.rainBoostExpiresAtAbsoluteDayTime = rebaseAbsoluteDeadline(
			crop.rainBoostExpiresAtAbsoluteDayTime,
			previous,
			current,
			Long.MIN_VALUE
		);
		crop.lastProcessedAbsoluteDayTime = current;
	}

	private static long rebaseAbsoluteDeadline(
		long deadline,
		long previousAbsoluteDayTime,
		long currentAbsoluteDayTime,
		long expiredValue
	) {
		if (deadline == Long.MIN_VALUE) {
			return Long.MIN_VALUE;
		}
		if (deadline <= previousAbsoluteDayTime) {
			return expiredValue;
		}
		return addAbsoluteDayTime(currentAbsoluteDayTime, deadline - previousAbsoluteDayTime);
	}

	private static long normalizePreviousAbsoluteTick(long previousAbsoluteTick, long currentAbsoluteTick) {
		long safePrevious = Math.max(0L, previousAbsoluteTick);
		long safeCurrent = Math.max(0L, currentAbsoluteTick);
		if (safePrevious > safeCurrent + ABSOLUTE_TIME_ROLLBACK_RESET_TICKS) {
			return safeCurrent;
		}
		return safePrevious;
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
				JsonObject normalized = JSONFormatAPIManager.ensureManagedFile(file, defaults);
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

		installCropRules(plantingRules);
	}

	private static void installCropRules(Map<String, CropRule> plantingRules) {
		Map<String, CropRule> safeRules = plantingRules == null ? Map.of() : plantingRules;
		Map<Item, CropRule> plantingItems = new HashMap<>();
		Map<Block, CropRule> blocks = new HashMap<>();
		Map<Block, CropRule> growthBlocks = new HashMap<>();
		Map<Block, CropRule> matureBlocks = new HashMap<>();
		for (CropRule rule : safeRules.values()) {
			Item item = resolveItemByRegistryId(rule.plantingItemId());
			if (item != null) {
				plantingItems.put(item, rule);
			}
			Block cropBlock = resolveBlockByRegistryId(rule.cropBlockId());
			if (cropBlock != null) {
				blocks.put(cropBlock, rule);
				growthBlocks.put(cropBlock, rule);
			}
			Block attachedStemBlock = resolveAttachedStemBlock(rule);
			if (attachedStemBlock != null) {
				blocks.put(attachedStemBlock, rule);
				growthBlocks.put(attachedStemBlock, rule);
			}
			if (rule.usesDistinctMatureBlock()) {
				Block matureBlock = resolveBlockByRegistryId(rule.matureBlockId());
				if (matureBlock != null) {
					blocks.put(matureBlock, rule);
					matureBlocks.put(matureBlock, rule);
				}
			}
		}

		cropRulesByPlantingItemId = Map.copyOf(safeRules);
		cropRulesByPlantingItem = Map.copyOf(plantingItems);
		cropRulesByBlock = Map.copyOf(blocks);
		cropRulesByGrowthBlock = Map.copyOf(growthBlocks);
		cropRulesByMatureBlock = Map.copyOf(matureBlocks);
	}

	private static void captureClientSyncState() {
		if (clientSynchronized) return;
		savedClientSettings = settings;
		savedClientCropRules = cropRulesByPlantingItemId;
	}

	private static void captureClientComponents(Iterable<CropRule> rules) {
		Map<Item, DataComponentMap> captured = new LinkedHashMap<>(savedClientComponents);
		if (rules != null) {
			for (CropRule rule : rules) {
				if (rule == null) continue;
				Item item = resolveItemByRegistryId(rule.plantingItemId());
				if (item != null) captured.putIfAbsent(item, item.components());
			}
		}
		Item boneMeal = BuiltInRegistries.ITEM.getValue(Identifier.tryParse("minecraft:bone_meal"));
		if (boneMeal != null) captured.putIfAbsent(boneMeal, boneMeal.components());
		savedClientComponents = Map.copyOf(captured);
	}

	private static void restoreClientComponents() {
		for (Map.Entry<Item, DataComponentMap> entry : savedClientComponents.entrySet()) {
			Item item = entry.getKey();
			if (item == null) continue;
			((ItemComponentsAccessor) ((ItemBuiltInRegistryHolderAccessor) item).madokuCraft$getBuiltInRegistryHolder())
				.madokuCraft$bindComponents(entry.getValue());
		}
	}

	private static JsonObject readObject(JsonObject source, String key) {
		JsonElement element = source == null ? null : source.get(key);
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
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
		if (isDirectlyPlacedMatureCrop(world, cropPos, state)) {
			return null;
		}
		return stateRule;
	}

	/**
	 * Mature melon/pumpkin blocks placed directly by a player are decorations,
	 * not the mature result of a managed stem. Seed-planted crops also carry the
	 * player-placed marker, so only distinct mature blocks are excluded here.
	 */
	private static boolean isDirectlyPlacedMatureCrop(ServerLevel world, BlockPos cropPos, BlockState state) {
		if (world == null || cropPos == null || state == null
			|| !ChunkDataAPIManager.isPlayerPlacedBlock(world, cropPos)) {
			return false;
		}
		CropRule rule = resolveCropRuleByCropState(state);
		return rule != null && rule.usesDistinctMatureBlock() && isCropMatureBlock(state, rule);
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
					|| text.startsWith("Increases crop growth speed")) {
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

	private static PendingHarvestRule resolvePendingHarvest(ServerLevel world, BlockPos cropPos) {
		if (world == null || cropPos == null) {
			return null;
		}
		String pendingKey = resolvePendingHarvestKey(world, cropPos);
		PendingHarvestRule pending = pendingKey == null ? null : pendingHarvestRulesByKey.get(pendingKey);
		if (pending == null) {
			return null;
		}
		long now = TimeAPIManager.getGameplayTicks();
		if (pending.expiresAtGameplayTick < now) {
			pendingHarvestRulesByKey.remove(pendingKey);
			return null;
		}
		return pending;
	}

	private static CropRule resolvePendingHarvestRule(ServerLevel world, BlockPos cropPos) {
		PendingHarvestRule pending = resolvePendingHarvest(world, cropPos);
		return pending == null ? null : pending.rule;
	}

	private static boolean isCropBlock(BlockState state, CropRule rule) {
		return state != null && rule != null
			&& (cropRulesByBlock.get(state.getBlock()) == rule || isCropAttachedStemBlock(state, rule));
	}

	private static boolean isCropGrowthBlock(BlockState state, CropRule rule) {
		return state != null && rule != null
			&& (cropRulesByGrowthBlock.get(state.getBlock()) == rule || isCropAttachedStemBlock(state, rule));
	}

	private static boolean isCropMatureBlock(BlockState state, CropRule rule) {
		return state != null && rule != null && rule.usesDistinctMatureBlock()
			&& cropRulesByMatureBlock.get(state.getBlock()) == rule;
	}

	private static void markPendingHarvest(ServerLevel world, BlockPos cropPos, CropRule rule) {
		if (world == null || cropPos == null || rule == null) {
			return;
		}

		String key = cropKey(world, cropPos);
		long now = TimeAPIManager.getGameplayTicks();
		String existingKey = resolvePendingHarvestKey(world, cropPos);
		PendingHarvestRule existing = existingKey == null ? null : pendingHarvestRulesByKey.get(existingKey);
		if (existing != null) {
			if (existing.expiresAtGameplayTick >= now) {
				return;
			}
			pendingHarvestRulesByKey.remove(existingKey);
		}

		BlockState observedState = world.getBlockState(cropPos);
		BlockPos trackedCropPos = resolveTrackedCropPosition(world, cropPos, rule, observedState);
		long expiresAtGameplayTick = now + PENDING_HARVEST_TTL_TICKS;
		pendingHarvestRulesByKey.put(
			key,
			new PendingHarvestRule(
				rule,
				expiresAtGameplayTick,
				cropPos.asLong(),
				trackedCropPos.asLong(),
				observedState,
				false,
				false,
				1.0d
			)
		);
	}

	private static void markPendingHarvestYieldApplied(ServerLevel world, BlockPos cropPos) {
		String key = resolvePendingHarvestKey(world, cropPos);
		if (key == null) {
			return;
		}
		PendingHarvestRule pending = pendingHarvestRulesByKey.get(key);
		if (pending == null || pending.completed || pending.yieldApplied) {
			return;
		}
		pendingHarvestRulesByKey.put(
			key,
			new PendingHarvestRule(
				pending.rule,
				pending.expiresAtGameplayTick,
				pending.produceCropPos,
				pending.trackedCropPos,
				pending.harvestState,
				true,
				false,
				pending.completedYieldMultiplier
			)
		);
	}

	private static String resolvePendingHarvestKey(ServerLevel world, BlockPos cropPos) {
		if (world == null || cropPos == null) {
			return null;
		}
		String directKey = cropKey(world, cropPos);
		if (pendingHarvestRulesByKey.containsKey(directKey)) {
			return directKey;
		}
		CropRule stateRule = resolveCropRuleByCropState(world.getBlockState(cropPos));
		BlockPos trackedCropPos = resolveTrackedCropPosition(world, cropPos, stateRule);
		String trackedKey = cropKey(world, trackedCropPos);
		if (pendingHarvestRulesByKey.containsKey(trackedKey)) {
			return trackedKey;
		}

		long cropPosition = cropPos.asLong();
		String levelPrefix = levelId(world) + "|";
		for (Map.Entry<String, PendingHarvestRule> entry : pendingHarvestRulesByKey.entrySet()) {
			if (!entry.getKey().startsWith(levelPrefix)) {
				continue;
			}
			PendingHarvestRule pending = entry.getValue();
			if (pending != null && (pending.produceCropPos == cropPosition || pending.trackedCropPos == cropPosition)) {
				return entry.getKey();
			}
		}
		return null;
	}

	private static void purgeExpiredPendingHarvestRules() {
		long now = TimeAPIManager.getGameplayTicks();
		Iterator<Map.Entry<String, PendingHarvestRule>> iterator = pendingHarvestRulesByKey.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<String, PendingHarvestRule> entry = iterator.next();
			PendingHarvestRule pending = entry.getValue();
			if (pending == null || pending.expiresAtGameplayTick < now) {
				iterator.remove();
			}
		}
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
		return JSONAPIManager.normalizeRegistryIdentifierForLookup(value);
	}

	private static double resolveCropRequiredGrowthTicks(CropRule rule) {
		return Math.max(1.0d, rule.growthMinecraftDays() * TimeAPIManager.MINECRAFT_TICKS_PER_CYCLE);
	}

	private static long resolveMatureStemCooldownTicks(
		CropState crop,
		CropRule rule,
		double growthSpeedMultiplier
	) {
		if (crop == null || rule == null) {
			return 1L;
		}
		Block stemBlock = resolveBlockByRegistryId(rule.cropBlockId());
		BlockState stemState = stemBlock == null ? null : stemBlock.defaultBlockState();
		int ageLimit = Math.max(1, getCropAgeLimit(stemState));
		double baseStageDuration = Math.max(1.0d, crop.requiredGrowthTicks) / ageLimit;
		double safeGrowthSpeed = Double.isFinite(growthSpeedMultiplier) && growthSpeedMultiplier > 0.0d
			? growthSpeedMultiplier
			: 1.0d;
		double stageDuration = baseStageDuration / safeGrowthSpeed;
		return !Double.isFinite(stageDuration) || stageDuration >= Long.MAX_VALUE
			? Long.MAX_VALUE
			: Math.max(1L, (long) Math.ceil(stageDuration));
	}

	private static long addAbsoluteDayTime(long current, long duration) {
		if (duration >= Long.MAX_VALUE || current >= Long.MAX_VALUE - duration) {
			return Long.MAX_VALUE;
		}
		return Math.max(0L, current) + Math.max(1L, duration);
	}

	private static long resolveFertilizerDurationTicks() {
		double durationTicks = settings.fertilizerDurationDays() * TimeAPIManager.MINECRAFT_TICKS_PER_CYCLE;
		if (!Double.isFinite(durationTicks) || durationTicks <= 0.0d) {
			return 0L;
		}
		return durationTicks >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(1L, (long) Math.ceil(durationTicks));
	}

	private static double resolveGrowthSpeedMultiplier(
		CropRule rule,
		ServerLevel world,
		BlockPos cropPos,
		CropState crop
	) {
		return resolveGrowthSpeedMultiplier(
			rule,
			world,
			cropPos,
			isVerticalCrop(rule) ? resolveVerticalCropGeometry(world, cropPos, rule) : null,
			crop
		);
	}

	private static double resolveGrowthSpeedMultiplier(
		CropRule rule,
		ServerLevel world,
		BlockPos cropPos,
		VerticalCropGeometry geometry,
		CropState crop
	) {
		if (rule == null || world == null || cropPos == null) {
			return 0.0d;
		}
		if (geometry != null && geometry.growthBlocked()) {
			return 0.0d;
		}

		BlockPos soilPos = cropPos.below();
		BlockState soilState = world.getBlockState(soilPos);
		PlotState plot = findPlot(world, soilPos);
		double speedMultiplier = 1.0d + resolveGrowingConditionsSpeedModifier(rule, world, cropPos);
		if (plot != null && isPlotFertilized(world, plot)) {
			speedMultiplier += settings.fertilizedGrowthBoost;
		}
		if (crop == null ? world.isRainingAt(cropPos) : refreshCropRainBoost(world, cropPos, crop)) {
			speedMultiplier += settings.rainGrowthBoost;
		}
		if (isDryFarmland(soilState)) {
			speedMultiplier *= Math.max(0.0d, 1.0d - settings.dryFarmlandPenalty);
		}
		return quantizeGrowthSpeedMultiplier(speedMultiplier);
	}

	private static double quantizeGrowthSpeedMultiplier(double speedMultiplier) {
		if (!Double.isFinite(speedMultiplier) || speedMultiplier <= 0.0d) {
			return 0.0d;
		}
		return Math.max(
			0.0d,
			Math.round(speedMultiplier / GROWTH_SPEED_STEP) * GROWTH_SPEED_STEP
		);
	}

	private static double resolveGrowingConditionsSpeedModifier(CropRule rule, ServerLevel world, BlockPos cropPos) {
		if (rule == null || world == null || cropPos == null || rule.growingConditions() == null) {
			return 0.0d;
		}

		SeasonBiomeClimateAPIManager.Climate climate = SeasonAPIManager.resolveBiomeClimate(world, cropPos);
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

	private static boolean getBoolean(CompoundTag tag, String key, boolean fallback) {
		try { return tag != null ? tag.getBoolean(key).orElse(fallback) : fallback; }
		catch (RuntimeException exception) { return fallback; }
	}

	private static long getLong(CompoundTag tag, String key, long fallback) {
		try { return tag != null ? tag.getLong(key).orElse(fallback) : fallback; }
		catch (RuntimeException exception) { return fallback; }
	}

	private static double getDouble(CompoundTag tag, String key, double fallback) {
		try { return tag != null ? tag.getDouble(key).orElse(fallback) : fallback; }
		catch (RuntimeException exception) { return fallback; }
	}

	private static String getString(CompoundTag tag, String key, String fallback) {
		try { return tag != null ? tag.getString(key).orElse(fallback) : fallback; }
		catch (RuntimeException exception) { return fallback; }
	}

	private static boolean getBoolean(JsonObject object, String key, boolean fallback) {
		if (object == null) {
			return fallback;
		}
		JsonElement element = object.get(key);
		if (element == null || !element.isJsonPrimitive()) {
			return fallback;
		}

		var primitive = element.getAsJsonPrimitive();
		return primitive.isBoolean() ? primitive.getAsBoolean() : fallback;
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

	private static int clampInt(int value, int fallback, int min, int max) {
		if (value < min || value > max) {
			return fallback;
		}
		return value;
	}


	private record PendingHarvestRule(
		CropRule rule,
		long expiresAtGameplayTick,
		long produceCropPos,
		long trackedCropPos,
		BlockState harvestState,
		boolean yieldApplied,
		boolean completed,
		double completedYieldMultiplier
	) {
	}

	private record VerticalCropGeometry(BlockPos basePos, int height, boolean growthBlocked) { }

	private static final class PlotState {
		private final String levelId;
		private final long soilPos;
		private long cropPos;
		private String cropId;
		private boolean fertilized;
		private long fertilizedAtAbsoluteDayTime;

		private PlotState(String levelId, long soilPos) {
			this.levelId = levelId == null ? "" : levelId;
			this.soilPos = soilPos;
			this.cropPos = -1L;
			this.cropId = "";
			this.fertilized = false;
			this.fertilizedAtAbsoluteDayTime = Long.MIN_VALUE;
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

		private CompoundTag toNbt() {
			CompoundTag data = new CompoundTag();
			data.putString(FIELD_LEVEL_ID, levelId);
			data.putLong(FIELD_SOIL_POS, soilPos);
			data.putLong(FIELD_CROP_POS, cropPos);
			data.putString(FIELD_CROP_ID, cropId);
			data.putBoolean(FIELD_FERTILIZED, fertilized);
			data.putLong(FIELD_FERTILIZED_AT_ABSOLUTE_DAY_TIME, fertilizedAtAbsoluteDayTime);
			return data;
		}

		private static PlotState fromNbt(CompoundTag source) {
			if (source == null) {
				return null;
			}
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
			plot.fertilizedAtAbsoluteDayTime = getLong(source, FIELD_FERTILIZED_AT_ABSOLUTE_DAY_TIME, Long.MIN_VALUE);
			if (plot.fertilized && plot.fertilizedAtAbsoluteDayTime == Long.MIN_VALUE) {
				plot.fertilized = false;
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
		private long matureStemReadyAtAbsoluteDayTime;
		private boolean maturityYieldRecorded;
		private double maturityYieldMultiplier;
		private long rainBoostExpiresAtAbsoluteDayTime;

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
			this.matureStemReadyAtAbsoluteDayTime = Long.MIN_VALUE;
			this.maturityYieldRecorded = false;
			this.maturityYieldMultiplier = 1.0d;
			this.rainBoostExpiresAtAbsoluteDayTime = Long.MIN_VALUE;
		}

		private String key() {
			return levelId + "|" + cropPos;
		}

		private void clearMaturityYield() {
			this.maturityYieldRecorded = false;
			this.maturityYieldMultiplier = 1.0d;
		}

		private void clearRainBoost() {
			this.rainBoostExpiresAtAbsoluteDayTime = Long.MIN_VALUE;
		}

		private void clearMatureStemCooldown() {
			this.matureStemReadyAtAbsoluteDayTime = Long.MIN_VALUE;
		}

		private void recordMaturityYield(double multiplier) {
			if (maturityYieldRecorded) {
				return;
			}
			this.maturityYieldRecorded = true;
			this.maturityYieldMultiplier = Double.isFinite(multiplier)
				? Math.max(0.0d, multiplier)
				: 1.0d;
		}

		private CompoundTag toNbt() {
			CompoundTag data = new CompoundTag();
			data.putString(FIELD_LEVEL_ID, levelId);
			data.putLong(FIELD_CROP_POS, cropPos);
			data.putLong(FIELD_SOIL_POS, soilPos);
			data.putString(FIELD_CROP_ID, cropId);
			data.putDouble(FIELD_REQUIRED_GROWTH_TICKS, requiredGrowthTicks);
			data.putDouble(FIELD_PROGRESS_GROWTH_TICKS, progressGrowthTicks);
			data.putLong(FIELD_LAST_PROCESSED_ABSOLUTE_DAY_TIME, lastProcessedAbsoluteDayTime);
			data.putLong(FIELD_MATURE_STEM_READY_AT_ABSOLUTE_DAY_TIME, matureStemReadyAtAbsoluteDayTime);
			data.putBoolean(FIELD_MATURITY_YIELD_RECORDED, maturityYieldRecorded);
			data.putDouble(FIELD_MATURITY_YIELD_MULTIPLIER, maturityYieldMultiplier);
			data.putLong(FIELD_RAIN_BOOST_EXPIRES_AT_ABSOLUTE_DAY_TIME, rainBoostExpiresAtAbsoluteDayTime);
			return data;
		}

		private static CropState fromNbt(CompoundTag source) {
			if (source == null) {
				return null;
			}
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
			CropState crop = new CropState(
				levelId,
				cropPos,
				soilPos,
				cropId,
				requiredGrowthTicks,
				progressGrowthTicks,
				lastProcessedAbsoluteDayTime
			);
			if (getBoolean(source, FIELD_MATURITY_YIELD_RECORDED, false)) {
				crop.recordMaturityYield(getDouble(source, FIELD_MATURITY_YIELD_MULTIPLIER, 1.0d));
			}
			crop.rainBoostExpiresAtAbsoluteDayTime = getLong(
				source,
				FIELD_RAIN_BOOST_EXPIRES_AT_ABSOLUTE_DAY_TIME,
				Long.MIN_VALUE
			);
			crop.matureStemReadyAtAbsoluteDayTime = getLong(
				source,
				FIELD_MATURE_STEM_READY_AT_ABSOLUTE_DAY_TIME,
				Long.MIN_VALUE
			);
			return crop;
		}
	}

	private record Settings(
		boolean enabled,
		boolean farmIndicatorEnabled,
		double rainGrowthBoost,
		double fertilizedGrowthBoost,
		double fertilizerDurationDays,
		double dryFarmlandPenalty
	) {
		private static Settings defaults() {
			return new Settings(
				true,
				true,
				FarmingConfigManager.DEFAULT_RAIN_GROWTH_BOOST,
				FarmingConfigManager.DEFAULT_FERTILIZED_GROWTH_BOOST,
				FarmingConfigManager.DEFAULT_FERTILIZER_DURATION_DAYS,
				FarmingConfigManager.DEFAULT_DRY_FARMLAND_PENALTY
			);
		}

		private static Settings fromJson(JsonObject source) {
			boolean enabled = getBoolean(source, FarmingConfigManager.FIELD_ENABLED, true);
			boolean farmIndicatorEnabled = getBoolean(
				readObject(source, FarmingConfigManager.FIELD_FARM_INDICATOR),
				FarmingConfigManager.FIELD_ENABLED,
				true
			);
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
			double fertilizerDurationDays = clampDouble(
				getDouble(source, FarmingConfigManager.FIELD_FERTILIZER_DURATION_DAYS, FarmingConfigManager.DEFAULT_FERTILIZER_DURATION_DAYS),
				FarmingConfigManager.DEFAULT_FERTILIZER_DURATION_DAYS,
				0.0d,
				36500.0d
			);
			double dryFarmlandPenalty = clampDouble(
				getDouble(source, FarmingConfigManager.FIELD_DRY_FARMLAND_PENALTY, FarmingConfigManager.DEFAULT_DRY_FARMLAND_PENALTY),
				FarmingConfigManager.DEFAULT_DRY_FARMLAND_PENALTY,
				0.0d,
				1.0d
			);
			return new Settings(
				enabled,
				farmIndicatorEnabled,
				rainBoost,
				fertilizedBoost,
				fertilizerDurationDays,
				dryFarmlandPenalty
			);
		}

		private JsonObject toConfigJson() {
			return JSONFormatAPIManager.object()
				.put(FarmingConfigManager.FIELD_ENABLED, enabled)
				.object(FarmingConfigManager.FIELD_FARM_INDICATOR, indicator -> indicator.put(FarmingConfigManager.FIELD_ENABLED, farmIndicatorEnabled))
				.put(FarmingConfigManager.FIELD_RAIN_GROWTH_BOOST, rainGrowthBoost)
				.put(FarmingConfigManager.FIELD_FERTILIZED_GROWTH_BOOST, fertilizedGrowthBoost)
				.put(FarmingConfigManager.FIELD_FERTILIZER_DURATION_DAYS, fertilizerDurationDays)
				.put(FarmingConfigManager.FIELD_DRY_FARMLAND_PENALTY, dryFarmlandPenalty)
				.build();
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
		private final int maxHeight;
		private final Set<String> supportBlockIds;
		private final Set<String> supportTagIds;
		private final Set<Block> supportBlocks;
		private final Set<TagKey<Block>> supportTags;
		private final GrowingConditions growingConditions;
		private final Block matureBlock;
		private final boolean vanillaGrowth;
		private final double stemWiltChance;
		private final int harvestResetAge;

		private CropRule(
			String cropId,
			String cropBlockId,
			String matureBlockId,
			String plantingItemId,
			String yieldTableId,
			String displayName,
			double growthMinecraftDays,
			int maxHeight,
			Set<String> supportBlockIds,
			Set<String> supportTagIds,
			GrowingConditions growingConditions,
			Block matureBlock,
			boolean vanillaGrowth,
			double stemWiltChance,
			int harvestResetAge
		) {
			this.cropId = cropId;
			this.cropBlockId = cropBlockId;
			this.matureBlockId = matureBlockId;
			this.plantingItemId = plantingItemId;
			this.yieldTableId = yieldTableId == null ? "" : yieldTableId;
			this.displayName = displayName;
			this.growthMinecraftDays = growthMinecraftDays;
			this.maxHeight = Math.max(1, maxHeight);
			this.supportBlockIds = supportBlockIds == null ? Set.of() : Set.copyOf(supportBlockIds);
			this.supportTagIds = supportTagIds == null ? Set.of() : Set.copyOf(supportTagIds);
			this.supportBlocks = resolveSupportBlocks(this.supportBlockIds);
			this.supportTags = resolveSupportTags(this.supportTagIds);
			this.growingConditions = growingConditions;
			this.matureBlock = matureBlock;
			this.vanillaGrowth = vanillaGrowth;
			this.stemWiltChance = Math.max(0.0d, Math.min(1.0d, stemWiltChance));
			this.harvestResetAge = harvestResetAge;
		}

		private static List<CropRule> defaultRules() {
			return List.of(
				defaultPotato(),
				defaultCarrot(),
				defaultBeetroot(),
				defaultMelon(),
				defaultPumpkin(),
				defaultSweetBerries(),
				defaultWheat(),
				defaultSugarCane()
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
				case "sweet-berries", "sweet_berries", "minecraft:sweet_berries", "minecraft:sweet_berry_bush" -> defaultSweetBerries();
				case "wheat", "minecraft:wheat" -> defaultWheat();
				case "sugar-cane", "sugar_cane", "minecraft:sugar_cane" -> defaultSugarCane();
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
				4.0d,
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
				8.0d,
				"minecraft:blocks/melon",
				new GrowingConditions(50, 80, 35, 55),
				1,
				Set.of("minecraft:farmland"),
				Set.of(),
				CropsConfigManager.DEFAULT_STEM_WILT_CHANCE,
				false
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
				new GrowingConditions(45, 70, 40, 60),
				1,
				Set.of("minecraft:farmland"),
				Set.of(),
				CropsConfigManager.DEFAULT_STEM_WILT_CHANCE,
				false
			);
		}

		private static CropRule defaultSweetBerries() {
			return fromValues(
				"sweet_berries",
				"minecraft:sweet_berry_bush",
				"minecraft:sweet_berry_bush",
				"minecraft:sweet_berries",
				2.0d,
				"minecraft:blocks/sweet_berries",
				new GrowingConditions(10, 30, 60, 80),
				1,
				Set.of("minecraft:grass_block", "minecraft:coarse_dirt", "minecraft:podzol", "minecraft:farmland"),
				Set.of("minecraft:dirt"),
				CropsConfigManager.DEFAULT_STEM_WILT_CHANCE,
				false,
				1
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

		private static CropRule defaultSugarCane() {
			return fromValues(
				"sugar_cane",
				"minecraft:sugar_cane",
				"minecraft:sugar_cane",
				"minecraft:sugar_cane",
				6.0d,
				"",
				new GrowingConditions(60, 80, 10, 30),
				5,
				Set.of(
					"minecraft:grass_block",
					"minecraft:dirt",
					"minecraft:coarse_dirt",
					"minecraft:rooted_dirt",
					"minecraft:podzol",
					"minecraft:mycelium",
					"minecraft:sand",
					"minecraft:red_sand",
					"minecraft:suspicious_sand",
					"minecraft:moss_block",
					"minecraft:mud"
				),
				Set.of()
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
			int maxHeight = clampInt(
				(int) Math.round(getDouble(source, CropsConfigManager.FIELD_MAX_HEIGHT, fallback.maxHeight)),
				fallback.maxHeight,
				1,
				16
			);
			Set<String> supportBlockIds = parseSupportIds(
				source,
				CropsConfigManager.FIELD_SUPPORT_BLOCKS,
				fallback.supportBlockIds
			);
			Set<String> supportTagIds = parseSupportIds(
				source,
				CropsConfigManager.FIELD_SUPPORT_TAGS,
				fallback.supportTagIds
			);
			double stemWiltChance = clampDouble(
				getDouble(source, CropsConfigManager.FIELD_STEM_WILT_CHANCE, fallback.stemWiltChance()),
				fallback.stemWiltChance(),
				0.0d,
				1.0d
			);
			int harvestResetAge = clampInt(
				(int) Math.round(getDouble(source, CropsConfigManager.FIELD_HARVEST_RESET_AGE, fallback.harvestResetAge)),
				fallback.harvestResetAge,
				-1,
				CROP_MAX_AGE
			);
			return fromValues(
				cropId,
				fallback.cropBlockId,
				fallback.matureBlockId,
				fallback.plantingItemId,
				growthDays,
				parseYieldTableId(source, fallback.yieldTableId),
				parseGrowingConditions(source, fallback.growingConditions),
				maxHeight,
				supportBlockIds,
				supportTagIds,
				stemWiltChance,
				fallback.usesVanillaGrowth(),
				harvestResetAge
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
			return fromValues(
				cropId,
				cropBlockId,
				matureBlockId,
				plantingItemId,
				growthMinecraftDays,
				yieldTableId,
				growingConditions,
				1,
				Set.of("minecraft:farmland"),
				Set.of(),
				0.0d
			);
		}

		private static CropRule fromValues(
			String cropId,
			String cropBlockId,
			String matureBlockId,
			String plantingItemId,
			double growthMinecraftDays,
			String yieldTableId,
			GrowingConditions growingConditions,
			int maxHeight,
			Set<String> supportBlockIds,
			Set<String> supportTagIds
		) {
			return fromValues(
				cropId,
				cropBlockId,
				matureBlockId,
				plantingItemId,
				growthMinecraftDays,
				yieldTableId,
				growingConditions,
				maxHeight,
				supportBlockIds,
				supportTagIds,
				0.0d
			);
		}

		private static CropRule fromValues(
			String cropId,
			String cropBlockId,
			String matureBlockId,
			String plantingItemId,
			double growthMinecraftDays,
			String yieldTableId,
			GrowingConditions growingConditions,
			int maxHeight,
			Set<String> supportBlockIds,
			Set<String> supportTagIds,
			double stemWiltChance
		) {
			return fromValues(
				cropId,
				cropBlockId,
				matureBlockId,
				plantingItemId,
				growthMinecraftDays,
				yieldTableId,
				growingConditions,
				maxHeight,
				supportBlockIds,
				supportTagIds,
				stemWiltChance,
				false,
				-1
			);
		}

		private static CropRule fromValues(
			String cropId,
			String cropBlockId,
			String matureBlockId,
			String plantingItemId,
			double growthMinecraftDays,
			String yieldTableId,
			GrowingConditions growingConditions,
			int maxHeight,
			Set<String> supportBlockIds,
			Set<String> supportTagIds,
			double stemWiltChance,
			boolean vanillaGrowth
		) {
			return fromValues(
				cropId,
				cropBlockId,
				matureBlockId,
				plantingItemId,
				growthMinecraftDays,
				yieldTableId,
				growingConditions,
				maxHeight,
				supportBlockIds,
				supportTagIds,
				stemWiltChance,
				vanillaGrowth,
				-1
			);
		}

		private static CropRule fromValues(
			String cropId,
			String cropBlockId,
			String matureBlockId,
			String plantingItemId,
			double growthMinecraftDays,
			String yieldTableId,
			GrowingConditions growingConditions,
			int maxHeight,
			Set<String> supportBlockIds,
			Set<String> supportTagIds,
			double stemWiltChance,
			boolean vanillaGrowth,
			int harvestResetAge
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
				maxHeight,
				normalizeSupportIds(supportBlockIds, false),
				normalizeSupportIds(supportTagIds, true),
				growingConditions,
				matureBlock,
				vanillaGrowth,
				stemWiltChance,
				harvestResetAge
			);
		}

		private static String parseYieldTableId(JsonObject source, String fallback) {
			String value = getString(source, CropsConfigManager.FIELD_YIELD_ID, fallback);
			String normalized = normalizeRegistryId(value);
			return normalized.isBlank() ? fallback : normalized;
		}

		private static Set<String> parseSupportIds(JsonObject source, String key, Set<String> fallback) {
			JsonElement element = source == null ? null : source.get(key);
			if (element == null || !element.isJsonArray()) {
				return fallback == null ? Set.of() : fallback;
			}

			Set<String> values = new LinkedHashSet<>();
			for (JsonElement entry : element.getAsJsonArray()) {
				if (entry == null || !entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
					continue;
				}
				String value = entry.getAsString();
				if (CropsConfigManager.FIELD_SUPPORT_TAGS.equals(key) && value != null && value.startsWith("#")) {
					value = value.substring(1);
				}
				String normalized = normalizeRegistryId(value);
				if (!normalized.isBlank()) {
					values.add(normalized);
				}
			}
			return values.isEmpty()
				? (fallback == null ? Set.of() : fallback)
				: Set.copyOf(values);
		}

		private static Set<String> normalizeSupportIds(Set<String> values, boolean tags) {
			Set<String> normalized = new LinkedHashSet<>();
			if (values != null) {
				for (String value : values) {
					if (tags && value != null && value.startsWith("#")) {
						value = value.substring(1);
					}
					String identifier = normalizeRegistryId(value);
					if (!identifier.isBlank()) {
						normalized.add(identifier);
					}
				}
			}
			return Set.copyOf(normalized);
		}

		private static Set<Block> resolveSupportBlocks(Set<String> supportIds) {
			Set<Block> resolved = new LinkedHashSet<>();
			if (supportIds != null) {
				for (String supportId : supportIds) {
					Block block = resolveBlockByRegistryId(supportId);
					if (block != null) {
						resolved.add(block);
					}
				}
			}
			return Set.copyOf(resolved);
		}

		private static Set<TagKey<Block>> resolveSupportTags(Set<String> supportIds) {
			Set<TagKey<Block>> resolved = new LinkedHashSet<>();
			if (supportIds != null) {
				for (String supportId : supportIds) {
					Identifier identifier = Identifier.tryParse(supportId);
					if (identifier != null) {
						resolved.add(TagKey.create(Registries.BLOCK, identifier));
					}
				}
			}
			return Set.copyOf(resolved);
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

		public int maxHeight() {
			return maxHeight;
		}

		public boolean usesVanillaGrowth() {
			return vanillaGrowth;
		}

		public double stemWiltChance() {
			return stemWiltChance;
		}

		public int harvestResetAge() {
			return harvestResetAge;
		}

		private Set<Block> supportBlocks() {
			return supportBlocks;
		}

		private Set<TagKey<Block>> supportTags() {
			return supportTags;
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

		private JsonObject toConfigJson() {
			GrowingConditions conditions = growingConditions == null
				? new GrowingConditions(40.0d, 60.0d, 40.0d, 60.0d)
				: growingConditions;
			return JSONFormatAPIManager.object()
				.put(CropsConfigManager.FIELD_CROP_ID, cropId)
				.put(CropsConfigManager.FIELD_GROWTH_TIME, growthMinecraftDays)
				.put(CropsConfigManager.FIELD_STEM_WILT_CHANCE, stemWiltChance)
				.put(CropsConfigManager.FIELD_HARVEST_RESET_AGE, harvestResetAge)
				.put(CropsConfigManager.FIELD_MAX_HEIGHT, maxHeight)
				.put(CropsConfigManager.FIELD_YIELD_ID, yieldTableId)
				.array(CropsConfigManager.FIELD_SUPPORT_BLOCKS, blocks -> {
					for (String supportBlockId : supportBlockIds) {
						blocks.add(supportBlockId);
					}
				})
				.array(CropsConfigManager.FIELD_SUPPORT_TAGS, tags -> {
					for (String supportTagId : supportTagIds) {
						tags.add(supportTagId);
					}
				})
				.object(CropsConfigManager.FIELD_GROWING_CONDITIONS, conditionsRoot -> conditionsRoot
					.object(CropsConfigManager.FIELD_IDEAL_TEMPERATURE, temperature -> temperature
						.put(CropsConfigManager.FIELD_MINIMUM_TEMPERATURE, conditions.minimumTemperature())
						.put(CropsConfigManager.FIELD_MAXIMUM_TEMPERATURE, conditions.maximumTemperature()))
					.object(CropsConfigManager.FIELD_IDEAL_HUMIDITY, humidity -> humidity
						.put(CropsConfigManager.FIELD_MINIMUM_HUMIDITY, conditions.minimumHumidity())
						.put(CropsConfigManager.FIELD_MAXIMUM_HUMIDITY, conditions.maximumHumidity())))
				.build();
		}
	}

	public record GrowingConditions(
		double minimumTemperature,
		double maximumTemperature,
		double minimumHumidity,
		double maximumHumidity
	) { }

}
