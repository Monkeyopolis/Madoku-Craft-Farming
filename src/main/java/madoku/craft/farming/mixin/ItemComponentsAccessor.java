package madoku.craft.farming.mixin;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Item.class)
public interface ItemComponentsAccessor {
	@Accessor("components")
	DataComponentMap madokuCraft$getComponents();

	@Mutable
	@Accessor("components")
	void madokuCraft$setComponents(DataComponentMap components);
}
