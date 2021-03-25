package ratismal.drivebackup;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import ratismal.drivebackup.config.Config;
import ratismal.drivebackup.dropbox.DropboxUploader;
import ratismal.drivebackup.ftp.FTPUploader;
import ratismal.drivebackup.googledrive.GoogleDriveUploader;
import ratismal.drivebackup.handler.PlayerListener;
import ratismal.drivebackup.mysql.MySQLUploader;
import ratismal.drivebackup.onedrive.OneDriveUploader;
import ratismal.drivebackup.util.*;
import ratismal.drivebackup.util.Timer;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Created by Ratismal on 2016-01-22.
 */

public class UploadThread implements Runnable {
    private CommandSender initiator;

    private static LocalDateTime nextIntervalBackupTime = null;

    /**
     * The current status of the backup thread
     */
    enum BackupStatus {
        /**
         * The backup thread isn't running
         */
        NOT_RUNNING,

        /**
         * The backup thread is compressing the files to be backed up
         */
        COMPRESSING,

        /**
         * The backup thread is uploading the files
         */
        UPLOADING
    }

    /**
     * The list of items to be backed up by the backup thread
     */
    private static ArrayList<HashMap<String, Object>> backupList;

    /**
     * The {@code BackupStatus} of the backup thread
     */
    private static BackupStatus backupStatus = BackupStatus.NOT_RUNNING;

    /**
     * The backup currently being backed up by the 
     */
    private static int backupBackingUp = 0;

    /**
     * Creates an instance of the {@code UploadThread} object
     */
    public UploadThread() {
    }

    /**
     * Creates an instance of the {@code UploadThread} object
     * @param initiator the player who initiated the backup
     */
    public UploadThread(CommandSender initiator) {
        this.initiator = initiator;
    }

