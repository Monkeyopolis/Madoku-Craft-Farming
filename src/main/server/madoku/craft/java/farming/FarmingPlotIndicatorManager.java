package madoku.craft.java.farming;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
	private static final long MAX_SYNC_INTERVAL_TICKS = 8L;
	private static final int INDICATOR_VIEW_DISTANCE = 8;
	private static final Map<UUID, Map<Long, FarmingCropsManager.PlotIndicatorState>> LAST_SENT = new HashMap<>();
	private static final Map<UUID, ServerLevel> LAST_LEVEL_BY_PLAYER = new HashMap<>();
	private static final Map<UUID, ServerLevel> LAST_SYNC_CELL_LEVEL_BY_PLAYER = new HashMap<>();
	private static final Map<UUID, Long> LAST_SYNC_CELL_BY_PLAYER = new HashMap<>();
	private static final ChunkAPIManager.ChunkLifecycleListener CHUNK_LISTENER = new ChunkAPIManager.ChunkLifecycleListener() {
		@Override public void onChunkLoaded(ServerLevel level, int chunkX, int chunkZ) {
			requestResync(level, chunkX, chunkZ);
		}

		@Override public void onChunkUnloaded(ServerLevel level, int chunkX, int chunkZ) {
			requestResync(level, chunkX, chunkZ);
		}
	};
	private static long nextSyncTick = Long.MIN_VALUE;
	private static boolean resyncRequested;
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
			}
		});
		initialized = true;
	}

	static void onServerStarted(MinecraftServer server) {
		LAST_SENT.clear();
		LAST_LEVEL_BY_PLAYER.clear();
		LAST_SYNC_CELL_LEVEL_BY_PLAYER.clear();
		LAST_SYNC_CELL_BY_PLAYER.clear();
		AdaptiveIntervalAPIManager.clearSystem(ADAPTIVE_INTERVAL_ID);
		nextSyncTick = Long.MIN_VALUE;
		resyncRequested = false;
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
		boolean forcedResync = resyncRequested;
		resyncRequested = false;
		if (!forcedResync && nextSyncTick != Long.MIN_VALUE && now < nextSyncTick) {
			return;
		}
		long interval = AdaptiveIntervalAPIManager.resolve(
			ADAPTIVE_INTERVAL_ID,
			server,
			MIN_SYNC_INTERVAL_TICKS,
			MAX_SYNC_INTERVAL_TICKS
		);
		nextSyncTick = now + Math.max(1L, interval);

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!(player.level() instanceof ServerLevel serverLevel)) {
				continue;
			}
			boolean enteredSyncCell = updateSyncCell(player, serverLevel);
			syncPlayer(
				player,
				FarmingCropsManager.getPlotIndicatorStates(
					serverLevel,
					player.blockPosition(),
					INDICATOR_VIEW_DISTANCE
				),
				forcedResync || enteredSyncCell
			);
		}
	}

	static void onServerStopped() {
		LAST_SENT.clear();
		LAST_LEVEL_BY_PLAYER.clear();
		LAST_SYNC_CELL_LEVEL_BY_PLAYER.clear();
		LAST_SYNC_CELL_BY_PLAYER.clear();
		AdaptiveIntervalAPIManager.clearSystem(ADAPTIVE_INTERVAL_ID);
		nextSyncTick = Long.MIN_VALUE;
		resyncRequested = false;
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
					true
				));
			}
			previous.clear();
		}
	}

	private static void requestResync(ServerLevel level, int chunkX, int chunkZ) {
		resyncRequested = true;
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
			if (forceResync || changed(oldState, state)) {
				FarmingPlotIndicatorNetworking.send(player, toPayload(state));
			}
			entry.setValue(state);
		}
		for (var iterator = previous.entrySet().iterator(); iterator.hasNext();) {
			Map.Entry<Long, FarmingCropsManager.PlotIndicatorState> entry = iterator.next();
			if (current.containsKey(entry.getKey())) {
				continue;
			}
			if (FarmingCropsManager.isPlotGrowthComplete(serverLevel, entry.getKey())) {
				FarmingPlotIndicatorNetworking.send(player, new FarmingPlotIndicatorPayloadManager(
					entry.getKey(),
					-1L,
					0.0F,
					0.0F,
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
		return current.remainingGrowthTicks() <= previous.remainingGrowthTicks()
			? current
			: new FarmingCropsManager.PlotIndicatorState(
				current.soilPos(),
				current.cropPos(),
				previous.remainingGrowthTicks(),
				current.growthSpeedMultiplier()
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
			|| Math.abs(oldState.growthSpeedMultiplier() - newState.growthSpeedMultiplier()) >= 0.01D;
	}

	private static FarmingPlotIndicatorPayloadManager toPayload(FarmingCropsManager.PlotIndicatorState state) {
		return new FarmingPlotIndicatorPayloadManager(
			state.soilPos(),
			state.cropPos(),
			(float) Math.min(Float.MAX_VALUE, state.remainingGrowthTicks()),
			(float) Math.min(Float.MAX_VALUE, state.growthSpeedMultiplier()),
			false
		);
	}
}
