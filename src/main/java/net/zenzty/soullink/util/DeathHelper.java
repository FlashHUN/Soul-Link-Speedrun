package net.zenzty.soullink.util;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.scoreboard.ScoreAccess;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.Stats;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.rule.GameRules;
import net.zenzty.soullink.server.run.RunManager;

import java.util.Optional;

public class DeathHelper {
    public static void handleDeathInTeamsMode(ServerPlayerEntity player, DamageSource damageSource) {
        player.dropShoulderEntities();
        if (player.getEntityWorld().getGameRules().getValue(GameRules.FORGIVE_DEAD_PLAYERS)) {
            player.forgiveMobAnger();
        }

        if (!player.isSpectator()) {
            player.drop(player.getEntityWorld(), damageSource);

            player.setExperienceLevel(0);
            player.setExperiencePoints(0);

            player.getHungerManager().setFoodLevel(20);
            player.getHungerManager().setSaturationLevel(5.0f);
        }

        player.getEntityWorld().getScoreboard().forEachScore(ScoreboardCriterion.DEATH_COUNT, player, ScoreAccess::incrementScore);
        LivingEntity livingEntity = player.getPrimeAdversary();
        if (livingEntity != null) {
            player.incrementStat(Stats.KILLED_BY.getOrCreateStat(livingEntity.getType()));
            livingEntity.updateKilledAdvancementCriterion(player, damageSource);
        }

        player.incrementStat(Stats.DEATHS);
        player.resetStat(Stats.CUSTOM.getOrCreateStat(Stats.TIME_SINCE_DEATH));
        player.resetStat(Stats.CUSTOM.getOrCreateStat(Stats.TIME_SINCE_REST));
        player.extinguish();
        player.setFrozenTicks(0);
        player.setOnFire(false);
        player.getDamageTracker().update();
        player.setLastDeathPos(Optional.of(GlobalPos.create(player.getEntityWorld().getRegistryKey(), player.getBlockPos())));

        RunManager.getInstance().teleportDeadPlayerToSpawn(player);
    }
}
