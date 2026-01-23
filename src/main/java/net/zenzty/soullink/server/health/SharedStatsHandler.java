package net.zenzty.soullink.server.health;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.settings.Settings;
import net.zenzty.soullink.util.TeamsHelper;

// Note: Settings import is used for half heart mode max health calculations

/**
 * Handles shared health, hunger, and saturation between all players. Implements the "Soul Link"
 * mechanic where all players share the same vital stats.
 */
public class SharedStatsHandler {

    private static final Map<String, SharedStatsHolder> sharedStatsByTeam = new HashMap<>();

    private static SharedStatsHolder getHolder(ServerPlayerEntity player) {
        String playersTeamName = TeamsHelper.getPlayersTeamNameOrNull(player);
        if (sharedStatsByTeam.containsKey(playersTeamName)) {
            return sharedStatsByTeam.get(playersTeamName);
        }
        SharedStatsHolder holder = new SharedStatsHolder(getMaxHealth());
        sharedStatsByTeam.put(playersTeamName, holder);
        return holder;
    }

    // Prevent infinite sync loops
    private static volatile boolean isTickSyncing = false;

    /**
     * Gets the current max health based on settings.
     */
    private static float getMaxHealth() {
        return Settings.getInstance().isHalfHeartMode() ? 1.0f : 20.0f;
    }

    /**
     * Resets all shared stats to default values. Called when starting a new run.
     */
    public static void reset() {
        // Use half heart mode max health if enabled
        float maxHealth = getMaxHealth();

        sharedStatsByTeam.clear();
        for (String teamName : TeamsHelper.getAllTeamNames()) {
            sharedStatsByTeam.put(teamName, new SharedStatsHolder(maxHealth));
        }

        // Also reset other shared handlers
        SharedPotionHandler.reset();
        SharedJumpHandler.reset();

        SoulLink.LOGGER.info("Shared stats reset to defaults (maxHealth={})", maxHealth);
    }

    /**
     * Syncs a player's stats to the current shared values. Used for late joiners and reconnecting
     * players.
     */
    public static void syncPlayerToSharedStats(ServerPlayerEntity player) {
        SharedStatsHolder holder = getHolder(player);
        if (holder.isSyncing)
            return;

        holder.isSyncing = true;
        try {
            player.setHealth(holder.sharedHealth);
            player.setAbsorptionAmount(holder.sharedAbsorption);
            player.getHungerManager().setFoodLevel(holder.sharedHunger);
            player.getHungerManager().setSaturationLevel(holder.sharedSaturation);
            SoulLink.LOGGER.debug(
                    "Synced {} to shared stats: HP={}, Absorption={}, Food={}, Sat={}",
                    player.getName().getString(), holder.sharedHealth, holder.sharedAbsorption, holder.sharedHunger,
                    holder.sharedSaturation);
        } finally {
            holder.isSyncing = false;
        }
    }

    /**
     * Gets the ServerWorld for a player. In Yarn 1.21.11, ServerPlayerEntity.getEntityWorld()
     * returns ServerWorld directly.
     */
    private static ServerWorld getPlayerWorld(ServerPlayerEntity player) {
        return player.getEntityWorld();
    }

    /**
     * Called when a player's health changes after taking damage. Updates the master health and
     * syncs to all other players with visual feedback.
     * 
     * @param damagedPlayer The player who took damage
     * @param newHealth The player's health AFTER damage was applied (armor already calculated)
     * @param damageSource The source of the damage
     */
    public static void onPlayerHealthChanged(ServerPlayerEntity damagedPlayer, float newHealth,
            DamageSource damageSource) {
        SharedStatsHolder holder = getHolder(damagedPlayer);
        if (holder.isSyncing)
            return;

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive())
            return;

        ServerWorld playerWorld = getPlayerWorld(damagedPlayer);
        if (playerWorld == null)
            return;

        // Only process if in a temporary world
        if (!runManager.isTemporaryWorld(playerWorld.getRegistryKey()))
            return;

