package madoku.craft.farming.system;

import com.google.gson.JsonObject;

public final class MadokuFarmingConfig {
	public static final String FIELD_ENABLED = "enabled";
	public static final String FIELD_RAIN_GROWTH_BOOST = "rain-growth-boost";
	public static final String FIELD_FERTILIZED_GROWTH_BOOST = "fertilized-growth-boost";
	public static final String FIELD_OUT_OF_SEASON_PENALTY = "out-of-season-penalty";
	public static final String FIELD_DRY_FARMLAND_PENALTY = "dry-farmland-penalty";
	public static final String FIELD_PARTICLE_COUNT = "particleCount";
	public static final String FIELD_PARTICLE_SPREAD = "particleSpread";
	public static final String FIELD_PARTICLE_Y_OFFSET = "particleYOffset";

	public static final double DEFAULT_RAIN_GROWTH_BOOST = 0.25d;
	public static final double DEFAULT_FERTILIZED_GROWTH_BOOST = 0.5d;
	public static final double DEFAULT_OUT_OF_SEASON_PENALTY = 0.5d;
	public static final double DEFAULT_DRY_FARMLAND_PENALTY = 0.5d;
	public static final int MAX_PARTICLE_COUNT = 4;
	public static final int DEFAULT_PARTICLE_COUNT = 2;
	public static final double DEFAULT_PARTICLE_SPREAD = 0.12d;
	public static final double DEFAULT_PARTICLE_Y_OFFSET = 0.1d;

	private MadokuFarmingConfig() {
	}

	public static JsonObject buildFarmingDefaults() {
		JsonObject defaults = new JsonObject();
		defaults.addProperty(FIELD_ENABLED, true);
		defaults.addProperty(FIELD_RAIN_GROWTH_BOOST, DEFAULT_RAIN_GROWTH_BOOST);
		defaults.addProperty(FIELD_FERTILIZED_GROWTH_BOOST, DEFAULT_FERTILIZED_GROWTH_BOOST);
		defaults.addProperty(FIELD_OUT_OF_SEASON_PENALTY, DEFAULT_OUT_OF_SEASON_PENALTY);
		defaults.addProperty(FIELD_DRY_FARMLAND_PENALTY, DEFAULT_DRY_FARMLAND_PENALTY);
		return defaults;
	}
}
