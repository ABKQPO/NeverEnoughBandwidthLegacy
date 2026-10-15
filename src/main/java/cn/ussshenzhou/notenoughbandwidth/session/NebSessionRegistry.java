package cn.ussshenzhou.notenoughbandwidth.session;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.NetworkManager;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;

public class NebSessionRegistry {

    private static final Map<Channel, NebSession> SESSIONS = new ConcurrentHashMap<>();

    public static NebSession open(Channel channel) {
        NebSession existing = SESSIONS.get(channel);
        if (existing != null) {
            return existing;
        }
        NebSession session = new NebSession(channel);
        NebSession raced = SESSIONS.putIfAbsent(channel, session);
        if (raced != null) {
            return raced;
        }
        channel.closeFuture()
            .addListener((ChannelFutureListener) future -> {
                SESSIONS.remove(channel);
                session.release();
            });
        return session;
    }

    public static NebSession of(Channel channel) {
        return channel == null ? null : SESSIONS.get(channel);
    }

    public static NebSession of(NetworkManager manager) {
        return manager == null ? null : of(manager.channel());
    }

    public static Collection<NebSession> live() {
        return Collections.unmodifiableCollection(SESSIONS.values());
    }

    private NebSessionRegistry() {}
}
