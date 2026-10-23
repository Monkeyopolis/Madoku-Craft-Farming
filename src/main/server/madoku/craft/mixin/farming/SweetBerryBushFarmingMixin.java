package madoku.craft.mixin.farming;

import madoku.craft.java.farming.FarmingAPIManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SweetBerryBushBlock.class)
public abstract class SweetBerryBushFarmingMixin {
	@Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
	private void madokuCraft$handleManagedGrowth(
		BlockState state,
		ServerLevel level,
		BlockPos pos,
		RandomSource random,
		CallbackInfo ci
	) {
		if (!FarmingAPIManager.isEnabled() || !FarmingAPIManager.isManagedCrop(state)) {
			return;
		}

		if (FarmingAPIManager.handleCropRandomTick(level, pos, state, random)) {
			ci.cancel();
		}
	}

	@Inject(method = "useWithoutItem", at = @At("HEAD"), cancellable = true)
	private void madokuCraft$handleManagedHarvest(
		BlockState state,
		Level level,
		BlockPos pos,
		Player player,
		BlockHitResult hit,
		CallbackInfoReturnable<InteractionResult> cir
	) {
		if (!(level instanceof ServerLevel serverLevel) || !FarmingAPIManager.isEnabled()) {
			return;
		}

		if (!FarmingAPIManager.isManagedCrop(serverLevel, pos, state)) {
			return;
		}

		if (FarmingAPIManager.handleCropUseWithoutItem(serverLevel, pos, state)) {
			cir.setReturnValue(InteractionResult.SUCCESS);
		} else {
			// Vanilla sweet berries can be harvested before the farming timer is
			// complete. Managed bushes must wait for farming maturity instead.
			cir.setReturnValue(InteractionResult.PASS);
		}
	}
}
