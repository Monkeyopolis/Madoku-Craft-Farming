package madoku.craft.java.farming;

import com.google.gson.JsonObject;

import madoku.craft.java.core.json.JSONFormatAPIManager;
import madoku.craft.java.core.json.JSONAPIManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Static defaults and field names for individual farming crop files. */
public final class CropsConfigManager {
	public static final String FIELD_CROP_ID = "crop-id";
	public static final String FIELD_YIELD_ID = "yield-id";
	public static final String FIELD_GROWTH_TIME = "growth-time";
	public static final String FIELD_MAX_HEIGHT = "max-height";
	public static final String FIELD_SUPPORT_BLOCKS = "support-blocks";
	public static final String FIELD_SUPPORT_TAGS = "support-tags";
	public static final String FIELD_GROWING_CONDITIONS = "growing-conditions";
	public static final String FIELD_IDEAL_TEMPERATURE = "ideal-temperature";
	public static final String FIELD_IDEAL_HUMIDITY = "ideal-humidity";
	public static final String FIELD_MINIMUM_TEMPERATURE = "minimum-temperature";
	public static final String FIELD_MAXIMUM_TEMPERATURE = "maximum-temperature";
	public static final String FIELD_MINIMUM_HUMIDITY = "minimum-humidity";
	public static final String FIELD_MAXIMUM_HUMIDITY = "maximum-humidity";

	private CropsConfigManager() { }

	public static Map<String, JsonObject> buildDefaultCropFileDefaults() {
		Map<String, JsonObject> defaults = new LinkedHashMap<>();
		defaults.put("potato", buildCropDefaults("minecraft:potato", 3.0d, new ConditionDefault(60, 80, 10, 30), "minecraft:blocks/potatoes"));
		defaults.put("carrot", buildCropDefaults("minecraft:carrot", 5.0d, new ConditionDefault(20, 40, 70, 90), "minecraft:blocks/carrots"));
		defaults.put("beetroot", buildCropDefaults("minecraft:beetroot", 3.0d, new ConditionDefault(55, 65, 35, 45), "minecraft:blocks/beetroots"));
		defaults.put("melon", buildCropDefaults("minecraft:melon", 11.0d, new ConditionDefault(45, 55, 60, 80), "minecraft:blocks/melon"));
		defaults.put("pumpkin", buildCropDefaults("minecraft:pumpkin", 9.0d, new ConditionDefault(45, 55, 20, 40), "minecraft:blocks/pumpkin"));
		defaults.put("wheat", buildCropDefaults("minecraft:wheat", 7.0d, new ConditionDefault(30, 50, 50, 70), "minecraft:blocks/wheat"));
		defaults.put("sugar-cane", buildCropDefaults(
			"minecraft:sugar_cane",
			4.0d,
			new ConditionDefault(60, 80, 10, 30),
			"minecraft:blocks/sugar_cane",
			5,
			List.of("minecraft:grass_block"),
			List.of("minecraft:dirt", "minecraft:sand")
		));
		return defaults;
	}

	private static JsonObject buildCropDefaults(
		String cropId,
		double growthMinecraftDays,
		ConditionDefault conditions,
		String yieldTableId
	) {
		return buildCropDefaults(cropId, growthMinecraftDays, conditions, yieldTableId, 1);
	}

	private static JsonObject buildCropDefaults(
		String cropId,
		double growthMinecraftDays,
		ConditionDefault conditions,
		String yieldTableId,
		int maxHeight
	) {
		return buildCropDefaults(
			cropId,
			growthMinecraftDays,
			conditions,
			yieldTableId,
			maxHeight,
			List.of("minecraft:farmland"),
			List.of()
		);
	}

	private static JsonObject buildCropDefaults(
		String cropId,
		double growthMinecraftDays,
		ConditionDefault conditions,
		String yieldTableId,
		int maxHeight,
		List<String> supportBlocks,
		List<String> supportTags
	) {
		String normalizedCropId = normalizeRegistryId(cropId);
		JSONFormatAPIManager.ObjectBuilder root = JSONFormatAPIManager.object()
			.put(FIELD_CROP_ID, JSONAPIManager.normalizeRegistryIdentifierForJson(normalizedCropId))
			.put(FIELD_GROWTH_TIME, Math.max(0.25d, growthMinecraftDays));
		if (maxHeight > 1) {
			root.put(FIELD_MAX_HEIGHT, maxHeight);
		}

		root.put(FIELD_YIELD_ID, JSONAPIManager.normalizeRegistryIdentifierForJson(normalizeRegistryId(yieldTableId)));
		root.array(FIELD_SUPPORT_BLOCKS, blocks -> {
			for (String supportBlock : supportBlocks == null ? List.<String>of() : supportBlocks) {
				blocks.add(JSONAPIManager.normalizeRegistryIdentifierForJson(normalizeRegistryId(supportBlock)));
			}
		});
		root.array(FIELD_SUPPORT_TAGS, tags -> {
			for (String supportTag : supportTags == null ? List.<String>of() : supportTags) {
				String normalizedTag = supportTag != null && supportTag.startsWith("#")
					? supportTag.substring(1)
					: supportTag;
				tags.add(JSONAPIManager.normalizeRegistryIdentifierForJson(normalizeRegistryId(normalizedTag)));
			}
		});

		ConditionDefault safe = conditions == null ? new ConditionDefault(40, 60, 40, 60) : conditions;
		root.object(FIELD_GROWING_CONDITIONS, growing -> {
			growing.object(FIELD_IDEAL_TEMPERATURE, temperature -> temperature
				.put(FIELD_MINIMUM_TEMPERATURE, safe.minimumTemperature())
				.put(FIELD_MAXIMUM_TEMPERATURE, safe.maximumTemperature()));
			growing.object(FIELD_IDEAL_HUMIDITY, humidity -> humidity
				.put(FIELD_MINIMUM_HUMIDITY, safe.minimumHumidity())
				.put(FIELD_MAXIMUM_HUMIDITY, safe.maximumHumidity()));
		});
		return root.build();
	}

	private static String normalizeRegistryId(String value) {
		return JSONAPIManager.normalizeRegistryIdentifierForLookup(value);
	}

	private record ConditionDefault(double minimumTemperature, double maximumTemperature, double minimumHumidity, double maximumHumidity) { }
}

