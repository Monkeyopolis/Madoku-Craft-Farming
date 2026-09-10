package madoku.craft.mixin.farming;

import madoku.craft.java.farming.FarmingAPIManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FarmlandBlock.class)
public abstract class FarmlandBlockFarmingMixin {
	@Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
	private void madokuCraft$applySeasonalMoisture(
		BlockState state,
		ServerLevel level,
		BlockPos pos,
		RandomSource random,
		CallbackInfo ci
	) {
		if (!FarmingAPIManager.isEnabled()) {
			return;
		}

		FarmingAPIManager.handleFarmlandRandomTick(level, pos);
		if (FarmingAPIManager.applySeasonalMoisture(level, pos, state)) {
			ci.cancel();
		}
	}

	@Inject(method = "turnToDirt", at = @At("HEAD"), cancellable = true)
	private static void madokuCraft$preventManagedFarmlandDirt(
		Entity entity,
		BlockState state,
		Level level,
		BlockPos pos,
		CallbackInfo ci
	) {
		if (shouldHoldManagedFarmland(level, pos)) {
			ci.cancel();
		}
	}

	@Inject(method = "shouldMaintainFarmland", at = @At("RETURN"), cancellable = true)
	private static void madokuCraft$holdManagedFarmland(
		BlockGetter level,
		BlockPos pos,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (Boolean.FALSE.equals(cir.getReturnValue()) && shouldHoldManagedFarmland(level, pos)) {
			cir.setReturnValue(true);
		}
	}

	private static boolean shouldHoldManagedFarmland(BlockGetter level, BlockPos pos) {
		if (!FarmingAPIManager.isEnabled() || level == null || pos == null) {
			return false;
		}

		if (level instanceof ServerLevel serverLevel && FarmingAPIManager.shouldMaintainSeasonalMoisture(serverLevel, pos)) {
			return true;
		}

		BlockPos abovePos = pos.above();
		BlockState aboveState = level.getBlockState(abovePos);
		if (FarmingAPIManager.isManagedCrop(aboveState)) {
			if (level instanceof ServerLevel serverLevel && !FarmingAPIManager.isManagedPlot(serverLevel, pos)) {
				FarmingAPIManager.syncPlotFromSoil(serverLevel, pos, FarmingAPIManager.isFertilized(serverLevel, pos));
			}
			return true;
		}

		if (level instanceof ServerLevel serverLevel) {
			if (!FarmingAPIManager.isManagedPlot(serverLevel, pos) && FarmingAPIManager.isManagedCrop(serverLevel, abovePos, aboveState)) {
				FarmingAPIManager.syncPlotFromSoil(serverLevel, pos, FarmingAPIManager.isFertilized(serverLevel, pos));
			}
			return FarmingAPIManager.isManagedPlot(serverLevel, pos) || FarmingAPIManager.isManagedCrop(serverLevel, abovePos, aboveState);
		}

		return false;
	}
}

