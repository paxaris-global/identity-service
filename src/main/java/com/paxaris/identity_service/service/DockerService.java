package com.paxaris.identity_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerService {

    // Build repository name as "realm-clientId"
    private String getRepoName(String realmName, String clientId) {
        return (realmName + "-" + clientId).toLowerCase();
    }

    /** Create repo is optional when using CLI with host Docker login */
    public void createRepository(String realmName, String clientId) {
        String repoName = getRepoName(realmName, clientId);
        log.info("🐳 Skipping Docker Hub API repo creation. Repo name will be: {}", repoName);
        // Docker CLI push will auto-create the repo if logged in
    }

    /** Push Docker image using host Docker daemon */
    public void pushDockerImage(MultipartFile dockerImage, String realmName, String clientId) {
        try {
            String repoName = getRepoName(realmName, clientId);
            String repoFullName = System.getenv("DOCKER_USERNAME") + "/" + repoName;

            log.info("🚀 Starting Docker push for {}", repoFullName);

            // Save uploaded tar file temporarily
            File tempFile = File.createTempFile(repoName.replace("/", "-"), ".tar");
            dockerImage.transferTo(tempFile);

            // Load image from tar
            Process load = new ProcessBuilder("docker", "load", "-i", tempFile.getAbsolutePath())
                    .inheritIO().start();
            load.waitFor();

            // Get image ID from tar
            String imageName = tempFile.getName().replace(".tar", "");

            // Tag the image with repoFullName:latest
            new ProcessBuilder("docker", "tag", imageName, repoFullName + ":latest")
                    .inheritIO().start().waitFor();

            // Push to Docker Hub (host must be logged in via docker login)
            new ProcessBuilder("docker", "push", repoFullName + ":latest")
                    .inheritIO().start().waitFor();

            tempFile.delete();
            log.info("✅ Image pushed successfully to {}", repoFullName);

        } catch (Exception e) {
            log.error("💥 Docker push failed: {}", e.getMessage(), e);
            throw new RuntimeException("Docker push failed", e);
        }
    }
}
