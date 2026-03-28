package madoku.craft.farming.mixin;

import madoku.craft.farming.system.MadokuFarming;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeFarmingMixin {
	@Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
	private void madokuCraft$blockOutOfSeasonPlanting(
		ServerPlayer player,
		Level level,
		ItemStack stack,
		InteractionHand hand,
		BlockHitResult hitResult,
		CallbackInfoReturnable<InteractionResult> cir
	) {
		if (!MadokuFarming.isEnabled() || player == null || level == null || stack == null || stack.isEmpty()) {
			return;
		}
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		if (!MadokuFarming.isCropPlantItem(stack) || hitResult == null) {
			return;
		}

		BlockPos soilPos = hitResult.getBlockPos().relative(hitResult.getDirection()).below();
		if (!MadokuFarming.isFarmland(level.getBlockState(soilPos))) {
			return;
		}
		if (MadokuFarming.canPlantCrop(stack, serverLevel)) {
			return;
		}

		player.sendOverlayMessage(Component.literal(MadokuFarming.getCropSeasonBlockedMessage(stack, serverLevel)));
		cir.setReturnValue(InteractionResult.FAIL);
	}
}
