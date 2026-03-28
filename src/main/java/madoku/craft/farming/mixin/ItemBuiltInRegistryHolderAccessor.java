package madoku.craft.farming.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Item.class)
public interface ItemBuiltInRegistryHolderAccessor {
	@Accessor("builtInRegistryHolder")
	Holder.Reference<Item> madokuCraft$getBuiltInRegistryHolder();
}
