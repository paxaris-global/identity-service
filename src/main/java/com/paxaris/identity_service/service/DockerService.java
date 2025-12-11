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

    @Value("${docker.hub.username}")
    private String dockerHubUsername;

    @Value("${docker.hub.password}")
    private String dockerHubPassword;

    /**
     * Push Docker image to Docker Hub.
     * This will automatically create the repository if it does not exist.
     */
    public void pushDockerImage(MultipartFile dockerImage, String clientName) {
        try {
            log.info("🚀 Starting Docker image push for client: {}", clientName);

            // Save uploaded docker image as a temporary tar file
            File tempFile = File.createTempFile(clientName.trim(), ".tar");
            dockerImage.transferTo(tempFile);

            String imageTag = dockerHubUsername.trim() + "/" + clientName.trim() + ":latest";

            // ---- Docker Login using Personal Access Token ----
            ProcessBuilder login = new ProcessBuilder(
                    "docker", "login",
                    "--username", dockerHubUsername.trim(),
                    "--password-stdin"
            );
            Process loginProcess = login.start();
            try (OutputStream os = loginProcess.getOutputStream()) {
                os.write(dockerHubPassword.trim().getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            if (loginProcess.waitFor() != 0) {
                throw new RuntimeException("Docker login failed");
            }
            log.info("✅ Docker login successful");

            // ---- Load image from tar file ----
            Process load = new ProcessBuilder("docker", "load", "-i", tempFile.getAbsolutePath())
                    .inheritIO().start();
            if (load.waitFor() != 0) throw new RuntimeException("Docker image load failed");

            // ---- Tag image for Docker Hub ----
            Process tag = new ProcessBuilder("docker", "tag", clientName.trim(), imageTag)
                    .inheritIO().start();
            if (tag.waitFor() != 0) throw new RuntimeException("Docker image tag failed");

            // ---- Push image to Docker Hub (repository auto-created) ----
            Process push = new ProcessBuilder("docker", "push", imageTag)
                    .inheritIO().start();
            if (push.waitFor() != 0) throw new RuntimeException("Docker image push failed");

            log.info("✅ Docker image pushed successfully: {}", imageTag);

            // Delete temporary file
            tempFile.delete();

        } catch (Exception e) {
            log.error("💥 Docker image push failed: {}", e.getMessage(), e);
            throw new RuntimeException("Docker push failed", e);
        }
    }
}
