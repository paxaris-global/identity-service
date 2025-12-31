package com.paxaris.identity_service.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import java.net.HttpURLConnection;
import java.net.URL;

public class ProvisioningService {

    private final String githubToken;
    private final String githubOrg;

    public ProvisioningService(String githubToken, String githubOrg) {
        this.githubToken = githubToken;
        this.githubOrg = githubOrg;
    }

    public void provisionRepoAndPushZip(String realmName, String clientId, MultipartFile zipFile) {
        String repoName = realmName + "-" + clientId;

        try {
            createGitHubRepo(repoName);

            Path tempDir = unzip(zipFile);

            gitInitAddCommitPush(tempDir, repoName);

            // Cleanup temp directory
            deleteDirectory(tempDir.toFile());

        } catch (Exception e) {
            throw new RuntimeException("Provisioning failed: " + e.getMessage(), e);
        }
    }

    private void createGitHubRepo(String repoName) throws IOException {
        String url = "https://api.github.com/orgs/" + githubOrg + "/repos";
        String payload = "{\"name\":\"" + repoName + "\",\"private\":true}";

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "token " + githubToken);
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.getBytes());
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 201) {
            String errorMsg = new String(conn.getErrorStream().readAllBytes());
            throw new IOException("GitHub repo creation failed (" + responseCode + "): " + errorMsg);
        }
    }

    private Path unzip(MultipartFile zipFile) throws IOException {
        Path tempDir = Files.createTempDirectory("repo-source-");

        try (ZipInputStream zis = new ZipInputStream(zipFile.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path filePath = tempDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(filePath);
                } else {
                    Files.createDirectories(filePath.getParent());
                    try (OutputStream fos = Files.newOutputStream(filePath)) {
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }

        return tempDir;
    }

    private void gitInitAddCommitPush(Path repoDir, String repoName) throws IOException, InterruptedException {
        String repoUrl = "https://github.com/" + githubOrg + "/" + repoName + ".git";
        String authRepoUrl = "https://" + githubToken + "@github.com/" + githubOrg + "/" + repoName + ".git";

        runCommand(repoDir, "git", "init");
        runCommand(repoDir, "git", "remote", "add", "origin", repoUrl);
        runCommand(repoDir, "git", "add", ".");
        runCommand(repoDir, "git", "commit", "-m", "Initial commit");
        runCommand(repoDir, "git", "push", "-u", authRepoUrl, "master"); // or "main" depending on default branch
    }

    private void runCommand(Path workingDir, String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("[git] " + line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Command failed: " + String.join(" ", command));
        }
    }

    private void deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirectory(child);
                }
            }
        }
        dir.delete();
    }
}
