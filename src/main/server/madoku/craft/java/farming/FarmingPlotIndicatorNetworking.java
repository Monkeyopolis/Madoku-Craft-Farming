package madoku.craft.java.farming;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

final class FarmingPlotIndicatorNetworking {
	private FarmingPlotIndicatorNetworking() {
	}

	static boolean send(ServerPlayer player, FarmingPlotIndicatorPayloadManager payload) {
		if (player == null || payload == null) {
			return false;
		}
		boolean canSend = ServerPlayNetworking.canSend(player, payload.type());
		if (!canSend) {
			return false;
		}
		ServerPlayNetworking.send(player, payload);
		return true;
	}
}
