package madoku.craft.java.farming;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/** Client bootstrap for Farming-owned crop metadata application. */
public final class MadokuFarmingClient {
	private static boolean initialized;
	private static boolean metadataApplied;

	private MadokuFarmingClient() { }

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (metadataApplied || client.level == null) return;
			metadataApplied = true;
			FarmingAPIManager.applyCropItemMetadata();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> metadataApplied = false);
	}
}
