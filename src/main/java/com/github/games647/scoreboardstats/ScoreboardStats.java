package com.github.games647.scoreboardstats;

import com.avaje.ebean.EbeanServer;
import com.avaje.ebean.EbeanServerFactory;
import com.avaje.ebean.config.DataSourceConfig;
import com.avaje.ebean.config.ServerConfig;
import com.avaje.ebeaninternal.api.SpiEbeanServer;
import com.avaje.ebeaninternal.server.ddl.DdlGenerator;
import com.avaje.ebeaninternal.server.lib.sql.TransactionIsolation;

import com.github.games647.scoreboardstats.commands.DisableCommand;
import com.github.games647.scoreboardstats.commands.ReloadCommand;
import com.github.games647.scoreboardstats.commands.SidebarCommand;
import com.github.games647.scoreboardstats.listener.EntityListener;
import com.github.games647.scoreboardstats.listener.PlayerListener;
import com.github.games647.scoreboardstats.listener.PluginListener;
import com.github.games647.scoreboardstats.pvpstats.Database;
import com.github.games647.scoreboardstats.pvpstats.PlayerStats;
import com.github.games647.scoreboardstats.pvpstats.SaveTask;
import com.github.games647.scoreboardstats.scoreboard.ReflectionUtil;
import com.github.games647.scoreboardstats.scoreboard.SbManager;
import com.github.games647.variables.Commands;
import com.github.games647.variables.Message;
import com.github.games647.variables.Other;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Pattern;

import javax.persistence.PersistenceException;

import lombok.Getter;
import org.bukkit.Server;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import org.fusesource.jansi.Ansi;

public final class ScoreboardStats extends JavaPlugin {

    @Getter private static  ScoreboardStats instance;

    @Getter private final   Set<String>     hidelist = new HashSet<String>(10);

    private         int             refreshTask;
    private         int             saveTask;

    public ScoreboardStats() {
        super();
        instance = this;
    }

    @Override
    public void onEnable() {
        super.onEnable();

        Settings.loadConfig();

        if (Settings.isUpdateInfo()) {
            new Updater(this, "scoreboardstats", getFile(), Updater.UpdateType.DEFAULT, true);
        }

        setupDatabase();

        PluginListener.init();

        final Server server = getServer();

        server.getPluginManager().registerEvents(new PlayerListener(), this);
        server.getPluginManager().registerEvents(new EntityListener(), this);

        getCommand(Commands.RELOAD_COMMAND) .setExecutor(new ReloadCommand());
        getCommand(Commands.HIDE_COMMAND)   .setExecutor(new DisableCommand());
        getCommand(Commands.SIDEBAR)        .setExecutor(new SidebarCommand());

        SbManager.regAll();

        refreshTask = server.getScheduler().scheduleSyncRepeatingTask(this,
                new RefreshTask(),
                Other.STARTUP_DELAY,
                Settings.getIntervall() * Other.TICKS_PER_SECOND);
        if (Settings.isPvpStats()) {
            saveTask = server.getScheduler().scheduleAsyncRepeatingTask(this
                , new SaveTask()
                , Other.STARTUP_DELAY
                , Settings.getSaveIntervall() * Other.TICKS_PER_SECOND * 60);
        }
    }

    @Override
    public List<Class<?>> getDatabaseClasses() {
        final List<Class<?>> list = new ArrayList<Class<?>>(1);
        list.add(PlayerStats.class);

        return list;
    }

    @Override
    public void onLoad() {
        super.onLoad();

        ReflectionUtil.initReflections();
    }

    public void onReload() {
        final int     intervall     = Settings.getIntervall();
        final int     length        = Settings.getItemsLenght();
        final boolean pvpstats      = Settings.isPvpStats();

        Settings.loadConfig();

        if (intervall != Settings.getIntervall()) {
            getServer().getScheduler().cancelTask(refreshTask);
            getServer().getScheduler().scheduleSyncRepeatingTask(this,
                    new RefreshTask(), Other.STARTUP_DELAY,
                    Settings.getIntervall() * Other.TICKS_PER_SECOND);
        }

        if (length != Settings.getItemsLenght()) {
            SbManager.unregisterAll();
        }

        if (pvpstats != Settings.isPvpStats()) {
            instance.setupDatabase();
            SbManager.regAll();
        }
    }

