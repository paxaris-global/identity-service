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
    private String dockerHubToken; // Personal Access Token

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
            log.error("❌ Docker Hub login FAILED: {}", e.getMessage());
            throw new RuntimeException("Docker Hub login failed", e);
        }
    }

    /**
     * Create repository using JWT token
     */
    public void createRepository(String realmName, String clientId) {
        try {
            String repoName = (realmName + "-" + clientId).toLowerCase();
            log.info("🐳 Creating Docker Hub repo: {}", repoName);

            String jwt = getJwtToken();

            Map<String, Object> body = Map.of(
                    "namespace", dockerHubUsername,
                    "name", repoName,
                    "description", "Repo for " + repoName,
                    "is_private", true
            );

            String response = webClient.post()
                    .uri("/v2/repositories/" + dockerHubUsername + "/")
                    .header(HttpHeaders.AUTHORIZATION, "JWT " + jwt)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("✅ Repo created successfully: {}", response);

        } catch (Exception e) {
            if (e.getMessage().contains("409")) {
                log.warn("⚠ Repo already exists, skipping.");
                return;
            }
            log.error("❌ Repo creation FAILED: {}", e.getMessage());
            throw new RuntimeException("Docker Hub repo creation failed", e);
        }
    }

    /**
     * Push Docker image (.tar file)
     */
    public void pushDockerImage(MultipartFile dockerImage, String realmName, String clientId) {
        try {
            String repoFullName = dockerHubUsername + "/" + (realmName + "-" + clientId).toLowerCase();
            log.info("🚀 Starting push for repo {}", repoFullName);

            // Save uploaded tar
            File tempFile = File.createTempFile(repoFullName.replace("/", "-"), ".tar");
            dockerImage.transferTo(tempFile);

            // Login using CLI
            Process loginProcess = new ProcessBuilder(
                    "docker", "login",
                    "--username", dockerHubUsername,
                    "--password-stdin"
            ).start();

            try (OutputStream os = loginProcess.getOutputStream()) {
                os.write(dockerHubToken.getBytes(StandardCharsets.UTF_8));
            }

            if (loginProcess.waitFor() != 0) throw new RuntimeException("Docker login failed");

            // Load image
            Process loadProcess = new ProcessBuilder(
                    "docker", "load", "-i", tempFile.getAbsolutePath()
            ).inheritIO().start();
            loadProcess.waitFor();

            // Tag image
            String localImgName = dockerImage.getOriginalFilename().replace(".tar", "");
            new ProcessBuilder("docker", "tag", localImgName, repoFullName + ":latest")
                    .inheritIO().start().waitFor();

            // Push
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
