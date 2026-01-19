package net.zenzty.soullink.util;

import net.minecraft.scoreboard.Team;
import net.minecraft.server.network.ServerPlayerEntity;
import net.zenzty.soullink.server.settings.Settings;

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
}