    /**
     * Starts a backup
     */
    @Override
    public void run() {
        if (initiator != null && backupStatus != BackupStatus.NOT_RUNNING) {
            MessageUtil.sendMessage(initiator, "A backup is already running");
            MessageUtil.sendMessage(initiator, getBackupStatus());

            return;
        }

        if (initiator == null) {
            updateNextIntervalBackupTime();
        }

        Thread.currentThread().setPriority(Thread.MIN_PRIORITY + Config.getBackupThreadPriority());

        if (!DriveBackupApi.shouldStartBackup()) {
            return;
        }

        if (Config.isBackupsRequirePlayers() && !PlayerListener.isAutoBackupsActive() && initiator == null) {
            MessageUtil.sendConsoleMessage("Skipping backup due to inactivity");

            return;
        }

        boolean errorOccurred = false;

        if (!Config.isGoogleDriveEnabled() && !Config.isOneDriveEnabled() && !Config.isDropboxEnabled() && !Config.isFtpEnabled() && Config.getLocalKeepCount() == 0) {
            MessageUtil.sendMessageToPlayersWithPermission("No backup method is enabled", "drivebackup.linkAccounts", Collections.singletonList(initiator), true);

            return;
        }

        setAutoSave(false);

        MessageUtil.sendMessageToAllPlayers(Config.getBackupStart());


        ArrayList<Uploader> uploaders = new ArrayList<Uploader>();

        if (Config.isGoogleDriveEnabled()) {
            uploaders.add((Uploader) new GoogleDriveUploader());
        }
        if (Config.isOneDriveEnabled()) {
            uploaders.add((Uploader) new OneDriveUploader());
        }
        if (Config.isDropboxEnabled()) {
            uploaders.add((Uploader) new DropboxUploader());
        }
        if (Config.isFtpEnabled()) {
            uploaders.add((Uploader) new FTPUploader());
        }

        backupList = Config.getBackupList();

        ArrayList<HashMap<String, Object>> externalBackupList = Config.getExternalBackupList();
        if (externalBackupList != null) {
            
            for (HashMap<String, Object> externalBackup : externalBackupList) {
                switch ((String) externalBackup.get("type")) {
                    case "ftpServer":
                    case "ftpsServer":
                    case "sftpServer":
                        makeExternalFileBackup(externalBackup);
                        break;
                    case "mysqlDatabase":
                        makeExternalDatabaseBackup(externalBackup);
                        break;
                }
            }
        }

        backupBackingUp = 0;
        for (HashMap<String, Object> set : backupList) {
            String type;
            Boolean doGlob = false;
            if (set.containsKey("path")) {
                type = set.get("path").toString();
            } else if (set.containsKey("glob")) {
                type = set.get("glob").toString();
                doGlob = true;
            } else {
                // no path or glob specified
                MessageUtil.sendMessageToPlayersWithPermission("Failed to create a backup, no path or glob was specified. ", "drivebackup.linkAccounts", Collections.singletonList(initiator), true);
                backupStatus = BackupStatus.NOT_RUNNING;
                errorOccurred = true;

                setAutoSave(true);

                return;
            }
            String format = set.get("format").toString();
            String create = set.get("create").toString();

            ArrayList<String> blackList = new ArrayList<>();
            if (set.containsKey("blacklist")) {
                Object tempObject = set.get("blacklist");
                if (tempObject instanceof List<?>) {
                    blackList = (ArrayList<String>) tempObject;
                }
            }

            if(!doGlob) {
                Boolean res = doSingleBackup(type, format, create, blackList, uploaders);
                if(res) { // an error occurred
                    errorOccurred = true;
                    setAutoSave(true);
                    return;
                }
            } else {
                List<Path> folders = FileUtil.generateGlobFolderList(type, ".");
                for(Path folder : folders) {
                    Boolean err = doSingleBackup(folder.toString(), format, create, blackList, uploaders);
                    if(err) { // an error occurred
                        errorOccurred = true;
                        setAutoSave(true);
                        return;
                    }
                }
            }

            backupBackingUp++;
        }

        deleteFolder(new File("external-backups"));

        backupStatus = BackupStatus.NOT_RUNNING;
            
        if (Config.getLocalKeepCount() != 0) {
            MessageUtil.sendMessageToPlayersWithPermission(ChatColor.GOLD + "Local " + ChatColor.DARK_AQUA + "backup complete", "drivebackup.linkAccounts", Collections.singletonList(initiator), false);
        }

        for(int i = 0; i < uploaders.size(); i++) {
            uploaders.get(i).close();
            if (uploaders.get(i).isErrorWhileUploading()) {
                MessageUtil.sendMessageToPlayersWithPermission(uploaders.get(i).getSetupInstructions(), "drivebackup.linkAccounts", Collections.singletonList(initiator));
                errorOccurred = true;
            } else {
                MessageUtil.sendMessageToPlayersWithPermission("Backup to " + ChatColor.GOLD + uploaders.get(i).getName() + ChatColor.DARK_AQUA + " complete", "drivebackup.linkAccounts", Collections.singletonList(initiator), false);
            }
        }

        if (initiator != null) {
            MessageUtil.sendMessageToAllPlayers(Config.getBackupDone());
        } else {
            MessageUtil.sendMessageToAllPlayers(Config.getBackupDone() + " " + getNextAutoBackup());
        }

        if (Config.isBackupsRequirePlayers() && Bukkit.getOnlinePlayers().size() == 0 && PlayerListener.isAutoBackupsActive()) {
            MessageUtil.sendConsoleMessage("Disabling automatic backups due to inactivity");
            PlayerListener.setAutoBackupsActive(false);
        }

        setAutoSave(true);

        if (errorOccurred) {
            DriveBackupApi.backupError();
        } else {
            DriveBackupApi.backupDone();
        }
    }

