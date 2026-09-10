package madoku.craft.java.farming;

import madoku.craft.java.core.season.SeasonAPIManager;
import madoku.craft.java.core.season.SeasonBiomeClimateAPIManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

/** Runtime facade for farm-plot state owned by the farming runtime. */
public final class FarmingPlotsManager {
	private FarmingPlotsManager() {
	}

	public static boolean isFertilized(ServerLevel world, BlockPos soilPos) {
		return FarmingCropsManager.isFertilized(world, soilPos);
	}

	public static boolean isManagedPlot(ServerLevel world, BlockPos soilPos) {
		return FarmingCropsManager.isManagedPlot(world, soilPos);
	}

	public static void fertilizeSoil(ServerLevel world, BlockPos soilPos) {
		FarmingCropsManager.fertilizeSoil(world, soilPos);
	}

	public static void syncPlotFromSoil(ServerLevel world, BlockPos soilPos, boolean fertilized) {
		FarmingCropsManager.syncPlotFromSoil(world, soilPos, fertilized);
	}

	public static void handleFarmlandRandomTick(ServerLevel world, BlockPos soilPos) {
		FarmingCropsManager.handleFarmlandRandomTick(world, soilPos);
	}

	public static boolean shouldMaintainSeasonalMoisture(ServerLevel world, BlockPos soilPos) {
		if (world == null || soilPos == null || !FarmingCropsManager.isFarmland(world.getBlockState(soilPos))) {
			return false;
		}
		SeasonBiomeClimateAPIManager.Climate climate = SeasonAPIManager.resolveBiomeClimate(world, soilPos);
		return climate != null && Double.isFinite(climate.humidity()) && climate.humidity() >= 70.0D;
	}

	public static boolean applySeasonalMoisture(ServerLevel world, BlockPos soilPos, BlockState soilState) {
		if (!shouldMaintainSeasonalMoisture(world, soilPos) || soilState == null) {
			return false;
		}

		for (Property<?> property : soilState.getProperties()) {
			if (!(property instanceof IntegerProperty integerProperty) || !"moisture".equals(property.getName())) {
				continue;
			}
			int maximumMoisture = integerProperty.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(0);
			int currentMoisture = soilState.getValue(integerProperty);
			if (currentMoisture != maximumMoisture) {
				world.setBlock(soilPos, soilState.setValue(integerProperty, maximumMoisture), 2);
			}
			return true;
		}
		return false;
	}
}

