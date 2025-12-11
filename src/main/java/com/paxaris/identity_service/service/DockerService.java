package com.paxaris.identity_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.File;
import java.nio.file.Files;
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

    /**
     * Create Docker Hub repository using Docker Hub API
     */
    public void createRepository(String repoName) {
        try {
            log.info("🐳 Creating Docker Hub repository: {}", repoName);

            Map<String, Object> body = Map.of(
                    "name", repoName.toLowerCase(), // Docker Hub requires lowercase
                    "is_private", true
            );

            String auth = Base64.getEncoder()
                    .encodeToString((dockerHubUsername + ":" + dockerHubPassword).getBytes());

            WebClient.builder()
                    .baseUrl("https://hub.docker.com/v2/repositories/" + dockerHubUsername.trim() + "/")
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + auth)
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .build()
                    .post()
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .doOnSuccess(res -> log.info("✅ Repository '{}' created successfully", repoName))
                    .doOnError(err -> log.error("❌ Docker Hub repo creation error: {}", err.getMessage()))
                    .block();

        } catch (Exception e) {
            log.error("💥 Failed to create Docker Hub repository: {}", e.getMessage(), e);
            throw new RuntimeException("Docker Hub repository creation failed", e);
        }
    }

    /**
     * Push Docker image to Docker Hub using HTTP API (requires Docker Registry API)
     */
    public void pushDockerImage(MultipartFile dockerImage, String repoName) {
        try {
            log.info("🚀 Starting Docker image upload for client: {}", repoName);

            // Save image temporarily
            File tempFile = File.createTempFile(repoName.toLowerCase(), ".tar");
            dockerImage.transferTo(tempFile);

            // Docker Hub requires pushing through Docker Registry API (v2)
            // This is complex in raw HTTP; simplified option is to use Jib or Kaniko in CI/CD pipelines
            // For now, just log file saved
            log.info("📦 Docker image saved temporarily: {}", tempFile.getAbsolutePath());

            // You can integrate Jib library here to push the image programmatically
            // e.g., com.google.cloud.tools:jib-core

            log.info("✅ Docker image ready for push (manual or via CI/CD)");

            tempFile.deleteOnExit();

        } catch (Exception e) {
            log.error("💥 Docker image push failed: {}", e.getMessage(), e);
            throw new RuntimeException("Docker image push failed", e);
        }
    }
}