    /**
     * Backs up a single folder
     * @param type Path to the folder
     * @param format Save format configuration
     * @param create Create the zip file or just upload it? ("True" / "False")
     * @param blackList configured blacklist (with globs)
     * @param uploaders All servies to upload to
     * @return True if any error occurred
     */
    private Boolean doSingleBackup(String type, String format, String create, ArrayList<String> blackList, ArrayList<Uploader> uploaders) {
        MessageUtil.sendConsoleMessage("Doing backups for \"" + type + "\"");
        if (create.equalsIgnoreCase("true")) {
            backupStatus = BackupStatus.COMPRESSING;

            try {
                FileUtil.makeBackup(type, format, blackList);
            } catch (IllegalArgumentException exception) {
                MessageUtil.sendMessageToPlayersWithPermission("Failed to create a backup, path to folder to backup is absolute, expected a relative path", "drivebackup.linkAccounts", Collections.singletonList(initiator), true);
                MessageUtil.sendMessageToPlayersWithPermission("An absolute path can overwrite sensitive files, see the " + ChatColor.GOLD + "config.yml " + ChatColor.DARK_AQUA + "for more information", "drivebackup.linkAccounts", Collections.singletonList(initiator), true);

                backupStatus = BackupStatus.NOT_RUNNING;

                setAutoSave(true);

                return true;
            } catch (Exception exception) {
                MessageUtil.sendConsoleException(exception);
                MessageUtil.sendMessageToPlayersWithPermission("Failed to create a backup", "drivebackup.linkAccounts", Collections.singletonList(initiator), true);

                backupStatus = BackupStatus.NOT_RUNNING;

                setAutoSave(true);

                return true;
            }
        }

        try {
            backupStatus = BackupStatus.UPLOADING;

            if (FileUtil.isBaseFolder(type)) {
                type = "root";
            }

            File file = FileUtil.getNewestBackup(type, format);
            ratismal.drivebackup.util.Timer timer = new Timer();


            for(int i = 0; i < uploaders.size(); i++) {
                MessageUtil.sendConsoleMessage("Uploading file to " + uploaders.get(i).getName());
                timer.start();
                uploaders.get(i).uploadFile(file, type);
                timer.end();
                if(!uploaders.get(i).isErrorWhileUploading()) {
                    MessageUtil.sendConsoleMessage(timer.getUploadTimeMessage(file));
                } else {
                    MessageUtil.sendConsoleMessage("Upload failed");
                }
            }

            FileUtil.deleteFiles(type, format);
        } catch (Exception e) {
            MessageUtil.sendConsoleException(e);
        }
        return false;
    }

