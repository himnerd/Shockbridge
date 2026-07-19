package com.himnerd.shockbridge.api.event;

import com.himnerd.shockbridge.api.BedrockPlayer;
import lombok.Getter;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired on the main thread after a Bedrock client passes Xbox Live authentication
 * and before injection into Paper. Cancelling this event disconnects the client.
 */
public class BedrockPlayerAuthenticatedEvent extends Event implements Cancellable {

    private static final HandlerList HANDLER_LIST = new HandlerList();

    @Getter private final BedrockPlayer bedrockPlayer;
    private boolean cancelled;

    public BedrockPlayerAuthenticatedEvent(BedrockPlayer bedrockPlayer) {
        super(false);
        this.bedrockPlayer = bedrockPlayer;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLER_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLER_LIST;
    }
}