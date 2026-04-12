package pvp.framework;

import pvp.framework.arena.ArenaManager;
import pvp.framework.command.PfCommand;
import pvp.framework.command.PvpfCommand;
import pvp.framework.config.ConfigManager;
import pvp.framework.game.GameLoader;
import pvp.framework.gui.LobbyMenuListener;
import pvp.framework.kit.KitManager;
import pvp.framework.mode.KillListener;
import pvp.framework.mode.ModeEngine;
import pvp.framework.script.ScriptEngine;
import pvp.framework.session.SessionManager;
import pvp.framework.session.StateValidator;
import pvp.framework.template.TemplateManager;
import pvp.framework.scoreboard.ScoreboardManager;
import pvp.framework.npc.FancyNpcManager;
import pvp.framework.npc.NpcInteractListener;
import org.bukkit.plugin.java.JavaPlugin;

public class PvPFramework extends JavaPlugin {

    private static PvPFramework instance;

    private ConfigManager configManager;
    private KitManager kitManager;
    private ArenaManager arenaManager;
    private GameLoader gameLoader;
    private ScriptEngine scriptEngine;
    private ModeEngine modeEngine;
    private SessionManager sessionManager;
    private TemplateManager templateManager;
    private ScoreboardManager scoreboardManager;
    private FancyNpcManager fancyNpcManager;
    private LobbyMenuListener lobbyMenuListener;

    @Override
    public void onEnable() {
        instance = this;

        configManager  = new ConfigManager(this);
        configManager.load();

        kitManager = new KitManager(this);
        kitManager.load();

        arenaManager = new ArenaManager(this);
        arenaManager.load();

        scriptEngine = new ScriptEngine(getLogger());
        modeEngine   = new ModeEngine(getLogger(), scriptEngine);

        templateManager = new TemplateManager(this);
        templateManager.setup();

        gameLoader = new GameLoader(this);
        gameLoader.load();

        sessionManager = new SessionManager(this);
        scoreboardManager = new ScoreboardManager();
        fancyNpcManager = new FancyNpcManager(this);

        getServer().getPluginManager().registerEvents(new StateValidator(this), this);
        getServer().getPluginManager().registerEvents(new KillListener(this), this);

        // [FIX] インスタンスを保持してから登録する
        lobbyMenuListener = new LobbyMenuListener(this);
        getServer().getPluginManager().registerEvents(lobbyMenuListener, this);

        getServer().getPluginManager().registerEvents(new NpcInteractListener(this), this);

        PvpfCommand pvpfCmd = new PvpfCommand(this);
        getCommand("pvpf").setExecutor(pvpfCmd);
        getCommand("pvpf").setTabCompleter(pvpfCmd);

        PfCommand pfCmd = new PfCommand(this);
        getCommand("pf").setExecutor(pfCmd);
        getCommand("pf").setTabCompleter(pfCmd);

        getLogger().info("PvPFramework has been enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("PvPFramework has been disabled!");
    }

    public static PvPFramework getInstance() { return instance; }

    public ConfigManager     getConfigManager()     { return configManager; }
    public KitManager        getKitManager()        { return kitManager; }
    public ArenaManager      getArenaManager()      { return arenaManager; }
    public GameLoader        getGameLoader()        { return gameLoader; }
    public ScriptEngine      getScriptEngine()      { return scriptEngine; }
    public ModeEngine        getModeEngine()        { return modeEngine; }
    public SessionManager    getSessionManager()    { return sessionManager; }
    public TemplateManager   getTemplateManager()   { return templateManager; }
    public ScoreboardManager getScoreboardManager() { return scoreboardManager; }
    public FancyNpcManager   getFancyNpcManager()   { return fancyNpcManager; }
    public LobbyMenuListener getLobbyMenuListener() { return lobbyMenuListener; }
}
