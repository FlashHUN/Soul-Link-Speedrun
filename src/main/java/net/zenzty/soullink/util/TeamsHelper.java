package net.zenzty.soullink.util;

import net.minecraft.scoreboard.Team;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.zenzty.soullink.server.settings.Settings;

import java.util.List;
import java.util.Objects;

public final class TeamsHelper {
    private TeamsHelper() {}

    public static String getPlayersTeamNameOrNull(ServerPlayerEntity player) {
        if (!Settings.getInstance().isTeamsMode()) {
            return null;
        }
        Team team = player.getScoreboardTeam();
        if (team == null) {
            return null;
        }
        return team.getName();
    }

    public static List<ServerPlayerEntity> getPlayersOnPlayersTeam(ServerPlayerEntity player) {
        PlayerManager playerManager = player.getEntityWorld().getServer().getPlayerManager();
        List<ServerPlayerEntity> allPlayers = playerManager.getPlayerList();
        if (!Settings.getInstance().isTeamsMode()) {
            return allPlayers;
        }
        Team team = player.getScoreboardTeam();
        if (team == null) {
            return allPlayers.stream().filter(p -> p.getScoreboardTeam() == null).toList();
        }

        return team.getPlayerList().stream().map(playerManager::getPlayer).filter(Objects::nonNull).toList();
    }
}
