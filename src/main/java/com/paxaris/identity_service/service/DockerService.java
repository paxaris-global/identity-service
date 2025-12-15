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

    /** Save uploaded Docker image as a temporary tar file */
    public File saveDockerImage(MultipartFile dockerImage, String realmName, String clientId) {
        try {
            String repoName = getRepoName(realmName, clientId);
            File tempFile = File.createTempFile(repoName, ".tar");
            dockerImage.transferTo(tempFile);
            log.info("📦 Docker image tar saved at {}", tempFile.getAbsolutePath());
            return tempFile;
        } catch (IOException e) {
            log.error("💥 Failed to save Docker image tar: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to save Docker image tar", e);
        }
    }

    /** Push Docker image using local Docker CLI from a File */
    public void pushDockerImage(File dockerTar, String realmName, String clientId) {
        String repoName = getRepoName(realmName, clientId);

        try {
            // Docker login
            Process login = new ProcessBuilder("docker", "login",
                    "-u", dockerUsername, "-p", dockerPassword)
                    .inheritIO().start();
            if (login.waitFor() != 0) throw new RuntimeException("Docker login failed");
            log.info("✅ Docker login successful");

            // Load tar
            Process load = new ProcessBuilder("docker", "load", "-i", dockerTar.getAbsolutePath())
                    .inheritIO().start();
            if (load.waitFor() != 0) throw new RuntimeException("Docker load failed");
            log.info("✅ Docker image loaded successfully");

            // Tag
            String imageTag = dockerUsername + "/" + repoName + ":latest";
            Process tag = new ProcessBuilder("docker", "tag", repoName, imageTag)
                    .inheritIO().start();
            if (tag.waitFor() != 0) throw new RuntimeException("Docker tag failed");
            log.info("✅ Docker image tagged as {}", imageTag);

            // Push
            Process push = new ProcessBuilder("docker", "push", imageTag)
                    .inheritIO().start();
            if (push.waitFor() != 0) throw new RuntimeException("Docker push failed");
            log.info("🚀 Docker image pushed successfully: {}", imageTag);

        } catch (IOException | InterruptedException e) {
            log.error("💥 Docker push failed: {}", e.getMessage(), e);
            throw new RuntimeException("Docker push failed", e);
        } finally {
            // Clean up temporary tar
            if (dockerTar != null && dockerTar.exists()) {
                dockerTar.delete();
            }
        }
    }
}