    /**
     * Downloads files from a FTP server and stores them within the external-backups temporary folder, using the specified external backup settings
     * @param externalBackup the external backup settings
     */
    private void makeExternalFileBackup(HashMap<String, Object> externalBackup) {
        MessageUtil.sendConsoleMessage("Downloading files from a (S)FTP server (" + getSocketAddress(externalBackup) + ") to include in backup");

        FTPUploader ftpUploader = new FTPUploader(
                (String) externalBackup.get("hostname"), 
                (int) externalBackup.get("port"), 
                (String) externalBackup.get("username"), 
                (String) externalBackup.get("password"),
                externalBackup.get("type").equals("ftpsServer"),
                externalBackup.get("type").equals("sftpServer"), 
                (String) externalBackup.get("sftp-public-key"), 
                (String) externalBackup.get("sftp-passphrase"),
                "external-backups",
                ".");

        for (Map<String, Object> backup : (List<Map<String, Object>>) externalBackup.get("backup-list")) {
            ArrayList<HashMap<String, Object>> blacklist = new ArrayList<>();

            ArrayList<String> blacklistGlobs = new ArrayList<>();
            if (backup.containsKey("blacklist")) {
                Object tempObject = backup.get("blacklist");
                if (tempObject instanceof List<?>) {
                    blacklistGlobs = (ArrayList<String>) tempObject;
                }
            }

            for (String blacklistGlob : blacklistGlobs) {
                HashMap<String, Object> blacklistEntry = new HashMap<>();
    
                blacklistEntry.put("globPattern", blacklistGlob);
                blacklistEntry.put("pathMatcher", FileSystems.getDefault().getPathMatcher("glob:" + blacklistGlob));
                blacklistEntry.put("blacklistedFiles", 0);
    
                blacklist.add(blacklistEntry);
            }

            for (String relativeFilePath : ftpUploader.getFiles(externalBackup.get("base-dir") + "/" + backup.get("path"))) {
                String filePath = externalBackup.get("base-dir") + "/" + backup.get("path") + "/" + relativeFilePath;

                for (HashMap<String, Object> blacklistEntry : blacklist) {
                    PathMatcher pathMatcher = (PathMatcher) blacklistEntry.get("pathMatcher");
                    int blacklistedFiles = (int) blacklistEntry.get("blacklistedFiles");
                    

                    if (pathMatcher.matches(Paths.get(relativeFilePath))) {
                        blacklistEntry.put("blacklistedFiles", ++blacklistedFiles);
    
                        continue;
                    } 
                }

                String parentFolder = new File(relativeFilePath).getParent();
                String parentFolderPath;
                if (parentFolder != null) {
                    parentFolderPath = File.separator + parentFolder;
                } else {
                    parentFolderPath = "";
                }

                ftpUploader.downloadFile(filePath, getTempFolderName(externalBackup) + File.separator + backup.get("path") + parentFolderPath);
            }

            for (HashMap<String, Object> blacklistEntry : blacklist) {
                String globPattern = (String) blacklistEntry.get("globPattern");
                int blacklistedFiles = (int) blacklistEntry.get("blacklistedFiles");
    
                if (blacklistedFiles > 0) {
                    MessageUtil.sendConsoleMessage("Didn't include " + blacklistedFiles + " file(s) in the backup from the external (S)FTP server, as they are blacklisted by \"" + globPattern + "\"");
                }
            }
        }

        ftpUploader.close();

        HashMap<String, Object> backup = new HashMap<>();
        backup.put("path", "external-backups" + File.separator + getTempFolderName(externalBackup));
        backup.put("format", externalBackup.get("format"));
        backup.put("create", "true");
        backupList.add(backup);

        if (ftpUploader.isErrorWhileUploading()) {
            MessageUtil.sendMessageToPlayersWithPermission("Failed to include files from a (S)FTP server (" + getSocketAddress(externalBackup) + ") in the backup, please check the server credentials in the " + ChatColor.GOLD + "config.yml", "drivebackup.linkAccounts", Collections.singletonList(initiator), false);
        } else {
            MessageUtil.sendMessageToPlayersWithPermission("Files from a " + ChatColor.GOLD + "(S)FTP server (" + getSocketAddress(externalBackup) + ") " + ChatColor.DARK_AQUA + "were successfully included in the backup", "drivebackup.linkAccounts", Collections.singletonList(initiator), false);
        }
    }

