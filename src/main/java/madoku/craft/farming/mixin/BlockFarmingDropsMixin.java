package madoku.craft.farming.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import madoku.craft.api.data.MadokuChunkDataManager;
import madoku.craft.farming.system.MadokuFarming;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Block.class)
public abstract class BlockFarmingDropsMixin {
	@Inject(
		method = "dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private static void madokuCraft$overrideCropHarvestDrops(
		BlockState state,
		Level level,
		BlockPos pos,
		CallbackInfo ci
	) {
		overrideCropHarvestDrops(state, level instanceof ServerLevel serverLevel ? serverLevel : null, pos, ci);
	}

	@Inject(
		method = "playerWillDestroy(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/level/block/state/BlockState;",
		at = @At("HEAD")
	)
	private static void madokuCraft$snapshotCropHarvest(
		Level level,
		BlockPos pos,
		BlockState state,
		Player player,
		CallbackInfoReturnable<BlockState> cir
	) {
		if (level instanceof ServerLevel serverLevel) {
			MadokuFarming.prepareCropHarvest(serverLevel, pos, state);
		}
	}

	@Inject(
		method = "dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private static void madokuCraft$overrideCropHarvestDrops(
		BlockState state,
		LevelAccessor level,
		BlockPos pos,
		BlockEntity blockEntity,
		CallbackInfo ci
	) {
		overrideCropHarvestDrops(state, level instanceof ServerLevel serverLevel ? serverLevel : null, pos, ci);
	}

	@Inject(
		method = "dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private static void madokuCraft$overrideCropHarvestDrops(
		BlockState state,
		Level level,
		BlockPos pos,
		BlockEntity blockEntity,
		net.minecraft.world.entity.Entity entity,
		ItemStack tool,
		CallbackInfo ci
	) {
		overrideCropHarvestDrops(state, level instanceof ServerLevel serverLevel ? serverLevel : null, pos, ci);
	}

	private static void overrideCropHarvestDrops(
		BlockState state,
		ServerLevel level,
		BlockPos pos,
		CallbackInfo ci
	) {
		// Player-placed crop blocks must retain vanilla block drops. The placed
		// marker is available regardless of whether Madoku Farming is enabled.
		if (MadokuChunkDataManager.isPlayerPlacedBlock(level, pos)) {
			return;
		}
		if (!MadokuFarming.isEnabled() || !MadokuFarming.isManagedHarvestState(level, pos, state)) {
			return;
		}

		MadokuFarming.prepareCropHarvest(level, pos, state);
		RandomSource random = level == null ? RandomSource.create() : level.getRandom();
		ObjectArrayList<ItemStack> drops = new ObjectArrayList<>(MadokuFarming.calculateCropHarvestDrops(level, pos, state, random));
		if (drops.isEmpty()) {
			if (MadokuFarming.hasCropHarvestLootTable(level, pos, state)) {
				MadokuFarming.completeCropHarvest(level, pos, state);
				ci.cancel();
			}
			return;
		}
		for (ItemStack drop : drops) {
			if (drop != null && !drop.isEmpty()) {
				Block.popResource(level, pos, drop);
			}
		}
		MadokuFarming.completeCropHarvest(level, pos, state);
		ci.cancel();
	}
}