    @Override
    public void onDisable() {
        super.onDisable();

        getServer().getScheduler().cancelTasks(this);
        Database.saveAll(true);
        SbManager.unregisterAll();
        HandlerList.unregisterAll(this);
    }

    private void setupDatabase() {
        if (Settings.isPvpStats()) {
            final ServerConfig db = new ServerConfig();
            db.setRegister(false);
            db.setClasses(getDatabaseClasses());
            db.setName(getName());

            final DataSourceConfig ds   = getSqlConfig(db);
            ds.setUrl(replaceUrlString(ds.getUrl()));

            final ClassLoader previous  = Thread.currentThread().getContextClassLoader();

            Thread.currentThread().setContextClassLoader(getClassLoader());
            final EbeanServer database  = EbeanServerFactory.create(db);
            Thread.currentThread().setContextClassLoader(previous);

            try {
                database.find(PlayerStats.class).findRowCount();
            } catch (PersistenceException ex) {
                getLogger().log(Level.INFO, "{0}" + Message.NON_EXISTING_DATABASE + Ansi.ansi().fg(Ansi.Color.DEFAULT), Ansi.ansi().fg(Ansi.Color.YELLOW));
                final DdlGenerator gen = ((SpiEbeanServer) database).getDdlGenerator();
                gen.runScript(false, gen.generateCreateDdl());
            }

            Database.setDatabaseInstance(database);
        }
    }

    private String replaceUrlString(String input) {
        final Pattern pat = Pattern.compile("\\\\");
        return input
                .replace("{DIR}", pat.matcher(getDataFolder().getPath()).replaceAll("/") + '/')
                .replace("{NAME}", getName());
    }

    private DataSourceConfig getSqlConfig(ServerConfig sconfig) {
        final File file = new File(getDataFolder(), "sql.yml");
        final FileConfiguration sqlConfig;
        final DataSourceConfig config;

        if (file.exists()) {
            sqlConfig   = YamlConfiguration.loadConfiguration(file);
            config      = new DataSourceConfig();

            config.setUsername(sqlConfig.getString("SQL-Settings.Username"));
            config.setPassword(sqlConfig.getString("SQL-Settings.Password"));
            config.setIsolationLevel(TransactionIsolation.getLevel(sqlConfig.getString("SQL-Settings.Isolation")));
            config.setDriver(sqlConfig.getString("SQL-Settings.Driver"));
            config.setUrl(sqlConfig.getString("SQL-Settings.Url"));

            sconfig.setDataSourceConfig(config);
        } else {
            saveResource("sql.yml", false);

            getServer().configureDbConfig(sconfig);
            config = sconfig.getDataSourceConfig();

            sqlConfig = YamlConfiguration.loadConfiguration(file);
            sqlConfig.set("SQL-Settings.Username", config.getUsername());
            sqlConfig.set("SQL-Settings.Password", config.getPassword());
            sqlConfig.set("SQL-Settings.Isolation", TransactionIsolation.getLevelDescription(config.getIsolationLevel()));
            sqlConfig.set("SQL-Settings.Driver", config.getDriver());
            sqlConfig.set("SQL-Settings.Url", config.getUrl());
            try {
                sqlConfig.save(file);
            } catch (IOException ex) {
                getLogger().log(Level.WARNING, "{0}" + Message.FILE_EXCEPTION + Ansi.ansi().fg(Ansi.Color.DEFAULT), Ansi.ansi().fg(Ansi.Color.RED));
                getLogger().throwing(getClass().getName(), "getSqlConfig", ex);
            }
        }

        config.setMinConnections(sqlConfig.getInt("SQL-Settings.MinConnections"));
        config.setMaxConnections(sqlConfig.getInt("SQL-Settings.MaxConnections"));
        config.setWaitTimeoutMillis(sqlConfig.getInt("SQL-Settings.Timeout"));

        return config;
    }
}
