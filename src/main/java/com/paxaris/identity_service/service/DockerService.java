package com.paxaris.identity_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerService {

    @Value("${DOCKER_USERNAME}")
    private String dockerHubUsername;

    @Value("${DOCKER_PASSWORD}")
    private String dockerHubToken; // Personal Access Token from Docker Hub

    private final WebClient webClient = WebClient.builder()
            .baseUrl("https://hub.docker.com/v2")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();

    /**
     * Create a Docker Hub repository using Docker Hub API
     */
    public void createRepository(String repoName) {
        try {
            log.info("🐳 Creating Docker Hub repository: {}", repoName);

            // Correct endpoint: just /repositories/
            Map<String, Object> body = Map.of(
                    "name", repoName.toLowerCase(),
                    "namespace", dockerHubUsername,
                    "description", "Repository for client " + repoName,
                    "is_private", true
            );

            String response = webClient.post()
                    .uri("/repositories/")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + dockerHubToken)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("✅ Repository '{}' created successfully. Response: {}", repoName, response);

        } catch (Exception e) {
            log.error("❌ Docker Hub repo creation FAILED: {}", e.getMessage(), e);
            throw new RuntimeException("Docker Hub repository creation failed", e);
        }
    }


    /**
     * Push Docker image to Docker Hub using Docker CLI
     */
    public void pushDockerImage(MultipartFile dockerImage, String repoName) {
        try {
            log.info("🚀 Starting Docker image push for: {}", repoName);

            String repoFullName = dockerHubUsername + "/" + repoName.toLowerCase();

            // Save uploaded tar file temporarily
            File tempFile = File.createTempFile(repoName.toLowerCase(), ".tar");
            dockerImage.transferTo(tempFile);

            log.info("📦 Docker image saved: {}", tempFile.getAbsolutePath());

            // ---- LOGIN ----
            ProcessBuilder loginBuilder = new ProcessBuilder(
                    "docker", "login",
                    "--username", dockerHubUsername,
                    "--password-stdin"
            );

            Process loginProcess = loginBuilder.start();
            try (OutputStream os = loginProcess.getOutputStream()) {
                os.write(dockerHubToken.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            if (loginProcess.waitFor() != 0) {
                throw new RuntimeException("Docker login failed");
            }

            log.info("🔐 Docker login successful");

            // ---- LOAD IMAGE ----
            Process loadProcess = new ProcessBuilder(
                    "docker", "load", "-i", tempFile.getAbsolutePath()
            ).inheritIO().start();

            if (loadProcess.waitFor() != 0) {
                throw new RuntimeException("Docker image load failed");
            }

            log.info("📤 Docker image loaded");

            // ---- TAG IMAGE ----
            Process tagProcess = new ProcessBuilder(
                    "docker", "tag", tempFile.getName(), repoFullName + ":latest"
            ).inheritIO().start();

            if (tagProcess.waitFor() != 0) {
                throw new RuntimeException("Docker image tag failed");
            }

            log.info("🏷️ Docker image tagged as {}", repoFullName);

            // ---- PUSH IMAGE ----
            Process pushProcess = new ProcessBuilder(
                    "docker", "push", repoFullName + ":latest"
            ).inheritIO().start();

            if (pushProcess.waitFor() != 0) {
                throw new RuntimeException("Docker push failed");
            }

            log.info("✅ Docker image pushed successfully: {}", repoFullName);

            // Delete temporary file
            tempFile.delete();

        } catch (Exception e) {
            log.error("💥 Docker image push failed: {}", e.getMessage(), e);
            throw new RuntimeException("Docker push failed", e);
        }
    }
}
