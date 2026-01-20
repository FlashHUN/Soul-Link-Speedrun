package net.zenzty.soullink.mixin.player;

import java.util.List;

import net.zenzty.soullink.server.settings.Settings;
import net.zenzty.soullink.util.DeathHelper;
import net.zenzty.soullink.util.TeamsHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.run.RunManager;

/**
 * Mixin for ServerPlayerEntity to prevent death during active runs and sync healing.
 */
@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {

    /**
     * Final safety net - cancel any death during an active run. This prevents the death screen from
     * ever appearing. This is the correct place to detect death because armor/enchantment damage
     * reductions have already been applied.
     */
    @Inject(method = "onDeath", at = @At("HEAD"), cancellable = true)
    private void preventDeathDuringRun(DamageSource damageSource, CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        RunManager runManager = RunManager.getInstance();

        // Only intercept death during active runs
        if (runManager == null || !runManager.isRunActive()) {
            return;
        }

        boolean isTeamsMode = Settings.getInstance().isTeamsMode();

        SoulLink.LOGGER.info("Player {} died during active run - {}",
                player.getName().getString(), isTeamsMode ? "killing team " + TeamsHelper.getPlayersTeamNameOrNull(player) : "triggering game over");

        // Cancel the death event
        ci.cancel();

        // Broadcast death message to all players (use vanilla death message format)
        Text deathMessage = damageSource.getDeathMessage(player);
        Text formattedDeathMessage = Text.empty().append(RunManager.getPrefix())
                .append(Text.literal("☠ ").formatted(Formatting.DARK_RED))
                .append(deathMessage.copy().formatted(Formatting.RED));
        runManager.getServer().getPlayerManager().broadcast(formattedDeathMessage, false);

        // Restore health so player doesn't look dead
        player.setHealth(player.getMaxHealth());

        // Clear lingering harmful effects and extinguish fire
        List<RegistryEntry<StatusEffect>> effectsToRemove = player.getStatusEffects().stream()
                .filter(effect -> !effect.getEffectType().value().isBeneficial())
                .map(StatusEffectInstance::getEffectType).toList();

        effectsToRemove.forEach(player::removeStatusEffect);
        player.extinguish();

        if (isTeamsMode) {
            List<ServerPlayerEntity> playersOnTeam = TeamsHelper.getPlayersOnPlayersTeam(player);
            for (ServerPlayerEntity playerOnTeam : playersOnTeam) {
                DeathHelper.handleDeathInTeamsMode(playerOnTeam, damageSource);
            }
        } else if (!runManager.isGameOver()) {
            // Trigger game over if not already in that state
            net.minecraft.server.MinecraftServer server = runManager.getServer();
            if (server != null) {
                server.execute(() -> {
                    if (!runManager.isGameOver()) {
                        runManager.triggerGameOver();
                    }
                });
            }
        }
    }

}
