package madoku.craft.farming;

import madoku.craft.farming.system.MadokuFarming;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public class Madokucraftfarming implements ModInitializer {
	public static final String MOD_ID = "madoku-craft-farming";

	@Override
	public void onInitialize() {
		MadokuFarming.initialize();

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			MadokuFarming.reset();
			MadokuFarming.loadPersistedData(server);
			MadokuFarming.onServerStarted(server);
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			MadokuFarming.savePersistedData(server);
			MadokuFarming.reset();
		});

		ServerTickEvents.END_SERVER_TICK.register(MadokuFarming::autosavePersistedData);
	}
}