        holder.isSyncing = true;
        try {
            float oldHealth = holder.sharedHealth;
            float currentDamageAmount = oldHealth - newHealth;

            // Handle periodic damage (Poison/Wither) - normalize by player count
            // Without this, N players poisoned = Nx damage speed
            String damageType = damageSource.getName();
            if (damageType.equals("poison") || damageType.equals("wither")) {
                handlePeriodicDamage(damagedPlayer, currentDamageAmount);
                return;
            }

            // Update the master health to match the damaged player's health
            holder.sharedHealth = MathHelper.clamp(newHealth, 0.0f, getMaxHealth());

            // Check for death condition
            if (holder.sharedHealth <= 0 && !Settings.getInstance().isTeamsMode()) {
                SoulLink.LOGGER.info("Shared health depleted - triggering game over");
                runManager.triggerGameOver();
                return;
            }

            // Only sync to other players if health actually decreased
            if (holder.sharedHealth < oldHealth) {
                MinecraftServer server = runManager.getServer();
                if (server == null)
                    return;

                float syncedDamageAmount = oldHealth - holder.sharedHealth;
                List<ServerPlayerEntity> players = TeamsHelper.getPlayersOnPlayersTeam(damagedPlayer);

                // Broadcast damage notification to all players
                // Convert from half-hearts to full hearts for display (Minecraft stores health as
                // 0-20, where 1 heart = 2)
                // Round to nearest 0.5 hearts and ensure minimum of 0.5 for display
                float damageInHearts = syncedDamageAmount / 2.0f;
                float roundedDamage = Math.max(0.5f, Math.round(damageInHearts * 2.0f) / 2.0f);
                String damageText = String.format(java.util.Locale.US, "%.1f", roundedDamage);
                net.minecraft.text.Text damageNotification = net.minecraft.text.Text.empty()
                        .append(RunManager.getPrefix())
                        .append(net.minecraft.text.Text.literal(damagedPlayer.getName().getString())
                                .formatted(net.minecraft.util.Formatting.WHITE))
                        .append(net.minecraft.text.Text.literal(" has taken ")
                                .formatted(net.minecraft.util.Formatting.GRAY))
                        .append(net.minecraft.text.Text.literal(damageText + " ❤")
                                .formatted(net.minecraft.util.Formatting.RED))
                        .append(net.minecraft.text.Text.literal(" damage.")
                                .formatted(net.minecraft.util.Formatting.GRAY));

                for (ServerPlayerEntity player : players) {
                    if (player == damagedPlayer || player.isSpectator() || player.isCreative())
                        continue;
                    player.sendMessageToClient(damageNotification, false);

                    ServerWorld otherWorld = getPlayerWorld(player);
                    if (otherWorld == null)
                        continue;

                    // Skip players not in the run
                    if (!runManager.isTemporaryWorld(otherWorld.getRegistryKey()))
                        continue;

                    // Apply actual damage to trigger all client-side effects (red flash, screen
                    // shake, sound)
                    // Use the world's damage sources for correct API usage
                    DamageSource syncDamage = otherWorld.getDamageSources().generic();

                    // Apply damage using the world-aware damage method
                    // The isSyncing flag prevents onPlayerHealthChanged from recursing
                    player.damage(otherWorld, syncDamage, syncedDamageAmount);

                    // Safety check: if player "died" due to local damage but shared health remains,
                    // restore them
                    if (!player.isAlive() && holder.sharedHealth > 0) {
                        player.setHealth(Math.max(1.0f, holder.sharedHealth));
                    } else {
                        // Ensure health is exactly what we expect (in case of any rounding)
                        player.setHealth(holder.sharedHealth);
                    }
                }

                SoulLink.LOGGER.debug("Health synced: {} -> {} (from {})", oldHealth, holder.sharedHealth,
                        damagedPlayer.getName().getString());
            }

        } finally {
            holder.isSyncing = false;
        }
    }

    /**
     * Handles periodic damage (Poison/Wither) by normalizing it by player count and using an
     * accumulator.
     */
    private static void handlePeriodicDamage(ServerPlayerEntity damagedPlayer, float damageAmount) {
        RunManager runManager = RunManager.getInstance();
        MinecraftServer server = runManager.getServer();
        if (server == null)
            return;

        // Count players in the run
        int playerCount = 0;
        List<ServerPlayerEntity> players = TeamsHelper.getPlayersOnPlayersTeam(damagedPlayer);
        for (ServerPlayerEntity player : players) {
            ServerWorld world = getPlayerWorld(player);
            if (world != null && runManager.isTemporaryWorld(world.getRegistryKey())) {
                playerCount++;
            }
        }

        if (playerCount == 0)
            return;

        SharedStatsHolder holder = getHolder(damagedPlayer);

        // Divide the damage by player count and accumulate
        float normalizedDamage = damageAmount / playerCount;
        holder.damageAccumulator += normalizedDamage;

        SoulLink.LOGGER.debug(
                "[DAMAGE DEBUG] Player {} took {} periodic damage, normalized to {} ({} players), accumulator now {}",
                damagedPlayer.getName().getString(), damageAmount, normalizedDamage, playerCount,
                holder.damageAccumulator);

        // Only apply damage when we've accumulated at least 0.5 HP
        if (holder.damageAccumulator >= 0.5f) {
            float damageToApply = holder.damageAccumulator;
            holder.damageAccumulator = 0.0f;

            float oldHealth = holder.sharedHealth;
            holder.sharedHealth = MathHelper.clamp(holder.sharedHealth - damageToApply, 0.0f, getMaxHealth());

            // Sync to all players
            for (ServerPlayerEntity player : players) {
                ServerWorld otherWorld = getPlayerWorld(player);
                if (otherWorld == null || !runManager.isTemporaryWorld(otherWorld.getRegistryKey()))
                    continue;

                player.setHealth(holder.sharedHealth);
            }

            SoulLink.LOGGER.debug("[DAMAGE DEBUG] Applied {} periodic damage: {} -> {}",
                    damageToApply, oldHealth, holder.sharedHealth);

            if (holder.sharedHealth <= 0 && !Settings.getInstance().isTeamsMode()) {
                runManager.triggerGameOver();
            }
        } else {
            // Revert the damage to the player since it hasn't reached the threshold yet
            damagedPlayer.setHealth(holder.sharedHealth);
        }
    }

    /**
     * Called when a player heals (potions, etc.) Updates the master health and syncs to all
     * players.
     * 
     * Note: Regeneration effect healing is handled separately by onRegenerationHeal() to normalize
     * by player count.
     */
    public static void onPlayerHealed(ServerPlayerEntity healedPlayer, float newHealth) {
        SharedStatsHolder holder = getHolder(healedPlayer);
        if (holder.isSyncing)
            return;

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive())
            return;

        ServerWorld playerWorld = getPlayerWorld(healedPlayer);
        if (playerWorld == null)
            return;

        if (!runManager.isTemporaryWorld(playerWorld.getRegistryKey()))
            return;

        holder.isSyncing = true;
        try {
            float oldHealth = holder.sharedHealth;
            holder.sharedHealth = MathHelper.clamp(newHealth, 0.0f, getMaxHealth());

            // Only sync if health increased
            if (holder.sharedHealth > oldHealth) {
                MinecraftServer server = runManager.getServer();
                if (server == null)
                    return;

                for (ServerPlayerEntity player : TeamsHelper.getPlayersOnPlayersTeam(healedPlayer)) {
                    if (player == healedPlayer)
                        continue;

                    if (player.isSpectator() || player.isCreative())
                        continue;

                    ServerWorld otherWorld = getPlayerWorld(player);
                    if (otherWorld == null)
                        continue;

                    if (!runManager.isTemporaryWorld(otherWorld.getRegistryKey()))
                        continue;

                    player.setHealth(holder.sharedHealth);
                }

                SoulLink.LOGGER.debug("Healing synced: {} -> {}", oldHealth, holder.sharedHealth);
            }

        } finally {
            holder.isSyncing = false;
        }
    }

    /**
     * Called when a player heals from a regeneration effect. The healing amount is divided by the
     * number of players in the run to normalize regen speed.
     * 
     * Without this, N players with regeneration = Nx healing speed since each player's regen would
     * stack.
     */
    public static void onRegenerationHeal(ServerPlayerEntity regenPlayer, float healAmount) {
        SharedStatsHolder holder = getHolder(regenPlayer);
        if (holder.isSyncing)
            return;

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive())
            return;

        ServerWorld playerWorld = getPlayerWorld(regenPlayer);
        if (playerWorld == null)
            return;

        if (!runManager.isTemporaryWorld(playerWorld.getRegistryKey()))
            return;

        MinecraftServer server = runManager.getServer();
        if (server == null)
            return;

        // Count players in the run
        int playerCount = 0;
        List<ServerPlayerEntity> players = TeamsHelper.getPlayersOnPlayersTeam(regenPlayer);
        for (ServerPlayerEntity player : players) {
            ServerWorld world = getPlayerWorld(player);
            if (world != null && runManager.isTemporaryWorld(world.getRegistryKey())) {
                playerCount++;
            }
        }

        if (playerCount == 0)
            return;

        // Divide the heal amount by player count and accumulate
        float normalizedHeal = healAmount / playerCount;
        holder.regenerationHealAccumulator += normalizedHeal;

        SoulLink.LOGGER.debug(
                "[REGEN EFFECT DEBUG] Player {} healed {} HP from regeneration, normalized to {} ({} players), accumulator now {}",
                regenPlayer.getName().getString(), healAmount, normalizedHeal, playerCount,
                holder.regenerationHealAccumulator);

        // Only apply healing when we've accumulated at least 0.5 HP (prevents constant tiny
        // updates)
        if (holder.regenerationHealAccumulator >= 0.5f) {
            float healToApply = holder.regenerationHealAccumulator;
            holder.regenerationHealAccumulator = 0.0f;

            holder.isSyncing = true;
            try {
                float oldHealth = holder.sharedHealth;
                holder.sharedHealth = MathHelper.clamp(holder.sharedHealth + healToApply, 0.0f, getMaxHealth());

                if (holder.sharedHealth > oldHealth) {
                    // Sync to all players
                    for (ServerPlayerEntity player : players) {
                        ServerWorld otherWorld = getPlayerWorld(player);
                        if (otherWorld == null)
                            continue;

                        if (!runManager.isTemporaryWorld(otherWorld.getRegistryKey()))
                            continue;

                        player.setHealth(holder.sharedHealth);
                    }

                    SoulLink.LOGGER.debug(
                            "[REGEN EFFECT DEBUG] Applied {} HP healing from regeneration: {} -> {} ({} players in run)",
                            healToApply, oldHealth, holder.sharedHealth, playerCount);
                }
            } finally {
                holder.isSyncing = false;
            }
        } else {
            // Revert the healing to the player since it hasn't reached the threshold yet
            regenPlayer.setHealth(holder.sharedHealth);
        }
    }

    /**
     * Called when a player's absorption amount changes (from golden apples, etc). Updates the
     * master absorption and syncs to all other players.
     */
    public static void onAbsorptionChanged(ServerPlayerEntity changedPlayer, float newAbsorption) {
        SharedStatsHolder holder = getHolder(changedPlayer);

        if (holder.isSyncing)
            return;

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive())
            return;

        ServerWorld playerWorld = getPlayerWorld(changedPlayer);
        if (playerWorld == null)
            return;

        if (!runManager.isTemporaryWorld(playerWorld.getRegistryKey()))
            return;

        // Only sync if absorption actually changed
        if (Math.abs(newAbsorption - holder.sharedAbsorption) < 0.1f)
            return;

        holder.isSyncing = true;
        try {
            float oldAbsorption = holder.sharedAbsorption;
            holder.sharedAbsorption = MathHelper.clamp(newAbsorption, 0.0f, 20.0f);

            MinecraftServer server = runManager.getServer();
            if (server == null)
                return;

            // Sync to all other players
            for (ServerPlayerEntity player : TeamsHelper.getPlayersOnPlayersTeam(changedPlayer)) {
                if (player == changedPlayer)
                    continue;

                ServerWorld otherWorld = getPlayerWorld(player);
                if (otherWorld == null)
                    continue;

                if (!runManager.isTemporaryWorld(otherWorld.getRegistryKey()))
                    continue;

                player.setAbsorptionAmount(holder.sharedAbsorption);
            }

            SoulLink.LOGGER.debug("Absorption synced: {} -> {} (from {})", oldAbsorption,
                    holder.sharedAbsorption, changedPlayer.getName().getString());

        } finally {
            holder.isSyncing = false;
        }
    }

    /**
     * Called when a player naturally regenerates health (from saturation/hunger). The healing
     * amount is divided by the number of players in the run to normalize regen speed.
     * 
     * Without this, N players = Nx regen speed since each player's regen would stack.
     */
    public static void onNaturalRegen(ServerPlayerEntity regenPlayer, float healAmount) {
        SharedStatsHolder holder = getHolder(regenPlayer);

        if (holder.isSyncing)
            return;

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive())
            return;

        ServerWorld playerWorld = getPlayerWorld(regenPlayer);
        if (playerWorld == null)
            return;

        if (!runManager.isTemporaryWorld(playerWorld.getRegistryKey()))
            return;

        MinecraftServer server = runManager.getServer();
        if (server == null)
            return;

        // Count players in the run
        int playerCount = 0;
        List<ServerPlayerEntity> players = TeamsHelper.getPlayersOnPlayersTeam(regenPlayer);
        for (ServerPlayerEntity player : players) {
            ServerWorld world = getPlayerWorld(player);
            if (world != null && runManager.isTemporaryWorld(world.getRegistryKey())) {
                playerCount++;
            }
        }

        if (playerCount == 0)
            return;

        // Divide the heal amount by player count and accumulate
        float normalizedHeal = healAmount / playerCount;
        holder.regenAccumulator += normalizedHeal;

        SoulLink.LOGGER.debug(
                "[REGEN DEBUG] Player {} healed {} HP, normalized to {} ({} players), accumulator now {}",
                regenPlayer.getName().getString(), healAmount, normalizedHeal, playerCount,
                holder.regenAccumulator);

        // Only apply healing when we've accumulated at least 0.5 HP (prevents constant tiny
        // updates)
        if (holder.regenAccumulator >= 0.5f) {
            float healToApply = holder.regenAccumulator;
            holder.regenAccumulator = 0.0f;

            holder.isSyncing = true;
            try {
                float oldHealth = holder.sharedHealth;
                holder.sharedHealth = MathHelper.clamp(holder.sharedHealth + healToApply, 0.0f, getMaxHealth());

                if (holder.sharedHealth > oldHealth) {
                    // Sync to all players
                    for (ServerPlayerEntity player : players) {
                        ServerWorld otherWorld = getPlayerWorld(player);
                        if (otherWorld == null)
                            continue;

                        if (!runManager.isTemporaryWorld(otherWorld.getRegistryKey()))
                            continue;

                        player.setHealth(holder.sharedHealth);
                    }

                    SoulLink.LOGGER.debug(
                            "[REGEN DEBUG] Applied {} HP healing: {} -> {} ({} players in run)",
                            healToApply, oldHealth, holder.sharedHealth, playerCount);
                }
            } finally {
                holder.isSyncing = false;
            }
        }
    }

    /**
     * Called when a player's hunger changes. Updates master values and syncs to all other players.
     */
    public static void onPlayerHungerChanged(ServerPlayerEntity player, int newFoodLevel,
            float newSaturation) {
        SharedStatsHolder holder = getHolder(player);
        if (holder.isSyncing)
            return;

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive())
            return;

        ServerWorld playerWorld = getPlayerWorld(player);
        if (playerWorld == null)
            return;

        if (!runManager.isTemporaryWorld(playerWorld.getRegistryKey()))
            return;

        holder.isSyncing = true;
        try {

            // Check if values actually changed
            boolean foodChanged = newFoodLevel != holder.sharedHunger;
            boolean satChanged = Math.abs(newSaturation - holder.sharedSaturation) > 0.01f;

            if (!foodChanged && !satChanged) {
                return;
            }

            holder.sharedHunger = MathHelper.clamp(newFoodLevel, 0, 20);
            holder.sharedSaturation = MathHelper.clamp(newSaturation, 0.0f, 20.0f);

            MinecraftServer server = runManager.getServer();
            if (server == null)
                return;

            for (ServerPlayerEntity otherPlayer : TeamsHelper.getPlayersOnPlayersTeam(player)) {
                if (otherPlayer == player)
                    continue;

                ServerWorld otherWorld = getPlayerWorld(otherPlayer);
                if (otherWorld == null)
                    continue;

                if (!runManager.isTemporaryWorld(otherWorld.getRegistryKey()))
                    continue;

                otherPlayer.getHungerManager().setFoodLevel(holder.sharedHunger);
                otherPlayer.getHungerManager().setSaturationLevel(holder.sharedSaturation);
            }

            SoulLink.LOGGER.debug("Hunger synced: Food={}, Saturation={}", holder.sharedHunger,
                    holder.sharedSaturation);

        } finally {
            holder.isSyncing = false;
        }
    }

    /**
     * Called when a player's hunger/saturation drains from natural regeneration. The drain is
     * divided by the number of players to normalize drain rate.
     * 
     * Without this, N players = Nx hunger drain since each player's regen consumes hunger.
     */
    public static void onNaturalHungerDrain(ServerPlayerEntity drainPlayer, int foodDrain,
            float satDrain) {
        SharedStatsHolder holder = getHolder(drainPlayer);
        if (holder.isSyncing)
            return;

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive())
            return;

        ServerWorld playerWorld = getPlayerWorld(drainPlayer);
        if (playerWorld == null)
            return;

        if (!runManager.isTemporaryWorld(playerWorld.getRegistryKey()))
            return;

        MinecraftServer server = runManager.getServer();
        if (server == null)
            return;

        // Count players in the run
        int playerCount = 0;
        List<ServerPlayerEntity> players = TeamsHelper.getPlayersOnPlayersTeam(drainPlayer);
        for (ServerPlayerEntity player : players) {
            ServerWorld world = getPlayerWorld(player);
            if (world != null && runManager.isTemporaryWorld(world.getRegistryKey())) {
                playerCount++;
            }
        }

        if (playerCount == 0)
            return;

        // Divide the drain by player count and accumulate
        float normalizedFoodDrain = (float) foodDrain / playerCount;
        float normalizedSatDrain = satDrain / playerCount;

        holder.hungerDrainAccumulator += normalizedFoodDrain;
        holder.saturationDrainAccumulator += normalizedSatDrain;

        // Check if we should apply the accumulated drain
        boolean shouldApply = holder.hungerDrainAccumulator >= 1.0f || holder.saturationDrainAccumulator >= 0.5f;

        if (shouldApply) {
            holder.isSyncing = true;
            try {
                // Apply accumulated food drain (whole numbers only)
                int foodToApply = (int) holder.hungerDrainAccumulator;
                if (foodToApply > 0) {
                    holder.sharedHunger = MathHelper.clamp(holder.sharedHunger - foodToApply, 0, 20);
                    holder.hungerDrainAccumulator -= foodToApply;
                }

                // Apply accumulated saturation drain
                if (holder.saturationDrainAccumulator >= 0.1f) {
                    float satToApply = holder.saturationDrainAccumulator;
                    holder.sharedSaturation = MathHelper.clamp(holder.sharedSaturation - satToApply, 0.0f, 20.0f);
                    holder.saturationDrainAccumulator = 0.0f;
                }

                // Sync to all players
                for (ServerPlayerEntity player : players) {
                    ServerWorld otherWorld = getPlayerWorld(player);
                    if (otherWorld == null)
                        continue;

                    if (!runManager.isTemporaryWorld(otherWorld.getRegistryKey()))
                        continue;

                    player.getHungerManager().setFoodLevel(holder.sharedHunger);
                    player.getHungerManager().setSaturationLevel(holder.sharedSaturation);
                }

                SoulLink.LOGGER.debug(
                        "Natural hunger drain applied: Food={}, Sat={} (from {} players)",
                        holder.sharedHunger, holder.sharedSaturation, playerCount);
            } finally {
                holder.isSyncing = false;
            }
        }
    }

    /**
     * Periodic sync check - ensures all players stay in sync. Called from server tick event.
     */
    public static void tickSync(MinecraftServer server) {
        if (isTickSyncing)
            return;

        RunManager runManager = RunManager.getInstance();
        if (runManager == null || !runManager.isRunActive())
            return;

        // Only run every 20 ticks (1 second)
        if (server.getTicks() % 20 != 0)
            return;

        isTickSyncing = true;
        try {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                ServerWorld playerWorld = getPlayerWorld(player);
                if (playerWorld == null)
                    continue;

                if (!runManager.isTemporaryWorld(playerWorld.getRegistryKey()))
                    continue;

                SharedStatsHolder holder = getHolder(player);

                // If player's values drift from master, correct them
                float playerHealth = player.getHealth();
                float playerAbsorption = player.getAbsorptionAmount();
                int playerFood = player.getHungerManager().getFoodLevel();
                float playerSat = player.getHungerManager().getSaturationLevel();

                if (Math.abs(playerHealth - holder.sharedHealth) > 0.5f) {
                    player.setHealth(holder.sharedHealth);
                }
                if (Math.abs(playerAbsorption - holder.sharedAbsorption) > 0.5f) {
                    player.setAbsorptionAmount(holder.sharedAbsorption);
                }
                if (playerFood != holder.sharedHunger) {
                    player.getHungerManager().setFoodLevel(holder.sharedHunger);
                }
                if (Math.abs(playerSat - holder.sharedSaturation) > 0.5f) {
                    player.getHungerManager().setSaturationLevel(holder.sharedSaturation);
                }
            }
        } finally {
            isTickSyncing = false;
        }
    }

    /**
     * Checks if the system is currently syncing (to prevent loops).
     */
    public static boolean isSyncing(ServerPlayerEntity player) {
        return getHolder(player).isSyncing;
    }

    /**
     * Sets the syncing flag. Used by other shared handlers (like SharedPotionHandler) to prevent
     * heal/damage operations from triggering additional syncs.
     */
    public static void setSyncing(ServerPlayerEntity player, boolean syncing) {
        getHolder(player).isSyncing = syncing;
    }

