package ratismal.drivebackup;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import ratismal.drivebackup.uploaders.Authenticator;
import ratismal.drivebackup.uploaders.Uploader;
import ratismal.drivebackup.uploaders.Authenticator.AuthenticationProvider;
import ratismal.drivebackup.uploaders.dropbox.DropboxUploader;
import ratismal.drivebackup.uploaders.ftp.FTPUploader;
import ratismal.drivebackup.uploaders.googledrive.GoogleDriveUploader;
import ratismal.drivebackup.uploaders.onedrive.OneDriveUploader;
import ratismal.drivebackup.uploaders.mysql.MySQLUploader;
import ratismal.drivebackup.config.ConfigParser;
import ratismal.drivebackup.config.Permissions;
import ratismal.drivebackup.config.ConfigParser.Config;
import ratismal.drivebackup.config.configSections.BackupList.BackupListEntry;
import ratismal.drivebackup.config.configSections.BackupList.BackupListEntry.PathBackupLocation;
import ratismal.drivebackup.config.configSections.ExternalBackups.ExternalBackupSource;
import ratismal.drivebackup.config.configSections.ExternalBackups.ExternalFTPSource;
import ratismal.drivebackup.config.configSections.ExternalBackups.ExternalMySQLSource;
import ratismal.drivebackup.config.configSections.ExternalBackups.ExternalFTPSource.ExternalBackupListEntry;
import ratismal.drivebackup.config.configSections.ExternalBackups.ExternalMySQLSource.MySQLDatabaseBackup;
import ratismal.drivebackup.handler.PlayerListener;
import ratismal.drivebackup.plugin.Scheduler;
import ratismal.drivebackup.util.*;
import ratismal.drivebackup.util.Timer;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

import com.google.api.client.util.Strings;

import static ratismal.drivebackup.config.Localization.intl;

/**
 * Created by Ratismal on 2016-01-22.
 */

public class UploadThread implements Runnable {
    private CommandSender initiator;
    private Logger logger;

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
     * List of {@code Uploaders} to upload the backups to
     */
    private ArrayList<Uploader> uploaders;

    /**
     * The list of items to be backed up by the backup thread
     */
    private static List<BackupListEntry> backupList;

