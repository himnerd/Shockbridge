package com.himnerd.shockbridge;

import com.himnerd.shockbridge.command.ShockbridgeCommand;
import com.himnerd.shockbridge.chunk.AsyncChunkCache;
import com.himnerd.shockbridge.debug.AlphaDebugLogger;
import com.himnerd.shockbridge.haptics.HapticsListener;
import com.himnerd.shockbridge.identity.MicrosoftAuthValidator;
import com.himnerd.shockbridge.injection.JavaSessionInjector;
import com.himnerd.shockbridge.integration.ViaVersionBridge;
import com.himnerd.shockbridge.linking.AccountLinker;
import com.himnerd.shockbridge.linking.ConcurrentLoginGuard;
import com.himnerd.shockbridge.linking.StateSynchronizer;
import com.himnerd.shockbridge.network.PacketQueue;
import com.himnerd.shockbridge.network.RakNetListener;
import com.himnerd.shockbridge.network.SessionManager;
import com.himnerd.shockbridge.render.DynamicRenderController;
import com.himnerd.shockbridge.resourcepack.ResourcePackBridge;
import com.himnerd.shockbridge.safety.MemoryTracker;
import com.himnerd.shockbridge.translation.BlockPalette;
import com.himnerd.shockbridge.translation.ItemPalette;
import com.himnerd.shockbridge.translation.ShockbridgeMappingRegistry;
import com.himnerd.shockbridge.api.ShockbridgeAPIImpl;
import com.himnerd.shockbridge.api.ShockbridgeProvider;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

@Getter
public class ShockbridgePlugin extends JavaPlugin {

    private ShockbridgeConfig shockbridgeConfig;
    private AlphaDebugLogger debugLogger;
    private MemoryTracker memoryTracker;
    private SessionManager sessionManager;
    private RakNetListener rakNetListener;
    private PacketQueue packetQueue;
    private BlockPalette blockPalette;
    private ItemPalette itemPalette;
    private BukkitTask tickTask;
    private ResourcePackBridge resourcePackBridge;
    private ViaVersionBridge viaBridge;
    private JavaSessionInjector javaSessionInjector;
    private MicrosoftAuthValidator authValidator;
    private AccountLinker accountLinker;
    private ConcurrentLoginGuard concurrentLoginGuard;
    private StateSynchronizer stateSynchronizer;
    private AsyncChunkCache asyncChunkCache;
    private DynamicRenderController dynamicRenderController;
    private HapticsListener hapticsListener;
    private ShockbridgeMappingRegistry mappingRegistry;
    private volatile boolean active;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.shockbridgeConfig = new ShockbridgeConfig(this);
        this.debugLogger = new AlphaDebugLogger(this);
        this.memoryTracker = new MemoryTracker(debugLogger);
        this.authValidator = new MicrosoftAuthValidator(debugLogger);
        this.accountLinker = new AccountLinker(this);

        ShockbridgeProvider.register(new ShockbridgeAPIImpl(this));

        ShockbridgeCommand cmdHandler = new ShockbridgeCommand(this);
        getCommand("shockbridge").setExecutor(cmdHandler);
        getCommand("shockbridge").setTabCompleter(cmdHandler);

        if (shockbridgeConfig.isAlphaEnabled()) {
            activate();
        } else {
            getLogger().info("Shockbridge ALPHA disabled — set 'shockbridge.alpha-enabled: true' to activate.");
        }
    }

    /**
     * Hot-swap activation. Compiles palettes, opens the UDP listener,
     * and starts the main-thread tick drain. Safe to call at runtime.
     */
    public boolean activate() {
        if (active) return false;

        viaBridge = new ViaVersionBridge(this);
        viaBridge.detect();

        blockPalette = new BlockPalette(debugLogger);
        itemPalette = new ItemPalette(debugLogger);
        blockPalette.compile();
        itemPalette.compile();

        mappingRegistry = new ShockbridgeMappingRegistry(debugLogger);

        resourcePackBridge = new ResourcePackBridge(this);
        resourcePackBridge.initialize();

        packetQueue = new PacketQueue();
        sessionManager = new SessionManager(this);
        javaSessionInjector = new JavaSessionInjector(this);

        concurrentLoginGuard = new ConcurrentLoginGuard(this);
        Bukkit.getPluginManager().registerEvents(concurrentLoginGuard, this);

        stateSynchronizer = new StateSynchronizer(this);
        asyncChunkCache = new AsyncChunkCache(this);
        dynamicRenderController = new DynamicRenderController(this);

        hapticsListener = new HapticsListener(this);
        Bukkit.getPluginManager().registerEvents(hapticsListener, this);

        rakNetListener = new RakNetListener(this);
        try {
            rakNetListener.bind(shockbridgeConfig.getBindAddress(), shockbridgeConfig.getBindPort());
        } catch (Exception e) {
            getLogger().severe("Failed to bind RakNet listener: " + e.getMessage());
            return false;
        }

        tickTask = Bukkit.getScheduler().runTaskTimer(this, this::processTick, 1L, 1L);
        active = true;

        String addr = shockbridgeConfig.getBindAddress();
        int port = shockbridgeConfig.getBindPort();
        getLogger().info("");
        getLogger().info("§b⚡ §3━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ §b⚡");
        getLogger().info("§b⚡  §e§l★ §a§lThe Shockbridge Is Now Active! §e§l★  §b⚡");
        getLogger().info("§b⚡  §f§lListening on §b§l" + addr + ":" + port + "       §b⚡");
        getLogger().info("§b⚡  §7Bedrock players can join now!           §b⚡");
        getLogger().info("§b⚡ §3━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ §b⚡");
        getLogger().info("");
        return true;
    }

    /**
     * Hot-swap deactivation. Disconnects all sessions, unbinds UDP,
     * releases tracked buffers, and clears all references.
     */
    public boolean deactivate() {
        if (!active) return false;

        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (sessionManager != null) {
            sessionManager.disconnectAll("Shockbridge deactivated");
        }
        if (javaSessionInjector != null) {
            javaSessionInjector.shutdown();
            javaSessionInjector = null;
        }
        if (asyncChunkCache != null) {
            asyncChunkCache.shutdown();
            asyncChunkCache = null;
        }
        if (dynamicRenderController != null) {
            dynamicRenderController.shutdown();
            dynamicRenderController = null;
        }
        if (rakNetListener != null) {
            rakNetListener.shutdown();
            rakNetListener = null;
        }
        if (memoryTracker != null) {
            memoryTracker.releaseAll();
        }

        packetQueue = null;
        sessionManager = null;
        blockPalette = null;
        itemPalette = null;
        mappingRegistry = null;
        resourcePackBridge = null;
        concurrentLoginGuard = null;
        stateSynchronizer = null;
        hapticsListener = null;
        active = false;

        getLogger().info("Shockbridge ALPHA deactivated.");
        return true;
    }

    private void processTick() {
        if (packetQueue == null || sessionManager == null) return;
        packetQueue.drain(sessionManager::processTranslatedPacket, 512);
    }

    @Override
    public void onDisable() {
        deactivate();
        ShockbridgeProvider.unregister();
    }
}