    /**
     * Downloads databases from a MySQL server and stores them within the external-backups temporary folder, using the specified external backup settings
     * @param externalBackup the external backup settings
     */
    private void makeExternalDatabaseBackup(HashMap<String, Object> externalBackup) {
        MessageUtil.sendConsoleMessage("Downloading databases from a MySQL server (" + getSocketAddress(externalBackup) + ") to include in backup");

        // For backwards compatibility with <1.3.0
        boolean useSsl = false;
        if (externalBackup.containsKey("ssl")) {
            useSsl = (boolean) externalBackup.get("ssl");
        }

        MySQLUploader mysqlUploader = new MySQLUploader(
                (String) externalBackup.get("hostname"), 
                (int) externalBackup.get("port"), 
                (String) externalBackup.get("username"), 
                (String) externalBackup.get("password"),
                useSsl);

        if (externalBackup.containsKey("databases")) {

            for (Map<String, Object> database : (List<Map<String, Object>>) externalBackup.get("databases")) {
                if (database.containsKey("blacklist")) {
                    for (String blacklistEntry : (List<String>) database.get("blacklist")) {
                        MessageUtil.sendConsoleMessage("Didn't include database \"" + blacklistEntry + "\" in the backup, as it is blacklisted");
                    }

                    mysqlUploader.downloadDatabase((String) database.get("name"), getTempFolderName(externalBackup), (List<String>) database.get("blacklist"));
                } else {
                    mysqlUploader.downloadDatabase((String) database.get("name"), getTempFolderName(externalBackup));
                }
            }
        } else { // For backwards compatibility with <1.3.0

            for (String databaseName : (List<String>) externalBackup.get("names")) {
                mysqlUploader.downloadDatabase(databaseName, getTempFolderName(externalBackup));
            }
        }

        HashMap<String, Object> backup = new HashMap<>();
        backup.put("path", "external-backups" + File.separator + getTempFolderName(externalBackup));
        backup.put("format", externalBackup.get("format"));
        backup.put("create", "true");
        backupList.add(backup);

        if (mysqlUploader.isErrorWhileUploading()) {
            MessageUtil.sendMessageToPlayersWithPermission("Failed to include databases from a MySQL server (" + getSocketAddress(externalBackup) + ") in the backup, please check the server credentials in the " + ChatColor.GOLD + "config.yml", "drivebackup.linkAccounts", Collections.singletonList(initiator), false);
        } else {
            MessageUtil.sendMessageToPlayersWithPermission("Databases from a " + ChatColor.GOLD + "MySQL server (" + getSocketAddress(externalBackup) + ") " + ChatColor.DARK_AQUA + "were successfully included in the backup", "drivebackup.linkAccounts", Collections.singletonList(initiator), false);
        }
    }

    /**
     * Gets the current status of the backup thread
     * @return the status of the backup thread as a {@code String}
     */
    public static String getBackupStatus() {
        StringBuilder backupStatusMessage = new StringBuilder();

        if (backupStatus == BackupStatus.NOT_RUNNING) {
            backupStatusMessage.append("No backups are running");

            return backupStatusMessage.toString();
        }

        switch (backupStatus) {
            case COMPRESSING: backupStatusMessage.append("Compressing ");
                break;
            case UPLOADING: backupStatusMessage.append("Uploading ");
                break;
            default:
        }

        String backupSetName = (String) Config.getBackupList().get(backupBackingUp).get("path");

        backupStatusMessage.append("backup set \"" + backupSetName + "\", set " + (backupBackingUp + 1) + " of " + Config.getBackupList().size());

        return backupStatusMessage.toString();
    }

