package vn.treo;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/** Kết nối giả: không có client thật, mọi packet gửi đi đều bị bỏ. */
public class FakeConnection extends Connection {

    public FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        this.channel = new EmbeddedChannel();
    }

    @Override public void send(Packet<?> packet) {}
    @Override public void send(Packet<?> packet, PacketSendListener listener) {}
    @Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {}
    @Override public void setReadOnly() {}
    @Override public void handleDisconnection() {}
    @Override public void setListenerForServerboundHandshake(PacketListener listener) {}
    @Override public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> info, T listener) {}

    @Override
    public SocketAddress getRemoteAddress() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
    }
}
