package com.paxaris.identity_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerService {

    @Value("${docker.hub.username}")
    private String dockerUsername;

    @Value("${docker.hub.password}")
    private String dockerPat; // Docker Hub PAT token

    /* =================================================
       REPO NAME → realm-clientId
     ================================================= */
    private String repo(String realm, String client) {
        return (realm + "-" + client).toLowerCase();
    }

    /* =================================================
       STEP 1: GET DOCKER HUB JWT (FOR REPO CREATION)
     ================================================= */
    private String getDockerJwt() {
        try {
            URL url = new URL("https://hub.docker.com/v2/users/login/");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();

            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setDoOutput(true);

            String body = String.format("""
                {"username": "%s", "password": "%s"}
                """, dockerUsername, dockerPat);

            try (OutputStream os = con.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = con.getResponseCode();
            InputStream is = code >= 400 ? con.getErrorStream() : con.getInputStream();

            String response = new BufferedReader(new InputStreamReader(is))
                    .lines().reduce("", (a, b) -> a + b);

            if (code != 200) {
                throw new RuntimeException("Docker Hub login failed: " + response);
            }

            return response.split("\"token\":\"")[1].split("\"")[0];

        } catch (Exception e) {
            throw new RuntimeException("Failed to obtain Docker Hub JWT", e);
        }
    }

    /* =================================================
       STEP 2: CREATE DOCKER HUB REPOSITORY
     ================================================= */
    public void createRepository(String realm, String client) {
        String repoName = repo(realm, client);
        String jwt = getDockerJwt();

        try {
            URL url = new URL(
                    "https://hub.docker.com/v2/repositories/" + dockerUsername + "/"
            );
            HttpURLConnection con = (HttpURLConnection) url.openConnection();

            con.setRequestMethod("POST");
            con.setRequestProperty("Authorization", "JWT " + jwt);
            con.setRequestProperty("Content-Type", "application/json");
            con.setDoOutput(true);

            String body = String.format("""
                {
                  "name": "%s",
                  "is_private": false
                }
                """, repoName);

            try (OutputStream os = con.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = con.getResponseCode();
            InputStream is = code >= 400 ? con.getErrorStream() : con.getInputStream();
            String response = new BufferedReader(new InputStreamReader(is))
                    .lines().reduce("", (a, b) -> a + b);

            if (code == 201 || code == 409) {
                log.info("✅ Docker Hub repository ready: {}", repoName);
                return;
            }

            throw new RuntimeException("Repo creation failed HTTP " + code + ": " + response);

        } catch (Exception e) {
            throw new RuntimeException("Docker repo creation failed", e);
        }
    }

    /* =================================================
       STEP 3: SAVE TAR FILE
     ================================================= */
    public File saveDockerImage(MultipartFile file) {
        try {
            File tar = File.createTempFile("docker-", ".tar");
            file.transferTo(tar);
            log.info("📦 Docker image tar saved: {}", tar.getAbsolutePath());
            return tar;
        } catch (IOException e) {
            throw new RuntimeException("Failed to save Docker image tar", e);
        }
    }

    /* =================================================
       STEP 4: PUSH IMAGE
     ================================================= */
    public void pushDockerImage(File tar, String realm, String client) {
        String repoName = repo(realm, client);
        String fullTag = dockerUsername + "/" + repoName + ":latest";

        try {
            dockerLogin();

            String loadOutput = execWithFullOutput(
                    "docker", "load", "-i", tar.getAbsolutePath()
            );
            log.info("🐳 docker load output:\n{}", loadOutput);

            String sourceImage = extractImageFromLoad(loadOutput);
            log.info("🏷️ Source image resolved as: {}", sourceImage);

            exec("docker", "tag", sourceImage, fullTag);
            exec("docker", "push", fullTag);

            log.info("🚀 Docker image pushed successfully: {}", fullTag);

        } catch (Exception e) {
            throw new RuntimeException("Docker image push failed", e);
        } finally {
            if (tar != null && tar.exists()) tar.delete();
        }
    }

    /* =================================================
       DOCKER LOGIN (MODERN METHOD WITH --password-stdin)
     ================================================= */
    private void dockerLogin() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("docker", "login", "--username", dockerUsername, "--password-stdin");
        Process process = pb.start();

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()))) {
            writer.write(dockerPat);
            writer.flush();
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("🐳 docker login: {}", line);
            }
        }

        if (process.waitFor() != 0) {
            throw new RuntimeException("Docker login failed");
        }

        log.info("✅ Docker login successful");
    }

    /* =================================================
       PROCESS HELPERS
     ================================================= */
    private void exec(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("🐳 docker: {}", line);
            }
        }

        if (p.waitFor() != 0) {
            throw new RuntimeException("Command failed: " + String.join(" ", cmd));
        }
    }

    private String execWithFullOutput(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        p.waitFor();
        return output.toString();
    }

    /* =================================================
       IMAGE DETECTION (NAME OR ID)
     ================================================= */
    private String extractImageFromLoad(String output) {
        for (String line : output.split("\n")) {
            if (line.startsWith("Loaded image:")) {
                return line.replace("Loaded image:", "").trim();
            }
        }

        for (String line : output.split("\n")) {
            if (line.startsWith("Loaded image ID:")) {
                return line.replace("Loaded image ID:", "").trim();
            }
        }

        throw new RuntimeException(
                "Unable to detect loaded Docker image from output:\n" + output
        );
    }
}
