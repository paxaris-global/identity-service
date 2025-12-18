package com.paxaris.identity_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
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
    private String dockerPat; // Docker Hub PAT

    // REPO NAME → realm-client
    private String repo(String realm, String client) {
        return (realm + "-" + client).toLowerCase();
    }

    /* =========================================================
       STEP 1: GET DOCKER HUB JWT
       ========================================================= */
    private String getDockerJwt() {
        try {
            URL url = new URL("https://hub.docker.com/v2/users/login/");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();

            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setDoOutput(true);

            String body = String.format(
                    "{\"username\":\"%s\",\"password\":\"%s\"}",
                    dockerUsername, dockerPat
            );

            try (OutputStream os = con.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = con.getResponseCode();
            InputStream is = code >= 400 ? con.getErrorStream() : con.getInputStream();
            String response = new String(is.readAllBytes());

            if (code != 200) {
                throw new RuntimeException("Docker login failed: " + response);
            }

            return response.split("\"token\":\"")[1].split("\"")[0];

        } catch (Exception e) {
            throw new RuntimeException("Failed to obtain Docker JWT", e);
        }
    }

    /* =========================================================
       STEP 2: CREATE REPOSITORY (UNCHANGED)
       ========================================================= */
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

            String body = String.format(
                    "{\"name\":\"%s\",\"is_private\":false}", repoName
            );

            try (OutputStream os = con.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = con.getResponseCode();
            if (code == 201 || code == 409) {
                log.info("✅ Repo ready: {}", repoName);
                return;
            }

            throw new RuntimeException("Repo creation failed HTTP " + code);

        } catch (Exception e) {
            throw new RuntimeException("Docker repo creation failed", e);
        }
    }

    /* =========================================================
       STEP 3: SAVE TAR FILE
       ========================================================= */
    public File saveDockerImage(MultipartFile file) {
        try {
            if (file.isEmpty()) {
                throw new RuntimeException("Empty docker image uploaded");
            }

            File tar = File.createTempFile("docker-", ".tar");
            file.transferTo(tar);
            return tar;

        } catch (IOException e) {
            throw new RuntimeException("Failed to save docker image", e);
        }
    }

    /* =========================================================
       STEP 4: PUSH IMAGE (FIXED & ASYNC)
       ========================================================= */
    @Async
    public void pushDockerImage(File tar, String realm, String client) {
        String repoName = repo(realm, client);
        String fullTag = dockerUsername + "/" + repoName + ":latest";

        try {
            dockerLogin();

            log.info("🐳 docker load {}", tar.getAbsolutePath());
            String loadOutput = execWithOutput(
                    "docker", "load", "-i", tar.getAbsolutePath()
            );

            String sourceImage = extractImageFromLoad(loadOutput);

            exec("docker", "tag", sourceImage, fullTag);
            exec("docker", "push", fullTag);

            log.info("✅ Image pushed: {}", fullTag);

        } catch (Exception e) {
            log.error("❌ Docker push failed", e);
        } finally {
            if (tar.exists()) tar.delete();
        }
    }

    /* =========================================================
       DOCKER LOGIN
       ========================================================= */
    private void dockerLogin() throws Exception {
        Process p = new ProcessBuilder(
                "docker", "login",
                "--username", dockerUsername,
                "--password-stdin"
        ).start();

        try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(p.getOutputStream())
        )) {
            w.write(dockerPat);
        }

        if (p.waitFor() != 0) {
            throw new RuntimeException("Docker login failed");
        }
    }

    /* =========================================================
       HELPERS
       ========================================================= */
    private void exec(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream())
        )) {
            while (br.readLine() != null) {}
        }

        if (p.waitFor() != 0) {
            throw new RuntimeException("Command failed: " + String.join(" ", cmd));
        }
    }

    private String execWithOutput(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start();

        String output = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return output;
    }

    private String extractImageFromLoad(String output) {
        for (String line : output.split("\n")) {
            if (line.startsWith("Loaded image:")) {
                return line.replace("Loaded image:", "").trim();
            }
            if (line.startsWith("Loaded image ID:")) {
                return line.replace("Loaded image ID:", "").trim();
            }
        }
        throw new RuntimeException("Cannot detect image from docker load");
    }
}
