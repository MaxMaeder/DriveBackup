package ratismal.drivebackup.util;

import ratismal.drivebackup.config.Config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Created by Ratismal on 2016-01-20.
 */

public class FileUtil {
    private static final TreeMap<Date, File> backupList = new TreeMap<>();
    private static final List<String> fileList = new ArrayList<>();
    private static ArrayList<HashMap<String, Object>> blacklist = new ArrayList<>();
    private static int backupFiles = 0;

    /**
     * Gets the most recent backup of the specified backup type
     * @param type the type of back up (world, plugin, etc)
     * @param format the format of the file name
     * @return The file to upload
     */
    public static File getNewestBackup(String type, String format) {
        type = type.replace(".." + File.separator, "");

        backupList.clear();
        String path = new File(Config.getDir()).getAbsolutePath() + File.separator + type;
        File[] files = new File(path).listFiles();

        for (File file : files) {
            if (file.getName().endsWith(".zip")) {

                String dateString = file.getName();
                DateFormat dateFormat = new SimpleDateFormat(format, new Locale(Config.getDateLanguage()));

                try {
                    Date date = dateFormat.parse(dateString);
                    backupList.put(date, file);
                } catch (Exception e) {
                    MessageUtil.sendConsoleException(e);
                }
            }
        }

        return backupList.descendingMap().firstEntry().getValue();
    }

    /**
     * Creates a local backup zip file for the specified backup type
     * @param type what to back up (world, plugin, etc)
     * @param formatString the format of the file name
     * @param blacklistGlobs a list of glob patterns of files/folders to not include in the backup
     * @throws Exception
     */
    public static void makeBackup(String type, String formatString, List<String> blacklistGlobs) throws Exception {
        if (type.charAt(0) == File.separatorChar) {
            throw new IllegalArgumentException(); 
        }

        fileList.clear();

        ZonedDateTime now = ZonedDateTime.now(Config.getBackupScheduleTimezone());
        String fileName = now.format(DateTimeFormatter.ofPattern(formatString, new Locale(Config.getDateLanguage())));

        blacklist.clear();
        backupFiles = 0;
        for (String blacklistGlob : blacklistGlobs) {
            HashMap<String, Object> blacklistEntry = new HashMap<>();

            blacklistEntry.put("globPattern", blacklistGlob);
            blacklistEntry.put("pathMatcher", FileSystems.getDefault().getPathMatcher("glob:" + blacklistGlob));
            blacklistEntry.put("blacklistedFiles", 0);

            blacklist.add(blacklistEntry);
        }

        String subfolderName = type;
        if (isBaseFolder(subfolderName)) {
            subfolderName = "root";
        }

        File path = new File((Config.getDir() + File.separator + subfolderName).replace(".." + File.separator, "")); // Keeps working directory inside backups folder
        if (!path.exists()) {
            path.mkdirs();
        }

        generateFileList(type);

        for (HashMap<String, Object> blacklistEntry : blacklist) {
            String globPattern = (String) blacklistEntry.get("globPattern");
            int blacklistedFiles = (int) blacklistEntry.get("blacklistedFiles");

            if (blacklistedFiles > 0) {
                MessageUtil.sendConsoleMessage("Didn't include " + blacklistedFiles + " file(s) in the backup, as they are blacklisted by \"" + globPattern + "\"");
            }
        }

        if (backupFiles > 0) {
            MessageUtil.sendConsoleMessage("Didn't include " + backupFiles + " file(s) in the backup, as they are in the folder used for backups");
        }

        zipIt(type, path.getPath() + File.separator + fileName);
    }

    /**
     * Deletes the oldest files in the specified folder past the number to retain locally
     * <p>
     * The number of files to retain locally is specified by the user in the {@code config.yml}
     * @param type the type of file (ex. plugins, world)
     * @param formatString the format of the files name
     * @throws IOException
     */
    public static void deleteFiles(String type, String formatString) throws IOException {
        type = type.replace(".." + File.separator, "");

        if (Config.getLocalKeepCount() != -1) {
            try {
                getNewestBackup(type, formatString);

                if (backupList.size() > Config.getLocalKeepCount()) {
                    MessageUtil.sendConsoleMessage("There are " + backupList.size() + " file(s) which exceeds the local limit of " + Config.getLocalKeepCount() + ", deleting oldest");
                }
                

                while (backupList.size() > Config.getLocalKeepCount()) {
                    File fileToDelete = backupList.descendingMap().lastEntry().getValue();
                    Date dateOfFile = backupList.descendingMap().lastKey();

                    if (!fileToDelete.delete()) {
                        MessageUtil.sendConsoleMessage("Failed to delete local backup \"" + backupList.descendingMap().lastEntry().getValue().getName() + "\"");
                    }
                    
                    backupList.remove(dateOfFile);
                }
            } catch (Exception e) {
                MessageUtil.sendConsoleException(e);
                MessageUtil.sendConsoleMessage("Local backup deletion failed");
            }
        }
    }

