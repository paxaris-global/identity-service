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
import java.util.Base64;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerService {

    @Value("${docker.hub.username}")
    private String dockerHubUsername;  // e.g. vipulmehra

    @Value("${docker.hub.token}")       // ❗ NOT PASSWORD — use Access Token
    private String dockerHubToken;

    private WebClient webClient = WebClient.builder()
            .baseUrl("https://hub.docker.com/v2")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();

    /**
     * Create a Docker Hub repository
     */
    public void createRepository(String repoName) {
        try {
            log.info("🐳 Creating Docker Hub repository: {}", repoName);

            String auth = Base64.getEncoder()
                    .encodeToString((dockerHubUsername + ":" + dockerHubToken).getBytes());

            Map<String, Object> body = Map.of(
                    "name", repoName.toLowerCase(),
                    "is_private", true
            );

            webClient.post()
                    .uri("/repositories/" + dockerHubUsername + "/")
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + auth)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .doOnSuccess(res -> log.info("✅ Repository '{}' created successfully", repoName))
                    .doOnError(err -> log.error("❌ Error creating repo: {}", err.getMessage()))
                    .block();

        } catch (Exception e) {
            log.error("💥 Failed to create Docker Hub repository: {}", e.getMessage(), e);
            throw new RuntimeException("Docker Hub repository creation failed", e);
        }
    }
}
