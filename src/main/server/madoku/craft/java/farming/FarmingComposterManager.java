package madoku.craft.java.farming;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import madoku.craft.java.core.json.JSONFormatAPIManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

public final class FarmingComposterManager {
	private static final Logger LOGGER = LoggerFactory.getLogger(FarmingComposterManager.class);
	private static final String COMPOSTER_CONFIG_SETTINGS_FILE_NAME = "madoku-composter";

	private static volatile boolean enabled = true;

	private FarmingComposterManager() {
	}

	public static void initialize() {
		loadStaticConfig();
		ComposterCropsManager.initialize();
	}

	public static void reset() {
		// The farming manager is reset when a server starts and stops. Reload the
		// static composter rules here so the server-start reset does not erase the
		// rules loaded during mod initialization.
		initialize();
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static boolean isComposterItem(Item item) {
		if (!isEnabled() || item == null) {
			return false;
		}
		return ComposterCropsManager.isComposterItem(item);
	}

	public static boolean isComposterItem(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		return isComposterItem(stack.getItem());
	}

	public static int getComposterAdjustment(ItemStack stack) {
		if (!isEnabled() || stack == null || stack.isEmpty()) {
			return 0;
		}
		if (!isComposterItem(stack)) {
			return 0;
		}
		return ComposterCropsManager.getAdjustment(stack);
	}

	private static void loadStaticConfig() {
		try {
			Path rootDirectory = FarmingConfigManager.resolveComposterConfigDirectory();
			Path settingsFile = resolveJsonFile(rootDirectory, COMPOSTER_CONFIG_SETTINGS_FILE_NAME);
			JsonObject settingsRoot = JSONFormatAPIManager.ensureManagedFile(
				settingsFile,
				ComposterConfigManager.buildComposterSystemDefaults()
			);
			boolean composterEnabled = readBoolean(
				settingsRoot,
				ComposterConfigManager.FIELD_COMPOSTER_SYSTEM_ENABLED,
				true
			);

			enabled = composterEnabled;
		} catch (IOException | RuntimeException exception) {
			enabled = false;
			LOGGER.error("Failed to load FarmingComposterManager config; disabling custom composter rules.", exception);
		}
	}

	private static boolean readBoolean(JsonObject root, String key, boolean fallback) {
		if (root == null) {
			return fallback;
		}
		JsonElement element = root.get(key);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
			return fallback;
		}
		return element.getAsBoolean();
	}

	private static Path resolveJsonFile(Path directory, String fileName) {
		String normalized = fileName == null ? "" : fileName.trim();
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("Config file name must not be blank.");
		}
		if (!normalized.endsWith(".json")) {
			normalized = normalized + ".json";
		}
		return directory.resolve(normalized);
	}
}


