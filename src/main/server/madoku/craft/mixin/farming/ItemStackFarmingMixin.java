package madoku.craft.mixin.farming;

import madoku.craft.java.core.data.ChunkDataAPIManager;
import madoku.craft.java.farming.FarmingAPIManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemStack.class)
public abstract class ItemStackFarmingMixin {
	@Unique
	private int madokuCraft$preUseOnCount = -1;

	@Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
	private void madokuCraft$handleFarmingItemUse(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
		ItemStack stack = (ItemStack) (Object) this;
		if (!FarmingAPIManager.isEnabled() || context == null || stack.isEmpty()) {
			return;
		}

		Level level = context.getLevel();
		if (level == null) {
			return;
		}

		BlockPos pos = context.getClickedPos();
		if (pos == null) {
			return;
		}

		if (FarmingAPIManager.isCropPlantItem(stack)) {
			madokuCraft$preUseOnCount = stack.getCount();
		}

			BlockState state = level.getBlockState(pos);
			if (stack.is(Items.BONE_MEAL) && FarmingAPIManager.isManagedCrop(state)) {
				cir.setReturnValue(InteractionResult.FAIL);
				return;
			}

			ServerLevel serverLevel = level instanceof ServerLevel ? (ServerLevel) level : null;

			if (stack.is(Items.BONE_MEAL) && FarmingAPIManager.isFarmland(state)) {
				if (serverLevel != null && FarmingAPIManager.isFertilized(serverLevel, pos)) {
				if (context.getPlayer() != null) {
						context.getPlayer().displayClientMessage(Component.literal("Farmland is already fertilized."), true);
				}
				cir.setReturnValue(InteractionResult.FAIL);
				return;
			}

			if (level.isClientSide()) {
				cir.setReturnValue(InteractionResult.SUCCESS);
				return;
			}

			madokuCraft$consumeOneItem(context.getPlayer(), stack);
			FarmingAPIManager.fertilizeSoil((ServerLevel) level, pos);
			FarmingAPIManager.syncPlotFromSoil((ServerLevel) level, pos, true);
			level.playSound(null, pos, SoundEvents.BONE_MEAL_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
			cir.setReturnValue(InteractionResult.SUCCESS);
			return;
		}

	}

	@Unique
	private static void madokuCraft$consumeOneItem(Player player, ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return;
		}
		if (player != null && player.getAbilities().instabuild) {
			return;
		}
		stack.shrink(1);
	}

	@Unique
	private static BlockPos madokuCraft$getCropPlantingSoilPos(UseOnContext context) {
		if (context == null) {
			return null;
		}
		BlockPos clickedPos = context.getClickedPos();
		Direction face = context.getClickedFace();
		if (clickedPos == null || face == null) {
			return null;
		}
		return clickedPos.relative(face).below();
	}

	@Inject(method = "useOn", at = @At("RETURN"))
	private void madokuCraft$trackCropPlanting(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
		ItemStack stack = (ItemStack) (Object) this;
		if (!FarmingAPIManager.isEnabled() || context == null) {
			madokuCraft$preUseOnCount = -1;
			return;
		}
		boolean cropPlantAttempt = madokuCraft$preUseOnCount >= 0;
		if (!cropPlantAttempt) {
			madokuCraft$preUseOnCount = -1;
			return;
		}

		InteractionResult result = cir.getReturnValue();
		if (result == null || !result.consumesAction()) {
			madokuCraft$restoreUseOnCount(stack);
			madokuCraft$preUseOnCount = -1;
			return;
		}

		Level level = context.getLevel();
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}

		BlockPos soilPos = madokuCraft$getCropPlantingSoilPos(context);
		if (soilPos == null || !FarmingAPIManager.isFarmland(level.getBlockState(soilPos))) {
			madokuCraft$preUseOnCount = -1;
			return;
		}

		BlockState cropState = level.getBlockState(soilPos.above());
		if (!FarmingAPIManager.isManagedCrop(cropState)) {
			madokuCraft$preUseOnCount = -1;
			return;
		}

		// Crop seeds are BlockItems, so Core's generic placement tracker also
		// records the planted crop. Farming owns these crops and must not let that
		// marker suppress the configured harvest path.
		ChunkDataAPIManager.removePlayerPlacedBlock(serverLevel, soilPos.above());
		FarmingAPIManager.syncPlotFromSoil(serverLevel, soilPos, FarmingAPIManager.isFertilized(serverLevel, soilPos));
		madokuCraft$preUseOnCount = -1;
	}

	@Unique
	private void madokuCraft$restoreUseOnCount(ItemStack stack) {
		if (stack != null && madokuCraft$preUseOnCount >= 0) {
			stack.setCount(madokuCraft$preUseOnCount);
		}
	}
}

