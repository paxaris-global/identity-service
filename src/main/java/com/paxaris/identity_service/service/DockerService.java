package com.paxaris.identity_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.File;
import java.io.OutputStream;
import java.util.Base64;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerService {

    @Value("${docker.hub.username}")
    private String dockerHubUsername;

    @Value("${docker.hub.password}")
    private String dockerHubPassword;

    private WebClient buildClient() {
        String auth = Base64.getEncoder()
                .encodeToString((dockerHubUsername + ":" + dockerHubPassword).getBytes());

        return WebClient.builder()
                .baseUrl("https://hub.docker.com/v2/repositories/")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + auth)
                .build();
    }

    /**
     * Create a Docker Hub repository
     */
    public void createRepository(String clientName) {

        try {
            log.info("🐳 Creating Docker Hub repository: {}", clientName);

            Map<String, Object> repoRequest = Map.of(
                    "name", clientName,
                    "is_private", true
            );

            buildClient()
                    .post()
                    .uri(dockerHubUsername + "/")
                    .bodyValue(repoRequest)
                    .retrieve()
                    .bodyToMono(String.class)
                    .doOnSuccess(res -> log.info("✅ Repository '{}' created successfully", clientName))
                    .doOnError(err -> log.error("❌ Docker Hub repo creation error: {}", err.getMessage()))
                    .block();

        } catch (Exception e) {
            log.error("💥 Failed to create Docker Hub repository: {}", e.getMessage());
            throw new RuntimeException("Docker Hub repository creation failed", e);
        }
    }

    /**
     * Push Docker image to Docker Hub
     */
    public void pushDockerImage(MultipartFile dockerImage, String clientName) {

        try {
            log.info("🚀 Starting Docker image push for client: {}", clientName);

            // Save tar file
            File tempFile = File.createTempFile(clientName, ".tar");
            dockerImage.transferTo(tempFile);

            String imageTag = dockerHubUsername + "/" + clientName + ":latest";

            // ---- Docker Login ----
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

            if (loginProcess.waitFor() != 0) {
                throw new RuntimeException("Docker login failed");
            }

            // ---- Load image ----
            Process load = new ProcessBuilder("docker", "load", "-i", tempFile.getAbsolutePath())
                    .inheritIO().start();
            if (load.waitFor() != 0) throw new RuntimeException("Docker image load failed");

            // ---- Tag image ----
            Process tag = new ProcessBuilder("docker", "tag", clientName, imageTag)
                    .inheritIO().start();
            if (tag.waitFor() != 0) throw new RuntimeException("Docker image tag failed");

            // ---- Push image ----
            Process push = new ProcessBuilder("docker", "push", imageTag)
                    .inheritIO().start();
            if (push.waitFor() != 0) throw new RuntimeException("Docker image push failed");

            log.info("✅ Docker image pushed successfully: {}", imageTag);

            tempFile.delete();

        } catch (Exception e) {
            log.error("💥 Docker image push failed: {}", e.getMessage());
            throw new RuntimeException("Docker push failed", e);
        }
    }
}
