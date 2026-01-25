package net.zenzty.soullink.server.settings;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;
import net.zenzty.soullink.SoulLink;
import net.zenzty.soullink.mixin.ui.ScreenHandlerAccessor;
import net.zenzty.soullink.server.run.RunManager;

import java.util.*;
import java.util.stream.Collectors;

import static net.zenzty.soullink.server.settings.SettingsGui.createItemName;

public class TeamsGui {

    // Slot positions in the GUI
    private static final int TEAMS_COUNT_SLOT = 4;
    private static final int PLAYERS_START_SLOT = 9;
    private static final int PLAYERS_END_SLOT = 44;
    private static final int CONFIRM_SLOT = 49; // Bottom center

    // Size of double chest
    private static final int INVENTORY_SIZE = 54;

    public static void open(ServerPlayerEntity player) {
        Teams teams = Teams.getInstance();

        Teams.TeamsSnapshot originalSnapshot = new Teams.TeamsSnapshot(teams.getPlayerToTeamId());

        TeamsInventory inventory = new TeamsInventory(originalSnapshot, RunManager.getInstance().getServer().getPlayerManager().getPlayerList());

        player.openHandledScreen(
                new SimpleNamedScreenHandlerFactory((syncId, playerInventory, playerEntity) -> {
                    return new TeamsScreenHandler(syncId, inventory, player);
                }, Text.literal("Soul Link Teams").formatted(Formatting.DARK_GRAY)));
    }

    public static class TeamsInventory extends SimpleInventory {
        private final int maxTeams;

        private int numberOfTeams;
        private final Map<String, Integer> pendingPlayerToTeamId;
        private final List<ServerPlayerEntity> players;
        private final Teams.TeamsSnapshot original;

        public TeamsInventory(Teams.TeamsSnapshot original, List<ServerPlayerEntity> players) {
            super(INVENTORY_SIZE);
            this.players = players;
            this.original = original;
            this.pendingPlayerToTeamId = new HashMap<>(original.playerToTeamId());
            Set<String> playersNotInTeams = players.stream().map(PlayerEntity::getNameForScoreboard).filter(name -> !pendingPlayerToTeamId.containsKey(name)).collect(Collectors.toSet());

            this.maxTeams = Math.min(players.size(), 15);

            int highestTeamId = Math.min(maxTeams, Teams.getTeams(pendingPlayerToTeamId).size() + 1);
            for (String player : playersNotInTeams) {
                pendingPlayerToTeamId.put(player, highestTeamId);
            }
            this.numberOfTeams = Teams.getTeams(pendingPlayerToTeamId).size();

            populateItems();
        }

        public void populateItems() {
            // Fill with gray stained glass panes as background
            ItemStack filler = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
            filler.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "));
            for (int i = 0; i < INVENTORY_SIZE; i++) {
                setStack(i, filler.copy());
            }

            setStack(TEAMS_COUNT_SLOT, createTeamsCountItem());
            int playerCount = Math.min(PLAYERS_END_SLOT - PLAYERS_START_SLOT, players.size());
            for (int i = 0; i < playerCount; i++) {
                setStack(PLAYERS_START_SLOT + i, createPlayerItem(players.get(i)));
            }
            setStack(CONFIRM_SLOT, createConfirmItem());
        }

        private ItemStack createTeamsCountItem() {
            ItemStack item = new ItemStack(Items.DIAMOND, numberOfTeams);

            item.set(DataComponentTypes.CUSTOM_NAME,
                    createItemName("Number of Teams: " + numberOfTeams, Formatting.GREEN, Formatting.BOLD));

            return item;
        }

        private ItemStack createPlayerItem(ServerPlayerEntity player) {
            int teamId = pendingPlayerToTeamId.get(player.getNameForScoreboard());
            Formatting teamColour = Formatting.byColorIndex(teamId);

            ItemStack item = new ItemStack(Items.PLAYER_HEAD, teamId);
            item.set(DataComponentTypes.CUSTOM_NAME,
                    createItemName(player.getGameProfile().name(), teamColour, Formatting.BOLD));
            item.set(DataComponentTypes.PROFILE, ProfileComponent.ofStatic(player.getGameProfile()));

            List<Text> loreLines = new ArrayList<>();
            loreLines.add(Text.literal("Current Team: ")
                    .setStyle(Style.EMPTY.withItalic(true).withFormatting(Formatting.GRAY))
                    .append(Text.literal(teamColour.getName())).setStyle(Style.EMPTY.withItalic(false).withFormatting(teamColour)));

            LoreComponent lore = new LoreComponent(loreLines);
            item.set(DataComponentTypes.LORE, lore);

            return item;
        }

