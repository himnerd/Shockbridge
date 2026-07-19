package com.himnerd.shockbridge.api;

import com.himnerd.shockbridge.ShockbridgePlugin;
import com.himnerd.shockbridge.linking.AccountLinker;
import com.himnerd.shockbridge.linking.LinkedAccount;
import com.himnerd.shockbridge.network.SessionManager;
import com.himnerd.shockbridge.network.VirtualSession;
import com.himnerd.shockbridge.translation.BlockPalette;
import com.himnerd.shockbridge.translation.ItemPalette;
import com.himnerd.shockbridge.translation.ShockbridgeMappingRegistry;
import com.himnerd.shockbridge.translation.ShockbridgeMappingRegistry.BedrockItemMapping;
import com.himnerd.shockbridge.translation.ShockbridgeMappingRegistry.JavaItemKey;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class ShockbridgeAPIImpl implements ShockbridgeAPI {

    private final ShockbridgePlugin plugin;

    public ShockbridgeAPIImpl(ShockbridgePlugin plugin) {
        this.plugin = plugin;
    }

    // ── Bridge Status ───────────────────────────────────────

    @Override
    public boolean isActive() {
        return plugin.isActive();
    }

    @Override
    public int getBedrockSessionCount() {
        SessionManager sm = plugin.getSessionManager();
        return sm != null ? sm.getSessionCount() : 0;
    }

    // ── Player Queries ──────────────────────────────────────

    @Override
    public boolean isBedrockPlayer(Player player) {
        if (player == null) return false;
        SessionManager sm = plugin.getSessionManager();
        if (sm == null) return false;
        for (VirtualSession session : sm.getAllSessions().values()) {
            if (session.isConnected() && session.getLinkedAccount() != null
                    && player.getUniqueId().equals(session.getLinkedAccount().getJavaUuid())) {
                return true;
            }
            if (session.isConnected() && player.getName().equals(session.getXboxGamertag())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isBedrockSession(UUID sessionId) {
        SessionManager sm = plugin.getSessionManager();
        if (sm == null) return false;
        VirtualSession session = sm.getSessionById(sessionId);
        return session != null && session.isConnected();
    }

    @Override
    public Optional<BedrockPlayer> getBedrockPlayer(Player player) {
        if (player == null) return Optional.empty();
        SessionManager sm = plugin.getSessionManager();
        if (sm == null) return Optional.empty();
        for (VirtualSession session : sm.getAllSessions().values()) {
            if (!session.isConnected()) continue;
            boolean match = false;
            if (session.getLinkedAccount() != null
                    && player.getUniqueId().equals(session.getLinkedAccount().getJavaUuid())) {
                match = true;
            } else if (player.getName().equals(session.getXboxGamertag())) {
                match = true;
            }
            if (match) return Optional.of(toSnapshot(session));
        }
        return Optional.empty();
    }

    @Override
    public Optional<BedrockPlayer> getBedrockPlayer(UUID sessionId) {
        SessionManager sm = plugin.getSessionManager();
        if (sm == null) return Optional.empty();
        VirtualSession session = sm.getSessionById(sessionId);
        if (session == null || !session.isConnected()) return Optional.empty();
        return Optional.of(toSnapshot(session));
    }

    @Override
    public Collection<BedrockPlayer> getOnlineBedrockPlayers() {
        SessionManager sm = plugin.getSessionManager();
        if (sm == null) return Collections.emptyList();
        return sm.getAllSessions().values().stream()
                .filter(VirtualSession::isConnected)
                .map(this::toSnapshot)
                .collect(Collectors.toUnmodifiableList());
    }

    // ── Account Linking ─────────────────────────────────────

    @Override
    public void linkAccount(String xuid, UUID javaUuid, String javaName) {
        plugin.getAccountLinker().link(xuid, javaUuid, javaName);
    }

    @Override
    public void unlinkAccount(String xuid) {
        plugin.getAccountLinker().unlink(xuid);
    }

    @Override
    public Optional<UUID> getLinkedJavaUuid(String xuid) {
        LinkedAccount link = plugin.getAccountLinker().getByXuid(xuid);
        return link != null ? Optional.of(link.getJavaUuid()) : Optional.empty();
    }

    @Override
    public Optional<String> getLinkedXuid(UUID javaUuid) {
        LinkedAccount link = plugin.getAccountLinker().getByJavaUuid(javaUuid);
        return link != null ? Optional.of(link.getXuid()) : Optional.empty();
    }

    @Override
    public boolean isAccountLinked(UUID javaUuid) {
        return plugin.getAccountLinker().isJavaUuidLinked(javaUuid);
    }

    @Override
    public int getLinkedAccountCount() {
        return plugin.getAccountLinker().getLinkCount();
    }

    // ── ID Conversion ───────────────────────────────────────

    @Override
    public int convertBlockToJava(int bedrockBlockId) {
        BlockPalette bp = plugin.getBlockPalette();
        return bp != null ? bp.toJava(bedrockBlockId) : 0;
    }

    @Override
    public int convertBlockToBedrock(int javaBlockId) {
        BlockPalette bp = plugin.getBlockPalette();
        return bp != null ? bp.toBedrock(javaBlockId) : 0;
    }

    @Override
    public int convertItemToJava(int bedrockItemId) {
        ItemPalette ip = plugin.getItemPalette();
        return ip != null ? ip.toJava(bedrockItemId) : 0;
    }

    @Override
    public int convertItemToBedrock(int javaItemId) {
        ItemPalette ip = plugin.getItemPalette();
        return ip != null ? ip.toBedrock(javaItemId) : 0;
    }

    // 
    // 
    //  Custom Item Translation
    // 
    // 

    @Override
    public Optional<String> translateCustomItemToBedrock(String material, int customModelData) {
        ShockbridgeMappingRegistry reg = plugin.getMappingRegistry();
        if (reg == null) return Optional.empty();
        return reg.translateToBedrock(material, customModelData)
                .map(BedrockItemMapping::bedrockIdentifier);
    }

    @Override
    public Optional<String> translateCustomItemToBedrock(String itemModelId) {
        ShockbridgeMappingRegistry reg = plugin.getMappingRegistry();
        if (reg == null) return Optional.empty();
        return reg.translateToBedrockByModel(itemModelId)
                .map(BedrockItemMapping::bedrockIdentifier);
    }

    @Override
    public Optional<String> translateCustomItemToJava(String bedrockIdentifier) {
        ShockbridgeMappingRegistry reg = plugin.getMappingRegistry();
        if (reg == null) return Optional.empty();
        return reg.translateToJava(bedrockIdentifier)
                .map(key -> key.material() + ":" + key.customModelData());
    }

    @Override
    public int getCustomItemMappingCount() {
        ShockbridgeMappingRegistry reg = plugin.getMappingRegistry();
        return reg != null ? reg.getMappingCount() : 0;
    }

    // ── Internal ────────────────────────────────────────────

    private BedrockPlayer toSnapshot(VirtualSession session) {
        LinkedAccount link = session.getLinkedAccount();
        return BedrockPlayer.builder()
                .sessionId(session.getSessionId())
                .gamertag(session.getXboxGamertag())
                .xuid(session.getXuid())
                .protocolVersion(session.getBedrockProtocol())
                .authenticated(session.getAuthResult() != null && session.getAuthResult().isAuthenticated())
                .linkedJavaUuid(link != null ? link.getJavaUuid() : null)
                .linkedJavaName(link != null ? link.getJavaName() : null)
                .connected(session.isConnected())
                .build();
    }
}