    /**
     * Gets the date/time of the next automatic backup, if enabled
     * @return the time and/or date of the next automatic backup formatted using the messages in the {@code config.yml} 
     */
    public static String getNextAutoBackup() {
        String nextBackupMessage = "";

        if (Config.isBackupsScheduled()) {

            ZonedDateTime nextBackupDate = null;

            ZonedDateTime now = ZonedDateTime.now(Config.getBackupScheduleTimezone());

            int weeksCheckedForDate;
            for (weeksCheckedForDate = 0; weeksCheckedForDate < 2; weeksCheckedForDate++) {
                for (ZonedDateTime date : DriveBackup.getBackupDatesList()) {

                    if (nextBackupDate == null &&

                        ((LocalTime.from(date).isAfter(LocalTime.from(now)) && // This might not work if time specified is 00:00
                        date.getDayOfWeek().compareTo(now.getDayOfWeek()) == 0) ||

                        date.getDayOfWeek().compareTo(now.getDayOfWeek()) > 0)
                    ) {
                        nextBackupDate = date;
                        continue;
                    }

                    if (nextBackupDate != null &&

                        ((LocalTime.from(date).isBefore(LocalTime.from(nextBackupDate)) && // This might not work if time specified is 00:00
                        LocalTime.from(date).isAfter(LocalTime.from(now)) &&
                        (date.getDayOfWeek().compareTo(nextBackupDate.getDayOfWeek()) == 0 ||
                        date.getDayOfWeek().compareTo(now.getDayOfWeek()) == 0)) || 

                        (date.getDayOfWeek().compareTo(nextBackupDate.getDayOfWeek()) < 0 &&
                        date.getDayOfWeek().compareTo(now.getDayOfWeek()) > 0))
                    ) {
                        nextBackupDate = date;
                    }
                }

                if (nextBackupDate != null) {
                    break;
                }

                now = now
                    .with(ChronoField.DAY_OF_WEEK, 1)
                    .with(ChronoField.CLOCK_HOUR_OF_DAY, 1)
                    .with(ChronoField.MINUTE_OF_HOUR, 0)
                    .with(ChronoField.SECOND_OF_DAY, 0);
            }

            if (weeksCheckedForDate == 1) {
                nextBackupDate = nextBackupDate
                    .with(ChronoField.YEAR, now.get(ChronoField.YEAR))
                    .with(ChronoField.ALIGNED_WEEK_OF_YEAR, now.get(ChronoField.ALIGNED_WEEK_OF_YEAR) + 1);
            } else {
                nextBackupDate = nextBackupDate
                    .with(ChronoField.YEAR, now.get(ChronoField.YEAR))
                    .with(ChronoField.ALIGNED_WEEK_OF_YEAR, now.get(ChronoField.ALIGNED_WEEK_OF_YEAR));
            }

            nextBackupMessage = Config.getBackupNextScheduled().replaceAll("%DATE", nextBackupDate.format(DateTimeFormatter.ofPattern(Config.getBackupNextScheduledFormat(), new Locale(Config.getDateLanguage()))));
        } else if (Config.getBackupDelay() != -1) {
            nextBackupMessage = Config.getBackupNext().replaceAll("%TIME", String.valueOf(LocalDateTime.now().until(nextIntervalBackupTime, ChronoUnit.MINUTES)));
        } else {
            nextBackupMessage = Config.getAutoBackupsDisabled();
        }

        return nextBackupMessage;
    }

    /**
     * Sets the time of the next interval-based backup to the current time + the configured interval
     */
    public static void updateNextIntervalBackupTime() {
        nextIntervalBackupTime = LocalDateTime.now().plus(Config.getBackupDelay(), ChronoUnit.MINUTES);
    }

    /**
     * Gets the socket address (ipaddress/hostname:port) of an external backup server based on the specified settings
     * @param externalBackup the external backup settings
     * @return the socket address
     */
    private static String getSocketAddress(HashMap<String, Object> externalBackup) {
        return externalBackup.get("hostname") + "-" + externalBackup.get("port");
    }

    /**
     * Generates the name for a folder based on the specified external backup settings to be stored within the external-backups temporary folder
     * @param externalBackup the external backup settings
     * @return the folder name
     */
    private static String getTempFolderName(HashMap<String, Object> externalBackup) {
        if (externalBackup.get("type").equals("mysqlDatabase")) {
            return "mysql-" + getSocketAddress(externalBackup);
        } else {
            return "ftp-" + getSocketAddress(externalBackup);
        }
    }

    /**
     * Deletes the specified folder
     * @param folder the folder to be deleted
     * @return whether deleting the folder was successful
     */
    private static boolean deleteFolder(File folder) {
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                deleteFolder(file);
            }
        }
        return folder.delete();
    }

    /**
     * Turns the server auto save on/off
     * @param autoSave whether to save automatically
     */
    private static void setAutoSave(boolean autoSave) {
        if (!Config.isSavingDisabledDuringBackups()) {
            return;
        }

        try {
            Bukkit.getScheduler().callSyncMethod(DriveBackup.getInstance(), new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), autoSave ? "save-on" : "save-off");
                }
            }).get();
        } catch (Exception exception) { }
    }
}
