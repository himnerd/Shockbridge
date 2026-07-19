package com.himnerd.shockbridge.api.event;

import lombok.Getter;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired when a Bedrock XUID is linked to or unlinked from a Java account.
 * Listeners can use this to sync external databases, update scoreboards, etc.
 */
public class BedrockAccountLinkEvent extends Event {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    @Getter private final String xuid;
    @Getter private final UUID javaUuid;
    @Getter private final String javaName;
    @Getter private final boolean unlink;

    public BedrockAccountLinkEvent(String xuid, UUID javaUuid, String javaName, boolean unlink) {
        super(false);
        this.xuid = xuid;
        this.javaUuid = javaUuid;
        this.javaName = javaName;
        this.unlink = unlink;
    }

    /**
     * @return true if this is a link event, false if it's an unlink event
     */
    public boolean isLink() {
        return !unlink;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}