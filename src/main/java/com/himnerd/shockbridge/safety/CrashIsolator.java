package com.himnerd.shockbridge.safety;

import com.himnerd.shockbridge.ShockbridgePlugin;
import com.himnerd.shockbridge.network.VirtualSession;

import java.net.InetSocketAddress;

/**
 * Wraps packet-handling work in a supervisor boundary.
 * On unhandled exception: terminates the offending session, logs a debug dump,
 * and prevents the exception from reaching the server thread or Netty pipeline.
 */
public final class CrashIsolator {

    private CrashIsolator() {}

    public static void execute(ShockbridgePlugin plugin, InetSocketAddress sender, Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            plugin.getDebugLogger().logException("Pipeline[" + sender + "]", e);
            if (plugin.getSessionManager() != null) {
                VirtualSession session = plugin.getSessionManager().getSession(sender);
                if (session != null) {
                    try {
                        session.disconnect("Internal error: " + e.getClass().getSimpleName());
                    } catch (Exception inner) {
                        plugin.getLogger().severe("[CrashIsolator] Failed to disconnect session: " + inner.getMessage());
                    }
                }
            }
        }
    }
}