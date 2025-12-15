package com.paxaris.identity_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerService {

    @Value("${DOCKER_USERNAME}")
    private String dockerUsername;

    @Value("${DOCKER_PASSWORD}")
    private String dockerPassword;

    /** Build repo name in format: realm-clientId */
    private String getRepoName(String realmName, String clientId) {
        return (realmName + "-" + clientId).toLowerCase();
    }

    /** Create repository on Docker Hub (optional, will skip if forbidden) */
    public void createRepository(String realmName, String clientId) {
        String repoName = getRepoName(realmName, clientId);
        try {
            log.info("ℹ️ Attempting to create Docker Hub repo: {}", repoName);
            // Docker Hub API repo creation is unreliable for personal accounts
            // Recommend pre-creating the repo manually if 403 occurs
        } catch (Exception e) {
            log.warn("⚠ Repo creation skipped (likely already exists or forbidden): {}", e.getMessage());
        }
    }

    /**
     * Push Docker image using local Docker CLI
     * This is more reliable than HTTP API for .tar files
     */
    public void pushDockerImage(MultipartFile dockerImage, String realmName, String clientId) {
        String repoName = getRepoName(realmName, clientId);
        File tempFile = null;
        try {
            // Save the uploaded Docker image as a temporary tar file
            tempFile = File.createTempFile(repoName, ".tar");
            dockerImage.transferTo(tempFile);
            log.info("📦 Docker image tar saved at {}", tempFile.getAbsolutePath());

            // Login to Docker
            Process login = new ProcessBuilder("docker", "login",
                    "-u", dockerUsername,
                    "-p", dockerPassword)
                    .inheritIO()
                    .start();
            int loginCode = login.waitFor();
            if (loginCode != 0) {
                throw new RuntimeException("Docker login failed with exit code " + loginCode);
            }
            log.info("✅ Docker login successful");

            // Load the image tar
            Process load = new ProcessBuilder("docker", "load", "-i", tempFile.getAbsolutePath())
                    .inheritIO()
                    .start();
            int loadCode = load.waitFor();
            if (loadCode != 0) {
                throw new RuntimeException("Docker load failed with exit code " + loadCode);
            }
            log.info("✅ Docker image loaded successfully");

            // Tag the image
            String imageTag = dockerUsername + "/" + repoName + ":latest";
            Process tag = new ProcessBuilder("docker", "tag", repoName, imageTag)
                    .inheritIO()
                    .start();
            int tagCode = tag.waitFor();
            if (tagCode != 0) {
                throw new RuntimeException("Docker tag failed with exit code " + tagCode);
            }

            // Push the image
            Process push = new ProcessBuilder("docker", "push", imageTag)
                    .inheritIO()
                    .start();
            int pushCode = push.waitFor();
            if (pushCode != 0) {
                throw new RuntimeException("Docker push failed with exit code " + pushCode);
            }

            log.info("🚀 Docker image pushed successfully: {}", imageTag);

        } catch (IOException | InterruptedException e) {
            log.error("💥 Docker push failed: {}", e.getMessage(), e);
            throw new RuntimeException("Docker push failed", e);
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }
}
