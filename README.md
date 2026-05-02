## Madoku Craft: Farming

Madoku Craft: Farming is a configurable Farming system.
It allows users to customize Farming to their specific needs.
This system adjusts Crops to grow based on Time instead of randomly.
It also modifies the Yield for Crops.
Most of these features are customizable in the CONFIG files.

## Dependencies

- Fabric API
- Madoku Craft API

## Implementation

Crop Growth:

- All Crops grow based on in-game Time.
- If Madoku Season is Enabled, some Crops will only grow during certain Seasons.
- Crops grow faster when it Rains.

Fertilizer:

- Placing Bone-meal on Farmland fertlizes the block.
- Fertilized Farmland allows Crops to grow faster and increases their Yield.

Adjusted Crops:

- Pumpkins and Melons no longer grow adjacent to their Stem.
- Since Pumpkins and Melons replace their Stem, their Yield were adjusted accordingly.
- This allows Pumpkins and Melons to grow similar to other Crops.