    private static LocalDateTime nextIntervalBackupTime = null;

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
        logger = (input, placeholders) -> {
            MessageUtil.Builder().mmText(input, placeholders)
                .to(initiator)
                .toPerm(Permissions.BACKUP)
                .send();
        };
    }

    /**
     * Creates an instance of the {@code UploadThread} object
     * @param initiator the player who initiated the backup
     */
    public UploadThread(CommandSender initiator) {
        this.initiator = initiator;
        
        logger = (input, placeholders) -> {
            MessageUtil.Builder().mmText(input, placeholders)
                .to(initiator)
                .toPerm(Permissions.BACKUP)
                .send();
        };
    }

    /**
     * Starts a backup
     */
    @Override
    public void run() {
        Config config = ConfigParser.getConfig();

        if (initiator != null && backupStatus != BackupStatus.NOT_RUNNING) {
            MessageUtil.Builder()
                .mmText(intl("backup-already-running"), "backup-status", getBackupStatus())
                .to(initiator)
                .toConsole(false)
                .send();

            return;
        }

        if (initiator == null) {
            updateNextIntervalBackupTime();
        }

        Thread.currentThread().setPriority(config.backupStorage.threadPriority);

        if (!DriveBackupApi.shouldStartBackup()) {
            return;
        }

        if (config.backupStorage.backupsRequirePlayers && !PlayerListener.isAutoBackupsActive() && initiator == null) {
            logger.log(intl("backup-skipped-inactivity"));

            return;
        }

        boolean errorOccurred = false;

        if (
            !config.backupMethods.googleDrive.enabled && 
            !config.backupMethods.oneDrive.enabled && 
            !config.backupMethods.dropbox.enabled && 
            !config.backupMethods.ftp.enabled && 
            config.backupStorage.localKeepCount == 0
            ) {

            logger.log(intl("backup-no-methods"));

            return;
        }

        ServerUtil.setAutoSave(false);

        MessageUtil.Builder().text(intl("backup-start")).all().send();


        uploaders = new ArrayList<Uploader>();

        if (config.backupMethods.googleDrive.enabled) {
            uploaders.add(new GoogleDriveUploader());
        }
        if (config.backupMethods.oneDrive.enabled) {
            uploaders.add(new OneDriveUploader());
        }
        if (config.backupMethods.dropbox.enabled) {
            uploaders.add(new DropboxUploader());
        }
        if (config.backupMethods.ftp.enabled) {
            uploaders.add(new FTPUploader());
        }

        ensureMethodsLinked();

        backupList = Arrays.asList(config.backupList.list);

        List<ExternalBackupSource> externalBackupList = Arrays.asList(config.externalBackups.sources);
        for (ExternalBackupSource externalBackup : externalBackupList) {
            if (externalBackup instanceof ExternalFTPSource) {
                makeExternalFileBackup((ExternalFTPSource) externalBackup);
            } else {
                makeExternalDatabaseBackup((ExternalMySQLSource) externalBackup);
            }
        }

        backupBackingUp = 0;
        for (BackupListEntry set : backupList) {
            for(Path folder : set.location.getPaths()) {
                doSingleBackup(folder.toString(), set.formatter, set.create, Arrays.asList(set.blacklist), uploaders);
            }

            backupBackingUp++;
        }

        FileUtil.deleteFolder(new File("external-backups"));

        backupStatus = BackupStatus.NOT_RUNNING;

        for(int i = 0; i < uploaders.size(); i++) {
            uploaders.get(i).close();
            if (uploaders.get(i).isErrorWhileUploading()) {
                MessageUtil builder = MessageUtil.Builder().text(uploaders.get(i).getSetupInstructions());
                if (initiator != null) builder.to(initiator);
                builder.toPerm(Permissions.BACKUP).send();
                errorOccurred = true;
            } else {
                MessageUtil.Builder()
                    .mmText(intl("backup-method-complete"), "upload-method", uploaders.get(i).getName())
                    .to(initiator)
                    .toConsole(false)
                    .send();
            }
        }

        if (initiator != null) {
            MessageUtil.Builder().text(intl("backup-complete")).to(initiator).send();
        } else {
            MessageUtil.Builder().text(intl("backup-complete") + " " + getNextAutoBackup()).all().send();
        }

        if (config.backupStorage.backupsRequirePlayers && Bukkit.getOnlinePlayers().size() == 0 && PlayerListener.isAutoBackupsActive()) {
            MessageUtil.Builder().mmText(intl("backup-disabled-inactivity")).toConsole(true).send();
            PlayerListener.setAutoBackupsActive(false);
        }

        ServerUtil.setAutoSave(true);

        if (errorOccurred) {
            DriveBackupApi.backupError();
        } else {
            DriveBackupApi.backupDone();
        }
    }

    private void ensureMethodsLinked() {
        for (Uploader uploader : uploaders) {
            AuthenticationProvider provider = uploader.getAuthProvider();
            if (provider == null) continue;

            if (!Authenticator.hasRefreshToken(provider)) {
                logger.log(
                    intl("backup-method-not-linked")
                        .replace(
                            "link-command", 
                            "/drivebackup linkaccount " + provider.getId().replace("-", "")), 
                    "backup-method", 
                    provider.getName());

                uploaders.remove(uploader);
            }
        }
    }

    /**
     * Backs up a single folder
     * @param location Path to the folder
     * @param formatter Save format configuration
     * @param create Create the zip file or just upload it? ("True" / "False")
     * @param blackList configured blacklist (with globs)
     * @param uploaders All services to upload to
     * @return True if any error occurred
     */
    private void doSingleBackup(String location, LocalDateTimeFormatter formatter, boolean create, List<String> blackList, List<Uploader> uploaders) {
        MessageUtil.Builder().mmText(intl("backup-location-start"), "location", location).toConsole(true).send();
        if (create) {
            backupStatus = BackupStatus.COMPRESSING;

            try {
                FileUtil.makeBackup(location, formatter, blackList);
            } catch (IllegalArgumentException exception) {
                logger.log(intl("backup-failed-absolute-path"));

                return;
            } catch (Exception exception) {
                logger.log(intl("backup-local-failed"));

                return;
            }
        }

        try {
            backupStatus = BackupStatus.UPLOADING;

            if (FileUtil.isBaseFolder(location)) {
                location = "root";
            }

            File file = FileUtil.getNewestBackup(location, formatter);
            Timer timer = new Timer();

            for (Uploader uploader : uploaders) {
                logger.log(
                    intl("backup-uploading-to-method"),
                    "backup-method",
                    uploader.getName());

                timer.start();
                uploader.uploadFile(file, location);
                timer.end();

                if (!uploader.isErrorWhileUploading()) {
                    logger.log(timer.getUploadTimeMessage(file));
                } else {
                    logger.log(intl("backup-failed-to-upload-to-method"));
                }
            }

            FileUtil.deleteFiles(location, formatter);
        } catch (Exception e) {

            logger.log(intl("backup-failed-to-upload-to-method"));
            MessageUtil.sendConsoleException(e);
        }
    }

    /**
     * Downloads files from a FTP server and stores them within the external-backups temporary folder, using the specified external backup settings
     * @param externalBackup the external backup settings
     */
    private void makeExternalFileBackup(ExternalFTPSource externalBackup) {
        MessageUtil.Builder().text("Downloading files from a (S)FTP server (" + getSocketAddress(externalBackup) + ") to include in backup").toConsole(true).send();

        FTPUploader ftpUploader = new FTPUploader(
                externalBackup.hostname, 
                externalBackup.port, 
                externalBackup.username, 
                externalBackup.password,
                externalBackup.ftps,
                externalBackup.sftp,
                externalBackup.publicKey, 
                externalBackup.passphrase,
                "external-backups",
                ".");

        for (ExternalBackupListEntry backup : externalBackup.backupList) {
            ArrayList<BlacklistEntry> blacklist = new ArrayList<>();

            for (String blacklistGlob : backup.blacklist) {
                BlacklistEntry blacklistEntry = new BlacklistEntry(
                    blacklistGlob, 
                    FileSystems.getDefault().getPathMatcher("glob:" + blacklistGlob)
                    );
    
                blacklist.add(blacklistEntry);
            }

            String baseDirectory;
            if (Strings.isNullOrEmpty(externalBackup.baseDirectory)) {
                baseDirectory = backup.path;
            } else {
                baseDirectory = externalBackup.baseDirectory + "/" + backup.path;
            }

            for (String relativeFilePath : ftpUploader.getFiles(baseDirectory)) {
                String filePath = baseDirectory + "/" + relativeFilePath;

                for (BlacklistEntry blacklistEntry : blacklist) {
                    if (blacklistEntry.getPathMatcher().matches(Paths.get(relativeFilePath))) {
                        blacklistEntry.incrementBlacklistedFiles();
    
                        continue;
                    } 
                }

                String parentFolder = new File(relativeFilePath).getParent();
                String parentFolderPath;
                if (parentFolder != null) {
                    parentFolderPath = "/" + parentFolder;
                } else {
                    parentFolderPath = "";
                }

                ftpUploader.downloadFile(filePath, getTempFolderName(externalBackup) + "/" + backup.path + parentFolderPath);
            }

            for (BlacklistEntry blacklistEntry : blacklist) {
                String globPattern = blacklistEntry.getGlobPattern();
                int blacklistedFiles = blacklistEntry.getBlacklistedFiles();
    
                if (blacklistedFiles > 0) {
                    MessageUtil.Builder().text("Didn't include " + blacklistedFiles + " file(s) in the backup from the external (S)FTP server, as they are blacklisted by \"" + globPattern + "\"").toConsole(true).send();
                }
            }
        }

        ftpUploader.close();

        BackupListEntry backup = new BackupListEntry(
            new PathBackupLocation("external-backups" + File.separator + getTempFolderName(externalBackup)),
            externalBackup.format,
            true,
            new String[0]
        );
        backupList.add(backup);

        if (ftpUploader.isErrorWhileUploading()) {
            MessageUtil.Builder().text("Failed to include files from a (S)FTP server (" + getSocketAddress(externalBackup) + ") in the backup, please check the server credentials in the ").emText("config.yml").toPerm("drivebackup.linkAccounts").to(initiator).toConsole(false).send();
        } else {
            MessageUtil.Builder().text("Files from a ").emText("(S)FTP server (" + getSocketAddress(externalBackup) + ") ").text("were successfully included in the backup").toPerm("drivebackup.linkAccounts").to(initiator).toConsole(false).send();
        }
    }

    /**
     * Downloads databases from a MySQL server and stores them within the external-backups temporary folder, using the specified external backup settings
     * @param externalBackup the external backup settings
     */
    private void makeExternalDatabaseBackup(ExternalMySQLSource externalBackup) {
        MessageUtil.Builder().text("Downloading databases from a MySQL server (" + getSocketAddress(externalBackup) + ") to include in backup").toConsole(true).send();

        MySQLUploader mysqlUploader = new MySQLUploader(
                externalBackup.hostname, 
                externalBackup.port, 
                externalBackup.username, 
                externalBackup.password,
                externalBackup.ssl);

        for (MySQLDatabaseBackup database : externalBackup.databaseList) {
            for (String blacklistEntry : database.blacklist) {
                MessageUtil.Builder().text("Didn't include table \"" + blacklistEntry + "\" in the backup, as it is blacklisted").toConsole(true).send();
            }

            mysqlUploader.downloadDatabase(database.name, getTempFolderName(externalBackup), Arrays.asList(database.blacklist));
        }

        BackupListEntry backup = new BackupListEntry(
            new PathBackupLocation("external-backups" + File.separator + getTempFolderName(externalBackup)),
            externalBackup.format,
            true,
            new String[0]
        );
        backupList.add(backup);

        if (mysqlUploader.isErrorWhileUploading()) {
            MessageUtil.Builder().text("Failed to include databases from a MySQL server (" + getSocketAddress(externalBackup) + ") in the backup, please check the server credentials in the ").emText("config.yml").toPerm("drivebackup.linkAccounts").to(initiator).toConsole(false).send();
        } else {
            MessageUtil.Builder().text("Databases from a ").emText("MySQL server (" + getSocketAddress(externalBackup) + ") ").text("were successfully included in the backup").toPerm("drivebackup.linkAccounts").to(initiator).toConsole(false).send();
        }
    }

    /**
     * Gets the current status of the backup thread
     * @return the status of the backup thread as a {@code String}
     */
    public static String getBackupStatus() {
        Config config = ConfigParser.getConfig();
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

        BackupListEntry[] backupList = config.backupList.list;
        String backupSetName = backupList[backupBackingUp].location.toString();

        backupStatusMessage.append("backup set \"" + backupSetName + "\", set " + (backupBackingUp + 1) + " of " + backupList.length);

        return backupStatusMessage.toString();
    }

    /**
     * Gets the date/time of the next automatic backup, if enabled
     * @return the time and/or date of the next automatic backup formatted using the messages in the {@code config.yml} 
     */
    public static String getNextAutoBackup() {
        Config config = ConfigParser.getConfig();
        String nextBackupMessage = "";

        if (config.backupScheduling.enabled) {
            long now = ZonedDateTime.now(config.advanced.dateTimezone).toEpochSecond();

            ZonedDateTime nextBackupDate = Collections.min(Scheduler.getBackupDatesList(), new Comparator<ZonedDateTime>() {
                public int compare(ZonedDateTime d1, ZonedDateTime d2) {
                    long diff1 = Math.abs(d1.toEpochSecond() - now);
                    long diff2 = Math.abs(d2.toEpochSecond() - now);
                    return Long.compare(diff1, diff2);
                }
            });

            DateTimeFormatter backupDateFormatter = DateTimeFormatter.ofPattern(intl("next-schedule-backup-format"), config.advanced.dateLanguage);
            nextBackupMessage = intl("next-schedule-backup").replaceAll("%DATE", nextBackupDate.format(backupDateFormatter));
        } else if (config.backupStorage.delay != -1) {
            nextBackupMessage = intl("next-backup").replaceAll("%TIME", String.valueOf(LocalDateTime.now().until(nextIntervalBackupTime, ChronoUnit.MINUTES)));
        } else {
            nextBackupMessage = intl("auto-backups-disabled");
        }

        return nextBackupMessage;
    }

    /**
     * Sets the time of the next interval-based backup to the current time + the configured interval
     */
    public static void updateNextIntervalBackupTime() {
        nextIntervalBackupTime = LocalDateTime.now().plus(ConfigParser.getConfig().backupStorage.delay, ChronoUnit.MINUTES);
    }

    /**
     * Gets the socket address (ipaddress/hostname:port) of an external backup server based on the specified settings
     * @param externalBackup the external backup settings
     * @return the socket address
     */
    private static String getSocketAddress(ExternalBackupSource externalBackup) {
        return externalBackup.hostname + "-" + externalBackup.port;
    }

    /**
     * Generates the name for a folder based on the specified external backup settings to be stored within the external-backups temporary folder
     * @param externalBackup the external backup settings
     * @return the folder name
     */
    private static String getTempFolderName(ExternalBackupSource externalBackup) {
        if (externalBackup instanceof ExternalFTPSource) {
            return "ftp-" + getSocketAddress(externalBackup);
        } else {
            return "mysql-" + getSocketAddress(externalBackup);
        }
    }
}
