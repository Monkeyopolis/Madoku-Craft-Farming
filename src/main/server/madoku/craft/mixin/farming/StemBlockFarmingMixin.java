package madoku.craft.mixin.farming;

import madoku.craft.java.farming.FarmingAPIManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StemBlock.class)
public abstract class StemBlockFarmingMixin {
	@Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
	private void madokuCraft$handleStemRandomTick(
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
}

