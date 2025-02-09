package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.events.PlayerEvents;
import de.maxhenkel.voicechat.net.NetManager;
import de.maxhenkel.voicechat.net.PlayerStatePacket;
import de.maxhenkel.voicechat.net.PlayerStatesPacket;
import de.maxhenkel.voicechat.voice.common.PlayerState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerStateManager {

    private ConcurrentHashMap<UUID, PlayerState> states;

    public PlayerStateManager() {
        states = new ConcurrentHashMap<>();
        PlayerEvents.PLAYER_LOGGED_OUT.register(this::removePlayer);
        PlayerEvents.PLAYER_LOGGED_IN.register(this::notifyPlayer);

        NetManager.registerServerReceiver(PlayerStatePacket.class, (server, player, handler, responseSender, packet) -> {
            PlayerState state = packet.getPlayerState();
            state.setGameProfile(player.getGameProfile());
            states.put(player.getUUID(), state);
            broadcastState(server, state);
        });
    }

    private void broadcastState(MinecraftServer server, PlayerState state) {
        PlayerStatePacket packet = new PlayerStatePacket(state);
        server.getPlayerList().getPlayers().forEach(p -> NetManager.sendToClient(p, packet));
    }

    private void notifyPlayer(ServerPlayer player) {
        PlayerStatesPacket packet = new PlayerStatesPacket(states);
        NetManager.sendToClient(player, packet);
        broadcastState(player.server, new PlayerState(false, true, player.getGameProfile()));
    }

    private void removePlayer(ServerPlayer player) {
        states.remove(player.getUUID());
        broadcastState(player.server, new PlayerState(true, true, player.getGameProfile())); //TODO maybe remove
    }

    @Nullable
    public PlayerState getState(UUID playerUUID) {
        return states.get(playerUUID);
    }

    public List<PlayerState> getStates() {
        return new ArrayList<>(states.values());
    }

}
