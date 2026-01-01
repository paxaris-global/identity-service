package com.paxaris.identity_service.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class ProvisioningService {

    private final String githubToken;
    private final String githubOrg;

    public ProvisioningService(
            @Value("${github.token}") String githubToken,
            @Value("${github.org}") String githubOrg
    ) {
        if (githubToken == null || githubToken.isBlank()) {
            throw new IllegalStateException("GITHUB_TOKEN is missing");
        }
        if (githubOrg == null || githubOrg.isBlank()) {
            throw new IllegalStateException("GITHUB_ORG is missing");
        }
        this.githubToken = githubToken;
        this.githubOrg = githubOrg;
    }

    public void provisionRepoAndPushZip(
            String realmName,
            String clientId,
            MultipartFile zipFile
    ) {

        String repoName = realmName + "-" + clientId;

        try {
            createGitHubRepo(repoName);
            Path tempDir = unzip(zipFile);
            gitInitAddCommitPush(tempDir, repoName);
            deleteDirectory(tempDir);
        } catch (Exception e) {
            throw new RuntimeException("Provisioning failed: " + e.getMessage(), e);
        }
    }

    // ===============================
    // GitHub Repo Creation
    // ===============================
    private void createGitHubRepo(String repoName) throws IOException {
        String apiUrl = "https://api.github.com/orgs/" + githubOrg + "/repos";
        String payload = """
            {
              "name": "%s",
              "private": true
            }
            """.formatted(repoName);

        HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "token " + githubToken);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.getBytes());
        }

        if (conn.getResponseCode() != 201) {
            throw new IOException(
                    "GitHub repo creation failed: " +
                            new String(conn.getErrorStream().readAllBytes())
            );
        }
    }

    // ===============================
    // Unzip
    // ===============================
    private Path unzip(MultipartFile zipFile) throws IOException {
        Path tempDir = Files.createTempDirectory("repo-");

        try (ZipInputStream zis = new ZipInputStream(zipFile.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path path = tempDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(path);
                } else {
                    Files.createDirectories(path.getParent());
                    Files.copy(zis, path, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
        return tempDir;
    }

    // ===============================
    // Git Init + Push
    // ===============================
    private void gitInitAddCommitPush(Path repoDir, String repoName)
            throws IOException, InterruptedException {

        run(repoDir, "git", "init");
        run(repoDir, "git", "config", "user.name", "Paxaris CI");
        run(repoDir, "git", "config", "user.email", "ci@paxaris.com");
        run(repoDir, "git", "branch", "-M", "main");

        run(repoDir, "git", "remote", "add", "origin",
                "https://github.com/" + githubOrg + "/" + repoName + ".git");

        run(repoDir, "git", "add", ".");
        run(repoDir, "git", "commit", "-m", "Initial commit");

        run(repoDir, "git", "push",
                "https://x-access-token:" + githubToken +
                        "@github.com/" + githubOrg + "/" + repoName + ".git",
                "main");
    }

    private void run(Path dir, String... cmd)
            throws IOException, InterruptedException {

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);

        Process p = pb.start();
        p.waitFor();

        if (p.exitValue() != 0) {
            throw new IOException("Command failed: " + String.join(" ", cmd));
        }
    }

    private void deleteDirectory(Path path) throws IOException {
        Files.walk(path)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException ignored) {}
                });
    }
}