        private ItemStack createConfirmItem() {
            ItemStack item = new ItemStack(Items.EMERALD);
            item.set(DataComponentTypes.CUSTOM_NAME,
                    createItemName("✓ Confirm", Formatting.GREEN, Formatting.BOLD));

            List<Text> loreLines = new ArrayList<>();

            loreLines.add(Text.literal("Current Settings:")
                    .setStyle(Style.EMPTY.withItalic(false).withFormatting(Formatting.WHITE)));

            loreLines.add(Text.literal("  • Number of Teams: ")
                    .setStyle(Style.EMPTY.withItalic(false).withFormatting(Formatting.GRAY))
                    .append(Text.literal(String.valueOf(numberOfTeams))
                            .setStyle(Style.EMPTY.withItalic(false).withFormatting(Formatting.GREEN))));

            loreLines.add(Text.literal("  • Teams: ")
                    .setStyle(Style.EMPTY.withItalic(false).withFormatting(Formatting.GRAY)));

            Collection<List<String>> teams = Teams.getTeams(pendingPlayerToTeamId);
            for (List<String> teamMembers : teams) {
                loreLines.add(Text.literal("    • ")
                        .setStyle(Style.EMPTY.withItalic(false).withFormatting(Formatting.GRAY)).append(Text.literal(String.join(", ", teamMembers)).formatted(Formatting.GREEN)));
            }
            loreLines.add(Text.empty());
            loreLines.add(Text.literal("⚠ Teams apply next run!")
                    .setStyle(Style.EMPTY.withItalic(false).withFormatting(Formatting.YELLOW)));
            loreLines.add(Text.empty());
            loreLines.add(Text.literal("Click to save and close.")
                    .setStyle(Style.EMPTY.withItalic(false).withFormatting(Formatting.DARK_GRAY)));

            LoreComponent lore = new LoreComponent(loreLines);
            item.set(DataComponentTypes.LORE, lore);

            return item;
        }

        public boolean hasChanges() {
            return pendingPlayerToTeamId != original.playerToTeamId();
        }

        public Teams.TeamsSnapshot getPendingSnapshot() {
            return new Teams.TeamsSnapshot(pendingPlayerToTeamId);
        }

        public Teams.TeamsSnapshot getOriginal() {
            return original;
        }

        public Map<String, Integer> getPendingPlayerToTeamId() {
            return pendingPlayerToTeamId;
        }

        public int getMaxTeams() {
            return maxTeams;
        }

        public int getNumberOfTeams() {
            return numberOfTeams;
        }

        public List<ServerPlayerEntity> getPlayers() {
            return players;
        }

        public void cyclePlayersTeam(String player) {
            int teamId = pendingPlayerToTeamId.get(player);
            if (teamId >= numberOfTeams) {
                teamId = 1;
            } else {
                teamId +=  1;
            }
            pendingPlayerToTeamId.put(player, teamId);
        }

        public void cycleNumberOfTeams() {
            if (numberOfTeams >= maxTeams) {
                numberOfTeams = Math.min(Collections.max(pendingPlayerToTeamId.values()), maxTeams);
            } else {
                numberOfTeams += 1;
            }
        }
    }

    public static class TeamsScreenHandler extends GenericContainerScreenHandler {

        private final TeamsInventory teamsInventory;
        private final ServerPlayerEntity player;
        // Tracks if changes were confirmed vs cancelled
        private boolean confirmed = false;

        public TeamsScreenHandler(int syncId, TeamsInventory inventory, ServerPlayerEntity player) {
            super(ScreenHandlerType.GENERIC_9X6, syncId, player.getInventory(), inventory, 6);
            this.teamsInventory = inventory;
            this.player = player;

            // Replace inventory slots (0-53) with virtual slots
            // Player inventory slots (54+) should remain normal
            for (int i = 0; i < INVENTORY_SIZE; i++) {
                Slot oldSlot = this.slots.get(i);
                Slot newSlot = new SettingsGui.VirtualSlot(inventory, i, oldSlot.x, oldSlot.y);
                this.slots.set(i, newSlot);
            }
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity clickingPlayer) {
            // Handle settings GUI slots (works for all game modes including spectator)
            if (slotIndex < INVENTORY_SIZE && slotIndex >= 0) {
                // Handle the settings change (this is a virtual click, not a real item interaction)
                handleSettingsClick(slotIndex);

                // Clear cursor on server side - virtual GUI doesn't allow item movement
                setCursorStack(net.minecraft.item.ItemStack.EMPTY);

                // For spectators, we need to force a complete state sync to avoid protocol errors
                // The standard sendContentUpdates doesn't work properly for spectators because
                // Minecraft's client ignores standard sync packets in spectator mode.
                // Using updateToClient forces a complete resync with fresh revision numbers.
                if (clickingPlayer instanceof ServerPlayerEntity serverPlayer
                        && serverPlayer.interactionManager.getGameMode() == GameMode.SPECTATOR) {
                    // Force complete state sync - this sends all slots + cursor with fresh revision
                    ScreenHandlerAccessor accessor = (ScreenHandlerAccessor) this;
                    accessor.invokeUpdateToClient();
                } else {
                    // For non-spectators, normal content updates work fine
                    sendContentUpdates();
                }

                // Don't call parent - this is a virtual GUI, we handle everything ourselves
                return;
            }

            // Delegate player inventory slot clicks (54+) to super for normal behavior
            super.onSlotClick(slotIndex, button, actionType, clickingPlayer);
        }

