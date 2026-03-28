package madoku.craft.farming.mixin;

import madoku.craft.farming.system.MadokuFarming;
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
		if (!MadokuFarming.isEnabled() || context == null || stack.isEmpty()) {
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

		if (MadokuFarming.isCropPlantItem(stack)) {
			madokuCraft$preUseOnCount = stack.getCount();
		}

			BlockState state = level.getBlockState(pos);
			if (stack.is(Items.BONE_MEAL) && MadokuFarming.isManagedCrop(state)) {
				cir.setReturnValue(InteractionResult.FAIL);
				return;
			}

			BlockPos cropSoilPos = madokuCraft$getCropPlantingSoilPos(context);
			ServerLevel serverLevel = level instanceof ServerLevel ? (ServerLevel) level : null;
			if (MadokuFarming.isCropPlantItem(stack) && cropSoilPos != null && MadokuFarming.isFarmland(level.getBlockState(cropSoilPos)) && !MadokuFarming.canPlantCrop(stack, serverLevel)) {
				if (serverLevel != null && context.getPlayer() != null) {
					context.getPlayer().sendOverlayMessage(Component.literal(MadokuFarming.getCropSeasonBlockedMessage(stack, serverLevel)));
				}
				madokuCraft$restoreUseOnCount(stack);
				cir.setReturnValue(InteractionResult.FAIL);
				return;
			}

			if (stack.is(Items.BONE_MEAL) && MadokuFarming.isFarmland(state)) {
				if (serverLevel != null && MadokuFarming.isFertilized(serverLevel, pos)) {
				if (context.getPlayer() != null) {
						context.getPlayer().sendOverlayMessage(Component.literal("Farmland is already fertilized."));
				}
				cir.setReturnValue(InteractionResult.FAIL);
				return;
			}

			if (level.isClientSide()) {
				cir.setReturnValue(InteractionResult.SUCCESS);
				return;
			}

			madokuCraft$consumeOneItem(context.getPlayer(), stack);
			MadokuFarming.fertilizeSoil((ServerLevel) level, pos);
			MadokuFarming.syncPlotFromSoil((ServerLevel) level, pos, true);
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
		if (!MadokuFarming.isEnabled() || context == null || stack.isEmpty() || !MadokuFarming.isCropPlantItem(stack)) {
			madokuCraft$preUseOnCount = -1;
			return;
		}

		InteractionResult result = cir.getReturnValue();
		if (result != InteractionResult.SUCCESS && result != InteractionResult.CONSUME) {
			madokuCraft$restoreUseOnCount(stack);
			madokuCraft$preUseOnCount = -1;
			return;
		}

		Level level = context.getLevel();
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}

		BlockPos soilPos = madokuCraft$getCropPlantingSoilPos(context);
		if (soilPos == null || !MadokuFarming.isFarmland(level.getBlockState(soilPos))) {
			return;
		}

		if (!MadokuFarming.canPlantCrop(stack, serverLevel)) {
			return;
		}

		BlockState cropState = level.getBlockState(soilPos.above());
		if (!MadokuFarming.isManagedCrop(cropState)) {
			return;
		}

		MadokuFarming.registerCropPlanting(serverLevel, soilPos, stack);
		MadokuFarming.syncPlotFromSoil(serverLevel, soilPos, MadokuFarming.isFertilized(serverLevel, soilPos));
		madokuCraft$preUseOnCount = -1;
	}

	@Unique
	private void madokuCraft$restoreUseOnCount(ItemStack stack) {
		if (stack != null && madokuCraft$preUseOnCount >= 0) {
			stack.setCount(madokuCraft$preUseOnCount);
		}
	}
}
