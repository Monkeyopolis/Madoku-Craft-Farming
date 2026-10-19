package madoku.craft.java.farming;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import madoku.craft.java.core.chunk.ChunkAPIManager;
import madoku.craft.java.core.runtime.AdaptiveIntervalAPIManager;
import madoku.craft.java.core.time.TimeAPIManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;

/** Publishes nearby plot progress without exposing the farming runtime to the client. */
final class FarmingPlotIndicatorManager {
	private static final String ADAPTIVE_INTERVAL_ID = "madoku-farming-plot-indicators";
	private static final long MIN_SYNC_INTERVAL_TICKS = 2L;
	private static final long MAX_SYNC_INTERVAL_TICKS = 10L;
	private static final double GROWTH_SPEED_CHANGE_EPSILON = 0.01D;
	private static final double WORLD_CLOCK_RATE_CHANGE_EPSILON = 0.001D;
	private static final int INDICATOR_VIEW_DISTANCE = 8;
	private static final Map<UUID, Map<Long, FarmingCropsManager.PlotIndicatorState>> LAST_SENT = new HashMap<>();
	private static final Map<UUID, ServerLevel> LAST_LEVEL_BY_PLAYER = new HashMap<>();
	private static final Map<UUID, ServerLevel> LAST_SYNC_CELL_LEVEL_BY_PLAYER = new HashMap<>();
	private static final Map<UUID, Long> LAST_SYNC_CELL_BY_PLAYER = new HashMap<>();
	private static final Map<ServerLevel, Set<UUID>> DIRTY_PLAYERS_BY_LEVEL = new HashMap<>();
	private static final ChunkAPIManager.ChunkLifecycleListener CHUNK_LISTENER = new ChunkAPIManager.ChunkLifecycleListener() {
		@Override public void onChunkLoaded(ServerLevel level, int chunkX, int chunkZ) {
			requestResync(level, chunkX, chunkZ);
		}

		@Override public void onChunkUnloaded(ServerLevel level, int chunkX, int chunkZ) {
			requestResync(level, chunkX, chunkZ);
		}
	};
	private static long nextSyncTick = Long.MIN_VALUE;
	private static boolean initialized;

	private FarmingPlotIndicatorManager() {
	}