        @Override
        public ItemStack quickMove(net.minecraft.entity.player.PlayerEntity playerEntity,
                                   int slot) {
            // Virtual GUI - disable all shift-click transfers
            return ItemStack.EMPTY;
        }

        @Override
        public boolean canInsertIntoSlot(ItemStack stack, net.minecraft.screen.slot.Slot slot) {
            // Virtual GUI - prevent any item insertion into virtual slots
            if (slot.inventory == teamsInventory) {
                return false;
            }
            // Allow normal player inventory interactions
            return super.canInsertIntoSlot(stack, slot);
        }

        private void handleSettingsClick(int slotIndex) {
            if (slotIndex == TEAMS_COUNT_SLOT) {
                teamsInventory.cycleNumberOfTeams();
                teamsInventory.populateItems();
                playClickSound();
            } else if (slotIndex == CONFIRM_SLOT) {
                if (teamsInventory.hasChanges()) {
                    // Apply the changes
                    Teams.getInstance()
                            .applySnapshot(teamsInventory.getPendingSnapshot());
                    confirmed = true;

                    // Broadcast changes to all players
                    broadcastChanges();

                    player.closeHandledScreen();
                    playConfirmSound();
                } else {
                    player.sendMessage(RunManager.formatMessage("No changes to save."), false);
                    player.closeHandledScreen();
                }
            } else if (slotIndex >= PLAYERS_START_SLOT && slotIndex <= PLAYERS_END_SLOT) {
                ItemStack item = this.slots.get(slotIndex).getStack();
                if (item.isOf(Items.PLAYER_HEAD) && item.contains(DataComponentTypes.PROFILE)) {
                    String playerName = item.get(DataComponentTypes.PROFILE).getGameProfile().name();
                    teamsInventory.cyclePlayersTeam(playerName);
                    teamsInventory.populateItems();
                    playClickSound();
                }
            }

        }

        /**
         * Broadcasts the settings changes to all players in chat.
         */
        private void broadcastChanges() {
            RunManager runManager = RunManager.getInstance();
            if (runManager == null)
                return;

            MinecraftServer server = runManager.getServer();
            if (server == null)
                return;

            String playerName = player.getName().getString();

            // Header message
            Text headerMsg = Text.empty().append(RunManager.getPrefix())
                    .append(Text.literal(playerName).formatted(Formatting.WHITE))
                    .append(Text.literal(" changed teams:").formatted(Formatting.GRAY));
            server.getPlayerManager().broadcast(headerMsg, false);

            Collection<List<String>> teams = Teams.getTeams(teamsInventory.getPendingPlayerToTeamId());
            for (List<String> team : teams) {
                Formatting teamColour = Formatting.byColorIndex(teamsInventory.getPendingPlayerToTeamId().get(team.getFirst()));
                Text teamMsg = Text.empty().append(RunManager.getPrefix())
                        .append(Text.literal("  • ").setStyle(
                                Style.EMPTY.withItalic(false).withFormatting(Formatting.GRAY)))
                        .append(Text.literal(String.join(", ", team)).setStyle(
                                Style.EMPTY.withItalic(false).withFormatting(teamColour)
                        ));
                server.getPlayerManager().broadcast(teamMsg, false);
            }

            // Footer message
            Text footerMsg = Text.empty().append(RunManager.getPrefix()).append(
                    Text.literal("Changes will apply on next run.").formatted(Formatting.YELLOW));
            server.getPlayerManager().broadcast(footerMsg, false);
        }

        private void playClickSound() {
            player.getEntityWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                    net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(),
                    net.minecraft.sound.SoundCategory.MASTER, 0.5f, 1.0f);
        }

        private void playConfirmSound() {
            player.getEntityWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                    net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP,
                    net.minecraft.sound.SoundCategory.MASTER, 0.5f, 1.0f);
        }


        @Override
        public void onClosed(net.minecraft.entity.player.PlayerEntity closingPlayer) {
            super.onClosed(closingPlayer);

            // If closed without confirming and there were changes, notify player
            if (!confirmed && teamsInventory.hasChanges()) {
                player.sendMessage(RunManager.formatMessage("Teams changes discarded."), false);
            }
        }

        @Override
        public boolean canUse(net.minecraft.entity.player.PlayerEntity playerEntity) {
            return true;
        }
    }
}
