package globalquake.client;

import globalquake.core.GlobalQuake;
import globalquake.core.exception.RuntimeApplicationException;
import gqserver.api.GQApi;
import gqserver.api.Packet;
import gqserver.api.data.system.ServerClientConfig;
import gqserver.api.packets.earthquake.ArchivedQuakesRequestPacket;
import gqserver.api.packets.earthquake.EarthquakesRequestPacket;
import gqserver.api.packets.station.StationsRequestPacket;
import gqserver.api.packets.system.HandshakePacket;
import gqserver.api.packets.system.HandshakeSuccessfulPacket;
import gqserver.api.packets.system.HeartbeatPacket;
import gqserver.api.packets.system.TerminationPacket;
import org.tinylog.Logger;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ClientSocket {

    private static final int CONNECT_TIMEOUT = 10 * 1000;
    private static final int SO_TIMEOUT = 60 * 1000;
    private ExecutorService inputService;
    private Socket socket;
    private ScheduledExecutorService heartbeatService;

    private ObjectInputStream inputStream;

    private ObjectOutputStream outputStream;
    private ScheduledExecutorService quakeCheckService;
    private ScheduledExecutorService reconnectService;
    private String ip;
    private int port;

    private ClientSocketStatus status = ClientSocketStatus.DISCONNECTED;

    public void connect(String ip, int port) throws IOException, ClassNotFoundException {
        this.ip = ip;
        this.port = port;
        status = ClientSocketStatus.CONNECTING;
        try {
            socket = new Socket();
            socket.setSoTimeout(SO_TIMEOUT);
            socket.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT);

            outputStream = new ObjectOutputStream(socket.getOutputStream());
            inputStream = new ObjectInputStream(socket.getInputStream());

            handshake();

            inputService = Executors.newSingleThreadExecutor();
            inputService.submit(this::runReader);
            heartbeatService = Executors.newSingleThreadScheduledExecutor();
            heartbeatService.scheduleAtFixedRate(this::sendHeartbeat, 0, 10, TimeUnit.SECONDS);

            quakeCheckService = Executors.newSingleThreadScheduledExecutor();
            quakeCheckService.scheduleAtFixedRate(this::sendQuakeRequest, 0, 20, TimeUnit.SECONDS);

            sendPacket(new ArchivedQuakesRequestPacket());
            sendPacket(new StationsRequestPacket());
            status = ClientSocketStatus.CONNECTED;
        }catch(Exception e){
            status = ClientSocketStatus.DISCONNECTED;
            throw e;
        }
    }

    public void runReconnectService(){
        reconnectService = Executors.newSingleThreadScheduledExecutor();
        reconnectService.scheduleAtFixedRate(this::checkReconnect, 0, 1, TimeUnit.SECONDS);
    }

    public void destroy(){
        if(reconnectService == null){
            return;
        }

        GlobalQuake.instance.stopService(reconnectService);
    }

    private void checkReconnect() {
        if(!socket.isConnected() || socket.isClosed()){
            try {
                connect(ip, port);
            } catch (Exception e) {
                Logger.error("Unable to reconnect: %s".formatted(e.getMessage()));
            }
        }
    }

    private void sendQuakeRequest() {
        try {
            sendPacket(new EarthquakesRequestPacket());
        } catch (IOException e) {
            Logger.error(e);
            onClose();
        }
    }

    private void sendHeartbeat() {
        try {
            sendPacket(new HeartbeatPacket());
        } catch (IOException e) {
            Logger.error(e);
            onClose();
        }
    }

    private void onClose() {
        status = ClientSocketStatus.DISCONNECTED;
        if(socket != null){
            try {
                socket.close();
            } catch (IOException e) {
                Logger.error(e);
            }
        }

        GlobalQuake.instance.stopService(heartbeatService);
        GlobalQuake.instance.stopService(inputService);
        GlobalQuake.instance.stopService(quakeCheckService);
    }

    public boolean isConnected(){
        return socket.isConnected() && !socket.isClosed();
    }

    private void runReader() {
        try {
            while (isConnected()) {
                Packet packet = (Packet) inputStream.readObject();
                ((GlobalQuakeClient) GlobalQuake.instance).processPacket(this, packet);
            }
        } catch (Exception e){
            Logger.error(e);
            onClose();
        }
    }

    public synchronized void sendPacket(Packet packet) throws IOException {
        outputStream.writeObject(packet);
    }

    private void handshake() throws IOException, ClassNotFoundException {
        sendPacket(new HandshakePacket(GQApi.COMPATIBILITY_VERSION, new ServerClientConfig(true, true)));
        Packet packet = (Packet) inputStream.readObject();
        if(!(packet instanceof HandshakeSuccessfulPacket)) {
            if(packet instanceof TerminationPacket terminationPacket){
                throw new RuntimeApplicationException(terminationPacket.cause());
            } else {
                throw new RuntimeApplicationException("Unknown");
            }
        }
    }

    public ClientSocketStatus getStatus() {
        return status;
    }
}
