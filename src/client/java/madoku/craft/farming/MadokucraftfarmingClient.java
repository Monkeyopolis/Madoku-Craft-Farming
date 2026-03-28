package madoku.craft.farming;

import madoku.craft.farming.system.MadokuFarming;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class MadokucraftfarmingClient implements ClientModInitializer {
	private static boolean cropMetadataApplied = false;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (cropMetadataApplied || client.level == null) {
				return;
			}

			cropMetadataApplied = true;
			MadokuFarming.applyCropItemMetadata();
		});
	}
}
