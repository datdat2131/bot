package vn.treo;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Kết nối giả. Pipeline có đủ các handler tên giống kết nối thật
 * (timeout, splitter, decoder, prepender, encoder, packet_handler)
 * để ProtocolLib / PacketEvents (nLogin) / ViaVersion không bị lỗi khi móc vào.
 */
public class FakeConnection extends Connection {

    /** Handler "nuốt" mọi dữ liệu gửi đi, tránh tràn bộ nhớ. */
    private static class Sink extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ReferenceCountUtil.release(msg);
            promise.setSuccess();
        }
    }

    public FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        EmbeddedChannel ch = new EmbeddedChannel();
        ChannelPipeline p = ch.pipeline();
        p.addLast("timeout", new ChannelInboundHandlerAdapter());
        p.addLast("splitter", new ChannelInboundHandlerAdapter());
        p.addLast("decoder", new ChannelInboundHandlerAdapter());
        p.addLast("prepender", new Sink());
        p.addLast("encoder", new Sink());
        p.addLast("packet_handler", new ChannelInboundHandlerAdapter());
        this.channel = ch;
    }

    public void closeQuietly() {
        try {
            if (this.channel != null) this.channel.close();
        } catch (Throwable ignored) {
        }
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
