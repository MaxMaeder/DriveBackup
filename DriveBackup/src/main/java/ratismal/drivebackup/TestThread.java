package ratismal.drivebackup;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Random;

import org.bukkit.command.CommandSender;

import ratismal.drivebackup.uploaders.Uploader;
import ratismal.drivebackup.uploaders.dropbox.DropboxUploader;
import ratismal.drivebackup.uploaders.ftp.FTPUploader;
import ratismal.drivebackup.uploaders.googledrive.GoogleDriveUploader;
import ratismal.drivebackup.uploaders.onedrive.OneDriveUploader;
import ratismal.drivebackup.config.ConfigParser;
import ratismal.drivebackup.config.ConfigParser.Config;
import ratismal.drivebackup.util.MessageUtil;

import static ratismal.drivebackup.config.Localization.intl;

public class TestThread implements Runnable {
    private CommandSender initiator;
    private String[] args;

    /**
     * Creates an instance of the {@code TestThread} object
     * @param initiator the player who initiated the test
     * @param args any arguments that followed the command that initiated the test
     */
    public TestThread(CommandSender initiator, String[] args) {
        this.initiator = initiator;
        this.args = args;
    }

    /**
     * Starts a test of a backup method
     */
    @Override
    public void run() {

        /**
         * Arguments:
         * 0) The backup method to test
         * 1) The name of the test file to upload during the test
         * 2) The size (in bytes) of the file
         */

        if (args.length < 2) {
            MessageUtil.Builder().mmText(intl("test-method-not-specified")).to(initiator).toConsole(false).send();

            return;
        }

        String testFileName;
        if (args.length > 2) {
            testFileName = args[2];
        } else {
            testFileName = "testfile.txt";
        }

        int testFileSize;
        try {
            testFileSize = Integer.parseInt(args[2]);
        } catch (Exception exception) {
            testFileSize = 1000;
        }

        try {
            testUploadMethod(testFileName, testFileSize, args[1]);
        } catch (Exception exception) {
            MessageUtil.Builder()
                .mmText(intl("test-method-invalid"), "specified-method", args[1])
                .to(initiator)
                .toConsole(false)
                .send();
        }
    }

    /**
     * Tests a specific upload method
     * @param testFileName name of the test file to upload during the test
     * @param testFileSize the size (in bytes) of the file
     * @param method name of the upload method to test
     */
    private void testUploadMethod(String testFileName, int testFileSize, String method) throws Exception {
        Config config = ConfigParser.getConfig();
        Uploader uploadMethod = null;
        
        switch (method) {
            case "googledrive":
                if (config.backupMethods.googleDrive.enabled) {
                    uploadMethod = new GoogleDriveUploader();
                } else {
                    sendMethodDisabled(initiator, GoogleDriveUploader.UPLOADER_NAME);
                    return;
                }
                break;
            case "onedrive":
                if (config.backupMethods.oneDrive.enabled) {
                    uploadMethod = new OneDriveUploader();
                } else {
                    sendMethodDisabled(initiator, OneDriveUploader.UPLOADER_NAME);
                    return;
                }
                break;
            case "dropbox":
                if (config.backupMethods.dropbox.enabled) {
                    uploadMethod = new DropboxUploader();
                } else {
                    sendMethodDisabled(initiator, DropboxUploader.UPLOADER_NAME);
                    return;
                }
                break;
            case "ftp":
                if (config.backupMethods.ftp.enabled) {
                    uploadMethod = new FTPUploader();
                } else {
                    sendMethodDisabled(initiator, FTPUploader.UPLOADER_NAME);
                    return;
                }
                break;
            default:
                throw new Exception();
        }

        MessageUtil.Builder().mmText(intl("test-method-begin"), "upload-method", uploadMethod.getName()).to(initiator).toConsole(false).send();

        String localTestFilePath = config.backupStorage.localDirectory + File.separator + testFileName;
        new File(config.backupStorage.localDirectory).mkdirs();

        try (FileOutputStream fos = new FileOutputStream(localTestFilePath)) {
            Random byteGenerator = new Random();
            
            byte[] randomBytes = new byte[testFileSize];
            byteGenerator.nextBytes(randomBytes);

            fos.write(randomBytes);
            fos.flush();
        } catch (Exception exception) {
            MessageUtil.Builder().mmText(intl("test-file-creation-failed")).to(initiator).toConsole(false).send();
            MessageUtil.sendConsoleException(exception);
        }

        File testFile = new File(localTestFilePath);
        
        uploadMethod.test(testFile);

        // TODO: network catch?

        if (uploadMethod.isErrorWhileUploading()) {
            MessageUtil.Builder().mmText(intl("test-method-failed"), "upload-method", uploadMethod.getName()).to(initiator).toConsole(false).send();
        } else {
            MessageUtil.Builder().mmText(intl("test-method-successful"), "upload-method", uploadMethod.getName()).to(initiator).toConsole(false).send();
        }
        
        testFile.delete();
    }

    private void sendMethodDisabled(CommandSender initiator, String methodName) {
        MessageUtil.Builder()
            .mmText(intl("test-method-not-enabled"), "upload-method", methodName)
            .to(initiator)
            .toConsole(false)
            .send();
    }
}