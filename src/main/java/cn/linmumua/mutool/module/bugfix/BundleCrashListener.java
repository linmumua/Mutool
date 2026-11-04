package cn.linmumua.mutool.module.bugfix;

import cn.linmumua.mutool.PacketEventsHook;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSelectBundleItem;

/**
 * PacketEvents listener that neutralises the 1.21+ "bundle" server-crash exploit by
 * cancelling malformed {@code SELECT_BUNDLE_ITEM} packets from clients.
 *
 * <p>Faithful port of {@code MumuTools} {@code module_bugfix} {@code BundleCrash}. The
 * packet only exists on 1.21.1+, so the check is skipped on servers at or below 1.21.
 * A bundle item index must be {@code >= -1} (with {@code -1} meaning "deselect"); a
 * client sending anything lower is exploiting the bug and the packet is cancelled.</p>
 */
public final class BundleCrashListener implements PacketListener {

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.SELECT_BUNDLE_ITEM) {
            return;
        }

        // The bundle packet is only meaningful on 1.21.1+; on <= 1.21 there is nothing to protect.
        ServerVersion version = PacketEventsHook.serverVersion();
        if (version != null && version.isOlderThanOrEquals(ServerVersion.V_1_21)) {
            return;
        }

        WrapperPlayClientSelectBundleItem wrapper = new WrapperPlayClientSelectBundleItem(event);
        if (wrapper.getSelectedItemIndex() >= -1) {
            return;
        }

        event.setCancelled(true);
    }
}
