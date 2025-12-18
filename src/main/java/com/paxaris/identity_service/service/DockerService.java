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

    // REPO NAME → realm-clientId
    private String repo(String realm, String client) {
        log.info("🔧 Generating repo name for realm='{}', client='{}'", realm, client);
        return (realm + "-" + client).toLowerCase();
    }

    // STEP 1: GET DOCKER HUB JWT
    private String getDockerJwt() {
        log.info("🔑 Starting Docker Hub JWT retrieval");
        try {
            URL url = new URL("https://hub.docker.com/v2/users/login/");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();

            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setDoOutput(true);

            String body = String.format("{\"username\": \"%s\", \"password\": \"%s\"}", dockerUsername, dockerPat);
            log.info("📦 Sending login request to Docker Hub");

            try (OutputStream os = con.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = con.getResponseCode();
            InputStream is = code >= 400 ? con.getErrorStream() : con.getInputStream();
            String response = new BufferedReader(new InputStreamReader(is))
                    .lines().reduce("", (a, b) -> a + b);

            log.info("📥 Docker Hub login response code={}, response={}", code, response);

            if (code != 200) {
                throw new RuntimeException("Docker Hub login failed: " + response);
            }

            String token = response.split("\"token\":\"")[1].split("\"")[0];
            log.info("✅ Docker Hub JWT obtained successfully");
            return token;

        } catch (Exception e) {
            log.error("❌ Failed to obtain Docker Hub JWT", e);
            throw new RuntimeException("Failed to obtain Docker Hub JWT", e);
        }
    }

    // STEP 2: CREATE DOCKER HUB REPOSITORY
    public void createRepository(String realm, String client) {
        String repoName = repo(realm, client);
        log.info("🧱 Creating Docker Hub repository '{}'", repoName);
        String jwt = getDockerJwt();

        try {
            URL url = new URL("https://hub.docker.com/v2/repositories/" + dockerUsername + "/");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();

            con.setRequestMethod("POST");
            con.setRequestProperty("Authorization", "JWT " + jwt);
            con.setRequestProperty("Content-Type", "application/json");
            con.setDoOutput(true);

            String body = String.format("{\"name\": \"%s\", \"is_private\": false}", repoName);
            log.info("📦 Sending create repo request to Docker Hub");

            try (OutputStream os = con.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = con.getResponseCode();
            InputStream is = code >= 400 ? con.getErrorStream() : con.getInputStream();
            String response = new BufferedReader(new InputStreamReader(is))
                    .lines().reduce("", (a, b) -> a + b);

            log.info("📥 Docker Hub repo create response code={}, response={}", code, response);

            if (code == 201 || code == 409) {
                log.info("✅ Docker Hub repository ready: {}", repoName);
                return;
            }

            throw new RuntimeException("Repo creation failed HTTP " + code + ": " + response);

        } catch (Exception e) {
            log.error("❌ Docker repo creation failed", e);
            throw new RuntimeException("Docker repo creation failed", e);
        }
    }

    // STEP 3: SAVE TAR FILE
    public File saveDockerImage(MultipartFile file) {
        log.info("💾 Saving uploaded Docker image to temp file");
        try {
            File tar = File.createTempFile("docker-", ".tar");
            file.transferTo(tar);
            log.info("📦 Docker image tar saved at {}", tar.getAbsolutePath());
            return tar;
        } catch (IOException e) {
            log.error("❌ Failed to save Docker image tar", e);
            throw new RuntimeException("Failed to save Docker image tar", e);
        }
    }

    // STEP 4: PUSH IMAGE
    public void pushDockerImage(File tar, String realm, String client) {
        String repoName = repo(realm, client);
        String fullTag = dockerUsername + "/" + repoName + ":latest";
        log.info("🚀 Starting Docker image push process for {}", fullTag);

        try {
            dockerLogin();

            log.info("🐳 Loading Docker image from tar: {}", tar.getAbsolutePath());
            String loadOutput = execWithFullOutput("docker", "load", "-i", tar.getAbsolutePath());
            log.info("📥 docker load output:\n{}", loadOutput);

            String sourceImage = extractImageFromLoad(loadOutput);
            log.info("🏷️ Source image resolved as: {}", sourceImage);

            log.info("🔖 Tagging image '{}' as '{}'", sourceImage, fullTag);
            exec("docker", "tag", sourceImage, fullTag);

            log.info("📤 Pushing Docker image '{}'", fullTag);
            exec("docker", "push", fullTag);

            log.info("✅ Docker image pushed successfully: {}", fullTag);

        } catch (Exception e) {
            log.error("❌ Docker image push failed", e);
            throw new RuntimeException("Docker image push failed", e);
        } finally {
            if (tar != null && tar.exists()) {
                log.info("🗑️ Deleting temporary tar file {}", tar.getAbsolutePath());
                tar.delete();
            }
        }
    }

    // DOCKER LOGIN
    private void dockerLogin() throws Exception {
        log.info("🔑 Logging in to Docker Hub as '{}'", dockerUsername);
        ProcessBuilder pb = new ProcessBuilder("docker", "login", "--username", dockerUsername, "--password-stdin");
        Process process = pb.start();

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()))) {
            writer.write(dockerPat);
            writer.flush();
            log.info("📤 Sent password for docker login");
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("🐳 docker login: {}", line);
            }
        }

        if (process.waitFor() != 0) {
            log.error("❌ Docker login failed");
            throw new RuntimeException("Docker login failed");
        }

        log.info("✅ Docker login successful");
    }

    // HELPER METHODS
    private void exec(String... cmd) throws Exception {
        log.info("⚡ Executing command: {}", String.join(" ", cmd));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("🐳 docker: {}", line);
            }
        }

        if (p.waitFor() != 0) {
            log.error("❌ Command failed: {}", String.join(" ", cmd));
            throw new RuntimeException("Command failed: " + String.join(" ", cmd));
        }
    }

    private String execWithFullOutput(String... cmd) throws Exception {
        log.info("⚡ Executing command with full output: {}", String.join(" ", cmd));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                output.append(line).append("\n");
                log.info("🐳 docker output: {}", line);
            }
        }

        p.waitFor();
        return output.toString();
    }

    // EXTRACT IMAGE NAME/ID
    private String extractImageFromLoad(String output) {
        log.info("🔍 Extracting image name or ID from docker load output");
        for (String line : output.split("\n")) {
            if (line.startsWith("Loaded image:")) {
                log.info("🏷️ Found loaded image: {}", line);
                return line.replace("Loaded image:", "").trim();
            }
        }

        for (String line : output.split("\n")) {
            if (line.startsWith("Loaded image ID:")) {
                log.info("🏷️ Found loaded image ID: {}", line);
                return line.replace("Loaded image ID:", "").trim();
            }
        }

        log.error("❌ Unable to detect loaded Docker image from output:\n{}", output);
        throw new RuntimeException("Unable to detect loaded Docker image from output:\n" + output);
    }
}
