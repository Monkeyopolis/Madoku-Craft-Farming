package madoku.craft.mixin.farming;

import madoku.craft.java.farming.FarmingAPIManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

	@Mixin(BlockBehaviour.BlockStateBase.class)
	public abstract class ComposterBlockMixin {
		private static final int MADOKU_COMPOSTER_MAX_LEVEL = 8;
		private static final int MADOKU_COMPOSTER_MIN_LEVEL = 0;

		@Shadow
		protected abstract BlockState asState();

	@Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
	private void madokuCraft$handleComposterCategoryItem(
		ItemStack stack,
		Level world,
		Player player,
		InteractionHand hand,
		BlockHitResult hit,
		CallbackInfoReturnable<InteractionResult> cir
	) {
		BlockState state = asState();
		if (!(state.getBlock() instanceof ComposterBlock)) {
			return;
		}
		if (!FarmingAPIManager.isComposterEnabled()) {
			return;
		}
		if (stack == null || stack.isEmpty()) {
			return;
		}
		if (!FarmingAPIManager.isComposterItem(stack)) {
			cir.setReturnValue(InteractionResult.FAIL);
			return;
		}

		if (hit == null) {
			return;
		}

		BlockPos pos = hit.getBlockPos();
		if (!state.hasProperty(ComposterBlock.LEVEL)) {
			return;
		}

		if (world.isClientSide()) {
			cir.setReturnValue(InteractionResult.SUCCESS);
			return;
		}

		int currentLevel = madokuCraft$getComposterLevel(state);
		int adjustment = Math.max(1, FarmingAPIManager.getComposterAdjustment(stack));
		int nextLevel = currentLevel + adjustment;

		madokuCraft$consumeOneItem(player, stack);

		if (nextLevel >= MADOKU_COMPOSTER_MAX_LEVEL) {
			madokuCraft$dropBoneMeal(world, pos);
			world.setBlockAndUpdate(pos, state.setValue(ComposterBlock.LEVEL, MADOKU_COMPOSTER_MIN_LEVEL));
			madokuCraft$playEffects(world, pos, false);
		} else {
			world.setBlockAndUpdate(pos, state.setValue(ComposterBlock.LEVEL, nextLevel));
			madokuCraft$playEffects(world, pos, true);
		}

		cir.setReturnValue(InteractionResult.SUCCESS);
	}

	@Unique
	private static int madokuCraft$getComposterLevel(BlockState state) {
		if (state == null || !state.hasProperty(ComposterBlock.LEVEL)) {
			return MADOKU_COMPOSTER_MIN_LEVEL;
		}
		Integer level = state.getValue(ComposterBlock.LEVEL);
		return level == null ? MADOKU_COMPOSTER_MIN_LEVEL : level;
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
	private static void madokuCraft$dropBoneMeal(Level world, BlockPos pos) {
		if (!(world instanceof ServerLevel serverLevel)) {
			return;
		}
		int count = 1 + serverLevel.getRandom().nextInt(3);
		Block.popResource(serverLevel, pos, new ItemStack(Items.BONE_MEAL, count));
	}

	@Unique
	private static void madokuCraft$playEffects(Level world, BlockPos pos, boolean fill) {
		world.levelEvent(1500, pos, fill ? 1 : 0);
	}
}

