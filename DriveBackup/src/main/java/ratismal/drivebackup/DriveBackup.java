package ratismal.drivebackup;

import org.bstats.bukkit.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.json.JSONArray;

import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import ratismal.drivebackup.config.Config;
import ratismal.drivebackup.handler.CommandHandler;
import ratismal.drivebackup.handler.CommandTabComplete;
import ratismal.drivebackup.handler.PlayerListener;
import ratismal.drivebackup.util.MessageUtil;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

public class DriveBackup extends JavaPlugin {

    private static String newVersionTitle = "";
    private static double newVersion = 0;
    private static double currentVersion = 0;
    private static String currentVersionTitle = "";

    private static Config pluginconfig;
    private static DriveBackup plugin;
    public Logger log = getLogger();

    /**
     * Global instance of the HTTP client
     */
    private static final OkHttpClient httpClient = new OkHttpClient();

    /**
     * Global instance of Adventure audience
     */
    public static BukkitAudiences adventure;

    /**
     * List of the IDs of the scheduled backup tasks
     */
    private static ArrayList<Integer> backupTasks = new ArrayList<>();

    /**
     * List of Dates representing each time a scheduled backup will occur
     */
    private static ArrayList<ZonedDateTime> backupDatesList = new ArrayList<>();

    /**
     * What to do when plugin is enabled (init)
     */
    public void onEnable() {
        saveDefaultConfig();

        reloadConfig();

        pluginconfig = new Config(getConfig());
        pluginconfig.reload();
        // reloadLocalConfig();
        getCommand("drivebackup").setTabCompleter(new CommandTabComplete(this));
        getCommand("drivebackup").setExecutor(new CommandHandler(this));
        plugin = this;
        DriveBackup.adventure = BukkitAudiences.create(this);

        PluginManager pm = this.getServer().getPluginManager();
        pm.registerEvents(new PlayerListener(), this);

        currentVersionTitle = getDescription().getVersion().split("-")[0];
        currentVersion = Double.valueOf(currentVersionTitle.replaceFirst("\\.", ""));

        if (Config.isMetrics()) {
            try {
                initMetrics();
                MessageUtil.sendConsoleMessage("Metrics started");
            } catch (IOException e) {
                MessageUtil.sendConsoleMessage("Metrics failed to start");
            }
        }

        startThread();

        /**
         * Starts update checker
         */
        this.getServer().getScheduler().runTask(this, new Runnable() {

            @Override
            public void run() {
                getServer().getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {

                    @Override
                    public void run() {
                        if (Config.isUpdateCheck()) {
                            try {
                                MessageUtil.sendConsoleMessage("Checking for updates...");
                                newVersion = updateCheck(currentVersion);
                                if (newVersion > currentVersion) {
                                    MessageUtil.sendConsoleMessage("Version " + newVersionTitle + " has been released." + " You are currently running version " + currentVersionTitle);
                                    MessageUtil.sendConsoleMessage("Update at: http://dev.bukkit.org/bukkit-plugins/drivebackupv2/");
                                } else if (currentVersion > newVersion) {
                                    MessageUtil.sendConsoleMessage("You are running an unsupported release!");
                                    MessageUtil.sendConsoleMessage("The recommended release is " + newVersionTitle + ", and you are running " + currentVersionTitle);
                                    MessageUtil.sendConsoleMessage("If the plugin has just recently updated, please ignore this message");
                                } else {
                                    MessageUtil.sendConsoleMessage("Hooray! You are running the latest release!");
                                }
                            } catch (Exception e) {
                                // ignore exceptions
                            }
                        }
                    }
                }, 0, 430000);

            }

        });

    }

    public void initMetrics() throws IOException {
        Metrics metrics = new Metrics(this, 7537);

        metrics.addCustomChart(new Metrics.SimplePie("automaticBackupType", new Callable<String>() {
            @Override
            public String call() throws Exception {
            	if (Config.isBackupsScheduled()) {
                    return "Schedule Based";
                } else if (Config.getBackupDelay() != -1) {
                    return "Interval Based";
                } else {
                    return "Not Enabled";
                }
            }
        }));

        metrics.addCustomChart(new Metrics.SimplePie("backupMethodEnabled", new Callable<String>() {
            @Override
            public String call() throws Exception {
                return (Config.isGoogleDriveEnabled() || Config.isOneDriveEnabled() || Config.isFtpEnabled()) ? "Enabled" : "Disabled";
            }
        }));

        metrics.addCustomChart(new Metrics.SimplePie("googleDriveEnabled", new Callable<String>() {
            @Override
            public String call() throws Exception {
                return Config.isGoogleDriveEnabled() ? "Enabled" : "Disabled";
            }
        }));
        
        metrics.addCustomChart(new Metrics.SimplePie("oneDriveEnabled", new Callable<String>() {
            @Override
            public String call() throws Exception {
            	return Config.isOneDriveEnabled() ? "Enabled" : "Disabled";
            }
        }));

        metrics.addCustomChart(new Metrics.SimplePie("dropboxEnabled", new Callable<String>() {
            @Override
            public String call() throws Exception {
                return Config.isDropboxEnabled() ? "Enabled" : "Disabled";
            }
        }));

        metrics.addCustomChart(new Metrics.SimplePie("ftpEnabled", new Callable<String>() {
            @Override
            public String call() throws Exception {
            	return Config.isFtpEnabled() ? "Enabled" : "Disabled";
            }
        }));

        if (Config.isFtpEnabled()) {
            metrics.addCustomChart(new Metrics.SimplePie("sftpEnabledNew", new Callable<String>() {
                @Override
                public String call() throws Exception {
                    return Config.isFtpSftp() ? "FTP using SSH" : "FTP";
                }
            }));
        }

        metrics.addCustomChart(new Metrics.SimplePie("updateCheckEnabled", new Callable<String>() {
            @Override
            public String call() throws Exception {
            	return Config.isUpdateCheck() ? "Enabled" : "Disabled";
            }
        }));
    }

    /**
     * What to do when plugin is disabled
     */
    public void onDisable() {
        MessageUtil.sendConsoleMessage("Stopping plugin!");
    }

    /**
     * Gets an instance of the plugin
     *
     * @return DriveBackup plugin
     */
    public static DriveBackup getInstance() {
        return plugin;
    }

    /**
     * Starts the backup thread
     */
    public static void startThread() {
        BukkitScheduler taskScheduler = Bukkit.getServer().getScheduler();

        if (Config.isBackupsScheduled()) {
            cancelAllTasks(backupTasks);
            backupDatesList.clear();

            ZoneOffset timezone = Config.getBackupScheduleTimezone();

            for (HashMap<String, Object> schedule : Config.getBackupScheduleList()) {

                ArrayList<String> scheduleDays = new ArrayList<>();
                scheduleDays.addAll((List<String>) schedule.get("days"));

                for (int i = 0; i < scheduleDays.size(); i++) {
                    switch (scheduleDays.get(i)) {
                        case "weekdays":
                            scheduleDays.remove(scheduleDays.get(i));
                            addIfNotAdded(scheduleDays, "monday");
                            addIfNotAdded(scheduleDays, "tuesday");
                            addIfNotAdded(scheduleDays, "wednesday");
                            addIfNotAdded(scheduleDays, "thursday");
                            addIfNotAdded(scheduleDays, "friday");
                            break;
                        case "weekends":
                            scheduleDays.remove(scheduleDays.get(i));
                            addIfNotAdded(scheduleDays, "sunday");
                            addIfNotAdded(scheduleDays, "saturday");
                            break;
                        case "everyday":
                            scheduleDays.remove(scheduleDays.get(i));
                            addIfNotAdded(scheduleDays, "sunday");
                            addIfNotAdded(scheduleDays, "monday");
                            addIfNotAdded(scheduleDays, "tuesday");
                            addIfNotAdded(scheduleDays, "wednesday");
                            addIfNotAdded(scheduleDays, "thursday");
                            addIfNotAdded(scheduleDays, "friday");
                            addIfNotAdded(scheduleDays, "saturday");
                            break;
                    }
                }

                TemporalAccessor scheduleTime = DateTimeFormatter.ofPattern ("kk:mm", Locale.ENGLISH).parse((String) schedule.get("time"));

                for (int i = 0; i < scheduleDays.size(); i++) {
                    ZonedDateTime previousOccurrence = ZonedDateTime.now(timezone)
                        .with(TemporalAdjusters.previous(DayOfWeek.valueOf(scheduleDays.get(i).toUpperCase())))
                        .with(ChronoField.CLOCK_HOUR_OF_DAY, scheduleTime.get(ChronoField.CLOCK_HOUR_OF_DAY))
                        .with(ChronoField.MINUTE_OF_HOUR, scheduleTime.get(ChronoField.MINUTE_OF_HOUR))
                        .with(ChronoField.SECOND_OF_MINUTE, 0);

                    ZonedDateTime now = ZonedDateTime.now(timezone);
                    
                    ZonedDateTime nextOccurrence = ZonedDateTime.now(timezone)
                        .with(TemporalAdjusters.nextOrSame(DayOfWeek.valueOf(scheduleDays.get(i).toUpperCase())))
                        .with(ChronoField.CLOCK_HOUR_OF_DAY, scheduleTime.get(ChronoField.CLOCK_HOUR_OF_DAY))
                        .with(ChronoField.MINUTE_OF_HOUR, scheduleTime.get(ChronoField.MINUTE_OF_HOUR))
                        .with(ChronoField.SECOND_OF_MINUTE, 0);

                    // Adjusts nextOccurrence date when it was set to earlier on same day, as the DayOfWeek TemporalAdjuster only takes into account the day, not the time
                    ZonedDateTime startingOccurrence = nextOccurrence;
                    if (now.isAfter(startingOccurrence)) {
                        startingOccurrence = startingOccurrence.plusWeeks(1);
                    }

                    backupTasks.add(taskScheduler.runTaskTimerAsynchronously(
                        getInstance(), 
                        new UploadThread(),
                        ChronoUnit.SECONDS.between(now, startingOccurrence) * 20, // 20 ticks per second 
                        ChronoUnit.SECONDS.between(previousOccurrence, nextOccurrence) * 20
                    ).getTaskId());

                    backupDatesList.add(startingOccurrence);
                }

                ZonedDateTime scheduleMessageTime = ZonedDateTime.now(timezone)
                    .with(ChronoField.CLOCK_HOUR_OF_DAY, scheduleTime.get(ChronoField.CLOCK_HOUR_OF_DAY))
                    .with(ChronoField.MINUTE_OF_HOUR, scheduleTime.get(ChronoField.MINUTE_OF_HOUR));
                StringBuilder scheduleMessage = new StringBuilder();
                scheduleMessage.append("Scheduling a backup to run at ");
                scheduleMessage.append(scheduleMessageTime.format(DateTimeFormatter.ofPattern("hh:mm a")));
                scheduleMessage.append(" every ");
                for (int i = 0; i < scheduleDays.size(); i++) {
                    if (i != 0) {
                        scheduleMessage.append(", ");
                    }
                    scheduleMessage.append(scheduleDays.get(i).substring(0, 1).toUpperCase() + scheduleDays.get(i).substring(1));
                }
                MessageUtil.sendConsoleMessage(scheduleMessage.toString());
            }
        } else if (Config.getBackupDelay() != -1) {
            cancelAllTasks(backupTasks);

            MessageUtil.sendConsoleMessage("Scheduling a backup to run every " + Config.getBackupDelay() + " minutes");

            backupTasks.add(taskScheduler.runTaskTimerAsynchronously(
                getInstance(), 
                new UploadThread(),
                Config.getBackupDelay() * 60 * 20,
                Config.getBackupDelay() * 60 * 20
            ).getTaskId());

            UploadThread.updateNextIntervalBackupTime();
        }
    }

    /**
     * Gets a list of Dates representing each time a scheduled backup will occur
     * @return the ArrayList of {@code ZonedDateTime} objects
     */
    public static ArrayList<ZonedDateTime> getBackupDatesList() {
        return backupDatesList;
    }

    /**
     * Reloads config
     */
    public static void reloadLocalConfig() {
        getInstance().reloadConfig();
        pluginconfig.reload(getInstance().getConfig());
    }

    /**
     * Checks if there is an available update (Adapted from Vault's update checker)
     *
     * @param currentVersion Current plugin version
     * @return Latest version
     */
    public double updateCheck(double currentVersion) {
        try {
            Request request = new Request.Builder()
                .url("https://api.curseforge.com/servermods/files?projectids=383461")
                .post(RequestBody.create("", null)) // Send empty request body
                .build();

            Response response = httpClient.newCall(request).execute();
            JSONArray pluginVersions = new JSONArray(response.body().string());
            response.close();

            if (pluginVersions.length() == 0) {
                this.getLogger().warning("No DriveBackupV2 releases found");
                return currentVersion;
            }

            newVersionTitle = pluginVersions.getJSONObject(pluginVersions.length() - 1).getString("name").replace("DriveBackupV2-", "").trim();
            return Double.valueOf(newVersionTitle.replaceFirst("\\.", "").trim());
        } catch (Exception exception) {
            MessageUtil.sendConsoleMessage("There was an issue attempting to check for the latest DriveBackupV2 release");
            MessageUtil.sendConsoleException(exception);
        }

        return currentVersion;
    }

    /**
     * Gets whether an update is available for the plugin
     * @return whether an update is available
     */
    public static boolean isUpdateAvailable() {
        return newVersion > currentVersion;
    } 

    /**
     * Cancels all of the specified tasks
     * @param taskList an ArrayList of the IDs of the tasks
     */
    private static void cancelAllTasks(ArrayList<Integer> taskList) {
        for (int i = 0; i < taskList.size(); i++) {
            Bukkit.getScheduler().cancelTask(taskList.get(i));
            taskList.remove(i);
        }
    }

    /**
     * Adds a Object to an ArrayList, if it doesn't already contain the Object
     * @param list the ArrayList
     * @param item the Object
     */
    private static void addIfNotAdded(ArrayList list, Object item) {
        if (!list.contains(item)) list.add(item);
    }
}