//    /**
//     * Executes a task with syncing temporarily disabled.
//     */
//    public static void withSyncingDisabled(Runnable task) {
//        boolean wasSyncing = isSyncing;
//        setSyncing(true); // "Syncing" means we are currently applying a sync, so ignore local
//                          // changes
//        try {
//            task.run();
//        } finally {
//            setSyncing(wasSyncing);
//        }
//    }
//
//    // Getters

//    public static float getSharedHealth() {
//        return sharedHealth;
//    }
//
//    public static int getSharedHunger() {
//        return sharedHunger;
//    }
//
//    public static float getSharedSaturation() {
//        return sharedSaturation;
//    }
//
//    /**
//     * Force sets the shared health (for admin/debug purposes).
//     */
//    public static void setSharedHealth(float health, MinecraftServer server) {
//        float clampedHealth = MathHelper.clamp(health, 0.0f, getMaxHealth());
//
//        if (server == null) {
//            sharedHealth = clampedHealth;
//            return;
//        }
//
//        RunManager runManager = RunManager.getInstance();
//        if (runManager == null || !runManager.isRunActive()) {
//            sharedHealth = clampedHealth;
//            return;
//        }
//        sharedHealth = clampedHealth;
//
//        isSyncing = true;
//        try {
//            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
//                ServerWorld playerWorld = getPlayerWorld(player);
//                if (playerWorld == null)
//                    continue;
//
//                if (runManager.isTemporaryWorld(playerWorld.getRegistryKey())) {
//                    // Skip spectators and creative mode players for health sync
//                    if (player.isSpectator() || player.isCreative()) {
//                        continue;
//                    }
//                    player.setHealth(sharedHealth);
//                }
//            }
//        } finally {
//            isSyncing = false;
//        }
//    }
}
