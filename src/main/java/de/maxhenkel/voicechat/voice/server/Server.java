package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.debug.CooldownTimer;
import de.maxhenkel.voicechat.voice.common.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.net.BindException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class Server extends Thread {

    private Map<UUID, ClientConnection> connections;
    private Map<UUID, UUID> secrets;
    private int port;
    private MinecraftServer server;
    private DatagramSocket socket;
    private ProcessThread processThread;
    private BlockingQueue<NetworkMessage> packetQueue;
    private PingManager pingManager;
    private PlayerStateManager playerStateManager;

    public Server(int port, MinecraftServer server) {
        this.port = port;
        this.server = server;
        connections = new HashMap<>();
        secrets = new HashMap<>();
        packetQueue = new LinkedBlockingQueue<>();
        pingManager = new PingManager(this);
        playerStateManager = new PlayerStateManager();
        setDaemon(true);
        setName("VoiceChatServerThread");
        processThread = new ProcessThread();
        processThread.start();
    }

    @Override
    public void run() {
        try {
            InetAddress address = null;
            String addr = Voicechat.SERVER_CONFIG.voiceChatBindAddress.get();
            try {
                if (!addr.isEmpty()) {
                    address = InetAddress.getByName(addr);
                }
            } catch (Exception e) {
                Voicechat.LOGGER.error("Failed to parse bind IP address '" + addr + "'");
                Voicechat.LOGGER.info("Binding to default IP address");
                e.printStackTrace();
            }
            try {
                socket = new DatagramSocket(port, address);
                socket.setTrafficClass(0x04); // IPTOS_RELIABILITY
            } catch (BindException e) {
                Voicechat.LOGGER.error("Failed to bind to address '" + addr + "'");
                e.printStackTrace();
                System.exit(1);
                return;
            }
            Voicechat.LOGGER.info("Server started at port " + port);

            while (!socket.isClosed()) {
                try {
                    NetworkMessage message = NetworkMessage.readPacketServer(socket, this);
                    packetQueue.add(message);
                } catch (Exception e) {
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    public UUID getSecret(UUID playerUUID) {
        if (secrets.containsKey(playerUUID)) {
            return secrets.get(playerUUID);
        } else {
            UUID secret = UUID.randomUUID();
            secrets.put(playerUUID, secret);
            return secret;
        }
    }

    public void disconnectClient(UUID playerUUID) {
        connections.remove(playerUUID);
        secrets.remove(playerUUID);
    }

    public void close() {
        socket.close();
        processThread.close();
    }

    private class ProcessThread extends Thread {
        private boolean running;

        public ProcessThread() {
            this.running = true;
            setDaemon(true);
            setName("VoiceChatPacketProcessingThread");
        }

        @Override
        public void run() {
            while (running) {
                try {
                    pingManager.checkTimeouts();
                    keepAlive();

                    NetworkMessage message = packetQueue.poll(10, TimeUnit.MILLISECONDS);
                    if (message == null) {
                        continue;
                    }
                    if (System.currentTimeMillis() - message.getTimestamp() > message.getTTL()) {
                        CooldownTimer.run("ttl", () -> {
                            Voicechat.LOGGER.warn("Dropping voice chat packets! Your Server might be overloaded!");
                            Voicechat.LOGGER.warn("Packet queue has {} packets", packetQueue.size());
                        });
                        continue;
                    }

                    if (message.getPacket() instanceof AuthenticatePacket) {
                        AuthenticatePacket packet = (AuthenticatePacket) message.getPacket();
                        UUID secret = secrets.get(packet.getPlayerUUID());
                        if (secret != null && secret.equals(packet.getSecret())) {
                            ClientConnection connection;
                            if (!connections.containsKey(packet.getPlayerUUID())) {
                                connection = new ClientConnection(packet.getPlayerUUID(), message.getAddress());
                                connections.put(packet.getPlayerUUID(), connection);
                                Voicechat.LOGGER.info("Successfully authenticated player {}", packet.getPlayerUUID());
                            } else {
                                connection = connections.get(packet.getPlayerUUID());
                            }
                            sendPacket(new AuthenticateAckPacket(), connection);
                        }
                    }

                    UUID playerUUID = message.getSender(Server.this);
                    if (playerUUID == null) {
                        continue;
                    }

                    ClientConnection conn = connections.get(playerUUID);

                    if (message.getPacket() instanceof MicPacket) {
                        MicPacket packet = (MicPacket) message.getPacket();
                        ServerPlayer player = server.getPlayerList().getPlayer(playerUUID);
                        if (player == null) {
                            continue;
                        }
                        PlayerState state = playerStateManager.getState(playerUUID);
                        if (state == null || !state.hasGroup()) {
                            processProximityPacket(player, packet);
                        } else {
                            processGroupPacket(state, packet);
                        }
                    } else if (message.getPacket() instanceof PingPacket) {
                        pingManager.onPongPacket((PingPacket) message.getPacket());
                    } else if (message.getPacket() instanceof KeepAlivePacket) {
                        conn.setLastKeepAliveResponse(System.currentTimeMillis());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        public void close() {
            running = false;
        }
    }

    private void processGroupPacket(PlayerState player, MicPacket packet) throws Exception {
        String group = player.getGroup();
        NetworkMessage soundMessage = new NetworkMessage(new SoundPacket(player.getGameProfile().getId(), packet.getData(), packet.getSequenceNumber()));
        for (PlayerState state : playerStateManager.getStates()) {
            if (!group.equals(state.getGroup())) {
                continue;
            }
            if (player.getGameProfile().getId().equals(state.getGameProfile().getId())) {
                continue;
            }
            ClientConnection connection = connections.get(state.getGameProfile().getId());
            if (connection != null) {
                connection.send(this, soundMessage);
            }
        }
    }

    private void processProximityPacket(Player player, MicPacket packet) throws Exception {
        double distance = Voicechat.SERVER_CONFIG.voiceChatDistance.get();
        List<ClientConnection> closeConnections = player.level
                .getEntitiesOfClass(
                        Player.class,
                        new AABB(
                                player.getX() - distance,
                                player.getY() - distance,
                                player.getZ() - distance,
                                player.getX() + distance,
                                player.getY() + distance,
                                player.getZ() + distance
                        )
                        , playerEntity -> !playerEntity.getUUID().equals(player.getUUID())
                )
                .stream()
                .map(playerEntity -> connections.get(playerEntity.getUUID()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        NetworkMessage soundMessage = new NetworkMessage(new SoundPacket(player.getUUID(), packet.getData(), packet.getSequenceNumber()));
        for (ClientConnection clientConnection : closeConnections) {
            if (!clientConnection.getPlayerUUID().equals(player.getUUID())) {
                clientConnection.send(this, soundMessage);
            }
        }
    }

    private void keepAlive() throws Exception {
        long timestamp = System.currentTimeMillis();
        KeepAlivePacket keepAlive = new KeepAlivePacket();
        List<UUID> connectionsToDrop = new ArrayList<>(connections.size());
        for (ClientConnection connection : connections.values()) {
            if (timestamp - connection.getLastKeepAliveResponse() >= Voicechat.SERVER_CONFIG.keepAlive.get() * 10L) {
                connectionsToDrop.add(connection.getPlayerUUID());
            } else if (timestamp - connection.getLastKeepAlive() >= Voicechat.SERVER_CONFIG.keepAlive.get()) {
                connection.setLastKeepAlive(timestamp);
                sendPacket(keepAlive, connection);
            }
        }
        for (UUID uuid : connectionsToDrop) {
            disconnectClient(uuid);
            Voicechat.LOGGER.info("Player {} timed out", uuid);
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                Voicechat.LOGGER.info("Reconnecting player {}", player.getDisplayName().getString());
                Voicechat.SERVER.initializePlayerConnection(player);
            } else {
                Voicechat.LOGGER.warn("Reconnecting player {} failed (Could not find player)", player.getDisplayName().getString());
            }
        }
    }

    public Map<UUID, ClientConnection> getConnections() {
        return connections;
    }

    public DatagramSocket getSocket() {
        return socket;
    }

    public void sendPacket(Packet<?> packet, ClientConnection connection) throws Exception {
        connection.send(this, new NetworkMessage(packet));
    }

    public PingManager getPingManager() {
        return pingManager;
    }

    public PlayerStateManager getPlayerStateManager() {
        return playerStateManager;
    }
}