    /**
     * Zips files in the specified folder into the specified file location
     * @param inputFolderPath the path of the zip file to create
     * @param outputFilePath the path of the folder to put it in
     */
    private static void zipIt(String inputFolderPath, String outputFilePath) throws Exception {
        byte[] buffer = new byte[1024];
        FileOutputStream fileOutputStream;
        ZipOutputStream zipOutputStream = null;

        String formattedInputFolderPath = new File(inputFolderPath).getName();
        if (isBaseFolder(inputFolderPath)) {
            formattedInputFolderPath = "root";
        }

        try {
            fileOutputStream = new FileOutputStream(outputFilePath);
            zipOutputStream = new ZipOutputStream(fileOutputStream);
            zipOutputStream.setLevel(Config.getZipCompression());

            for (String file : fileList) {
                zipOutputStream.putNextEntry(new ZipEntry(formattedInputFolderPath + File.separator + file));

                try (FileInputStream fileInputStream = new FileInputStream(inputFolderPath + File.separator + file)) {
                    
                    int len;
                    while ((len = fileInputStream.read(buffer)) > 0) {
                        zipOutputStream.write(buffer, 0, len);
                    }
                } catch (Exception e) {
                    MessageUtil.sendConsoleMessage("Failed to include \"" + new File(inputFolderPath + File.separator + file).getPath() + "\" in the backup, is it locked?");
                }

                zipOutputStream.closeEntry();
            }
            
            zipOutputStream.close();
        } catch (Exception exception) {
            if (zipOutputStream != null) {
                zipOutputStream.close();
            }

            throw exception; 
        }
    }

    /**
     * Generates a list of files to put in the zip created from the specified folder
     * @param inputFolderPath The path of the folder to create the zip from
     * @throws Exception
     */
    private static void generateFileList(String inputFolderPath) throws Exception {
        generateFileList(new File(inputFolderPath), inputFolderPath);
    }

    /**
     * Adds the specified file or folder to the generated list of files to put in the zip created from the specified folder
     * @param file the file or folder to add
     * @param inputFolderPath the path of the folder to create the zip from
     * @throws Exception
     */
    private static void generateFileList(File file, String inputFolderPath) throws Exception {

        if (file.isFile()) {
            if (file.getCanonicalPath().startsWith(new File(Config.getDir()).getCanonicalPath())) {
                backupFiles++;

                return;
            }

            for (HashMap<String, Object> blacklistEntry : blacklist) {
                PathMatcher pathMatcher = (PathMatcher) blacklistEntry.get("pathMatcher");
                int blacklistedFiles = (int) blacklistEntry.get("blacklistedFiles");

                if (pathMatcher.matches(Paths.get(getFileRelativePath(file.toString(), inputFolderPath)))) {
                    blacklistEntry.put("blacklistedFiles", ++blacklistedFiles);

                    return;
                }
            }

            fileList.add(getFileRelativePath(file.toString(), inputFolderPath));
        }

        if (file.isDirectory()) {
            for (String filename : file.list()) {
                generateFileList(new File(file, filename), inputFolderPath);
            }
        }
    }

    /**
     * Finds all folders that match a glob
     * @param glob the glob to search
     * @param rootPath the path to start searching from
     * @return List of all folders that match this glob under rootPath
     */
    public static List<Path> generateGlobFolderList(String glob, String rootPath) {
        PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:./" + glob);
        List<Path> list = new ArrayList<Path>();
        try (Stream<Path> walk = Files.walk(Paths.get(rootPath))) {
            list = walk.filter(pathMatcher::matches).filter(Files::isDirectory).collect(Collectors.toList());
        } catch (IOException e) {
            //TODO: log exeption somewhere
            MessageUtil.sendConsoleMessage(e.toString());
            return list;
        }
        return list;
    }

    /**
     * Gets the path of the specifed file relative to the specifed folder
     * @param file the file
     * @param baseFolderPath the absolute path of the folder
     */
    private static String getFileRelativePath(File file, String baseFolderPath) {
        return file.getAbsolutePath().replaceFirst(Pattern.quote(baseFolderPath + File.separator), "");
    }

    /**
     * Gets the path of the specifed file relative to the specifed folder
     * <p>
     * Both paths mush be relative or both must be absolute
     * @param file the file's path
     * @param baseFolderPath the path of the folder
     */
    private static String getFileRelativePath(String filePath, String baseFolderPath) {
        return filePath.replaceFirst(Pattern.quote(baseFolderPath + File.separator), "");
    }

    /**
     * Whether the specified folder is the base folder of the Minecraft server
     * <p>
     * In other words, whether the folder is the folder containing the server jar
     * @param folderPath the path of the folder
     * @return whether the folder is the base folder
     * @throws Exception
     */
    public static boolean isBaseFolder(String folderPath) throws Exception {
        return new File(folderPath).getPath().equals(".");
    }
}
