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

    /* -------------------------------------------------
       Repo name: realm-clientId
     ------------------------------------------------- */
    private String repo(String realm, String client) {
        return (realm + "-" + client).toLowerCase();
    }

    /* -------------------------------------------------
       STEP 1: GET DOCKER HUB JWT
     ------------------------------------------------- */
    private String getDockerJwt() {
        try {
            URL url = new URL("https://hub.docker.com/v2/users/login/");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();

            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setDoOutput(true);

            String body = """
            {
              "username": "%s",
              "password": "%s"
            }
            """.formatted(dockerUsername, dockerPat);

            con.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));

            if (con.getResponseCode() != 200) {
                throw new RuntimeException("Docker Hub login API failed");
            }

            BufferedReader br = new BufferedReader(
                    new InputStreamReader(con.getInputStream())
            );
            String response = br.readLine();

            // {"token":"JWT_TOKEN"}
            return response.split("\"token\":\"")[1].split("\"")[0];

        } catch (Exception e) {
            throw new RuntimeException("Failed to obtain Docker JWT", e);
        }
    }

    /* -------------------------------------------------
       STEP 2: CREATE REPOSITORY
     ------------------------------------------------- */
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

            String body = """
            {
              "name": "%s",
              "is_private": false
            }
            """.formatted(repoName);

            con.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));

            int code = con.getResponseCode();
            if (code == 201 || code == 409) {
                log.info("✅ Docker Hub repository ready: {}", repoName);
                return;
            }

            throw new RuntimeException("Repo creation failed HTTP " + code);

        } catch (Exception e) {
            throw new RuntimeException("Docker repo creation failed", e);
        }
    }

    /* -------------------------------------------------
       STEP 3: SAVE TAR FILE
     ------------------------------------------------- */
    public File saveDockerImage(MultipartFile file) {
        try {
            File tar = File.createTempFile("docker-", ".tar");
            file.transferTo(tar);
            log.info("📦 Docker image tar saved: {}", tar.getAbsolutePath());
            return tar;
        } catch (IOException e) {
            throw new RuntimeException("Failed to save docker image tar", e);
        }
    }

    /* -------------------------------------------------
       STEP 4: PUSH IMAGE
     ------------------------------------------------- */
    public void pushDockerImage(File tar, String realm, String client) {
        String repoName = repo(realm, client);
        String fullTag = dockerUsername + "/" + repoName + ":latest";

        try {
            dockerLogin();

            String loadOutput = execWithOutput(
                    "docker", "load", "-i", tar.getAbsolutePath()
            );
            String sourceImage = extractImageName(loadOutput);

            exec("docker", "tag", sourceImage, fullTag);
            exec("docker", "push", fullTag);

            log.info("🚀 Docker image pushed: {}", fullTag);

        } catch (Exception e) {
            throw new RuntimeException("Docker image push failed", e);
        } finally {
            if (tar.exists()) tar.delete();
        }
    }

    /* -------------------------------------------------
       DOCKER LOGIN (FIXED)
     ------------------------------------------------- */
    private void dockerLogin() throws Exception {
        Process process = new ProcessBuilder(
                "docker", "login", "-u", dockerUsername, "--password-stdin"
        ).start();

        try (BufferedWriter writer =
                     new BufferedWriter(new OutputStreamWriter(process.getOutputStream()))) {
            writer.write(dockerPat);
        }

        if (process.waitFor() != 0) {
            throw new RuntimeException("Docker login failed");
        }

        log.info("✅ Docker login successful");
    }

    /* -------------------------------------------------
       PROCESS HELPERS
     ------------------------------------------------- */
    private void exec(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        if (p.waitFor() != 0) {
            throw new RuntimeException("Command failed: " + String.join(" ", cmd));
        }
    }

    private String execWithOutput(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).start();
        BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream())
        );
        String output = br.readLine();
        p.waitFor();
        return output;
    }

    private String extractImageName(String output) {
        // Example: "Loaded image: myapp:1.0"
        if (output != null && output.contains("Loaded image:")) {
            return output.replace("Loaded image: ", "").trim();
        }
        throw new RuntimeException("Unable to detect loaded image name");
    }
}
