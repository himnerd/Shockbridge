package com.himnerd.shockbridge.command;

import com.himnerd.shockbridge.ShockbridgePlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ShockbridgeCommand implements CommandExecutor, TabCompleter {

    private final ShockbridgePlugin plugin;

    public ShockbridgeCommand(ShockbridgePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("shockbridge.admin")) {
            sender.sendMessage(ChatColor.RED + "No permission.");
            return true;
        }

        if (args.length == 0) {
            sendStatus(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "enable" -> {
                if (plugin.isActive()) {
                    sender.sendMessage(ChatColor.YELLOW + "Shockbridge is already active.");
                } else if (plugin.activate()) {
                    sender.sendMessage(ChatColor.GREEN + "Shockbridge ALPHA activated.");
                } else {
                    sender.sendMessage(ChatColor.RED + "Activation failed — check console.");
                }
            }
            case "disable" -> {
                if (!plugin.isActive()) {
                    sender.sendMessage(ChatColor.YELLOW + "Shockbridge is not active.");
                } else if (plugin.deactivate()) {
                    sender.sendMessage(ChatColor.GREEN + "Shockbridge ALPHA deactivated.");
                } else {
                    sender.sendMessage(ChatColor.RED + "Deactivation failed.");
                }
            }
            case "reload" -> {
                plugin.getShockbridgeConfig().reload();
                sender.sendMessage(ChatColor.GREEN + "Configuration reloaded.");
            }
            case "debug" -> {
                boolean state = plugin.getDebugLogger().toggle();
                sender.sendMessage(ChatColor.AQUA + "Debug logging: " +
                        (state ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
            }
            case "status" -> sendStatus(sender);
            case "sessions" -> {
                if (plugin.getSessionManager() == null) {
                    sender.sendMessage(ChatColor.GRAY + "No active session manager.");
                } else {
                    sender.sendMessage(ChatColor.AQUA + "Active sessions: " +
                            ChatColor.WHITE + plugin.getSessionManager().getSessionCount());
                    sender.sendMessage(ChatColor.AQUA + "Tracked buffers: " +
                            ChatColor.WHITE + plugin.getMemoryTracker().getTrackedCount());
                }
            }
            case "link" -> handleLink(sender, args);
            case "unlink" -> handleUnlink(sender, args);
            case "accounts" -> handleAccountInfo(sender, args);
            default -> sender.sendMessage(ChatColor.RED +
                    "Usage: /shockbridge <enable|disable|reload|debug|status|sessions|link|unlink|accounts>");
        }
        return true;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.DARK_AQUA + "══ Shockbridge ALPHA ══");
        sender.sendMessage(ChatColor.AQUA + "Status: " +
                (plugin.isActive() ? ChatColor.GREEN + "ACTIVE" : ChatColor.RED + "INACTIVE"));
        sender.sendMessage(ChatColor.AQUA + "Debug: " +
                (plugin.getDebugLogger().isEnabled() ? ChatColor.GREEN + "ON" : ChatColor.GRAY + "OFF"));
        sender.sendMessage(ChatColor.AQUA + "MS Auth: " +
                (plugin.getShockbridgeConfig().isRequireMicrosoftAuth()
                        ? ChatColor.GREEN + "REQUIRED" : ChatColor.YELLOW + "OPTIONAL"));
        if (plugin.getSessionManager() != null) {
            sender.sendMessage(ChatColor.AQUA + "Sessions: " +
                    ChatColor.WHITE + plugin.getSessionManager().getSessionCount());
        }
        if (plugin.getViaBridge() != null) {
            sender.sendMessage(ChatColor.AQUA + "Via: " + ChatColor.WHITE + plugin.getViaBridge().getSummary());
        }
        if (plugin.getResourcePackBridge() != null) {
            sender.sendMessage(ChatColor.AQUA + "Resource Pack: " +
                    (plugin.getResourcePackBridge().isPackReady() ? ChatColor.GREEN + "READY" : ChatColor.YELLOW + "NOT READY"));
        }
        if (plugin.getJavaSessionInjector() != null) {
            sender.sendMessage(ChatColor.AQUA + "Injected Players: " +
                    ChatColor.WHITE + plugin.getJavaSessionInjector().getInjectedCount());
        }
        if (plugin.getAccountLinker() != null) {
            sender.sendMessage(ChatColor.AQUA + "Linked Accounts: " +
                    ChatColor.WHITE + plugin.getAccountLinker().getLinkCount());
        }
        if (plugin.getConcurrentLoginGuard() != null) {
            sender.sendMessage(ChatColor.AQUA + "Active Guards: " +
                    ChatColor.WHITE + plugin.getConcurrentLoginGuard().getActiveCount());
        }
        if (plugin.getAsyncChunkCache() != null) {
            sender.sendMessage(ChatColor.AQUA + "Chunk Cache: " +
                    ChatColor.WHITE + plugin.getAsyncChunkCache().getCachedChunkCount() + " chunks");
        }
        if (plugin.getDynamicRenderController() != null) {
            sender.sendMessage(ChatColor.AQUA + "Render Profiles: " +
                    ChatColor.WHITE + plugin.getDynamicRenderController().getProfileCount());
        }
        sender.sendMessage(ChatColor.AQUA + "Haptics: " +
                (plugin.getShockbridgeConfig().isHapticsEnabled()
                        ? ChatColor.GREEN + "ON" : ChatColor.GRAY + "OFF"));
    }

    private void handleLink(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /shockbridge link <xuid> <player>");
            return;
        }
        String xuid = args[1];
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found: " + args[2]);
            return;
        }
        if (plugin.getAccountLinker() == null) {
            sender.sendMessage(ChatColor.RED + "Account linker not initialized.");
            return;
        }
        plugin.getAccountLinker().link(xuid, target.getUniqueId(), target.getName());
        sender.sendMessage(ChatColor.GREEN + "Linked XUID " + xuid + " → " + target.getName());
    }

    private void handleUnlink(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /shockbridge unlink <xuid>");
            return;
        }
        if (plugin.getAccountLinker() == null) {
            sender.sendMessage(ChatColor.RED + "Account linker not initialized.");
            return;
        }
        plugin.getAccountLinker().unlink(args[1]);
        sender.sendMessage(ChatColor.GREEN + "Unlinked XUID " + args[1]);
    }

    private void handleAccountInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /shockbridge accounts <xuid>");
            return;
        }
        if (plugin.getAccountLinker() == null) {
            sender.sendMessage(ChatColor.RED + "Account linker not initialized.");
            return;
        }
        var linked = plugin.getAccountLinker().getByXuid(args[1]);
        if (linked == null) {
            sender.sendMessage(ChatColor.GRAY + "No linked account for XUID " + args[1]);
        } else {
            sender.sendMessage(ChatColor.AQUA + "XUID: " + ChatColor.WHITE + linked.getXuid());
            sender.sendMessage(ChatColor.AQUA + "Java UUID: " + ChatColor.WHITE + linked.getJavaUuid());
            sender.sendMessage(ChatColor.AQUA + "Java Name: " + ChatColor.WHITE + linked.getJavaName());
            sender.sendMessage(ChatColor.AQUA + "Linked: " + ChatColor.WHITE + linked.getLinkedAt());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("enable", "disable", "reload", "debug", "status", "sessions", "link", "unlink", "accounts");
        }
        return Collections.emptyList();
    }
}