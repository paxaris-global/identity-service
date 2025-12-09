package com.paxaris.identity_service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.File;
import java.io.OutputStream;
import java.util.Base64;
import java.util.Map;

@Slf4j
@Service
public class DockerService {

    // Ideally inject via application.properties
    private final String dockerHubUsername = "YOUR_DOCKER_USERNAME";
    private final String dockerHubPassword = "YOUR_DOCKER_PASSWORD";

    /**
     * Create a Docker Hub repository with the given name
     */
    public void createRepository(String clientName) {
        try {
            log.info("🐳 Creating Docker Hub repository '{}'", clientName);

            String auth = Base64.getEncoder()
                    .encodeToString((dockerHubUsername + ":" + dockerHubPassword).getBytes());

            WebClient dockerClient = WebClient.builder()
                    .baseUrl("https://hub.docker.com/v2/repositories/")
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + auth)
                    .build();

            Map<String, Object> repoRequest = Map.of(
                    "name", clientName,
                    "is_private", true
            );

            dockerClient.post()
                    .uri(dockerHubUsername + "/")  // /v2/repositories/{username}/
                    .bodyValue(repoRequest)
                    .retrieve()
                    .bodyToMono(String.class)
                    .doOnSuccess(r -> log.info("✅ Docker Hub repository '{}' created successfully.", clientName))
                    .doOnError(e -> log.error("❌ Error creating Docker Hub repo: {}", e.getMessage(), e))
                    .block();

        } catch (Exception e) {
            log.error("💥 Docker Hub repository creation failed: {}", e.getMessage(), e);
            throw new RuntimeException("Docker Hub repo creation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Push a Docker image to Docker Hub
     */
    public void pushDockerImage(MultipartFile dockerImage, String clientName) {
        try {
            log.info("🚀 Pushing Docker image for client '{}'", clientName);

            // Save MultipartFile to temp file
            File tempFile = File.createTempFile(clientName, ".tar");
            dockerImage.transferTo(tempFile);

            // Login to Docker CLI
            ProcessBuilder login = new ProcessBuilder(
                    "docker", "login",
                    "--username", dockerHubUsername,
                    "--password-stdin"
            );
            Process loginProcess = login.start();
            try (OutputStream os = loginProcess.getOutputStream()) {
                os.write(dockerHubPassword.getBytes());
                os.flush();
            }
            loginProcess.waitFor();

            // Load Docker image from temp file
            Process load = new ProcessBuilder("docker", "load", "-i", tempFile.getAbsolutePath())
                    .inheritIO().start();
            load.waitFor();

            // Tag image
            String imageTag = dockerHubUsername + "/" + clientName + ":latest";
            Process tag = new ProcessBuilder("docker", "tag", clientName, imageTag)
                    .inheritIO().start();
            tag.waitFor();

            // Push image
            Process push = new ProcessBuilder("docker", "push", imageTag)
                    .inheritIO().start();
            push.waitFor();

            log.info("✅ Docker image pushed successfully: {}", imageTag);

            tempFile.delete();

        } catch (Exception e) {
            log.error("💥 Docker image push failed: {}", e.getMessage(), e);
            throw new RuntimeException("Docker image push failed: " + e.getMessage(), e);
        }
    }
}
