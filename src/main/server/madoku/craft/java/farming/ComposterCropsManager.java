package madoku.craft.java.farming;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import madoku.craft.java.core.json.JSONFormatAPIManager;
import madoku.craft.java.core.json.JSONTypeAPIManager;
import madoku.craft.java.core.json.JSONAPIManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

/** Group runtime for items that can be processed by a composter. */
public final class ComposterCropsManager {
	private static final Logger LOGGER = LoggerFactory.getLogger(ComposterCropsManager.class);
	private static volatile Map<Item, Integer> adjustmentsByItem = Map.of();

	private ComposterCropsManager() {
	}

	public static void initialize() {
		try {
			Path directory = FarmingConfigManager.resolveComposterConfigDirectory();
			Files.createDirectories(directory);
			Map<String, JsonObject> files = new LinkedHashMap<>();
			Map<String, JsonObject> defaults = ComposterConfigManager.buildDefaultComposterFileDefaults();
			for (Map.Entry<String, JsonObject> entry : defaults.entrySet()) {
				Path file = directory.resolve(entry.getKey() + ".json");
				files.put(entry.getKey(), JSONFormatAPIManager.ensureManagedFile(
					file, entry.getValue(), JSONTypeAPIManager.STATIC_CONFIG, null));
			}
			try (var stream = Files.list(directory)) {
				stream.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
					.forEach(path -> {
						String fileName = path.getFileName().toString();
						String fileKey = fileName.substring(0, fileName.length() - ".json".length());
						if (files.containsKey(fileKey)) return;
						try {
							files.put(fileKey, JSONFormatAPIManager.ensureManagedFile(
								path,
								ComposterConfigManager.buildDynamicComposterDefaultsForFile(fileKey),
								JSONTypeAPIManager.STATIC_CONFIG,
								null));
						} catch (IOException exception) {
							throw new RuntimeException(exception);
						}
					});
			}
			Map<Item, Integer> resolved = new LinkedHashMap<>();
			for (JsonObject root : files.values()) {
				if (root == null || !readBoolean(root, "enabled", true)) continue;
				Item item = resolveItem(readString(root, ComposterConfigManager.FIELD_ITEM_ID, ""));
				if (item == null) continue;
				resolved.put(item, Math.max(1, readInt(root, ComposterConfigManager.FIELD_COMPOSTER_ADJUSTMENT, 1)));
			}
			adjustmentsByItem = Map.copyOf(resolved);
		} catch (IOException | RuntimeException exception) {
			adjustmentsByItem = Map.of();
			LOGGER.error("Failed to load standalone composter item configs; disabling custom composter rules.", exception);
		}
	}

	public static void reset() {
		adjustmentsByItem = Map.of();
	}

	public static boolean isComposterItem(Item item) {
		return item != null && adjustmentsByItem.containsKey(item);
	}

	public static boolean isComposterItem(ItemStack stack) {
		return stack != null && !stack.isEmpty() && isComposterItem(stack.getItem());
	}

	public static int getAdjustment(ItemStack stack) {
		if (!isComposterItem(stack)) return 0;
		return adjustmentsByItem.getOrDefault(stack.getItem(), 0);
	}

	private static Item resolveItem(String itemId) {
		Identifier identifier = Identifier.tryParse(JSONAPIManager.normalizeRegistryIdentifierForLookup(itemId));
		return identifier == null || !BuiltInRegistries.ITEM.containsKey(identifier)
			? null : BuiltInRegistries.ITEM.getValue(identifier);
	}

	private static boolean readBoolean(JsonObject root, String key, boolean fallback) {
		JsonElement value = root == null ? null : root.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
			? value.getAsBoolean() : fallback;
	}

	private static int readInt(JsonObject root, String key, int fallback) {
		JsonElement value = root == null ? null : root.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
			? value.getAsInt() : fallback;
	}

	private static String readString(JsonObject root, String key, String fallback) {
		JsonElement value = root == null ? null : root.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
			? value.getAsString() : fallback;
	}
}

