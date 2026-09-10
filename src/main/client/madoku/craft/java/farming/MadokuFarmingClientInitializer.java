package madoku.craft.java.farming;

import net.fabricmc.api.ClientModInitializer;

/** Fabric client entrypoint for the standalone Farming jar. */
public final class MadokuFarmingClientInitializer implements ClientModInitializer {
	@Override public void onInitializeClient() { MadokuFarmingClient.initialize(); }
}
