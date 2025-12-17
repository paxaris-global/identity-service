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
    private String dockerToken; // PAT token

    /** repo = realm-clientId */
    private String repo(String realm, String client) {
        return (realm + "-" + client).toLowerCase();
    }

    /** ----------------------------------------
     *  CREATE DOCKER HUB REPOSITORY
     *  ---------------------------------------- */
    public void createRepository(String realm, String client) {
        String repoName = repo(realm, client);

        try {
            URL url = new URL("https://hub.docker.com/v2/repositories/" + dockerUsername + "/");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();

            con.setRequestMethod("POST");
            con.setRequestProperty("Authorization", "Bearer " + dockerToken);
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
                log.info("✅ Docker Hub repo ready: {}", repoName);
            } else {
                throw new RuntimeException("Repo create failed HTTP " + code);
            }

        } catch (Exception e) {
            throw new RuntimeException("Docker repo creation failed", e);
        }
    }

    /** ----------------------------------------
     *  SAVE TAR FILE
     *  ---------------------------------------- */
    public File saveDockerImage(MultipartFile file) {
        try {
            File tar = File.createTempFile("docker-", ".tar");
            file.transferTo(tar);
            return tar;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** ----------------------------------------
     *  PUSH IMAGE
     *  ---------------------------------------- */
    public void pushDockerImage(File tar, String realm, String client) {
        String repoName = repo(realm, client);
        String fullTag = dockerUsername + "/" + repoName + ":latest";

        try {
            exec("docker", "login", "-u", dockerUsername, "--password-stdin", dockerToken);

            // Load and CAPTURE IMAGE NAME
            String loadedImage = execWithOutput("docker", "load", "-i", tar.getAbsolutePath());
            String sourceImage = extractImageName(loadedImage);

            exec("docker", "tag", sourceImage, fullTag);
            exec("docker", "push", fullTag);

            log.info("🚀 Image pushed: {}", fullTag);

        } catch (Exception e) {
            throw new RuntimeException("Docker push failed", e);
        } finally {
            tar.delete();
        }
    }

    /** ----------------------------------------
     *  UTIL METHODS
     *  ---------------------------------------- */

    private void exec(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        if (p.waitFor() != 0)
            throw new RuntimeException("Command failed: " + String.join(" ", cmd));
    }

    private String execWithOutput(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).start();
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String out = br.readLine();
        p.waitFor();
        return out;
    }

    private String extractImageName(String dockerLoadOutput) {
        // "Loaded image: myapp:1.0"
        if (dockerLoadOutput.contains("Loaded image:")) {
            return dockerLoadOutput.replace("Loaded image: ", "").trim();
        }
        throw new RuntimeException("Cannot detect loaded image name");
    }
}
