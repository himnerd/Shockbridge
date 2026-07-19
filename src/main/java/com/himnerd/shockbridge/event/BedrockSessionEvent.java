package com.himnerd.shockbridge.event;

import lombok.Getter;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired on the main thread when a Bedrock client joins or quits through Shockbridge.
 * Other plugins can listen for cross-play session transitions.
 */
public class BedrockSessionEvent extends Event {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    public enum Type { JOIN, QUIT }

    @Getter private final UUID sessionId;
    @Getter private final String xboxGamertag;
    @Getter private final Type type;

    public BedrockSessionEvent(UUID sessionId, String xboxGamertag, Type type) {
        super(false);
        this.sessionId = sessionId;
        this.xboxGamertag = xboxGamertag;
        this.type = type;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}