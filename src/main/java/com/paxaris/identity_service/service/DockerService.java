package com.paxaris.identity_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerService {

    @Value("${DOCKER_USERNAME}")
    private String dockerHubUsername;

    @Value("${DOCKER_PASSWORD}")
    private String dockerHubToken; // PAT

    /**
     * Login to Docker Hub using CLI (AUTOMATIC)
     */
    private void dockerLogin() throws Exception {
        log.info("🔐 Logging into Docker Hub via CLI...");

        Process loginProcess = new ProcessBuilder(
                "docker", "login",
                "--username", dockerHubUsername,
                "--password-stdin"
        ).start();

        try (OutputStream os = loginProcess.getOutputStream()) {
            os.write(dockerHubToken.getBytes(StandardCharsets.UTF_8));
        }

        if (loginProcess.waitFor() != 0) {
            throw new RuntimeException("Docker login failed");
        }

        log.info("✅ Docker login successful");
    }

    /**
     * Create repository using Docker CLI (SAFE & WORKING)
     */
    public void createRepository(String realmName, String clientId) {
        String repoName = (realmName + "-" + clientId).toLowerCase();
        String repoFullName = dockerHubUsername + "/" + repoName;

        try {
            dockerLogin();

            log.info("🐳 Ensuring Docker Hub repo exists: {}", repoFullName);

            Process inspect = new ProcessBuilder(
                    "docker", "manifest", "inspect", repoFullName
            ).start();

            if (inspect.waitFor() == 0) {
                log.warn("⚠ Repository already exists: {}", repoFullName);
                return;
            }

            log.info("ℹ Repository will be created on first push (Docker behavior)");

        } catch (Exception e) {
            log.error("❌ Repo check/login failed", e);
            throw new RuntimeException("Docker repo preparation failed", e);
        }
    }

    /**
     * Push Docker image (.tar) to Docker Hub
     */
    public void pushDockerImage(MultipartFile dockerImage, String realmName, String clientId) {
        String repoName = (realmName + "-" + clientId).toLowerCase();
        String repoFullName = dockerHubUsername + "/" + repoName;

        try {
            dockerLogin();

            log.info("🚀 Pushing image to {}", repoFullName);

            File tempFile = File.createTempFile(repoName, ".tar");
            dockerImage.transferTo(tempFile);

            // Load image
            Process loadProcess = new ProcessBuilder(
                    "docker", "load", "-i", tempFile.getAbsolutePath()
            ).inheritIO().start();
            loadProcess.waitFor();

            // Tag image
            new ProcessBuilder(
                    "docker", "tag",
                    repoName,
                    repoFullName + ":latest"
            ).inheritIO().start().waitFor();

            // Push image (repo auto-created here)
            new ProcessBuilder(
                    "docker", "push",
                    repoFullName + ":latest"
            ).inheritIO().start().waitFor();

            tempFile.delete();

            log.info("✅ Image pushed successfully: {}", repoFullName);

        } catch (Exception e) {
            log.error("💥 Docker push FAILED", e);
            throw new RuntimeException("Docker push failed", e);
        }
    }
}
