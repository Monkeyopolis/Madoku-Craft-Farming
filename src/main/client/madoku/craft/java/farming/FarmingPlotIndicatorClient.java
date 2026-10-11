package madoku.craft.java.farming;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Renders compact growth-time and growth-speed rows above managed crop plots. */
public final class FarmingPlotIndicatorClient {
	private static final Map<Long, ClientPlotState> STATES_BY_SOIL = new ConcurrentHashMap<>();
	private static final List<VisiblePlot> VISIBLE_PLOTS = new ArrayList<>();
	private static final double MAX_INDICATOR_DISTANCE = 8.0D;
	private static final long VISIBILITY_REFRESH_INTERVAL_TICKS = 2L;
	private static final float CAMERA_REFRESH_ANGLE = 12.0F;
	private static final float INDICATOR_SCALE = 0.72F;
	private static final float ROW_SPACING = 0.28F;
	private static final Identifier INDICATOR_FONT_ID =
		Identifier.fromNamespaceAndPath("madoku-craft", "plot_indicator");
	private static final FontDescription INDICATOR_FONT = new FontDescription.Resource(INDICATOR_FONT_ID);
	private static final Identifier INDICATOR_SPACING_FONT_ID =
		Identifier.fromNamespaceAndPath("madoku-craft", "plot_indicator_spacing");
	private static final FontDescription INDICATOR_SPACING_FONT = new FontDescription.Resource(INDICATOR_SPACING_FONT_ID);
	private static final char GROWTH_TIME_ICON_CHARACTER = '\uE010';
	private static final char GROWTH_SPEED_ICON_CHARACTER = '\uE011';
	private static final char INDICATOR_SPACING_CHARACTER = '\uE012';
	private static ClientLevel visibleCacheLevel;
	private static long visibleCacheGameTime = Long.MIN_VALUE;
	private static long visibleCacheCameraBlockPos = Long.MIN_VALUE;
	private static float visibleCacheCameraXRot;
	private static float visibleCacheCameraYRot;
	private static volatile boolean visibleCacheDirty = true;
	private static boolean initialized;

	private FarmingPlotIndicatorClient() {
	}

