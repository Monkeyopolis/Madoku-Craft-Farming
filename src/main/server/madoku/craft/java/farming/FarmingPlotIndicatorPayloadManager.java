package madoku.craft.java.farming;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Synchronizes the server-authoritative display state for one managed crop plot. */
public record FarmingPlotIndicatorPayloadManager(
	long soilPos,
	long cropPos,
	float remainingGrowthTicks,
	float growthSpeedMultiplier,
	float worldClockRate,
	boolean removed
) implements CustomPacketPayload {
	public static final Type<FarmingPlotIndicatorPayloadManager> TYPE =
		new Type<>(Identifier.fromNamespaceAndPath("madoku-craft", "farming_plot_indicator"));
	public static final StreamCodec<RegistryFriendlyByteBuf, FarmingPlotIndicatorPayloadManager> CODEC =
		StreamCodec.composite(
			ByteBufCodecs.LONG,
			FarmingPlotIndicatorPayloadManager::soilPos,
			ByteBufCodecs.LONG,
			FarmingPlotIndicatorPayloadManager::cropPos,
			ByteBufCodecs.FLOAT,
			FarmingPlotIndicatorPayloadManager::remainingGrowthTicks,
			ByteBufCodecs.FLOAT,
			FarmingPlotIndicatorPayloadManager::growthSpeedMultiplier,
			ByteBufCodecs.FLOAT,
			FarmingPlotIndicatorPayloadManager::worldClockRate,
			ByteBufCodecs.BOOL,
			FarmingPlotIndicatorPayloadManager::removed,
			FarmingPlotIndicatorPayloadManager::new
		);

	@Override
	public Type<FarmingPlotIndicatorPayloadManager> type() {
		return TYPE;
	}
}
