package net.zenzty.soullink.util;

import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.settings.Settings;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public final class TeamsHelper {
    private TeamsHelper() {}

    public static List<String> getAllTeamNames() {
        Set<String> teamNames = new HashSet<>(RunManager.getInstance().getServer().getScoreboard().getTeams().stream().map(Team::getName).toList());
        teamNames.add(null);
        return teamNames.stream().toList();
    }

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

    public static List<ServerPlayerEntity> getPlayersOnTeam(@Nullable String teamName) {
        MinecraftServer server = RunManager.getInstance().getServer();
        PlayerManager playerManager = server.getPlayerManager();
        List<ServerPlayerEntity> allPlayers = playerManager.getPlayerList();
        if (!Settings.getInstance().isTeamsMode()) {
            return allPlayers;
        }
        if (teamName == null) {
            return allPlayers.stream().filter(p -> p.getScoreboardTeam() == null).toList();
        }
        Team team = server.getScoreboard().getTeam(teamName);
        if (team == null) {
            return allPlayers.stream().filter(p -> p.getScoreboardTeam() == null).toList();
        }
        return team.getPlayerList().stream().map(playerManager::getPlayer).filter(Objects::nonNull).toList();
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
