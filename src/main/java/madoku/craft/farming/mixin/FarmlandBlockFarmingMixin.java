package madoku.craft.farming.mixin;

import madoku.craft.farming.system.MadokuFarming;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FarmlandBlock.class)
public abstract class FarmlandBlockFarmingMixin {
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
		if (!MadokuFarming.isEnabled() || level == null || pos == null) {
			return false;
		}

		BlockPos abovePos = pos.above();
		BlockState aboveState = level.getBlockState(abovePos);
		if (MadokuFarming.isManagedCrop(aboveState)) {
			if (level instanceof ServerLevel serverLevel && !MadokuFarming.isManagedPlot(serverLevel, pos)) {
				MadokuFarming.syncPlotFromSoil(serverLevel, pos, MadokuFarming.isFertilized(serverLevel, pos));
			}
			return true;
		}

		if (level instanceof ServerLevel serverLevel) {
			if (!MadokuFarming.isManagedPlot(serverLevel, pos) && MadokuFarming.isManagedCrop(serverLevel, abovePos, aboveState)) {
				MadokuFarming.syncPlotFromSoil(serverLevel, pos, MadokuFarming.isFertilized(serverLevel, pos));
			}
			return MadokuFarming.isManagedPlot(serverLevel, pos) || MadokuFarming.isManagedCrop(serverLevel, abovePos, aboveState);
		}

		return false;
	}
}