	public static void initialize() {
		if (initialized) {
			return;
		}
		initialized = true;
		ClientPlayNetworking.registerGlobalReceiver(
			FarmingPlotIndicatorPayloadManager.TYPE,
			(payload, context) -> {
				if (payload.removed()) {
					STATES_BY_SOIL.remove(payload.soilPos());
					visibleCacheDirty = true;
					return;
				}
				ClientLevel level = Minecraft.getInstance().level;
				ClientPlotState previous = STATES_BY_SOIL.get(payload.soilPos());
				double receivedAtGameTime = currentClientTime();
				float receivedRemaining = Math.max(0.0F, payload.remainingGrowthTicks());
				if (previous != null && previous.cropPos().equals(BlockPos.of(payload.cropPos()))) {
					double previousElapsed = Math.max(0.0D, receivedAtGameTime - previous.receivedAtGameTime());
					double locallyProjectedRemaining = Math.max(
						0.0D,
						previous.remainingGrowthTicks() - previousElapsed * previous.growthSpeedMultiplier()
					);
					if (receivedRemaining > locallyProjectedRemaining) {
						receivedRemaining = (float) locallyProjectedRemaining;
					}
				}
				STATES_BY_SOIL.put(payload.soilPos(), new ClientPlotState(
					level,
					BlockPos.of(payload.soilPos()),
					BlockPos.of(payload.cropPos()),
					receivedRemaining,
					Math.max(0.0F, payload.growthSpeedMultiplier()),
					receivedAtGameTime
				));
				visibleCacheDirty = true;
			}
		);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			STATES_BY_SOIL.clear();
			VISIBLE_PLOTS.clear();
			visibleCacheLevel = null;
			visibleCacheGameTime = Long.MIN_VALUE;
			visibleCacheDirty = true;
		});
	}

	public static void render(
		LevelRenderState levelRenderState,
		PoseStack poseStack,
		SubmitNodeCollector collector
	) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null || levelRenderState == null || poseStack == null || collector == null) {
			return;
		}
		if (visibleCacheLevel != level) {
			STATES_BY_SOIL.entrySet().removeIf(entry -> {
				ClientPlotState plot = entry.getValue();
				return plot == null || (plot.level() != null && plot.level() != level);
			});
			bindUnboundStates(level);
			VISIBLE_PLOTS.clear();
			visibleCacheLevel = level;
			visibleCacheGameTime = Long.MIN_VALUE;
			visibleCacheDirty = true;
		}

		CameraRenderState cameraState = levelRenderState.cameraRenderState;
		if (cameraState == null || cameraState.pos == null) {
			return;
		}
		if (shouldRefreshVisiblePlots(levelRenderState, cameraState)) {
			refreshVisiblePlots(level, levelRenderState, cameraState);
		}

		for (VisiblePlot visiblePlot : VISIBLE_PLOTS) {
			ClientPlotState plot = bindStateToLevel(
				visiblePlot.soilPosKey(),
				STATES_BY_SOIL.get(visiblePlot.soilPosKey()),
				level
			);
			if (plot == null || (plot.level() != null && plot.level() != level)) {
				continue;
			}
			if (!isInFrustum(visiblePlot, cameraState)) {
				continue;
			}

			visiblePlot.updateComponents(plot, levelRenderState.gameTime);

			Vec3 anchor = new Vec3(
				visiblePlot.soilPos().getX() + 0.5D,
				visiblePlot.soilPos().getY() + 1.35D,
				visiblePlot.soilPos().getZ() + 0.5D
			);
			poseStack.pushPose();
			poseStack.translate(
				anchor.x - cameraState.pos.x,
				anchor.y - cameraState.pos.y,
				anchor.z - cameraState.pos.z
			);
			poseStack.scale(INDICATOR_SCALE, INDICATOR_SCALE, INDICATOR_SCALE);
			submitRow(
				collector,
				poseStack,
				cameraState,
				visiblePlot.timeComponent(),
				ROW_SPACING,
				visiblePlot.indicatorLight()
			);
			submitRow(
				collector,
				poseStack,
				cameraState,
				visiblePlot.speedComponent(),
				0.0F,
				visiblePlot.indicatorLight()
			);
			poseStack.popPose();
		}
	}

	private static void submitRow(
		SubmitNodeCollector collector,
		PoseStack poseStack,
		CameraRenderState cameraState,
		Component component,
		float y,
		int indicatorLight
	) {
		collector.submitNameTag(
			poseStack,
			new Vec3(0.0D, y, 0.0D),
			0,
			component,
			true,
			indicatorLight,
			cameraState
		);
	}

	private static boolean shouldRefreshVisiblePlots(
		LevelRenderState levelRenderState,
		CameraRenderState cameraState
	) {
		long gameTime = levelRenderState.gameTime;
		long cameraBlockPos = cameraState.blockPos == null ? Long.MIN_VALUE : cameraState.blockPos.asLong();
		if (visibleCacheDirty
			|| visibleCacheGameTime == Long.MIN_VALUE
			|| gameTime - visibleCacheGameTime >= VISIBILITY_REFRESH_INTERVAL_TICKS
			|| cameraBlockPos != visibleCacheCameraBlockPos) {
			return true;
		}
		return Math.abs(cameraState.xRot - visibleCacheCameraXRot) >= CAMERA_REFRESH_ANGLE
			|| Math.abs(cameraState.yRot - visibleCacheCameraYRot) >= CAMERA_REFRESH_ANGLE;
	}

	private static void refreshVisiblePlots(
		ClientLevel level,
		LevelRenderState levelRenderState,
		CameraRenderState cameraState
	) {
		// Server-side distance is intentionally not used to invalidate indicator
		// state. Bound the client cache by the chunks the client still has loaded;
		// entering a new server indicator cell forces a fresh state resend.
		STATES_BY_SOIL.entrySet().removeIf(entry -> {
			ClientPlotState plot = entry.getValue();
			if (plot == null || (plot.level() != null && plot.level() != level)) {
				return true;
			}
			BlockPos soilPos = plot.soilPos();
			return !level.getChunkSource().hasChunk(soilPos.getX() >> 4, soilPos.getZ() >> 4);
		});
		VISIBLE_PLOTS.clear();
		for (Map.Entry<Long, ClientPlotState> entry : STATES_BY_SOIL.entrySet()) {
			ClientPlotState plot = bindStateToLevel(entry.getKey(), entry.getValue(), level);
			if (plot == null || (plot.level() != null && plot.level() != level)) {
				continue;
			}
			if (!"visible".equals(visibilityReason(level, plot, cameraState))) {
				continue;
			}

			int indicatorLight = LightCoordsUtil.lightCoordsWithEmission(
				LightCoordsUtil.getLightCoords(level, plot.cropPos()),
				2
			);
			VISIBLE_PLOTS.add(new VisiblePlot(entry.getKey(), plot.soilPos(), indicatorLight));
		}

		visibleCacheGameTime = levelRenderState.gameTime;
		visibleCacheCameraBlockPos = cameraState.blockPos == null ? Long.MIN_VALUE : cameraState.blockPos.asLong();
		visibleCacheCameraXRot = cameraState.xRot;
		visibleCacheCameraYRot = cameraState.yRot;
		visibleCacheDirty = false;
	}

	private static String visibilityReason(
		ClientLevel level,
		ClientPlotState plot,
		CameraRenderState cameraState
	) {
		BlockPos soilPos = plot.soilPos();
		BlockPos cropPos = plot.cropPos();
		if (!level.getChunkSource().hasChunk(soilPos.getX() >> 4, soilPos.getZ() >> 4)
			|| !level.getBlockState(soilPos).is(Blocks.FARMLAND)) {
			return "chunk-not-loaded-or-not-farmland";
		}

		Vec3 center = new Vec3(soilPos.getX() + 0.5D, soilPos.getY() + 1.0D, soilPos.getZ() + 0.5D);
		double dx = center.x - cameraState.pos.x;
		double dy = center.y - cameraState.pos.y;
		double dz = center.z - cameraState.pos.z;
		if (dx * dx + dy * dy + dz * dz > MAX_INDICATOR_DISTANCE * MAX_INDICATOR_DISTANCE) {
			return "out-of-range";
		}

		Vec3 target = new Vec3(soilPos.getX() + 0.5D, soilPos.getY() + 1.35D, soilPos.getZ() + 0.5D);
		BlockHitResult hitResult = level.clip(new ClipContext(
			cameraState.pos,
			target,
			ClipContext.Block.VISUAL,
			ClipContext.Fluid.NONE,
			CollisionContext.empty()
		));
		return hitResult.getType() == HitResult.Type.MISS
			|| soilPos.equals(hitResult.getBlockPos())
			|| cropPos.equals(hitResult.getBlockPos())
			? "visible"
			: "occluded";
	}

	private static void bindUnboundStates(ClientLevel level) {
		for (Map.Entry<Long, ClientPlotState> entry : STATES_BY_SOIL.entrySet()) {
			bindStateToLevel(entry.getKey(), entry.getValue(), level);
		}
	}

	private static ClientPlotState bindStateToLevel(long soilPosKey, ClientPlotState plot, ClientLevel level) {
		if (plot == null || level == null || plot.level() != null) {
			return plot;
		}
		ClientPlotState bound = plot.withLevel(level);
		STATES_BY_SOIL.replace(soilPosKey, plot, bound);
		return bound;
	}

	private static boolean isInFrustum(VisiblePlot plot, CameraRenderState cameraState) {
		return cameraState.cullFrustum == null || cameraState.cullFrustum.isVisible(plot.bounds());
	}

	private static String formatRemainingTime(double remainingGrowthTicks, double speedMultiplier) {
		if (remainingGrowthTicks <= 0.05D) {
			return "0";
		}
		if (speedMultiplier <= 0.0D) {
			return "--";
		}

		long seconds = Math.max(1L, (long) Math.ceil(remainingGrowthTicks / speedMultiplier / 20.0D));
		long hours = seconds / 3600L;
		long minutes = (seconds % 3600L) / 60L;
		long remainingSeconds = seconds % 60L;
		if (hours > 0L) {
			return hours + ":" + twoDigits(minutes) + ":" + twoDigits(remainingSeconds);
		}
		if (minutes > 0L) {
			return minutes + ":" + twoDigits(remainingSeconds);
		}
		return Long.toString(seconds);
	}

	private static String twoDigits(long value) {
		return value < 10L ? "0" + value : Long.toString(value);
	}

	private static String formatSpeed(double value) {
		return Math.round((value - 1.0D) * 100.0D) + "%";
	}

	private static double currentClientTime() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.level == null ? 0.0D : minecraft.level.getGameTime();
	}

	private record ClientPlotState(
		ClientLevel level,
		BlockPos soilPos,
		BlockPos cropPos,
		float remainingGrowthTicks,
		float growthSpeedMultiplier,
		double receivedAtGameTime
	) {
		private ClientPlotState withLevel(ClientLevel nextLevel) {
			return new ClientPlotState(
				nextLevel,
				soilPos,
				cropPos,
				remainingGrowthTicks,
				growthSpeedMultiplier,
				receivedAtGameTime
			);
		}
	}

	private static final class VisiblePlot {
		private final long soilPosKey;
		private final BlockPos soilPos;
		private final int indicatorLight;
		private final AABB bounds;
		private long formattedGameTime = Long.MIN_VALUE;
		private Component timeComponent;
		private Component speedComponent;

		private VisiblePlot(long soilPosKey, BlockPos soilPos, int indicatorLight) {
			this.soilPosKey = soilPosKey;
			this.soilPos = soilPos;
			this.indicatorLight = indicatorLight;
			this.bounds = new AABB(
				soilPos.getX(),
				soilPos.getY(),
				soilPos.getZ(),
				soilPos.getX() + 1.0D,
				soilPos.getY() + 2.5D,
				soilPos.getZ() + 1.0D
			);
		}

		private void updateComponents(ClientPlotState plot, long gameTime) {
			if (formattedGameTime == gameTime) {
				return;
			}
			double elapsedTicks = Math.max(0.0D, gameTime - plot.receivedAtGameTime());
			double remainingGrowthTicks = Math.max(
				0.0D,
				plot.remainingGrowthTicks() - elapsedTicks * plot.growthSpeedMultiplier()
			);
			String nextTimeText = formatRemainingTime(remainingGrowthTicks, plot.growthSpeedMultiplier());
			String nextSpeedText = formatSpeed(plot.growthSpeedMultiplier());
			timeComponent = component(
				GROWTH_TIME_ICON_CHARACTER,
				nextTimeText,
				0xFFFFD34D
			);
			speedComponent = component(
				GROWTH_SPEED_ICON_CHARACTER,
				nextSpeedText,
				0xFF55FF77
			);
			formattedGameTime = gameTime;
		}

		private static Component component(char iconCharacter, String text, int color) {
			return Component.literal(String.valueOf(iconCharacter))
				.withStyle(style -> style.withFont(INDICATOR_FONT))
				.append(Component.literal(String.valueOf(INDICATOR_SPACING_CHARACTER))
					.withStyle(style -> style.withFont(INDICATOR_SPACING_FONT)))
				.append(Component.literal(text).withStyle(style -> style
					.withFont(FontDescription.DEFAULT)
					.withColor(color & 0xFFFFFF)));
		}

		private long soilPosKey() { return soilPosKey; }
		private BlockPos soilPos() { return soilPos; }
		private int indicatorLight() { return indicatorLight; }
		private AABB bounds() { return bounds; }
		private Component timeComponent() { return timeComponent; }
		private Component speedComponent() { return speedComponent; }
	}
}
