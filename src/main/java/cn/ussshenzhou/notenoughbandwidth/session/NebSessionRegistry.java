package cn.ussshenzhou.notenoughbandwidth.session;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.NetworkManager;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;

/**
 * Finds the {@link NebSession} for a channel and keeps the set of live ones for reporting.
 * <p>
 * The registry is only consulted from the message handlers and from the report command, never per
 * frame: the pipeline handlers hold their session directly. Entries are removed by the channel's
 * own close future, which also releases the native codecs, so a dropped connection cannot leak.
 *
 * @author USS_Shenzhou
 */
public class NebSessionRegistry {

    private static final Map<Channel, NebSession> SESSIONS = new ConcurrentHashMap<>();

    /**
     * Attaches a session to the channel, or returns the one already attached.
     */
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
            .addListener(new ChannelFutureListener() {

                @Override
                public void operationComplete(ChannelFuture future) {
                    SESSIONS.remove(channel);
                    session.release();
                }
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