	static void initialize() {
		if (initialized) {
			return;
		}
		PayloadTypeRegistry.clientboundPlay().register(
			FarmingPlotIndicatorPayloadManager.TYPE,
			FarmingPlotIndicatorPayloadManager.CODEC
		);
		ChunkAPIManager.registerChunkLifecycleListener(CHUNK_LISTENER);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> syncPlayer(handler.player));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (handler != null && handler.player != null) {
				LAST_SENT.remove(handler.player.getUUID());
				LAST_LEVEL_BY_PLAYER.remove(handler.player.getUUID());
				LAST_SYNC_CELL_LEVEL_BY_PLAYER.remove(handler.player.getUUID());
				LAST_SYNC_CELL_BY_PLAYER.remove(handler.player.getUUID());
				clearDirtyPlayer(handler.player.getUUID());
			}
		});
		initialized = true;
	}

	static void onServerStarted(MinecraftServer server) {
		LAST_SENT.clear();
		LAST_LEVEL_BY_PLAYER.clear();
		LAST_SYNC_CELL_LEVEL_BY_PLAYER.clear();
		LAST_SYNC_CELL_BY_PLAYER.clear();
		DIRTY_PLAYERS_BY_LEVEL.clear();
		AdaptiveIntervalAPIManager.clearSystem(ADAPTIVE_INTERVAL_ID);
		nextSyncTick = Long.MIN_VALUE;
	}

	static void onServerTick(MinecraftServer server) {
		if (server == null) {
			return;
		}
		if (!FarmingCropsManager.isPlotIndicatorEnabled()) {
			clearIndicators(server);
			return;
		}

		long now = Math.max(0L, TimeAPIManager.getGameplayTicks());
		boolean intervalDue = nextSyncTick == Long.MIN_VALUE || now >= nextSyncTick;
		if (intervalDue) {
			long interval = AdaptiveIntervalAPIManager.resolve(
				ADAPTIVE_INTERVAL_ID,
				server,
				MIN_SYNC_INTERVAL_TICKS,
				MAX_SYNC_INTERVAL_TICKS
			);
			nextSyncTick = now + Math.max(1L, interval);
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!(player.level() instanceof ServerLevel serverLevel)) {
				continue;
			}
			boolean forcedResync = consumeDirtyPlayer(serverLevel, player.getUUID());
			if (!intervalDue && !forcedResync) {
				continue;
			}
			boolean enteredSyncCell = updateSyncCell(player, serverLevel);
			List<FarmingCropsManager.PlotIndicatorState> availableStates = FarmingCropsManager.getPlotIndicatorStates(
				serverLevel,
				player.blockPosition(),
				INDICATOR_VIEW_DISTANCE
			);
			syncPlayer(
				player,
				availableStates,
				forcedResync || enteredSyncCell
			);
		}
	}

	static void onServerStopped() {
		LAST_SENT.clear();
		LAST_LEVEL_BY_PLAYER.clear();
		LAST_SYNC_CELL_LEVEL_BY_PLAYER.clear();
		LAST_SYNC_CELL_BY_PLAYER.clear();
		DIRTY_PLAYERS_BY_LEVEL.clear();
		AdaptiveIntervalAPIManager.clearSystem(ADAPTIVE_INTERVAL_ID);
		nextSyncTick = Long.MIN_VALUE;
	}

	private static void clearIndicators(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Map<Long, FarmingCropsManager.PlotIndicatorState> previous = LAST_SENT.get(player.getUUID());
			if (previous == null || previous.isEmpty()) {
				continue;
			}
			for (Long soilPos : previous.keySet()) {
				FarmingPlotIndicatorNetworking.send(player, new FarmingPlotIndicatorPayloadManager(
					soilPos,
					-1L,
					0.0F,
					0.0F,
					1.0F,
					true
				));
			}
			previous.clear();
		}
		DIRTY_PLAYERS_BY_LEVEL.clear();
	}

	private static void requestResync(ServerLevel level, int chunkX, int chunkZ) {
		if (level == null || level.getServer() == null) {
			return;
		}
		Set<UUID> dirtyPlayers = null;
		for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
			if (player == null || player.level() != level || !isChunkRelevant(player, chunkX, chunkZ)) {
				continue;
			}
			if (dirtyPlayers == null) {
				dirtyPlayers = DIRTY_PLAYERS_BY_LEVEL.computeIfAbsent(level, ignored -> new HashSet<>());
			}
			dirtyPlayers.add(player.getUUID());
		}
	}

	private static boolean consumeDirtyPlayer(ServerLevel level, UUID playerId) {
		if (level == null || playerId == null) {
			return false;
		}
		Set<UUID> dirtyPlayers = DIRTY_PLAYERS_BY_LEVEL.get(level);
		if (dirtyPlayers == null) {
			return false;
		}
		boolean dirty = dirtyPlayers.remove(playerId);
		if (dirtyPlayers.isEmpty()) {
			DIRTY_PLAYERS_BY_LEVEL.remove(level);
		}
		return dirty;
	}

	private static void clearDirtyPlayer(UUID playerId) {
		if (playerId == null) {
			return;
		}
		for (Set<UUID> dirtyPlayers : DIRTY_PLAYERS_BY_LEVEL.values()) {
			dirtyPlayers.remove(playerId);
		}
		DIRTY_PLAYERS_BY_LEVEL.entrySet().removeIf(entry -> entry.getValue().isEmpty());
	}

	private static boolean isChunkRelevant(ServerPlayer player, int chunkX, int chunkZ) {
		if (player == null) {
			return false;
		}
		double minX = chunkX * 16.0D;
		double maxX = minX + 16.0D;
		double minZ = chunkZ * 16.0D;
		double maxZ = minZ + 16.0D;
		double dx = player.getX() < minX ? minX - player.getX() : player.getX() > maxX ? player.getX() - maxX : 0.0D;
		double dz = player.getZ() < minZ ? minZ - player.getZ() : player.getZ() > maxZ ? player.getZ() - maxZ : 0.0D;
		return dx * dx + dz * dz <= (double) INDICATOR_VIEW_DISTANCE * INDICATOR_VIEW_DISTANCE;
	}

	private static boolean updateSyncCell(ServerPlayer player, ServerLevel level) {
		if (player == null || level == null) {
			return false;
		}

		UUID playerId = player.getUUID();
		int cellX = player.getBlockX() >> 3;
		int cellY = player.getBlockY() >> 3;
		int cellZ = player.getBlockZ() >> 3;
		long cell = new BlockPos(cellX, cellY, cellZ).asLong();
		ServerLevel previousLevel = LAST_SYNC_CELL_LEVEL_BY_PLAYER.put(playerId, level);
		Long previousCell = LAST_SYNC_CELL_BY_PLAYER.put(playerId, cell);
		return previousLevel != level || previousCell == null || previousCell.longValue() != cell;
	}

	/** Sends a removal only for an actual plot invalidation or completed crop. */
	static void invalidatePlot(ServerLevel level, long soilPos) {
		if (level == null) {
			return;
		}
		for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
			if (player == null || player.level() != level) {
				continue;
			}
			Map<Long, FarmingCropsManager.PlotIndicatorState> previous = LAST_SENT.get(player.getUUID());
			if (previous != null) {
				previous.remove(soilPos);
			}
			FarmingPlotIndicatorNetworking.send(player, new FarmingPlotIndicatorPayloadManager(
				soilPos,
				-1L,
				0.0F,
				0.0F,
				1.0F,
				true
			));
		}
	}

	private static void syncPlayer(ServerPlayer player) {
		if (player == null || !(player.level() instanceof ServerLevel serverLevel)) {
			return;
		}
		syncPlayer(
			player,
			FarmingCropsManager.getPlotIndicatorStates(
				serverLevel,
				player.blockPosition(),
				INDICATOR_VIEW_DISTANCE
			),
			true
		);
	}

	private static void syncPlayer(
		ServerPlayer player,
		List<FarmingCropsManager.PlotIndicatorState> availableStates,
		boolean forceResync
	) {
		if (player == null || availableStates == null || !(player.level() instanceof ServerLevel serverLevel)) {
			return;
		}

		UUID playerId = player.getUUID();
		ServerLevel previousLevel = LAST_LEVEL_BY_PLAYER.put(playerId, serverLevel);
		Map<Long, FarmingCropsManager.PlotIndicatorState> previous = LAST_SENT.computeIfAbsent(
			playerId,
			ignored -> new HashMap<>()
		);
		if (previousLevel != null && previousLevel != serverLevel) {
			previous.clear();
		}

		Map<Long, FarmingCropsManager.PlotIndicatorState> current = new HashMap<>();
		for (FarmingCropsManager.PlotIndicatorState state : availableStates) {
			if (state == null) {
				continue;
			}
			current.put(state.soilPos(), state);
		}

		for (Map.Entry<Long, FarmingCropsManager.PlotIndicatorState> entry : current.entrySet()) {
			FarmingCropsManager.PlotIndicatorState rawState = entry.getValue();
			FarmingCropsManager.PlotIndicatorState oldState = previous.get(entry.getKey());
			FarmingCropsManager.PlotIndicatorState state = stabilize(oldState, rawState);
			boolean stateChanged = changed(oldState, state);
			boolean shouldSend = forceResync || stateChanged;
			if (shouldSend) {
				FarmingPlotIndicatorPayloadManager payload = toPayload(state);
				FarmingPlotIndicatorNetworking.send(player, payload);
			}
			entry.setValue(state);
		}
		for (var iterator = previous.entrySet().iterator(); iterator.hasNext();) {
			Map.Entry<Long, FarmingCropsManager.PlotIndicatorState> entry = iterator.next();
			if (current.containsKey(entry.getKey())) {
				continue;
			}
			if (FarmingCropsManager.isPlotIndicatorSuppressed(serverLevel, entry.getKey())) {
				FarmingPlotIndicatorNetworking.send(player, new FarmingPlotIndicatorPayloadManager(
					entry.getKey(),
					-1L,
					0.0F,
					0.0F,
					1.0F,
					true
				));
				iterator.remove();
				continue;
			}
			if (FarmingCropsManager.isPlotGrowthComplete(serverLevel, entry.getKey())) {
				FarmingPlotIndicatorNetworking.send(player, new FarmingPlotIndicatorPayloadManager(
					entry.getKey(),
					-1L,
					0.0F,
					0.0F,
					1.0F,
					true
				));
				iterator.remove();
				continue;
			}
			if (isNear(player, entry.getKey())) {
				// A nearby registry entry can be temporarily unavailable while its chunk
				// lifecycle callback is settling. Keep the last client state in place.
				continue;
			}
			// Distance is a client rendering concern. Drop only the server's per-player
			// resend cache; the client remains responsible for hiding the old state.
			iterator.remove();
		}

		previous.putAll(current);
	}

	private static FarmingCropsManager.PlotIndicatorState stabilize(
		FarmingCropsManager.PlotIndicatorState previous,
		FarmingCropsManager.PlotIndicatorState current
	) {
		if (previous == null || current == null || previous.cropPos() != current.cropPos()) {
			return current;
		}
		if (Math.abs(current.growthSpeedMultiplier() - previous.growthSpeedMultiplier()) >= GROWTH_SPEED_CHANGE_EPSILON
			|| Math.abs(current.worldClockRate() - previous.worldClockRate()) >= WORLD_CLOCK_RATE_CHANGE_EPSILON) {
			// A weather, fertilization, or clock-rate change can make the displayed
			// duration increase. Do not preserve a stale near-zero value.
			return current;
		}
		return current.remainingGrowthTicks() <= previous.remainingGrowthTicks()
			? current
			: new FarmingCropsManager.PlotIndicatorState(
				current.soilPos(),
				current.cropPos(),
				previous.remainingGrowthTicks(),
				current.growthSpeedMultiplier(),
				current.worldClockRate()
			);
	}

	private static boolean isNear(ServerPlayer player, long soilPos) {
		BlockPos pos = BlockPos.of(soilPos);
		double dx = player.getX() - (pos.getX() + 0.5D);
		double dy = player.getY() - (pos.getY() + 0.5D);
		double dz = player.getZ() - (pos.getZ() + 0.5D);
		return dx * dx + dy * dy + dz * dz <= (double) INDICATOR_VIEW_DISTANCE * INDICATOR_VIEW_DISTANCE;
	}

	private static boolean changed(
		FarmingCropsManager.PlotIndicatorState oldState,
		FarmingCropsManager.PlotIndicatorState newState
	) {
		if (oldState == null || newState == null) {
			return oldState != newState;
		}
		return oldState.cropPos() != newState.cropPos()
			|| Math.abs(oldState.remainingGrowthTicks() - newState.remainingGrowthTicks()) >= 0.25D
			|| Math.abs(oldState.growthSpeedMultiplier() - newState.growthSpeedMultiplier()) >= GROWTH_SPEED_CHANGE_EPSILON
			|| Math.abs(oldState.worldClockRate() - newState.worldClockRate()) >= WORLD_CLOCK_RATE_CHANGE_EPSILON;
	}

	private static FarmingPlotIndicatorPayloadManager toPayload(FarmingCropsManager.PlotIndicatorState state) {
		return new FarmingPlotIndicatorPayloadManager(
			state.soilPos(),
			state.cropPos(),
			(float) Math.min(Float.MAX_VALUE, state.remainingGrowthTicks()),
			(float) Math.min(Float.MAX_VALUE, state.growthSpeedMultiplier()),
			state.worldClockRate(),
			false
		);
	}
}
