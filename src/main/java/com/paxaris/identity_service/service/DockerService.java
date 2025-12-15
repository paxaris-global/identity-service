package com.paxaris.identity_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerService {

    @Value("${DOCKER_USERNAME}")
    private String dockerUsername;

    @Value("${DOCKER_PASSWORD}")
    private String dockerPassword;


    private final ObjectMapper objectMapper = new ObjectMapper();

    private final WebClient webClient = WebClient.builder()
            .baseUrl("https://hub.docker.com")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();

    /** Build repo name in format: realm-clientId */
    private String getRepoName(String realmName, String clientId) {
        return (realmName + "-" + clientId).toLowerCase();
    }

    /** Create repository on Docker Hub using API */
    public void createRepository(String realmName, String clientId) {
        String repoName = getRepoName(realmName, clientId);

        // Docker Hub API requires a JWT token
        String jwt = getJwtToken();

        Map<String, Object> body = new HashMap<>();
        body.put("name", repoName);
        body.put("namespace", dockerUsername);
        body.put("is_private", true);
        body.put("description", "Repository for " + repoName);

        try {
            webClient.post()
                    .uri("/v2/repositories/" + dockerUsername + "/")
                    .header(HttpHeaders.AUTHORIZATION, "JWT " + jwt)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("✅ Docker Hub repo created successfully: {}", repoName);

        } catch (WebClientResponseException e) {
            if (e.getRawStatusCode() == 409) {
                log.warn("⚠ Repo already exists, skipping creation: {}", repoName);
            } else {
                log.error("💥 Docker Hub repo creation failed: {}", e.getMessage(), e);
                throw new RuntimeException("Docker Hub repo creation failed", e);
            }
        }
    }

    /** Get JWT token from Docker Hub */
    private String getJwtToken() {
        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("username", dockerUsername);
            payload.put("password", dockerPassword);

            Map<String, Object> response = webClient.post()
                    .uri("/v2/users/login/")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            String token = (String) response.get("token");
            log.info("✅ Docker Hub JWT token retrieved successfully");
            return token;
        } catch (Exception e) {
            log.error("💥 Docker login failed: {}", e.getMessage(), e);
            throw new RuntimeException("Docker Hub login failed", e);
        }
    }

    /** Push Docker image using Docker Hub HTTP API (v2) */
    public void pushDockerImage(MultipartFile dockerImage, String realm, String clientId) {
        try {
            String repoName = dockerUsername + "/" + getRepoName(realm, clientId);

            // Save tar locally
            File tempFile = File.createTempFile(getRepoName(realm, clientId), ".tar");
            dockerImage.transferTo(tempFile);

            // Load and push image using Docker CLI
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "load", "-i", tempFile.getAbsolutePath()
            );
            pb.inheritIO();
            Process process = pb.start();
            process.waitFor();

            // Tag & push (optional if tag exists in tar)
            ProcessBuilder pushPb = new ProcessBuilder(
                    "docker", "push", repoName
            );
            pushPb.inheritIO();
            Process pushProcess = pushPb.start();
            pushProcess.waitFor();

            log.info("✅ Docker image pushed successfully: {}", repoName);

            tempFile.delete();
        } catch (Exception e) {
            log.error("💥 Docker push failed", e);
            throw new RuntimeException("Docker push failed", e);
        }
    }

}
