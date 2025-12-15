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
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerService {

    @Value("${DOCKER_USERNAME}")
    private String dockerHubUsername;

    @Value("${DOCKER_PASSWORD}")
    private String dockerHubToken;

    private final WebClient webClient = WebClient.builder()
            .baseUrl("https://hub.docker.com")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();

    /** Login and get JWT token from Docker Hub */
    private String getJwtToken() {
        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("username", dockerHubUsername);
            payload.put("password", dockerHubToken);

            Map response = webClient.post()
                    .uri("/v2/users/login/")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            return (String) response.get("token");
        } catch (Exception e) {
            log.error("❌ Docker login failed: {}", e.getMessage());
            throw new RuntimeException("Docker Hub login failed", e);
        }
    }

    /** Create repository with format: realm-client */
    public void createRepository(String realmName, String clientId) {
        String repoName = (realmName + "-" + clientId).toLowerCase();
        log.info("🐳 Creating Docker Hub repo: {}", repoName);

        String jwt = getJwtToken();

        Map<String, Object> body = Map.of(
                "namespace", dockerHubUsername,
                "name", repoName,
                "description", "Repository for " + repoName,
                "is_private", true
        );

        try {
            webClient.post()
                    .uri("/v2/repositories/" + dockerHubUsername + "/")
                    .header(HttpHeaders.AUTHORIZATION, "JWT " + jwt)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("✅ Repo created successfully: {}", repoName);
        } catch (Exception e) {
            if (e.getMessage().contains("409")) {
                log.warn("⚠ Repo already exists, skipping creation.");
            } else {
                log.error("❌ Repo creation failed: {}", e.getMessage());
                throw new RuntimeException("Docker Hub repo creation failed", e);
            }
        }
    }

    /** Push Docker image using Docker CLI (after repo creation) */
    public void pushDockerImage(MultipartFile dockerImage, String realmName, String clientId) {
        try {
            String repoFullName = dockerHubUsername + "/" + (realmName + "-" + clientId).toLowerCase();
            log.info("🚀 Starting push for repo {}", repoFullName);

            // Save the uploaded tar file
            File tempFile = File.createTempFile(repoFullName.replace("/", "-"), ".tar");
            dockerImage.transferTo(tempFile);

            // Load image and push using CLI
            Process load = new ProcessBuilder("docker", "load", "-i", tempFile.getAbsolutePath())
                    .inheritIO().start();
            load.waitFor();

            // Tag image with repoFullName:latest
            String localImgName = tempFile.getName().replace(".tar", "");
            new ProcessBuilder("docker", "tag", localImgName, repoFullName + ":latest")
                    .inheritIO().start().waitFor();

            // Push to Docker Hub
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
