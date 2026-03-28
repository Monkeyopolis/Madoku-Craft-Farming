package madoku.craft.farming.mixin;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Holder.Reference.class)
public interface ItemComponentsAccessor {
	@Invoker("bindComponents")
	void madokuCraft$bindComponents(DataComponentMap components);
}
