package madoku.craft.farming.system;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import madoku.craft.clock.MadokuTicks;
import madoku.craft.config.StaticJsonSystem;
import madoku.craft.data.MadokuData;
import madoku.craft.debug.MadokuDebug;
import madoku.craft.scheduler.MadokuScheduler;
import madoku.craft.season.MadokuSeason;
import madoku.craft.season.MadokuSeasonConfig;
import madoku.craft.time.MadokuTime;
import madoku.craft.farming.mixin.ItemBuiltInRegistryHolderAccessor;
import madoku.craft.farming.mixin.ItemComponentsAccessor;
import net.minecraft.ChatFormatting;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Set;

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
	private static final String FARMING_SCHEDULER_OWNER_ID = "farming_gameplay";
	private static final String TASK_TYPE_FARMING_TICK = "farming_gameplay_tick";
	private static final long FARMING_SCHEDULER_INTERVAL_TICKS = 20L;
	private static final String FIELD_LEVEL_ID = "level_id";
	private static final String FIELD_SOIL_POS = "soil_pos";
	private static final String FIELD_CROP_POS = "crop_pos";
	private static final String FIELD_CROP_ID = "crop_id";
	private static final String FIELD_FERTILIZED = "fertilized";
	private static final String FIELD_FERTILIZED_AT_ABSOLUTE_DAY_TIME = "fertilized_at_absolute_day_time";
	private static final String FIELD_GROWTH_PROGRESS = "growth_progress";
	private static final String FIELD_LAST_PARTICLE_EMISSION_TICKS = "last_particle_emission_ticks";
	private static final String FIELD_PLOTS = "plots";

	private static final long AUTOSAVE_INTERVAL_TICKS = 60L * 20L;
	private static final long FERTILIZATION_DECAY_TICKS = 3L * 24000L;
	private static final long CROP_MISSING_GRACE_TICKS = 20L;
	private static final long PARTICLE_COOLDOWN_MIN_TICKS = 100L;
	private static final long PARTICLE_COOLDOWN_MAX_TICKS = 180L;
	private static final int CROP_MAX_AGE = 7;

	private static volatile Settings settings = Settings.defaults();
	private static volatile String farmingSchedulerId = "";
	private static volatile boolean farmingTaskScheduled = false;
	private static volatile long lastProcessedAbsoluteDayTime = Long.MIN_VALUE;
	private static volatile long lastAutosaveBucket = Long.MIN_VALUE;
	private static volatile boolean dirty = false;
	private static volatile Map<String, CropRule> cropRulesByPlantingItemId = new LinkedHashMap<>();
	private static volatile Map<String, CropRule> cropRulesByCropBlockId = new LinkedHashMap<>();
	private static volatile Map<String, CropRule> cropRulesByMatureBlockId = new LinkedHashMap<>();
	private static final Map<String, PendingHarvestRule> pendingHarvestRulesByKey = new LinkedHashMap<>();
	private static final Map<String, PlotState> plotsByKey = new LinkedHashMap<>();
	private static final Map<String, PlotState> activePlotsByKey = new LinkedHashMap<>();

	private MadokuFarming() {
	}

	public static void initialize() {
		loadStaticConfig();
		loadCropConfigs();
		MadokuScheduler.registerTaskHandler(TASK_TYPE_FARMING_TICK, MadokuFarming::runFarmingTask);
		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) ->
			handleBlockBreakBefore(world, pos, state, blockEntity)
		);
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) ->
			handleBlockBreak(world, pos, state, blockEntity)
		);
	}

	public static void reset() {
		plotsByKey.clear();
		activePlotsByKey.clear();
		pendingHarvestRulesByKey.clear();
		farmingSchedulerId = "";
		farmingTaskScheduled = false;
		lastProcessedAbsoluteDayTime = Long.MIN_VALUE;
		lastAutosaveBucket = Long.MIN_VALUE;
		dirty = false;
	}

	public static void onServerStarted(MinecraftServer server) {
		lastProcessedAbsoluteDayTime = MadokuTime.getCurrentAbsoluteDayTime(server.overworld());
		farmingSchedulerId = MadokuScheduler.createOrGetScheduler(MadokuScheduler.SchedulerOwner.global(FARMING_SCHEDULER_OWNER_ID));
		MadokuScheduler.clearQueuedRequests(farmingSchedulerId);
		requestFarmingProcessing(server, FARMING_SCHEDULER_INTERVAL_TICKS);
	}

	public static void onServerTickIncrement(MinecraftServer server, long tickIncrement) {
		// Farming progression is driven by the time-domain scheduler.
	}

	public static void loadPersistedData(MinecraftServer server) {
		if (server == null) {
			return;
		}

		loadStaticConfig();
		loadCropConfigs();
		MadokuData.createWorldData(server, DATA_FOLDER_NAME, DATA_FILE_NAME, createDefaultData());
		JsonObject data = MadokuData.loadWorldData(server, DATA_FOLDER_NAME, DATA_FILE_NAME);
		applyPersistedData(data);
		lastProcessedAbsoluteDayTime = MadokuTime.getCurrentAbsoluteDayTime(server.overworld());
		lastAutosaveBucket = Math.floorDiv(MadokuTicks.getGameplayTicks(), AUTOSAVE_INTERVAL_TICKS);
		dirty = false;
	}

	public static void autosavePersistedData(MinecraftServer server) {
		if (server == null) {
			return;
		}

		long bucket = Math.floorDiv(MadokuTicks.getGameplayTicks(), AUTOSAVE_INTERVAL_TICKS);
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

		MadokuData.saveWorldData(server, DATA_FOLDER_NAME, DATA_FILE_NAME, toPersistedData());
		dirty = false;
	}

	public static boolean isEnabled() {
		return settings.enabled;
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
		return getCropSeasonBlockedMessage(rule);
	}

	public static String getCropSeasonBlockedMessage(ItemStack stack, ServerLevel world) {
		CropRule rule = resolveCropRuleByPlantingItem(stack);
		return getCropSeasonBlockedMessage(rule, world);
	}

	public static String getCropSeasonBlockedMessage(ItemStack stack, String seasonId) {
		CropRule rule = resolveCropRuleByPlantingItem(stack);
		return getCropSeasonBlockedMessage(rule, seasonId);
	}

	public static boolean isFarmland(BlockState state) {
		return state != null && state.getBlock() == Blocks.FARMLAND;
	}

	public static boolean isManagedCrop(BlockState state) {
		return resolveCropRuleByCropState(state) != null;
	}

	public static boolean isManagedCrop(ServerLevel world, BlockPos cropPos, BlockState state) {
		return resolveManagedCropRule(world, cropPos, state) != null;
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
				plot.fertilizedAtAbsoluteDayTime = MadokuTime.getCurrentAbsoluteDayTime(world);
			plot.lastParticleEmissionTimeTicks = Long.MIN_VALUE;
			dirty = true;
			emitFarmingDebug(
				"farming.fertilize",
				world,
				soilPos,
				"soil:" + soilPos.getX() + "," + soilPos.getY() + "," + soilPos.getZ(),
				Map.of(
					"fertilized", "true",
					"crop_above", Boolean.toString(isManagedCrop(world.getBlockState(soilPos.above())))
				)
			);
		}
		refreshPlotActivity(world, soilPos, plot);
		requestFarmingProcessing(world.getServer(), FARMING_SCHEDULER_INTERVAL_TICKS);
		emitFertilizedParticles(world, soilPos, plot);
	}

	public static void syncPlotFromSoil(ServerLevel world, BlockPos soilPos, boolean fertilized) {
		if (!settings.enabled || world == null || soilPos == null) {
			return;
		}

		BlockState soilState = world.getBlockState(soilPos);
		if (!isFarmland(soilState)) {
			return;
		}

		BlockPos cropPos = soilPos.above();
		BlockState cropState = world.getBlockState(cropPos);
		CropRule rule = resolveCropRuleByCropState(cropState);
		if (!fertilized && (rule == null || !isCropBlock(cropState, rule))) {
			return;
		}

		PlotState plot = getOrCreatePlot(world, soilPos);
		if (plot == null) {
			return;
		}

		boolean changed = false;
		if (fertilized && !plot.fertilized) {
			plot.fertilized = true;
			plot.fertilizedAtAbsoluteDayTime = MadokuTime.getCurrentAbsoluteDayTime(world);
			plot.lastParticleEmissionTimeTicks = Long.MIN_VALUE;
			changed = true;
		}

		if (rule != null && isCropBlock(cropState, rule)) {
			if (plot.cropPos != cropPos.asLong()) {
				plot.cropPos = cropPos.asLong();
				changed = true;
			}
			if (!rule.plantingItemId().equals(plot.cropId)) {
				plot.cropId = rule.plantingItemId();
				changed = true;
			}
			double currentProgress = progressFromAge(getCropAge(cropState), getCropAgeLimit(cropState));
			if (currentProgress > plot.growthProgress) {
				plot.growthProgress = currentProgress;
				changed = true;
			}
			plot.missingCropSinceGameplayTicks = Long.MIN_VALUE;
		}

		if (plot.hasCrop()) {
			plot.fertilizedAtAbsoluteDayTime = Long.MIN_VALUE;
		}

		refreshPlotActivity(world, soilPos, plot);
		if (changed) {
			dirty = true;
			requestFarmingProcessing(world.getServer(), FARMING_SCHEDULER_INTERVAL_TICKS);
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

		BlockPos cropPos = soilPos.above();
		BlockState cropState = world.getBlockState(cropPos);
		if (!isCropBlock(cropState, rule)) {
			return;
		}

		PlotState plot = getOrCreatePlot(world, soilPos);
		if (plot == null) {
			return;
		}

		plot.cropPos = cropPos.asLong();
		plot.cropId = rule.plantingItemId();
		plot.growthProgress = 0.0d;
		plot.fertilizedAtAbsoluteDayTime = Long.MIN_VALUE;
		pendingHarvestRulesByKey.remove(cropKey(world, cropPos));
		refreshPlotActivity(world, soilPos, plot);
		dirty = true;
		requestFarmingProcessing(world.getServer(), FARMING_SCHEDULER_INTERVAL_TICKS);
	}

	public static void trackCrop(ServerLevel world, BlockPos cropPos, BlockState cropState) {
		CropRule rule = resolveCropRuleByCropState(cropState);
		if (!settings.enabled || world == null || cropPos == null || rule == null || !isCropBlock(cropState, rule)) {
			return;
		}

		BlockPos soilPos = cropPos.below();
		PlotState plot = getOrCreatePlot(world, soilPos);
		if (plot == null) {
			return;
		}

		boolean changed = false;
		if (plot.cropPos != cropPos.asLong()) {
			plot.cropPos = cropPos.asLong();
			changed = true;
		}
		if (!rule.plantingItemId().equals(plot.cropId)) {
			plot.cropId = rule.plantingItemId();
			changed = true;
		}

		double currentProgress = progressFromAge(getCropAge(cropState), getCropAgeLimit(cropState));
		if (currentProgress > plot.growthProgress) {
			plot.growthProgress = currentProgress;
			changed = true;
		}

		if (changed) {
			if (plot.hasCrop()) {
				plot.fertilizedAtAbsoluteDayTime = Long.MIN_VALUE;
			}
			refreshPlotActivity(world, soilPos, plot);
			dirty = true;
			requestFarmingProcessing(world.getServer(), FARMING_SCHEDULER_INTERVAL_TICKS);
		}
	}

	public static void handleBlockBreak(Level world, BlockPos pos, BlockState state, BlockEntity blockEntity) {
		if (!settings.enabled || world == null || world.isClientSide() || pos == null || state == null) {
			return;
		}

		ServerLevel serverLevel = world instanceof ServerLevel level ? level : null;
		if (serverLevel == null) {
			return;
		}

			if (isFarmland(state)) {
				emitFarmingDebug(
					"farming.farmland_break",
					serverLevel,
				pos,
				"soil:" + pos.getX() + "," + pos.getY() + "," + pos.getZ(),
				Map.of("action", "remove_plot")
			);
			removePlot(world, pos);
			return;
		}

		if (!isManagedCrop(serverLevel, pos, state)) {
			return;
		}
	}

	private static boolean handleBlockBreakBefore(Level world, BlockPos pos, BlockState state, BlockEntity blockEntity) {
		if (!settings.enabled || world == null || world.isClientSide() || pos == null || state == null) {
			return true;
		}

		if (!(world instanceof ServerLevel serverLevel)) {
			return true;
		}

			CropRule rule = resolveManagedCropRule(serverLevel, pos, state);
			if (rule != null) {
				BlockPos soilPos = pos.below();
				PlotState plot = findPlot(serverLevel, soilPos);
				if (isManagedHarvestState(serverLevel, pos, state)) {
					markPendingHarvest(serverLevel, pos, rule, isFertilized(serverLevel, soilPos));
				} else if (plot != null) {
				plot.clearCrop();
				plot.missingCropSinceGameplayTicks = Long.MIN_VALUE;
				if (plot.fertilized) {
					plot.fertilizedAtAbsoluteDayTime = MadokuTime.getCurrentAbsoluteDayTime(serverLevel);
				}
				if (plot.fertilized) {
					refreshPlotActivity(serverLevel, soilPos, plot);
				} else {
					removePlot(serverLevel, soilPos);
				}
				dirty = true;
			}
		}
		return true;
	}

	private static void runFarmingTask(MinecraftServer server, MadokuScheduler.TaskContext context, JsonObject payload) {
		if (context != null) {
			farmingSchedulerId = context.getSchedulerId();
		}
		farmingTaskScheduled = false;

		if (server == null || !settings.enabled) {
			return;
		}

		processFarmingTick(server);
		if (hasActivePlots()) {
			requestFarmingProcessing(server, FARMING_SCHEDULER_INTERVAL_TICKS);
		}
	}

	private static void processFarmingTick(MinecraftServer server) {
		if (server == null || !settings.enabled) {
			return;
		}

		purgeExpiredPendingHarvestRules();
		long currentAbsoluteDayTime = MadokuTime.getCurrentAbsoluteDayTime(server.overworld());
		long previousAbsoluteDayTime = lastProcessedAbsoluteDayTime;
		lastProcessedAbsoluteDayTime = currentAbsoluteDayTime;
		long deltaAbsoluteDayTime = previousAbsoluteDayTime == Long.MIN_VALUE
			? 0L
			: Math.max(0L, currentAbsoluteDayTime - previousAbsoluteDayTime);
		if (deltaAbsoluteDayTime <= 0L) {
			processFertilizationDecayOnly(server, currentAbsoluteDayTime);
			return;
		}

		processPlots(server, currentAbsoluteDayTime, deltaAbsoluteDayTime);
	}

	private static void processPlots(MinecraftServer server, long currentAbsoluteDayTime, long deltaAbsoluteDayTime) {
		if (server == null || deltaAbsoluteDayTime <= 0L) {
			return;
		}

		boolean changed = false;
		List<String> removedKeys = new ArrayList<>();
		List<Map.Entry<String, PlotState>> activeEntries = new ArrayList<>(activePlotsByKey.entrySet());
		for (Map.Entry<String, PlotState> entry : activeEntries) {
			String key = entry.getKey();
			PlotState plot = entry.getValue();
			if (plot == null) {
				removedKeys.add(key);
				activePlotsByKey.remove(key);
				plotsByKey.remove(key);
				changed = true;
				continue;
			}

				ServerLevel world = resolveLevel(server, plot.levelId);
				BlockPos soilPos = BlockPos.of(plot.soilPos);
						if (world == null) {
							CropRule rule = plot.hasCrop() ? resolveCropRuleByPlantingItemId(plot.cropId) : null;
							if (plot.hasCrop() && rule == null) {
								removedKeys.add(key);
								activePlotsByKey.remove(key);
								plotsByKey.remove(key);
							changed = true;
							continue;
						}

					changed |= advancePlotWithoutWorld(plot, currentAbsoluteDayTime, deltaAbsoluteDayTime, rule);
					if (!isActivePlot(plot)) {
						activePlotsByKey.remove(key);
						removedKeys.add(key);
						changed = true;
					}
					continue;
				}

						BlockState soilState = world.getBlockState(soilPos);
						if (!isFarmland(soilState)) {
							if (MadokuDebug.shouldEmit(MadokuDebug.Domain.FARMING, "farming.plot_removed")) {
								emitFarmingDebug(
									"farming.plot_removed",
									world,
									soilPos,
									"soil:" + soilPos.getX() + "," + soilPos.getY() + "," + soilPos.getZ(),
									Map.of(
										"reason", "soil_not_farmland",
										"soil_state", soilState == null ? "unknown" : soilState.getBlock().toString(),
										"crop_pos", BlockPos.of(plot.cropPos).toShortString(),
										"crop_id", plot.cropId,
										"fertilized", Boolean.toString(plot.fertilized)
									)
								);
							}
						removedKeys.add(key);
						activePlotsByKey.remove(key);
						plotsByKey.remove(key);
						changed = true;
						continue;
				}

					if (plot.fertilized) {
						emitFertilizedParticles(world, soilPos, plot);
					}

					if (!plot.hasCrop()) {
						BlockPos cropPos = soilPos.above();
						BlockState cropState = world.getBlockState(cropPos);
						CropRule discoveredRule = resolveCropRuleByCropState(cropState);
						if (discoveredRule != null && isCropBlock(cropState, discoveredRule)) {
							trackCrop(world, cropPos, cropState);
							changed = true;
							continue;
						}
						if (plot.fertilized && shouldDecayFertilization(plot, world, soilPos, currentAbsoluteDayTime)) {
							plot.fertilized = false;
							plot.fertilizedAtAbsoluteDayTime = Long.MIN_VALUE;
							changed = true;
						}
					if (!plot.fertilized) {
						removedKeys.add(key);
						activePlotsByKey.remove(key);
						plotsByKey.remove(key);
						changed = true;
					} else {
						refreshPlotActivity(world, soilPos, plot);
					}
					continue;
				}

						CropRule rule = resolveCropRuleByPlantingItemId(plot.cropId);
						if (rule == null) {
							if (MadokuDebug.shouldEmit(MadokuDebug.Domain.FARMING, "farming.plot_removed")) {
								emitFarmingDebug(
									"farming.plot_removed",
									world,
									soilPos,
									"soil:" + soilPos.getX() + "," + soilPos.getY() + "," + soilPos.getZ(),
									Map.of(
										"reason", "missing_crop_rule",
										"soil_state", soilState == null ? "unknown" : soilState.getBlock().toString(),
										"crop_pos", BlockPos.of(plot.cropPos).toShortString(),
										"crop_id", plot.cropId,
										"fertilized", Boolean.toString(plot.fertilized)
									)
								);
							}
						removedKeys.add(key);
						activePlotsByKey.remove(key);
						plotsByKey.remove(key);
						changed = true;
						continue;
				}

						BlockPos cropPos = BlockPos.of(plot.cropPos);
						BlockState cropState = world.getBlockState(cropPos);
						if (!isCropBlock(cropState, rule)) {
							boolean cropIsMissing = cropState == null || cropState.isAir();
							boolean harvestPending = resolvePendingHarvestRule(world, cropPos) != null;
							if (cropIsMissing) {
									long gameplayTicks = MadokuTicks.getGameplayTicks();
								if (plot.missingCropSinceGameplayTicks == Long.MIN_VALUE) {
									plot.missingCropSinceGameplayTicks = gameplayTicks;
								}
								if (harvestPending || gameplayTicks - plot.missingCropSinceGameplayTicks < CROP_MISSING_GRACE_TICKS) {
									if (harvestPending) {
										plot.clearCrop();
										plot.missingCropSinceGameplayTicks = Long.MIN_VALUE;
										if (plot.fertilized) {
											plot.fertilizedAtAbsoluteDayTime = currentAbsoluteDayTime;
										}
										if (!plot.fertilized) {
											removedKeys.add(key);
											activePlotsByKey.remove(key);
											plotsByKey.remove(key);
										} else {
											refreshPlotActivity(world, soilPos, plot);
										}
										changed = true;
									} else {
										refreshPlotActivity(world, soilPos, plot);
									}
									continue;
								}
								plot.clearCrop();
								plot.missingCropSinceGameplayTicks = Long.MIN_VALUE;
								if (plot.fertilized) {
									plot.fertilizedAtAbsoluteDayTime = currentAbsoluteDayTime;
								}
								if (!plot.fertilized) {
									removedKeys.add(key);
									activePlotsByKey.remove(key);
									plotsByKey.remove(key);
								} else {
									refreshPlotActivity(world, soilPos, plot);
								}
								changed = true;
								continue;
							}

							plot.missingCropSinceGameplayTicks = Long.MIN_VALUE;
							if (MadokuDebug.shouldEmit(MadokuDebug.Domain.FARMING, "farming.plot_removed")) {
								emitFarmingDebug(
									"farming.plot_removed",
									world,
									soilPos,
									"soil:" + soilPos.getX() + "," + soilPos.getY() + "," + soilPos.getZ(),
									Map.of(
										"reason", "crop_state_mismatch",
										"soil_state", soilState == null ? "unknown" : soilState.getBlock().toString(),
										"crop_state", cropState == null ? "unknown" : cropState.getBlock().toString(),
										"crop_pos", cropPos.toShortString(),
										"crop_id", plot.cropId,
										"fertilized", Boolean.toString(plot.fertilized)
									)
								);
							}
							plot.clearCrop();
							if (plot.fertilized) {
								plot.fertilizedAtAbsoluteDayTime = currentAbsoluteDayTime;
							}
							if (!plot.fertilized) {
								removedKeys.add(key);
								activePlotsByKey.remove(key);
								plotsByKey.remove(key);
							} else {
								refreshPlotActivity(world, soilPos, plot);
							}
							changed = true;
							continue;
						}

				boolean cropIsMatureBlock = isCropMatureBlock(cropState, rule);
				int ageLimit = getCropAgeLimit(cropState);
				int currentAge = cropIsMatureBlock ? ageLimit : getCropAge(cropState);
				double currentProgress = progressFromAge(currentAge, ageLimit);
				if (currentProgress > plot.growthProgress) {
					plot.growthProgress = currentProgress;
					changed = true;
				}

					double growthTicks = getGrowthTicks(rule);
					if (growthTicks <= 0.0d) {
						continue;
					}

					double growthMultiplier = resolveGrowthMultiplier(world, cropPos, rule, plot.fertilized);
					double addedProgress = (deltaAbsoluteDayTime * growthMultiplier) / growthTicks;
					if (addedProgress > 0.0d) {
						double updatedProgress = Math.min(1.0d, plot.growthProgress + addedProgress);
						if (updatedProgress > plot.growthProgress) {
						plot.growthProgress = updatedProgress;
						changed = true;
					}
				}

				int targetAge = ageFromProgress(plot.growthProgress, ageLimit);
					if (rule.usesDistinctMatureBlock()) {
						if (!cropIsMatureBlock && targetAge >= ageLimit) {
							if (rule.matureBlock() != null) {
								world.setBlockAndUpdate(cropPos, rule.matureBlock().defaultBlockState());
								cropState = world.getBlockState(cropPos);
								cropIsMatureBlock = true;
								changed = true;
							}
						} else if (!cropIsMatureBlock && targetAge > currentAge) {
							BlockState updatedState = setCropAge(cropState, targetAge);
							if (updatedState != cropState) {
								world.setBlockAndUpdate(cropPos, updatedState);
								cropState = updatedState;
								changed = true;
							}
						}

						if (cropIsMatureBlock || targetAge >= ageLimit) {
							plot.growthProgress = 1.0d;
						}
						} else {
							if (targetAge > currentAge) {
							BlockState updatedState = setCropAge(cropState, targetAge);
							if (updatedState != cropState) {
								world.setBlockAndUpdate(cropPos, updatedState);
								cropState = updatedState;
								changed = true;
							}
						}

					if (targetAge >= ageLimit) {
						plot.growthProgress = 1.0d;
					}
				}

				refreshPlotActivity(world, soilPos, plot);
			}

		for (String removedKey : removedKeys) {
			plotsByKey.remove(removedKey);
			activePlotsByKey.remove(removedKey);
		}

		if (changed || !removedKeys.isEmpty()) {
			dirty = true;
		}
	}

	private static void requestFarmingProcessing(MinecraftServer server, long delayTicks) {
		if (server == null || !settings.enabled || farmingTaskScheduled || !hasActivePlots()) {
			return;
		}

		String schedulerId = ensureFarmingSchedulerExists();
		if (enqueueFarmingTask(schedulerId, delayTicks)) {
			farmingTaskScheduled = true;
			return;
		}

		farmingSchedulerId = MadokuScheduler.createOrGetScheduler(MadokuScheduler.SchedulerOwner.global(FARMING_SCHEDULER_OWNER_ID));
		if (enqueueFarmingTask(farmingSchedulerId, delayTicks)) {
			farmingTaskScheduled = true;
		} else {
			LOGGER.error("Failed to enqueue MadokuFarming scheduler task.");
		}
	}

	private static String ensureFarmingSchedulerExists() {
		if (farmingSchedulerId == null || farmingSchedulerId.isBlank()) {
			farmingSchedulerId = MadokuScheduler.createOrGetScheduler(MadokuScheduler.SchedulerOwner.global(FARMING_SCHEDULER_OWNER_ID));
		}
		return farmingSchedulerId;
	}

	private static boolean enqueueFarmingTask(String schedulerId, long delayTicks) {
		if (schedulerId == null || schedulerId.isBlank()) {
			return false;
		}

		MadokuScheduler.EnqueueStatus status = MadokuScheduler.enqueue(
			schedulerId,
			Math.max(0L, delayTicks),
			TASK_TYPE_FARMING_TICK,
			new JsonObject(),
			MadokuScheduler.TickDomain.GAMEPLAY
		);
		return status == MadokuScheduler.EnqueueStatus.ACCEPTED
			|| status == MadokuScheduler.EnqueueStatus.QUEUE_FULL;
	}

	private static void processFertilizationDecayOnly(MinecraftServer server, long currentAbsoluteDayTime) {
		if (server == null || currentAbsoluteDayTime < 0L || activePlotsByKey.isEmpty()) {
			return;
		}

		boolean changed = false;
		List<String> removedKeys = new ArrayList<>();
			for (Map.Entry<String, PlotState> entry : new ArrayList<>(activePlotsByKey.entrySet())) {
				String key = entry.getKey();
				PlotState plot = entry.getValue();
				if (plot == null) {
					removedKeys.add(key);
					continue;
				}
				if (!isActivePlot(plot)) {
					removedKeys.add(key);
				}
			}

		for (String removedKey : removedKeys) {
			activePlotsByKey.remove(removedKey);
			PlotState plot = plotsByKey.get(removedKey);
			if (plot != null && !plot.fertilized && !plot.hasCrop()) {
				plotsByKey.remove(removedKey);
			}
		}

		if (changed || !removedKeys.isEmpty()) {
			dirty = true;
		}
	}

	private static boolean advancePlotWithoutWorld(PlotState plot, long currentAbsoluteDayTime, long deltaAbsoluteDayTime, CropRule rule) {
		if (plot == null || rule == null || deltaAbsoluteDayTime <= 0L || !plot.hasCrop()) {
			if (plot != null && plot.fertilized && !plot.hasCrop()) {
				return false;
			}
			return false;
		}

		double growthTicks = getGrowthTicks(rule);
		if (growthTicks <= 0.0d) {
			return false;
		}

		double multiplier = resolveGrowthMultiplier(null, null, rule, plot.fertilized);
		double updatedProgress = Math.min(1.0d, plot.growthProgress + ((deltaAbsoluteDayTime * multiplier) / growthTicks));
		if (updatedProgress <= plot.growthProgress) {
			return false;
		}

		plot.growthProgress = updatedProgress;
		return true;
	}

	private static boolean shouldDecayFertilization(PlotState plot, long currentAbsoluteDayTime) {
		if (plot == null || !plot.fertilized || plot.hasCrop() || currentAbsoluteDayTime < 0L) {
			return false;
		}

		long fertilizedAt = plot.fertilizedAtAbsoluteDayTime;
		if (fertilizedAt == Long.MIN_VALUE) {
			plot.fertilizedAtAbsoluteDayTime = currentAbsoluteDayTime;
			return false;
		}

		return currentAbsoluteDayTime - fertilizedAt >= FERTILIZATION_DECAY_TICKS;
	}

	private static boolean shouldDecayFertilization(PlotState plot, ServerLevel world, BlockPos soilPos, long currentAbsoluteDayTime) {
		return shouldDecayFertilization(plot, currentAbsoluteDayTime);
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

	private static PlotState getOrCreatePlot(ServerLevel world, BlockPos soilPos) {
		if (world == null || soilPos == null) {
			return null;
		}

		String key = plotKey(world, soilPos);
		PlotState plot = plotsByKey.get(key);
		if (plot != null) {
			return plot;
		}

		plot = new PlotState(levelId(world), soilPos.asLong());
		plot.lastParticleEmissionTimeTicks = Long.MIN_VALUE;
		plotsByKey.put(key, plot);
		if (isActivePlot(plot)) {
			activePlotsByKey.put(key, plot);
		}
		dirty = true;
		return plot;
	}

	private static PlotState findPlot(ServerLevel world, BlockPos soilPos) {
		if (world == null || soilPos == null) {
			return null;
		}
		return plotsByKey.get(plotKey(world, soilPos));
	}

	private static void removePlot(Level world, BlockPos soilPos) {
		if (!(world instanceof ServerLevel serverLevel) || soilPos == null) {
			return;
		}

		String key = plotKey(serverLevel, soilPos);
		if (plotsByKey.remove(key) != null) {
			activePlotsByKey.remove(key);
			dirty = true;
		}
	}

	private static boolean hasActivePlots() {
		return !activePlotsByKey.isEmpty();
	}

	private static boolean isActivePlot(PlotState plot) {
		return plot != null && (plot.fertilized || plot.hasCrop());
	}

	private static void refreshPlotActivity(ServerLevel world, BlockPos soilPos, PlotState plot) {
		if (world == null || soilPos == null || plot == null) {
			return;
		}

		String key = plotKey(world, soilPos);
		if (isActivePlot(plot)) {
			activePlotsByKey.put(key, plot);
		} else {
			activePlotsByKey.remove(key);
		}
	}

	private static String plotKey(ServerLevel world, BlockPos soilPos) {
		return levelId(world) + "|" + soilPos.asLong();
	}

	private static String levelId(ServerLevel world) {
		if (world == null) {
			return "";
		}
		return MadokuScheduler.normalizeLevelIdentifier(world.dimension().toString());
	}

	private static ServerLevel resolveLevel(MinecraftServer server, String levelId) {
		if (server == null || levelId == null || levelId.isBlank()) {
			return null;
		}

		String normalizedLevelId = MadokuScheduler.normalizeLevelIdentifier(levelId);
		Identifier identifier = Identifier.tryParse(normalizedLevelId == null ? "" : normalizedLevelId);
		if (identifier == null) {
			return null;
		}

		ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, identifier);
		return server.getLevel(key);
	}

	private static void applyPersistedData(JsonObject source) {
		plotsByKey.clear();
		activePlotsByKey.clear();
		if (source == null) {
			return;
		}

		JsonElement plotsElement = source.get(FIELD_PLOTS);
		if (plotsElement == null || !plotsElement.isJsonArray()) {
			return;
		}

		for (JsonElement element : plotsElement.getAsJsonArray()) {
			PlotState plot = PlotState.fromJson(element);
			if (plot == null) {
				continue;
			}
			plotsByKey.put(plot.key(), plot);
			if (plot.fertilized || plot.hasCrop()) {
				activePlotsByKey.put(plot.key(), plot);
			}
		}
	}

	private static JsonObject createDefaultData() {
		JsonObject root = new JsonObject();
		root.add(FIELD_PLOTS, new JsonArray());
		return root;
	}

	private static JsonObject toPersistedData() {
		JsonObject root = new JsonObject();
		JsonArray plots = new JsonArray();
		for (PlotState plot : plotsByKey.values()) {
			if (plot == null) {
				continue;
			}
			plots.add(plot.toJson());
		}
		root.add(FIELD_PLOTS, plots);
		return root;
	}

	private static void loadStaticConfig() {
		JsonObject defaults = MadokuFarmingConfig.buildFarmingDefaults();
		Settings fallback = Settings.defaults();

		try {
			Path directory = StaticJsonSystem.getOrCreateGlobalSystemDirectory(FARMING_CONFIG_ROOT_FOLDER_NAME);
			Path configFile = resolveJsonFile(directory, FARMING_CONFIG_FILE_NAME);
			JsonObject normalized = StaticJsonSystem.ensureManagedFile(configFile, defaults);
			Settings loaded = Settings.fromJson(normalized);
			JsonObject cleaned = loaded.toConfigJson();
			StaticJsonSystem.writeManagedFile(configFile, cleaned, defaults);
			settings = loaded;
		} catch (IOException | RuntimeException exception) {
			settings = fallback;
			LOGGER.error("Failed to load MadokuFarming static config; using defaults.", exception);
		}
	}

	private static void loadCropConfigs() {
		Map<String, CropRule> plantingRules = new LinkedHashMap<>();
		Map<String, CropRule> blockRules = new LinkedHashMap<>();
		Map<String, CropRule> matureBlockRules = new LinkedHashMap<>();
		Map<String, JsonObject> defaultFiles = MadokuCropConfig.buildDefaultCropFileDefaults();

		try {
			Path directory = StaticJsonSystem.getOrCreateGlobalSystemDirectory(CROP_CONFIG_ROOT_FOLDER_NAME);
			for (Map.Entry<String, JsonObject> entry : defaultFiles.entrySet()) {
				String fileKey = entry.getKey();
				JsonObject defaults = entry.getValue();
				Path file = resolveJsonFile(directory, fileKey);
				JsonObject normalized = StaticJsonSystem.ensureManagedFile(file, defaults);
				CropRule rule = CropRule.fromJson(fileKey, normalized);
				if (rule == null) {
					rule = CropRule.fromJson(fileKey, defaults);
				}
				if (rule == null) {
					rule = CropRule.defaultRule(fileKey);
				}
					if (rule == null) {
						continue;
					}

					JsonObject cleaned = rule.toJson();
					StaticJsonSystem.writeManagedFile(file, cleaned, defaults);
					plantingRules.put(rule.plantingItemId(), rule);
					blockRules.put(rule.cropBlockId(), rule);
					if (rule.usesDistinctMatureBlock()) {
						matureBlockRules.put(rule.matureBlockId(), rule);
					}
				}
			cropRulesByPlantingItemId = Map.copyOf(plantingRules);
			cropRulesByCropBlockId = Map.copyOf(blockRules);
			cropRulesByMatureBlockId = Map.copyOf(matureBlockRules);
		} catch (IOException | RuntimeException exception) {
			LOGGER.error("Failed to load MadokuFarming crop config; using defaults.", exception);
			cropRulesByPlantingItemId = Map.copyOf(defaultCropRulesByPlantingItemId());
			cropRulesByCropBlockId = Map.copyOf(defaultCropRulesByCropBlockId());
			cropRulesByMatureBlockId = Map.copyOf(defaultCropRulesByMatureBlockId());
		}
	}

	/**
	 * Compat hook for mods that provide item-system integration.
	 * Standalone Farming no longer invokes this automatically.
	 */
	public static void applyCropItemMetadata() {
		if (!settings.enabled) {
			return;
		}

		for (CropRule rule : cropRulesByPlantingItemId.values()) {
			if (rule == null || rule.plantingItem() == null) {
				continue;
			}
			applyFarmingLore(rule);
		}

		Item boneMeal = BuiltInRegistries.ITEM.getValue(Identifier.tryParse("minecraft:bone_meal"));
		if (boneMeal != null) {
			applyFertilizerLore(boneMeal);
		}
	}

	private static void applyFarmingLore(CropRule rule) {
		if (rule == null || rule.plantingItem() == null) {
			return;
		}

		Item plantingItem = rule.plantingItem();
		if (plantingItem == null) {
			return;
		}

		DataComponentMap base = plantingItem.components();

		List<Component> updatedLines = new ArrayList<>();
		ItemLore currentLore = base.get(DataComponents.LORE);
		if (currentLore != null) {
			for (Component line : currentLore.lines()) {
				String text = line == null ? "" : line.getString();
				if (text.startsWith(LORE_SEASON_PREFIX) || text.startsWith(LORE_GROWING_TIME_PREFIX)) {
					continue;
				}
				updatedLines.add(line);
			}
		}

		if (MadokuSeason.isEnabled()) {
			updatedLines.add(Component.literal(formatSeasonLoreLine(rule.blockedSeasonIds())).withStyle(ChatFormatting.DARK_GREEN));
		}
		updatedLines.add(Component.literal(formatGrowthTimeLoreLine(rule.growthMinecraftDays())).withStyle(ChatFormatting.GOLD));

		ItemLore updatedLore = new ItemLore(updatedLines);
		if (Objects.equals(currentLore, updatedLore)) {
			return;
		}

		DataComponentMap.Builder builder = DataComponentMap.builder().addAll(base);
		builder.set(DataComponents.LORE, updatedLore);
		((ItemComponentsAccessor) ((ItemBuiltInRegistryHolderAccessor) plantingItem).madokuCraft$getBuiltInRegistryHolder())
			.madokuCraft$bindComponents(builder.build());
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

		ItemLore updatedLore = new ItemLore(updatedLines);
		if (Objects.equals(currentLore, updatedLore)) {
			return;
		}

		DataComponentMap.Builder builder = DataComponentMap.builder().addAll(base);
		builder.set(DataComponents.LORE, updatedLore);
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

	private static String formatGrowthDays(double growthMinecraftDays) {
		BigDecimal value = BigDecimal.valueOf(Math.max(0.0d, growthMinecraftDays)).stripTrailingZeros();
		String formatted = value.toPlainString();
		return formatted.isEmpty() ? "0" : formatted;
	}

	private static String capitalizeSeasonId(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
		return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
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

	private static boolean isCropGrowingSeason(CropRule rule) {
		return isCropGrowingSeason(rule, (ServerLevel) null);
	}

	private static boolean isCropGrowingSeason(CropRule rule, ServerLevel world) {
		if (rule == null || !MadokuSeason.isEnabled()) {
			return true;
		}

		String seasonId = world == null ? MadokuSeason.getCurrentSeasonId() : MadokuSeason.getCurrentSeasonId(world);
		if (seasonId == null || seasonId.isBlank()) {
			return true;
		}
			return !rule.blockedSeasonIds().contains(normalizeSeasonId(seasonId));
	}

	private static boolean isCropGrowingSeason(CropRule rule, String seasonId) {
		if (rule == null || !MadokuSeason.isEnabled()) {
			return true;
		}
		if (seasonId == null || seasonId.isBlank()) {
			return true;
		}
		return !rule.blockedSeasonIds().contains(normalizeSeasonId(seasonId));
	}

	private static String getCropSeasonBlockedMessage(CropRule rule) {
		return getCropSeasonBlockedMessage(rule, (ServerLevel) null);
	}

	private static String getCropSeasonBlockedMessage(CropRule rule, ServerLevel world) {
		String seasonName = world == null ? MadokuSeason.getCurrentSeasonDisplayName() : MadokuSeason.getCurrentSeasonDisplayName(world);
		if (seasonName == null || seasonName.isBlank()) {
			seasonName = "this season";
		}
		String cropName = rule == null || rule.displayName() == null || rule.displayName().isBlank()
			? "Crop"
			: rule.displayName();
			return cropName + " can't grow during " + seasonName + ".";
	}

	private static String getCropSeasonBlockedMessage(CropRule rule, String seasonId) {
		String seasonName = seasonId == null || seasonId.isBlank()
			? "this season"
			: capitalizeSeasonLabel(seasonId);
		String cropName = rule == null || rule.displayName() == null || rule.displayName().isBlank()
			? "Crop"
			: rule.displayName();
		return cropName + " can't grow during " + seasonName + ".";
	}

	private static String capitalizeSeasonLabel(String value) {
		if (value == null || value.isBlank()) {
			return "this season";
		}
		String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
		return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
	}

	private static CropRule resolveCropRuleByPlantingItem(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		Item item = stack.getItem();
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

	public static CropRule resolveHarvestRule(ServerLevel world, BlockPos cropPos, BlockState state) {
		CropRule managedRule = resolveManagedCropRule(world, cropPos, state);
		if (managedRule != null) {
			return managedRule;
		}
		return resolvePendingHarvestRule(world, cropPos);
	}

	private static CropRule resolveManagedCropRule(ServerLevel world, BlockPos cropPos, BlockState state) {
		CropRule stateRule = resolveCropRuleByCropState(state);
		if (stateRule == null || world == null || cropPos == null || state == null) {
			return null;
		}

		PlotState plot = findPlot(world, cropPos.below());
			if (plot != null && plot.hasCrop()) {
				CropRule plotRule = resolveCropRuleByPlantingItemId(plot.cropId);
				if (plotRule != null) {
					if (!plotRule.usesDistinctMatureBlock()) {
						if (isCropBlock(state, plotRule) && plot.cropPos == cropPos.asLong()) {
							return plotRule;
						}
					} else if (plot.cropPos == cropPos.asLong() && (isCropGrowthBlock(state, plotRule) || isCropMatureBlock(state, plotRule))) {
						return plotRule;
					}
				}
			}

		if (resolvePendingHarvestRule(world, cropPos, state) != null) {
			return stateRule;
		}

		if (!stateRule.usesDistinctMatureBlock()) {
			return null;
		}

		if (isCropMatureBlock(state, stateRule)) {
			CropRule adjacentRule = resolveAdjacentDistinctMatureRule(world, cropPos, stateRule);
			if (adjacentRule != null) {
				return adjacentRule;
			}
		}

		return null;
	}

	private static CropRule resolveAdjacentDistinctMatureRule(ServerLevel world, BlockPos maturePos, CropRule matureRule) {
		if (world == null || maturePos == null || matureRule == null || !matureRule.usesDistinctMatureBlock()) {
			return null;
		}

		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos candidateCropPos = maturePos.relative(direction);
			BlockState candidateState = world.getBlockState(candidateCropPos);
			if (!isCropGrowthBlock(candidateState, matureRule)) {
				continue;
			}

			PlotState plot = findPlot(world, candidateCropPos.below());
			if (plot != null && plot.hasCrop() && matureRule.plantingItemId().equals(plot.cropId) && plot.cropPos == candidateCropPos.asLong()) {
				return matureRule;
			}

			CropRule pendingRule = resolvePendingHarvestRule(world, candidateCropPos, candidateState);
			if (pendingRule != null) {
				return pendingRule;
			}
		}

		return null;
	}

	private static CropRule resolvePendingHarvestRule(ServerLevel world, BlockPos cropPos) {
		if (world == null || cropPos == null) {
			return null;
		}

		String key = cropKey(world, cropPos);
		PendingHarvestRule pending = pendingHarvestRulesByKey.get(key);
		if (pending == null) {
			return null;
		}

		long now = MadokuTicks.getGameplayTicks();
		if (pending.expiresAtGameplayTick < now) {
			pendingHarvestRulesByKey.remove(key);
			return null;
		}

		return pending.rule;
	}

	private static CropRule resolvePendingHarvestRule(ServerLevel world, BlockPos cropPos, BlockState state) {
		CropRule pendingRule = resolvePendingHarvestRule(world, cropPos);
		if (pendingRule == null || state == null) {
			return null;
		}

		Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		if (id == null) {
			return null;
		}

		String normalized = normalizeRegistryId(id.toString());
		return normalized.equals(pendingRule.matureBlockId()) ? pendingRule : null;
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

	public static boolean isCropHarvestReady(ServerLevel world, BlockPos cropPos, BlockState state) {
		CropRule pendingRule = resolvePendingHarvestRule(world, cropPos);
		if (pendingRule != null) {
			return true;
		}

		CropRule rule = resolveManagedCropRule(world, cropPos, state);
		if (rule == null) {
			return false;
		}
		if (state == null) {
			return true;
		}
		if (isCropMatureBlock(state, rule)) {
			return true;
		}
		return isMaxAge(state);
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
		return resolvePendingHarvestRule(world, cropPos) != null || resolvePendingHarvestRule(world, cropPos, state) != null;
	}

	private static String normalizeSeasonId(String value) {
		return MadokuSeasonConfig.normalizeKey(value);
	}

		private static void markPendingHarvest(ServerLevel world, BlockPos cropPos, CropRule rule, boolean fertilized) {
			if (world == null || cropPos == null || rule == null) {
				return;
			}

			String key = cropKey(world, cropPos);
			PendingHarvestRule existing = pendingHarvestRulesByKey.get(key);
			boolean retainedFertilized = fertilized || (existing != null && existing.fertilized());
			long expiresAtGameplayTick = MadokuTicks.getGameplayTicks() + 2L;
			pendingHarvestRulesByKey.put(key, new PendingHarvestRule(
				rule,
				expiresAtGameplayTick,
				retainedFertilized
			));
		}

	private static void purgeExpiredPendingHarvestRules() {
		long now = MadokuTicks.getGameplayTicks();
		pendingHarvestRulesByKey.entrySet().removeIf(entry -> entry == null
			|| entry.getValue() == null
			|| entry.getValue().expiresAtGameplayTick < now);
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

	private static String cropKey(ServerLevel world, BlockPos cropPos) {
		return levelId(world) + "|" + (cropPos == null ? -1L : cropPos.asLong());
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
		return identifier == null ? trimmed.toLowerCase(java.util.Locale.ROOT) : identifier.toString();
	}

	private static Map<String, CropRule> defaultCropRulesByPlantingItemId() {
		Map<String, CropRule> defaults = new LinkedHashMap<>();
		for (CropRule rule : CropRule.defaultRules()) {
			defaults.put(rule.plantingItemId(), rule);
		}
		return defaults;
	}

	private static Map<String, CropRule> defaultCropRulesByCropBlockId() {
		Map<String, CropRule> defaults = new LinkedHashMap<>();
		for (CropRule rule : CropRule.defaultRules()) {
			defaults.put(rule.cropBlockId(), rule);
		}
		return defaults;
	}

	private static Map<String, CropRule> defaultCropRulesByMatureBlockId() {
		Map<String, CropRule> defaults = new LinkedHashMap<>();
		for (CropRule rule : CropRule.defaultRules()) {
			if (rule.usesDistinctMatureBlock()) {
				defaults.put(rule.matureBlockId(), rule);
			}
		}
		return defaults;
	}

	private static double getGrowthTicks(CropRule rule) {
		double growthDays = rule == null ? 1.0d : rule.growthMinecraftDays();
		return Math.max(1.0d, 24000.0d * growthDays);
	}

	private static double resolveGrowthMultiplier(ServerLevel world, BlockPos cropPos, CropRule rule, boolean fertilized) {
		double multiplier = 1.0d;
		if (world != null && cropPos != null && world.isRainingAt(cropPos)) {
			multiplier += settings.rainGrowthBonus;
		}
		if (fertilized) {
			multiplier += settings.fertilizedGrowthBonus;
		}
		multiplier = Math.min(1.5d, multiplier);
		if (rule != null && !isCropGrowingSeason(rule, world)) {
			multiplier *= Math.max(0.0d, settings.outOfSeasonGrowthMultiplier);
		}
		return multiplier;
	}

	public static int calculateCropHarvestCount(ServerLevel world, BlockPos cropPos, BlockState state, RandomSource random) {
		CropRule rule = resolveHarvestRule(world, cropPos, state);
		boolean pendingHarvest = resolvePendingHarvestRule(world, cropPos) != null;
		if (!settings.enabled || rule == null || (!pendingHarvest && !isCropBlock(state, rule)) || !isCropHarvestReady(world, cropPos, state)) {
			if (MadokuDebug.shouldEmit(MadokuDebug.Domain.FARMING, "farming.harvest_count")) {
				emitFarmingDebug(
					"farming.harvest_count",
					world,
					cropPos,
					"crop:" + (cropPos == null ? "unknown" : cropPos.getX() + "," + cropPos.getY() + "," + cropPos.getZ()),
					Map.of(
						"result", "0",
						"reason", !settings.enabled ? "disabled" : rule == null ? "unmanaged" : !isCropBlock(state, rule) ? "state_mismatch" : "not_ready",
						"state", state == null ? "unknown" : state.getBlock().toString()
					)
				);
			}
			return 0;
		}

		RandomSource safeRandom = random == null ? RandomSource.create() : random;
		int minCount = Math.max(1, rule.minHarvestCount());
		int maxCount = Math.max(minCount, rule.maxHarvestCount());
		int baseCount = minCount + safeRandom.nextInt(maxCount - minCount + 1);
		double multiplier = resolveCropHarvestMultiplier(world, cropPos, rule);
		int result = applyScaledItemCount(baseCount, multiplier, safeRandom);
		if (MadokuDebug.shouldEmit(MadokuDebug.Domain.FARMING, "farming.harvest_count")) {
			boolean fertilizedHarvest = isHarvestFertilized(world, cropPos, rule);
			emitFarmingDebug(
				"farming.harvest_count",
				world,
				cropPos,
				"crop:" + (cropPos == null ? "unknown" : cropPos.getX() + "," + cropPos.getY() + "," + cropPos.getZ()),
				Map.of(
					"rule", rule.cropId(),
					"state", state == null ? "unknown" : state.getBlock().toString(),
					"min", Integer.toString(minCount),
					"max", Integer.toString(maxCount),
					"base", Integer.toString(baseCount),
					"multiplier", Double.toString(multiplier),
					"result", Integer.toString(result),
					"fertilized_harvest", Boolean.toString(fertilizedHarvest),
					"mature_block", rule.matureBlockId(),
					"distinct_mature", Boolean.toString(rule.usesDistinctMatureBlock())
				)
			);
			}
			return result;
		}

	public static int calculateCropSecondaryHarvestCount(ServerLevel world, BlockPos cropPos, BlockState state, RandomSource random) {
		CropRule rule = resolveHarvestRule(world, cropPos, state);
		boolean pendingHarvest = resolvePendingHarvestRule(world, cropPos) != null;
		if (!settings.enabled || rule == null || (!pendingHarvest && !isCropBlock(state, rule)) || !isCropHarvestReady(world, cropPos, state) || !rule.hasSecondaryHarvestDrop()) {
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

		BlockPos soilPos = cropPos.below();
		PlotState plot = findPlot(world, soilPos);
		boolean fertilized = isFertilized(world, soilPos);
		if (MadokuDebug.shouldEmit(MadokuDebug.Domain.FARMING, "farming.harvest_prepare")) {
			emitFarmingDebug(
				"farming.harvest_prepare",
				world,
				soilPos,
				"soil:" + soilPos.getX() + "," + soilPos.getY() + "," + soilPos.getZ(),
				Map.of(
					"crop_pos", cropPos.getX() + "," + cropPos.getY() + "," + cropPos.getZ(),
					"fertilized", Boolean.toString(fertilized),
					"rule", rule.cropId(),
					"plot_present", Boolean.toString(plot != null),
					"plot_fertilized", Boolean.toString(plot != null && plot.fertilized),
					"plot_crop_id", plot == null ? "" : plot.cropId,
					"plot_has_crop", Boolean.toString(plot != null && plot.hasCrop()),
					"plot_progress", plot == null ? "n/a" : Double.toString(plot.growthProgress)
				)
			);
		}
		markPendingHarvest(world, cropPos, rule, fertilized);
	}

	public static void completeCropHarvest(ServerLevel world, BlockPos cropPos, BlockState state) {
		if (!settings.enabled || world == null || cropPos == null || state == null) {
			return;
		}

		CropRule rule = resolveHarvestRule(world, cropPos, state);
		if (rule == null || !isManagedHarvestState(world, cropPos, state)) {
			return;
		}

		BlockPos soilPos = cropPos.below();
		PlotState plot = findPlot(world, soilPos);
		if (plot == null) {
			return;
		}

		boolean wasFertilized = plot.fertilized;
		pendingHarvestRulesByKey.remove(cropKey(world, cropPos));
		emitFarmingDebug(
			"farming.harvest_clear",
			world,
			soilPos,
			"soil:" + soilPos.getX() + "," + soilPos.getY() + "," + soilPos.getZ(),
			Map.of(
				"crop_pos", cropPos.getX() + "," + cropPos.getY() + "," + cropPos.getZ(),
				"max_age", Boolean.toString(isCropHarvestReady(world, cropPos, state)),
				"fertilized_before", Boolean.toString(wasFertilized)
			)
		);
		plot.clearCrop();
		plot.fertilized = false;
		plot.fertilizedAtAbsoluteDayTime = Long.MIN_VALUE;
		removePlot(world, soilPos);
		dirty = true;
	}

	private static double resolveCropHarvestMultiplier(ServerLevel world, BlockPos cropPos, CropRule rule) {
		double multiplier = 1.0d;
		if (world != null && cropPos != null) {
			if (isHarvestFertilized(world, cropPos, rule)) {
				multiplier += settings.fertilizedGrowthBonus;
			}
		}
		if (rule != null && !isCropGrowingSeason(rule, world)) {
			multiplier *= Math.max(0.0d, settings.outOfSeasonGrowthMultiplier);
		}
		return multiplier;
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

	public static boolean isMaxAge(BlockState state) {
		return getCropAge(state) >= getCropAgeLimit(state);
	}

	private static double progressFromAge(int age, int ageLimit) {
		int safeLimit = Math.max(1, ageLimit);
		int safeAge = Math.max(0, Math.min(safeLimit, age));
		return safeAge / (double) safeLimit;
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

	private static IntegerProperty findAgeProperty(BlockState state) {
		if (state == null) {
			return null;
		}

		for (Property<?> property : state.getProperties()) {
			if (property instanceof IntegerProperty integerProperty && "age".equals(property.getName())) {
				return integerProperty;
			}
		}
		return null;
	}

	private static int ageFromProgress(double progress, int ageLimit) {
		if (!Double.isFinite(progress) || progress <= 0.0d) {
			return 0;
		}
		double clamped = Math.max(0.0d, Math.min(1.0d, progress));
		int safeLimit = Math.max(1, ageLimit);
		int age = (int) Math.floor(clamped * safeLimit);
		return Math.min(safeLimit, age);
	}

	private static boolean readBoolean(JsonObject root, String key, boolean fallback) {
		if (root == null) {
			return fallback;
		}

		JsonElement element = root.get(key);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
			return fallback;
		}

		return element.getAsBoolean();
	}

	private static long readLong(JsonObject root, String key, long fallback) {
		if (root == null) {
			return fallback;
		}

		JsonElement element = root.get(key);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			return fallback;
		}

		try {
			return element.getAsLong();
		} catch (RuntimeException exception) {
			return fallback;
		}
	}

	private static int readInt(JsonObject root, String key, int fallback) {
		long value = readLong(root, key, fallback);
		if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
			return fallback;
		}
		return (int) value;
	}

	private static double readDouble(JsonObject root, String key, double fallback) {
		if (root == null) {
			return fallback;
		}

		JsonElement element = root.get(key);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			return fallback;
		}

		try {
			return element.getAsDouble();
		} catch (RuntimeException exception) {
			return fallback;
		}
	}

	private static String readString(JsonObject root, String key, String fallback) {
		if (root == null) {
			return fallback;
		}

		JsonElement element = root.get(key);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			return fallback;
		}

		String value = element.getAsString();
		return value == null ? fallback : value;
	}

	private static double clampDouble(double value, double fallback, double minimum, double maximum) {
		double safeValue = Double.isFinite(value) ? value : fallback;
		if (safeValue < minimum || safeValue > maximum) {
			return fallback;
		}
		return safeValue;
	}

		private static int clampInt(int value, int fallback, int minimum, int maximum) {
			if (minimum > maximum) {
				return fallback;
			}
		if (value < minimum || value > maximum) {
			return fallback;
		}
		return value;
	}

	private static void emitFarmingDebug(String metricId, ServerLevel world, BlockPos pos, String subject, Map<String, String> fields) {
		if (!MadokuDebug.shouldEmit(MadokuDebug.Domain.FARMING, metricId)) {
			return;
		}

		MadokuDebug.EventBuilder builder = MadokuDebug.event(metricId, MadokuDebug.Domain.FARMING)
			.side(MadokuDebug.Side.SERVER)
			.tick(MadokuTicks.getGameplayTicks())
			.world(world == null ? "" : world.dimension().toString())
			.subject(subject == null || subject.isBlank() ? "global" : subject);

		if (fields != null) {
			for (Map.Entry<String, String> entry : fields.entrySet()) {
				if (entry == null) {
					continue;
				}
				builder.field(entry.getKey(), entry.getValue());
			}
		}

		builder.log();
	}

	public static void emitPendingHarvestUsedDebug(ServerLevel world, BlockPos cropPos, BlockState state, String source) {
		if (world == null || cropPos == null || !MadokuDebug.shouldEmit(MadokuDebug.Domain.FARMING, "farming.pending_harvest_used")) {
			return;
		}

		boolean pendingHarvest = resolvePendingHarvestRule(world, cropPos) != null;
		boolean managedCrop = isManagedCrop(world, cropPos, state);
		if (!pendingHarvest || managedCrop) {
			return;
		}

		emitFarmingDebug(
			"farming.pending_harvest_used",
			world,
			cropPos,
			"crop:" + cropPos.getX() + "," + cropPos.getY() + "," + cropPos.getZ(),
			Map.of(
				"source", source == null || source.isBlank() ? "unknown" : source,
				"state", state == null ? "unknown" : state.getBlock().toString(),
				"pending", Boolean.toString(pendingHarvest),
				"managed", Boolean.toString(managedCrop)
			)
		);
	}

	private record CropRule(
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
		Block cropBlock,
		Block matureBlock,
		Item plantingItem,
		Item harvestItem,
		Item secondaryHarvestItem
	) {
		private static List<CropRule> defaultRules() {
			return List.of(defaultPotato(), defaultCarrot(), defaultBeetroot(), defaultMelon(), defaultPumpkin(), defaultWheat());
		}

		private static CropRule defaultRule(String fileKey) {
			String normalized = MadokuCropConfig.normalizeRegistryId(fileKey);
			return switch (normalized) {
				case "minecraft:potato", "potato", "minecraft:potatoes" -> defaultPotato();
				case "minecraft:carrot", "carrot", "minecraft:carrots" -> defaultCarrot();
				case "minecraft:beetroot", "beetroot", "minecraft:beetroots" -> defaultBeetroot();
				case "minecraft:melon", "melon" -> defaultMelon();
				case "minecraft:pumpkin", "pumpkin" -> defaultPumpkin();
				case "minecraft:wheat", "wheat" -> defaultWheat();
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

			String cropId = MadokuCropConfig.normalizeRegistryId(readString(source, MadokuCropConfig.FIELD_CROP_ID, fallback.cropId));
			if (cropId.isEmpty()) {
				cropId = fallback.cropId;
			}

			String cropBlockId = MadokuCropConfig.normalizeRegistryId(readString(source, MadokuCropConfig.FIELD_CROP_BLOCK_ID, fallback.cropBlockId));
			if (cropBlockId.isEmpty()) {
				cropBlockId = fallback.cropBlockId;
			}

			String matureBlockId = MadokuCropConfig.normalizeRegistryId(readString(source, MadokuCropConfig.FIELD_MATURE_BLOCK_ID, fallback.matureBlockId));
			if (matureBlockId.isEmpty()) {
				matureBlockId = fallback.matureBlockId;
			}

			String plantingItemId = MadokuCropConfig.normalizeRegistryId(readString(source, MadokuCropConfig.FIELD_PLANTING_ITEM_ID, fallback.plantingItemId));
			if (plantingItemId.isEmpty()) {
				plantingItemId = fallback.plantingItemId;
			}

			String harvestItemId = MadokuCropConfig.normalizeRegistryId(readString(source, MadokuCropConfig.FIELD_HARVEST_ITEM_ID, fallback.harvestItemId));
			if (harvestItemId.isEmpty()) {
				harvestItemId = fallback.harvestItemId;
			}

			String secondaryHarvestItemId = MadokuCropConfig.normalizeRegistryId(readString(source, MadokuCropConfig.FIELD_SECONDARY_HARVEST_ITEM_ID, fallback.secondaryHarvestItemId));
			if (secondaryHarvestItemId.isEmpty()) {
				secondaryHarvestItemId = fallback.secondaryHarvestItemId;
			}

			double growthDays = clampDouble(
				readDouble(source, MadokuCropConfig.FIELD_GROWTH_MINECRAFT_DAYS, fallback.growthMinecraftDays),
				fallback.growthMinecraftDays,
				0.25d,
				365.0d
			);
			int minHarvestCount = clampInt(
				readInt(source, MadokuCropConfig.FIELD_MIN_HARVEST_COUNT, fallback.minHarvestCount),
				fallback.minHarvestCount,
				1,
				1024
			);
			int maxHarvestCount = clampInt(
				readInt(source, MadokuCropConfig.FIELD_MAX_HARVEST_COUNT, fallback.maxHarvestCount),
				fallback.maxHarvestCount,
				minHarvestCount,
				1024
			);
			int secondaryMinHarvestCount = clampInt(
				readInt(source, MadokuCropConfig.FIELD_SECONDARY_MIN_HARVEST_COUNT, fallback.secondaryMinHarvestCount),
				fallback.secondaryMinHarvestCount,
				0,
				1024
			);
			int secondaryMaxHarvestCount = clampInt(
				readInt(source, MadokuCropConfig.FIELD_SECONDARY_MAX_HARVEST_COUNT, fallback.secondaryMaxHarvestCount),
				fallback.secondaryMaxHarvestCount,
				secondaryMinHarvestCount,
				1024
			);
			Set<String> blockedSeasonIds = parseBlockedSeasonIds(source, fallback.blockedSeasonIds);
			if (blockedSeasonIds.isEmpty()) {
				blockedSeasonIds = fallback.blockedSeasonIds;
			}

			Identifier cropBlockIdentifier = Identifier.tryParse(cropBlockId);
			Identifier matureBlockIdentifier = Identifier.tryParse(matureBlockId);
			Identifier plantingIdentifier = Identifier.tryParse(plantingItemId);
			Identifier harvestIdentifier = Identifier.tryParse(harvestItemId);
			Identifier secondaryHarvestIdentifier = secondaryHarvestItemId.isBlank() ? null : Identifier.tryParse(secondaryHarvestItemId);
			if (cropBlockIdentifier == null || matureBlockIdentifier == null || plantingIdentifier == null || harvestIdentifier == null) {
				return fallback;
			}
			if (!BuiltInRegistries.BLOCK.containsKey(cropBlockIdentifier)
				|| !BuiltInRegistries.BLOCK.containsKey(matureBlockIdentifier)
				|| !BuiltInRegistries.ITEM.containsKey(plantingIdentifier)
				|| !BuiltInRegistries.ITEM.containsKey(harvestIdentifier)) {
				return fallback;
			}
			if (secondaryHarvestIdentifier != null && !BuiltInRegistries.ITEM.containsKey(secondaryHarvestIdentifier)) {
				return fallback;
			}

			return fromValues(
				cropId,
				cropBlockId,
				matureBlockId,
				plantingItemId,
				harvestItemId,
				secondaryHarvestItemId,
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
			String normalizedCropId = MadokuCropConfig.normalizeRegistryId(cropId);
			String normalizedCropBlockId = MadokuCropConfig.normalizeRegistryId(cropBlockId);
			String normalizedMatureBlockId = MadokuCropConfig.normalizeRegistryId(matureBlockId);
			String normalizedPlantingItemId = MadokuCropConfig.normalizeRegistryId(plantingItemId);
			String normalizedHarvestItemId = MadokuCropConfig.normalizeRegistryId(harvestItemId);
			String normalizedSecondaryHarvestItemId = MadokuCropConfig.normalizeRegistryId(secondaryHarvestItemId);
			Identifier cropBlockIdentifier = Identifier.tryParse(normalizedCropBlockId);
			Identifier matureBlockIdentifier = Identifier.tryParse(normalizedMatureBlockId);
			Identifier plantingIdentifier = Identifier.tryParse(normalizedPlantingItemId);
			Identifier harvestIdentifier = Identifier.tryParse(normalizedHarvestItemId);
			Identifier secondaryHarvestIdentifier = normalizedSecondaryHarvestItemId.isBlank() ? null : Identifier.tryParse(normalizedSecondaryHarvestItemId);
			Block cropBlock = cropBlockIdentifier == null ? null : BuiltInRegistries.BLOCK.getValue(cropBlockIdentifier);
			Block matureBlock = matureBlockIdentifier == null ? null : BuiltInRegistries.BLOCK.getValue(matureBlockIdentifier);
			Item plantingItem = plantingIdentifier == null ? null : BuiltInRegistries.ITEM.getValue(plantingIdentifier);
			Item harvestItem = harvestIdentifier == null ? null : BuiltInRegistries.ITEM.getValue(harvestIdentifier);
			Item secondaryHarvestItem = secondaryHarvestIdentifier == null ? null : BuiltInRegistries.ITEM.getValue(secondaryHarvestIdentifier);
			Set<String> normalizedBlockedSeasons = blockedSeasonIds == null || blockedSeasonIds.isEmpty()
				? Set.of()
				: blockedSeasonIds.stream()
					.map(MadokuCropConfig::normalizeSeasonId)
					.filter(value -> !value.isBlank())
					.collect(java.util.stream.Collectors.toUnmodifiableSet());
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
				minHarvestCount,
				maxHarvestCount,
				normalizedBlockedSeasons,
				cropBlock,
				matureBlock,
				plantingItem,
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

		private boolean usesDistinctMatureBlock() {
			return matureBlockId != null && !matureBlockId.isBlank() && !matureBlockId.equals(cropBlockId);
		}

		private boolean hasSecondaryHarvestDrop() {
			return secondaryHarvestItem != null && secondaryMinHarvestCount > 0 && secondaryMaxHarvestCount >= secondaryMinHarvestCount;
		}

		private JsonObject toJson() {
			JsonObject root = new JsonObject();
			root.addProperty(MadokuCropConfig.FIELD_CROP_ID, cropId);
			root.addProperty(MadokuCropConfig.FIELD_CROP_BLOCK_ID, cropBlockId);
			if (usesDistinctMatureBlock()) {
				root.addProperty(MadokuCropConfig.FIELD_MATURE_BLOCK_ID, matureBlockId);
			}
			root.addProperty(MadokuCropConfig.FIELD_PLANTING_ITEM_ID, plantingItemId);
			root.addProperty(MadokuCropConfig.FIELD_HARVEST_ITEM_ID, harvestItemId);
			if (secondaryHarvestItemId != null && !secondaryHarvestItemId.isBlank() && secondaryHarvestItem != null) {
				root.addProperty(MadokuCropConfig.FIELD_SECONDARY_HARVEST_ITEM_ID, secondaryHarvestItemId);
				root.addProperty(MadokuCropConfig.FIELD_SECONDARY_MIN_HARVEST_COUNT, secondaryMinHarvestCount);
				root.addProperty(MadokuCropConfig.FIELD_SECONDARY_MAX_HARVEST_COUNT, secondaryMaxHarvestCount);
			}
			root.addProperty(MadokuCropConfig.FIELD_GROWTH_MINECRAFT_DAYS, growthMinecraftDays);
			root.addProperty(MadokuCropConfig.FIELD_MIN_HARVEST_COUNT, minHarvestCount);
			root.addProperty(MadokuCropConfig.FIELD_MAX_HARVEST_COUNT, maxHarvestCount);
			JsonArray blockedSeasons = new JsonArray();
			for (String seasonId : blockedSeasonIds) {
				if (seasonId != null && !seasonId.isBlank()) {
					blockedSeasons.add(seasonId);
				}
			}
			root.add(MadokuCropConfig.FIELD_PLANTING_BLOCKED_SEASONS, blockedSeasons);
			return root;
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
	}

	private static final class PlotState {
		private final String levelId;
		private final long soilPos;
		private long cropPos;
		private String cropId;
		private boolean fertilized;
			private long fertilizedAtAbsoluteDayTime;
				private double growthProgress;
				private transient long lastParticleEmissionTimeTicks;
				private transient long nextParticleEmissionTimeTicks;
				private transient long missingCropSinceGameplayTicks;

		private PlotState(String levelId, long soilPos) {
			this.levelId = levelId == null ? "" : levelId;
			this.soilPos = soilPos;
			this.cropPos = -1L;
			this.cropId = "";
			this.fertilized = false;
				this.fertilizedAtAbsoluteDayTime = Long.MIN_VALUE;
					this.growthProgress = 0.0d;
					this.lastParticleEmissionTimeTicks = Long.MIN_VALUE;
					this.nextParticleEmissionTimeTicks = Long.MIN_VALUE;
					this.missingCropSinceGameplayTicks = Long.MIN_VALUE;
				}

		private String key() {
			return levelId + "|" + soilPos;
		}

		private boolean hasCrop() {
			return cropPos != -1L && resolveCropRuleByPlantingItemId(cropId) != null;
		}

			private void clearCrop() {
				cropPos = -1L;
				cropId = "";
				growthProgress = 0.0d;
				missingCropSinceGameplayTicks = Long.MIN_VALUE;
			}

		private JsonObject toJson() {
			JsonObject root = new JsonObject();
			root.addProperty(FIELD_LEVEL_ID, levelId);
			root.addProperty(FIELD_SOIL_POS, soilPos);
			root.addProperty(FIELD_CROP_POS, cropPos);
			root.addProperty(FIELD_CROP_ID, cropId);
			root.addProperty(FIELD_FERTILIZED, fertilized);
			root.addProperty(FIELD_FERTILIZED_AT_ABSOLUTE_DAY_TIME,
				fertilizedAtAbsoluteDayTime == Long.MIN_VALUE ? -1L : fertilizedAtAbsoluteDayTime);
			root.addProperty(FIELD_GROWTH_PROGRESS, Math.max(0.0d, growthProgress));
			root.addProperty(FIELD_LAST_PARTICLE_EMISSION_TICKS, lastParticleEmissionTimeTicks == Long.MIN_VALUE ? -1L : lastParticleEmissionTimeTicks);
			return root;
		}

		private static PlotState fromJson(JsonElement element) {
			if (element == null || !element.isJsonObject()) {
				return null;
			}

			JsonObject source = element.getAsJsonObject();
			String levelId = readString(source, FIELD_LEVEL_ID, "").trim();
			if (levelId.isEmpty()) {
				return null;
			}

			long soilPos = readLong(source, FIELD_SOIL_POS, Long.MIN_VALUE);
			if (soilPos == Long.MIN_VALUE) {
				return null;
			}

			PlotState plot = new PlotState(levelId, soilPos);
			plot.cropPos = readLong(source, FIELD_CROP_POS, -1L);
			plot.cropId = readString(source, FIELD_CROP_ID, "");
			plot.fertilized = readBoolean(source, FIELD_FERTILIZED, false);
			plot.fertilizedAtAbsoluteDayTime = readLong(source, FIELD_FERTILIZED_AT_ABSOLUTE_DAY_TIME, -1L);
			if (plot.fertilizedAtAbsoluteDayTime < 0L) {
				plot.fertilizedAtAbsoluteDayTime = Long.MIN_VALUE;
			}
			double progress = readDouble(source, FIELD_GROWTH_PROGRESS, 0.0d);
			plot.growthProgress = Math.max(0.0d, Math.min(1.0d, Double.isFinite(progress) ? progress : 0.0d));
			plot.lastParticleEmissionTimeTicks = readLong(source, FIELD_LAST_PARTICLE_EMISSION_TICKS, -1L);
			if (plot.lastParticleEmissionTimeTicks < 0L) {
				plot.lastParticleEmissionTimeTicks = Long.MIN_VALUE;
			}

			if (plot.hasCrop()) {
				plot.growthProgress = Math.max(plot.growthProgress, 0.0d);
				plot.fertilizedAtAbsoluteDayTime = Long.MIN_VALUE;
			} else {
				plot.cropPos = -1L;
				plot.cropId = "";
				if (!plot.fertilized) {
					plot.growthProgress = 0.0d;
				} else if (plot.fertilizedAtAbsoluteDayTime == Long.MIN_VALUE) {
					plot.fertilizedAtAbsoluteDayTime = MadokuTime.getCurrentAbsoluteDayTime();
				}
			}

			return plot;
		}
	}

	private record PendingHarvestRule(CropRule rule, long expiresAtGameplayTick, boolean fertilized) {
		private boolean matches(CropRule other) {
			return rule != null && other != null && rule.cropId().equals(other.cropId());
		}
	}

		private record Settings(
			boolean enabled,
			double rainGrowthBonus,
			double fertilizedGrowthBonus,
			double outOfSeasonGrowthMultiplier,
			int particleCount,
			double particleSpread,
			double particleYOffset
		) {
			private static Settings defaults() {
					return new Settings(
						true,
						MadokuFarmingConfig.DEFAULT_RAIN_GROWTH_BONUS,
						MadokuFarmingConfig.DEFAULT_FERTILIZED_GROWTH_BONUS,
						MadokuFarmingConfig.DEFAULT_OUT_OF_SEASON_GROWTH_MULTIPLIER,
						MadokuFarmingConfig.DEFAULT_PARTICLE_COUNT,
						MadokuFarmingConfig.DEFAULT_PARTICLE_SPREAD,
						MadokuFarmingConfig.DEFAULT_PARTICLE_Y_OFFSET
				);
			}

			private static Settings fromJson(JsonObject source) {
				boolean enabled = readBoolean(source, MadokuFarmingConfig.FIELD_FARMING_SYSTEM_ENABLED, true);
				double rainBonus = clampDouble(
					readDouble(source, MadokuFarmingConfig.FIELD_RAIN_GROWTH_BONUS, MadokuFarmingConfig.DEFAULT_RAIN_GROWTH_BONUS),
					MadokuFarmingConfig.DEFAULT_RAIN_GROWTH_BONUS,
					0.0d,
					1.0d
				);
					double fertilizedBonus = clampDouble(
						readDouble(source, MadokuFarmingConfig.FIELD_FERTILIZED_GROWTH_BONUS, MadokuFarmingConfig.DEFAULT_FERTILIZED_GROWTH_BONUS),
						MadokuFarmingConfig.DEFAULT_FERTILIZED_GROWTH_BONUS,
						0.0d,
						1.0d
					);
					double outOfSeasonGrowthMultiplier = clampDouble(
						readDouble(source, MadokuFarmingConfig.FIELD_OUT_OF_SEASON_GROWTH_MULTIPLIER, MadokuFarmingConfig.DEFAULT_OUT_OF_SEASON_GROWTH_MULTIPLIER),
						MadokuFarmingConfig.DEFAULT_OUT_OF_SEASON_GROWTH_MULTIPLIER,
						0.0d,
						1000.0d
					);
					int particleCount = clampInt(
						readInt(source, MadokuFarmingConfig.FIELD_PARTICLE_COUNT, MadokuFarmingConfig.DEFAULT_PARTICLE_COUNT),
					MadokuFarmingConfig.DEFAULT_PARTICLE_COUNT,
					1,
					MadokuFarmingConfig.MAX_PARTICLE_COUNT
				);
				double particleSpread = clampDouble(
					readDouble(source, MadokuFarmingConfig.FIELD_PARTICLE_SPREAD, MadokuFarmingConfig.DEFAULT_PARTICLE_SPREAD),
					MadokuFarmingConfig.DEFAULT_PARTICLE_SPREAD,
					0.0d,
					3.0d
				);
				double particleYOffset = clampDouble(
					readDouble(source, MadokuFarmingConfig.FIELD_PARTICLE_Y_OFFSET, MadokuFarmingConfig.DEFAULT_PARTICLE_Y_OFFSET),
					MadokuFarmingConfig.DEFAULT_PARTICLE_Y_OFFSET,
					0.0d,
					3.0d
				);

					return new Settings(
						enabled,
						rainBonus,
						fertilizedBonus,
						outOfSeasonGrowthMultiplier,
						particleCount,
						particleSpread,
						particleYOffset
					);
			}

			private JsonObject toConfigJson() {
				JsonObject root = new JsonObject();
				root.addProperty(MadokuFarmingConfig.FIELD_FARMING_SYSTEM_ENABLED, enabled);
				root.addProperty(MadokuFarmingConfig.FIELD_RAIN_GROWTH_BONUS, rainGrowthBonus);
				root.addProperty(MadokuFarmingConfig.FIELD_FERTILIZED_GROWTH_BONUS, fertilizedGrowthBonus);
				root.addProperty(MadokuFarmingConfig.FIELD_OUT_OF_SEASON_GROWTH_MULTIPLIER, outOfSeasonGrowthMultiplier);
				return root;
			}
		}
}
