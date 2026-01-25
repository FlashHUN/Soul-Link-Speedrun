package net.zenzty.soullink.server.settings;

import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.server.run.RunManager;
import net.zenzty.soullink.server.run.RunState;

import java.util.*;
import java.util.stream.Collectors;

public class Teams {
    private static final Teams instance = new Teams();

    private Map<String, Integer> playerToTeamId = new HashMap<>();

    private TeamsSnapshot pendingSnapshot;

    private Teams() {}

    public static Teams getInstance() {
        return instance;
    }

    public Map<String, Integer> getPlayerToTeamId() {
        return playerToTeamId;
    }

    public void setPlayerToTeamId(Map<String, Integer> playerToTeamId) {
        this.playerToTeamId = playerToTeamId;
    }

    public TeamsSnapshot createSnapshot() {
        return new TeamsSnapshot(playerToTeamId);
    }

    public static void cleanupTeams(MinecraftServer server) {
        List<Team> teams = server.getScoreboard().getTeams().stream().toList();
        for (Team team : teams) {
            server.getScoreboard().removeTeam(team);
        }
    }

    public static void createTeams(MinecraftServer server) {
        cleanupTeams(server);
        Scoreboard scoreboard = server.getScoreboard();
        Collection<List<String>> teams = getTeams();
        int teamId = 1;
        for (List<String> team : teams) {
            String teamName = getTeamName(teamId);
            Team scoreboardTeam = scoreboard.addTeam(teamName);
            for (String player : team) {
                scoreboard.addScoreHolderToTeam(player, scoreboardTeam);
            }
            teamId += 1;
        }
    }

    public static Collection<List<String>> getTeams(Map<String, Integer> playerToTeamId) {
        return playerToTeamId.entrySet().stream()
                .collect(Collectors.groupingBy(Map.Entry::getValue, Collectors.mapping(Map.Entry::getKey, Collectors.toList()))).values();
    }

    private static Collection<List<String>> getTeams() {
        return getTeams(getInstance().playerToTeamId);
    }

    public static String getTeamName(int id) {
        return "soullink-" + id;
    }

    public void applySnapshot(TeamsSnapshot snapshot) {
        // Check if a run is active
        RunManager runManager = RunManager.getInstance();
        boolean runActive = runManager != null && (runManager.getGameState() == RunState.RUNNING
                || runManager.getGameState() == RunState.GENERATING_WORLD);

        if (runActive) {
            // Check if anything actually changed
            Teams.TeamsSnapshot current = createSnapshot();
            if (snapshot.equals(current)) {
                this.pendingSnapshot = null;
                return;
            }

            // Defer all changes until next run
            this.pendingSnapshot = snapshot;
            SoulLink.LOGGER.info("Settings changes queued for next run: {}", snapshot);
        } else {
            // No active run - apply immediately
            applySnapshotInternal(snapshot);
            this.pendingSnapshot = null;
        }
    }

    private void applySnapshotInternal(TeamsSnapshot snapshot) {
        this.playerToTeamId = snapshot.playerToTeamId;

        SoulLink.LOGGER.info("Teams applied: {}", String.join(" | ", getTeams().stream().map(team -> String.join(", ", team)).toList()));
    }

    public void applyPendingTeams() {
        if (pendingSnapshot == null) {
            return;
        }
        applySnapshotInternal(pendingSnapshot);
        pendingSnapshot = null;
    }

    public record TeamsSnapshot(Map<String, Integer> playerToTeamId) {}
}
