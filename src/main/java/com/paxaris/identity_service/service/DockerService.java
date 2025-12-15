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
    private String dockerHubToken; // Docker Hub PAT

    private final WebClient webClient = WebClient.builder()
            .baseUrl("https://hub.docker.com")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();

    /**
     * Login to Docker Hub and receive JWT token
     */
    private String getJwtToken() {
        try {
            Map<String, String> requestMap = new HashMap<>();
            requestMap.put("username", dockerHubUsername);
            requestMap.put("password", dockerHubToken);

            Map response = webClient.post()
                    .uri("/v2/users/login/")
                    .bodyValue(requestMap)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            return (String) response.get("token");

        } catch (Exception e) {
            log.error("❌ Docker Hub login FAILED", e);
            throw new RuntimeException("Docker Hub login failed", e);
        }
    }

    /**
     * Create Docker Hub repository: realmName-clientId
     */
    public void createRepository(String realmName, String clientId) {
        String repoName = (realmName + "-" + clientId).toLowerCase();

        try {
            log.info("🐳 Creating Docker Hub repo: {}", repoName);

            String jwt = getJwtToken();

            Map<String, Object> body = Map.of(
                    "namespace", dockerHubUsername,
                    "name", repoName,
                    "description", "Repo for realm " + realmName + " and client " + clientId,
                    "is_private", true
            );

            webClient.post()
                    .uri("/v2/repositories/" + dockerHubUsername + "/")
                    .header(HttpHeaders.AUTHORIZATION, "JWT " + jwt)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("✅ Repository created: {}", repoName);

        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("409")) {
                log.warn("⚠ Repository already exists: {}", repoName);
                return;
            }
            log.error("❌ Repo creation FAILED", e);
            throw new RuntimeException("Docker Hub repo creation failed", e);
        }
    }

    /**
     * Push Docker image (.tar) to Docker Hub
     */
    public void pushDockerImage(MultipartFile dockerImage, String realmName, String clientId) {
        String repoName = (realmName + "-" + clientId).toLowerCase();
        String repoFullName = dockerHubUsername + "/" + repoName;

        try {
            log.info("🚀 Pushing image to {}", repoFullName);

            // Save uploaded tar file
            File tempFile = File.createTempFile(repoName, ".tar");
            dockerImage.transferTo(tempFile);

            // Docker login
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

            // Push image
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
