package net.zenzty.soullink.server.health;

public class SharedStatsHolder {
    volatile float sharedHealth = 20.0f;
    volatile int sharedHunger = 20;
    volatile float sharedSaturation = 5.0f;
    volatile float sharedAbsorption = 0.0f; // Absorption hearts (golden apples, etc)

    // Accumulator for fractional natural regen (since we divide by player count)
    volatile float regenAccumulator = 0.0f;

    // Accumulator for fractional regeneration effect healing (since we divide by player count)
    volatile float regenerationHealAccumulator = 0.0f;

    // Accumulators for fractional hunger/saturation drain (since we divide by player count)
    volatile float hungerDrainAccumulator = 0.0f;
    volatile float saturationDrainAccumulator = 0.0f;

    // Accumulator for fractional damage (Poison/Wither)
    volatile float damageAccumulator = 0.0f;

    // Prevent infinite sync loops
    volatile boolean isSyncing = false;

    public SharedStatsHolder(float maxHealth) {
        sharedHealth = maxHealth;
    }
}
