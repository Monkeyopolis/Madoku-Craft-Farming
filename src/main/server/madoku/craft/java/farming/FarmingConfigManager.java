package madoku.craft.java.farming;

import com.google.gson.JsonObject;

import madoku.craft.java.core.json.JSONFormatAPIManager;
import madoku.craft.java.core.json.JSONAPIManager;

import com.google.gson.JsonElement;

import java.io.IOException;
import java.nio.file.Path;

public final class FarmingConfigManager {
	public static final String CONFIG_ROOT_FOLDER_NAME = "madoku-craft-farming";
	public static final String CONFIG_FILE_NAME = "madoku-farming";
	public static final String CROPS_CONFIG_FOLDER_NAME = "madoku-crops";
	public static final String COMPOSTER_CONFIG_FOLDER_NAME = "madoku-composter";
	public static final String FIELD_ENABLED = "enabled";
	public static final String FIELD_RAIN_GROWTH_BOOST = "rain-growth-boost";
	public static final String FIELD_FERTILIZED_GROWTH_BOOST = "fertilized-growth-boost";
	public static final String FIELD_FERTILIZED_YIELD_BOOST = "fertilized-yield-boost";
	public static final String FIELD_DRY_FARMLAND_PENALTY = "dry-farmland-penalty";

	public static final double DEFAULT_RAIN_GROWTH_BOOST = 0.25d;
	public static final double DEFAULT_FERTILIZED_GROWTH_BOOST = 0.25d;
	public static final double DEFAULT_FERTILIZED_YIELD_BOOST = 0.5d;
	public static final double DEFAULT_DRY_FARMLAND_PENALTY = 0.5d;

	private FarmingConfigManager() {
	}

	public static void initialize() {
		try {
			loadFarmingSettings();
		} catch (IOException | RuntimeException ignored) {
			// FarmingCropsManager owns runtime fallbacks; this call only ensures
			// the managed farming file exists before item configuration is loaded.
		}
	}

	public static JsonObject loadFarmingSettings() throws IOException {
		Path directory = JSONAPIManager.getOrCreateGlobalSystemDirectory(CONFIG_ROOT_FOLDER_NAME);
		return JSONFormatAPIManager.ensureManagedFile(
			directory.resolve(CONFIG_FILE_NAME + ".json"),
			buildFarmingDefaults()
		);
	}

	public static Path resolveCropsConfigDirectory() throws IOException {
		return JSONAPIManager.getOrCreateGlobalSystemDirectory(CONFIG_ROOT_FOLDER_NAME)
			.resolve(CROPS_CONFIG_FOLDER_NAME);
	}

	public static Path resolveComposterConfigDirectory() throws IOException {
		return JSONAPIManager.getOrCreateGlobalSystemDirectory(CONFIG_ROOT_FOLDER_NAME)
			.resolve(COMPOSTER_CONFIG_FOLDER_NAME);
	}

	public static boolean readBoolean(JsonObject source, String key, boolean fallback) {
		JsonElement element = source == null ? null : source.get(key);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) return fallback;
		try { return element.getAsBoolean(); } catch (RuntimeException ignored) { return fallback; }
	}

	public static JsonObject buildFarmingDefaults() {
		return JSONFormatAPIManager.object()
			.put(FIELD_ENABLED, true)
			.put(FIELD_RAIN_GROWTH_BOOST, DEFAULT_RAIN_GROWTH_BOOST)
			.put(FIELD_FERTILIZED_GROWTH_BOOST, DEFAULT_FERTILIZED_GROWTH_BOOST)
			.put(FIELD_FERTILIZED_YIELD_BOOST, DEFAULT_FERTILIZED_YIELD_BOOST)
			.put(FIELD_DRY_FARMLAND_PENALTY, DEFAULT_DRY_FARMLAND_PENALTY)
			.build();
	}
}


