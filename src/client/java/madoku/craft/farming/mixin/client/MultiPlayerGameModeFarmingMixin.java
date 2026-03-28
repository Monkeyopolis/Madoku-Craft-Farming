package madoku.craft.farming.mixin.client;

import madoku.craft.farming.system.MadokuFarming;
import madoku.craft.season.MadokuSeason;
import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeFarmingMixin {
	@Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
	private void madokuCraft$blockOutOfSeasonPlanting(
		LocalPlayer player,
		InteractionHand hand,
		BlockHitResult hitResult,
		CallbackInfoReturnable<InteractionResult> cir
	) {
		if (!MadokuFarming.isEnabled() || player == null || hitResult == null) {
			return;
		}

		Level level = player.level();
		if (level == null) {
			return;
		}

		ItemStack stack = player.getItemInHand(hand);
		if (stack == null || stack.isEmpty() || !MadokuFarming.isCropPlantItem(stack)) {
			return;
		}

		BlockPos soilPos = hitResult.getBlockPos().relative(hitResult.getDirection()).below();
		if (!MadokuFarming.isFarmland(level.getBlockState(soilPos))) {
			return;
		}

		String seasonId = MadokuSeason.getCurrentSeasonId();
		if (seasonId == null || seasonId.isBlank()) {
			return;
		}

		if (MadokuFarming.canPlantCrop(stack, seasonId)) {
			return;
		}

		player.sendOverlayMessage(Component.literal(MadokuFarming.getCropSeasonBlockedMessage(stack, seasonId)));
		cir.setReturnValue(InteractionResult.FAIL);
	}
}
