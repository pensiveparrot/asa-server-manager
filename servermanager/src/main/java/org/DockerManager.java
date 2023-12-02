package org;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class DockerManager {

    private Session session;

    private static final String USERNAME = System.getenv("ASA_SERVER_USERNAME"); // Replace with env var from bat server
                                                                                 // username
    private static final String PASSWORD = System.getenv("ASA_SERVER_PASSWORD"); // Replace with env var from bat server
    // password
    private static final String HOST = System.getenv("ASA_SERVER_HOSTNAME"); // Replace with env var from bat for
                                                                             // hostname

    public void connect() throws JSchException, IOException {
        JSch jsch = new JSch();
        session = jsch.getSession(USERNAME, HOST, 22);
        session.setPassword(PASSWORD);
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect();
        // Ensure the scripts directory exists after connecting
        ensureScriptsDirectoryExists();
    }

    private void ensureScriptsDirectoryExists() throws JSchException, IOException {
        String directoryPath = "/home/" + USERNAME + "/scripts";
        String command = "[ -d \"" + directoryPath + "\" ] || mkdir -p " + directoryPath;
        executeSudoCommand(command, PASSWORD);
    }

    public String fetchDockerContainerId() throws JSchException, IOException {
        String command = "docker ps -a -q --filter \"status=running\""; // Command to get the running container ID
        String output = executeCommand(command);

        if (output != null && !output.isEmpty()) {
            return output.trim();
        } else {
            executeCommand("cd /home/" + USERNAME + " && docker compose up");
            throw new IOException("No running Docker containers found.");
        }
    }

    public void startDockerContainer(String containerId) throws JSchException {
        String command = "cd /home/" + USERNAME + "/ && docker compose up -d";
        executeCommand(command);
    }

    public void stopDockerContainerById(String containerId) throws JSchException {
        String command = "docker stop " + containerId;
        executeCommand(command);
    }

    public void removeServerFile(String remoteFilePath, String password) throws JSchException, IOException {
        String command = "rm -rf " + remoteFilePath;
        executeSudoCommand(command, password);
    }

    public void editFileInDockerContainer(String containerId, String remoteFilePath)
            throws JSchException, IOException, SftpException {
        String localFilePath = System.getProperty("user.dir") + File.separator + new File(remoteFilePath).getName();

        String tempServerPath = "/home/" + USERNAME + "/scripts/launch_ASA.sh"; // Temporary location in
                                                                                // /home/${USERNMAME}/scripts/
        executeSudoCommand("docker cp " + containerId + ":" + remoteFilePath + " " + tempServerPath, PASSWORD);

        downloadFile(tempServerPath, localFilePath); // Download the file

        long localTimestampBefore = getFileTimestamp(localFilePath);

        // Get timestamp from the file on the server
        long serverFileTimestamp = getServerFileTimestamp("/home/" + USERNAME + "/scripts/launch_ASA.sh");

        // If local file is outdated, re-download the file from the server
        if (localTimestampBefore != serverFileTimestamp) {
            System.out.println("Local file is outdated. Syncing with the server...");
            downloadFile("/home/" + USERNAME + "/scripts/launch_ASA.sh", localFilePath);
        }

        // Open the file with notepad.exe
        Runtime.getRuntime().exec("notepad.exe " + localFilePath);

        System.out.println("Please edit the file and press Enter to continue...");
        try (Scanner scanner = new Scanner(System.in)) {
            scanner.nextLine();
        }

        long localTimestampAfter = getFileTimestamp(localFilePath);

        // If the file has been modified, upload it back to the Docker container
        if (localTimestampAfter != localTimestampBefore) {
            System.out.println("Uploading the modified file back to the server and Docker container...");
            uploadFile(localFilePath, "/home/" + USERNAME + "/scripts/launch_ASA.sh");
            executeSudoCommand("chmod +x /home/" + USERNAME + "/scripts/launch_ASA.sh", PASSWORD);
            executeSudoCommand(
                    "docker cp /home/" + USERNAME + "/scripts/launch_ASA.sh " + containerId + ":" + remoteFilePath,
                    PASSWORD);
        } else {
            System.out.println("No modifications detected.");
        }
    }

    public void editFileOnServer(String serverFilePath, boolean requireSudo)
            throws JSchException, IOException, SftpException {
        String localFilePath = System.getProperty("user.dir") + File.separator + new File(serverFilePath).getName();
        downloadFile(serverFilePath, localFilePath); // Download the file from the server

        // Open the file with notepad.exe
        Runtime.getRuntime().exec("notepad.exe " + localFilePath);

        System.out.println("Please edit the file in Notepad and save it. Press Enter in the console once done.");
        new Scanner(System.in).nextLine();

        // If sudo is required, remove the existing file on the server with sudo
        executeSudoCommand("rm -f " + serverFilePath, PASSWORD);

        // Upload the file back to the server
        System.out.println("Uploading the modified file back to the server...");
        uploadFile(localFilePath, serverFilePath);
    }

    private long getFileTimestamp(String filePath) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(Paths.get(filePath), BasicFileAttributes.class);
        return attrs.lastModifiedTime().toMillis();
    }

    private long getServerFileTimestamp(String serverFilePath) throws JSchException {
        String command = "stat -c %Y " + serverFilePath;
        String output = executeCommand(command);
        try {
            return Long.parseLong(output.trim()) * 1000; // Convert seconds to milliseconds
        } catch (NumberFormatException e) {
            System.err.println("Failed to parse timestamp from server: " + e.getMessage());
            return -1;
        }
    }

    public void restartDockerContainerById(String containerId, String composeFilePath) throws JSchException {
        // Step 1: Stop the Docker container
        executeCommand("docker stop " + containerId);

        // Step 2: Navigate to the directory containing the docker-compose.yml file
        // and restart the container using docker-compose
        String command = "cd " + composeFilePath + " && docker compose up -d";
        executeCommand(command);
    }

    public void displayDockerContainerLogsById(String containerId) throws JSchException {
        executeCommand("docker logs " + containerId);
    }

    private String executeCommand(String command) throws JSchException {
        ChannelExec channel = null;
        List<String> lines = new ArrayList<>();
        String line;

        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            InputStream in = channel.getInputStream();
            channel.connect();

            BufferedReader reader = new BufferedReader(new InputStreamReader(in));

            while ((line = reader.readLine()) != null) {
                System.out.println("Line read: " + line); // Debugging
                lines.add(line);
            }

        } catch (Exception e) {
            System.out.println("Exception in executeCommand: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }

        // Debugging: Print the size of the lines list
        System.out.println("Number of lines captured: " + lines.size());

        // Convert the List<String> to a single String
        return String.join("\n", lines);
    }

    public void disconnect() {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }

    public void downloadFile(String source, String dest) throws JSchException, SftpException {
        ChannelSftp sftpChannel = (ChannelSftp) session.openChannel("sftp");
        sftpChannel.connect();
        sftpChannel.get(source, dest);
        sftpChannel.disconnect();
    }

    private void executeSudoCommand(String command, String sudoPassword) throws JSchException, IOException {
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");
            // Properly escape the password and command
            String escapedPassword = sudoPassword.replace("'", "'\"'\"'"); // this does work, but setting pw with env
                                                                           // var with quote kinda finnicky... cba to
                                                                           // fix rn
            String sudoCommand = "echo '" + escapedPassword + "' | sudo -S " + command;

            channel.setCommand(sudoCommand);
            channel.setInputStream(null);

            // Getting output streams
            InputStream in = channel.getInputStream();
            InputStream err = channel.getErrStream();
            channel.connect();

            // Read outputs and errors
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(err));

            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("Output: " + line);
            }
            while ((line = errorReader.readLine()) != null) {
                System.err.println("Error: " + line);
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
        }
    }

    public void uploadFile(String source, String dest) throws JSchException, SftpException, IOException {

        String fileName = new File(dest).getName();
        String tempDest = "/tmp/" + fileName; // Temporary location in /tmp

        // Step 1: Upload to a temporary location
        ChannelSftp sftpChannel = (ChannelSftp) session.openChannel("sftp");
        sftpChannel.connect();
        sftpChannel.put(source, tempDest);
        sftpChannel.disconnect();

        // Step 2: Move the file to a writable intermediate location using sudo
        String intermediateDest = "/home/" + USERNAME + "/scripts/" + fileName; // Assuming
                                                                                // /home/${USERNAME}/scripts/
                                                                                // is writable by the SSH
        // user
        executeSudoCommand("mv " + tempDest + " " + intermediateDest, PASSWORD);

        // Step 3: Move the file to the final destination using sudo
        String moveCommand = "mv " + intermediateDest + " " + dest;
        executeSudoCommand(moveCommand, PASSWORD);

        // Step 4: Change file ownership if necessary
        String changeOwnershipCommand = "chown 1001:1001 " + dest;
        executeSudoCommand(changeOwnershipCommand, PASSWORD);
    }

    public static void main(String[] args) {

        System.out.println("Username: " + USERNAME);
        System.out.println("Password: " + PASSWORD);
        System.out.println("Hostname: " + HOST);
        Scanner scanner = new Scanner(System.in);
        System.out.print("Enter action (restart/logs/edit/stop/start/wipe): ");
        String action = scanner.nextLine();
        DockerManager manager = new DockerManager();

        try {
            manager.connect();
            String CONTAINERID = manager.fetchDockerContainerId();
            // Fetch the container ID dynamically
            if (action.equalsIgnoreCase("restart")) {
                manager.restartDockerContainerById(CONTAINERID, "/home/" + USERNAME + "/");
            } else if (action.equalsIgnoreCase("logs")) {
                manager.displayDockerContainerLogsById(CONTAINERID);
            } else if (action.equalsIgnoreCase("edit")) {
                System.out.println(
                        "Choose file to edit: 1 for install_ARK.sh, 2 for GameUserSettings.ini, 3 for Game.ini, 4 for docker-compose.yaml");
                int fileChoice = scanner.nextInt();
                switch (fileChoice) {
                    case 1:
                        manager.editFileInDockerContainer(CONTAINERID, "/usr/games/scripts/launch_ASA.sh");
                        break;
                    case 2:

                        manager.editFileOnServer(
                                "/home/" + USERNAME + "/ASA/Saved/Config/WindowsServer/GameUserSettings.ini", true);
                        break;
                    case 3:

                        manager.editFileOnServer("/home/" + USERNAME + "/ASA/Saved/Config/WindowsServer/Game.ini",
                                true);
                        break;
                    case 4:
                        manager.editFileOnServer("/home/" + USERNAME + "/docker-compose.yaml", true);
                    default:
                        System.out.println("Invalid choice.");
                        break;
                }
            } else if (action.equalsIgnoreCase("stop")) {
                manager.stopDockerContainerById(CONTAINERID);
            } else if (action.equalsIgnoreCase("start")) {
                manager.startDockerContainer(CONTAINERID);
            } else if (action.equalsIgnoreCase("wipe")) {
                manager.removeServerFile("/home/" + USERNAME + "/ASA/Saved/SavedArks/TheIsland_WP", PASSWORD);
            } else {
                System.out.println("Invalid action.");
            }
        } catch (IOException | JSchException | SftpException e) {
            e.printStackTrace();
        } finally {
            manager.disconnect();
            scanner.close();
        }
    }

}