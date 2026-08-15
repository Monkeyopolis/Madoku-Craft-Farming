package madoku.craft.farming.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import madoku.craft.api.data.MadokuChunkDataManager;
import madoku.craft.farming.system.MadokuFarming;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;

@Mixin(LootTable.class)
public abstract class LootTableFarmingMixin {
	@Inject(
		method = "getRandomItems(Lnet/minecraft/world/level/storage/loot/LootContext;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;",
		at = @At("RETURN"),
		cancellable = true
	)
	private void madokuCraft$overrideCropHarvestDrops(
		LootContext lootContext,
		CallbackInfoReturnable<ObjectArrayList<ItemStack>> cir
	) {
		if (!MadokuFarming.isEnabled() || lootContext == null) {
			return;
		}

		ServerLevel level = lootContext.getLevel();
		BlockState state = resolveBlockStateParameter(lootContext);
		BlockPos pos = resolveBlockPosParameter(lootContext);
		if (MadokuChunkDataManager.isPlayerPlacedBlock(level, pos)) {
			return;
		}
		if (!MadokuFarming.isManagedCrop(level, pos, state) || !MadokuFarming.isCropHarvestReady(level, pos, state)) {
			return;
		}

		MadokuFarming.prepareCropHarvest(level, pos, state);
		RandomSource random = lootContext.getRandom();
		ObjectArrayList<ItemStack> drops = new ObjectArrayList<>(MadokuFarming.calculateCropHarvestDrops(level, pos, state, random));
		if (drops.isEmpty()) {
			if (MadokuFarming.hasCropHarvestLootTable(level, pos, state)) {
				MadokuFarming.completeCropHarvest(level, pos, state);
				cir.setReturnValue(drops);
			}
			return;
		}
		MadokuFarming.completeCropHarvest(level, pos, state);
		cir.setReturnValue(drops);
	}

	private static BlockState resolveBlockStateParameter(LootContext lootContext) {
		Object parameter = LootContextParams.BLOCK_STATE;
		if (lootContext == null || parameter == null) {
			return null;
		}

		try {
			Method hasParameter = findLootContextMethod(lootContext.getClass(), "hasParameter", parameter.getClass());
			if (hasParameter != null) {
				Object present = hasParameter.invoke(lootContext, parameter);
				if (!(present instanceof Boolean) || !((Boolean) present)) {
					return null;
				}
			}

			Method getOrThrow = findLootContextMethod(lootContext.getClass(), "getOrThrow", parameter.getClass());
			if (getOrThrow != null) {
				Object state = getOrThrow.invoke(lootContext, parameter);
				return state instanceof BlockState blockState ? blockState : null;
			}

			Method get = findLootContextMethod(lootContext.getClass(), "get", parameter.getClass());
			if (get != null) {
				Object state = get.invoke(lootContext, parameter);
				return state instanceof BlockState blockState ? blockState : null;
			}
		} catch (ReflectiveOperationException | RuntimeException exception) {
			return null;
		}

		return null;
	}

	private static BlockPos resolveBlockPosParameter(LootContext lootContext) {
		Object parameter = LootContextParams.ORIGIN;
		if (lootContext == null || parameter == null) {
			return null;
		}

		try {
			Method getParameter = findLootContextMethod(lootContext.getClass(), "getParameter", parameter.getClass());
			if (getParameter == null) {
				getParameter = findLootContextMethod(lootContext.getClass(), "getParam", parameter.getClass());
			}
			if (getParameter == null) {
				getParameter = findLootContextMethod(lootContext.getClass(), "getOptionalParameter", parameter.getClass());
			}
			if (getParameter == null) {
				getParameter = findLootContextMethod(lootContext.getClass(), "getParamOrNull", parameter.getClass());
			}
			if (getParameter == null) {
				return null;
			}

			Object origin = getParameter.invoke(lootContext, parameter);
			if (!(origin instanceof Vec3 vec3)) {
				return null;
			}
			return BlockPos.containing(vec3);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			return null;
		}
	}

	private static Method findLootContextMethod(Class<?> type, String name, Class<?> parameterType) {
		if (type == null || name == null || parameterType == null) {
			return null;
		}

		for (Method method : type.getMethods()) {
			if (!name.equals(method.getName()) || method.getParameterCount() != 1) {
				continue;
			}

			Class<?> declaredParameter = method.getParameterTypes()[0];
			if (declaredParameter.isAssignableFrom(parameterType) || parameterType.isAssignableFrom(declaredParameter)) {
				method.setAccessible(true);
				return method;
			}
		}
		return null;
	}
}
