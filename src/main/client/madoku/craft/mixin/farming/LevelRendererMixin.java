package madoku.craft.mixin.farming;

import com.mojang.blaze3d.vertex.PoseStack;
import madoku.craft.java.farming.FarmingPlotIndicatorClient;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Inject(
		method = "submitEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
		at = @At("TAIL")
	)
	private void madokuCraft$submitFarmingPlotIndicators(
		PoseStack poseStack,
		LevelRenderState levelRenderState,
		SubmitNodeCollector collector,
		CallbackInfo callbackInfo
	) {
		FarmingPlotIndicatorClient.render(levelRenderState, poseStack, collector);
	}
}
