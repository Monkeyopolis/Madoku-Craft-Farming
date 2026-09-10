package madoku.craft.java.farming;

import madoku.craft.java.core.module.MadokuStandaloneModule;
import madoku.craft.java.core.module.MadokuStandaloneRuntime;
import net.fabricmc.api.ModInitializer;
import net.minecraft.server.MinecraftServer;

/** Fabric entrypoint for the standalone Farming jar. */
public final class MadokuFarmingInitializer implements ModInitializer, MadokuStandaloneModule {
	@Override public void onInitialize() { MadokuStandaloneRuntime.initialize(this); }
	@Override public void initialize() { MadokuFarmingManager.initialize(); }
	@Override public void reset() { MadokuFarmingManager.reset(); }
	@Override public void loadPersistedData(MinecraftServer server) { MadokuFarmingManager.loadPersistedData(server); }
	@Override public void onServerStarted(MinecraftServer server) { MadokuFarmingManager.onServerStarted(server); }
}
