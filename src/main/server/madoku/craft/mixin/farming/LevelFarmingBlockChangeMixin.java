package madoku.craft.mixin.farming;

import madoku.craft.java.farming.FarmingAPIManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Wakes blocked mature stems after a nearby block state changes. */
@Mixin(Level.class)
public abstract class LevelFarmingBlockChangeMixin {
	@Inject(
		method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
		at = @At("RETURN")
	)
	private void madokuCraft$notifyFarmingBlockStateChange(
		BlockPos pos,
		BlockState state,
		int flags,
		int maxUpdateDepth,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (cir.getReturnValueZ() && (Object) this instanceof ServerLevel serverLevel) {
			FarmingAPIManager.handleBlockStateChanged(serverLevel, pos);
		}
	}
}
