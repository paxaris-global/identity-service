package com.paxaris.identity_service.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paxaris.identity_service.dto.RoleCreationRequest;

import com.paxaris.identity_service.dto.SignupRequest;
import com.paxaris.identity_service.dto.UrlEntry;
import com.paxaris.identity_service.service.DynamicJwtDecoder;
import com.paxaris.identity_service.service.KeycloakClientService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/")
@RequiredArgsConstructor
public class KeycloakClientController {

    private final RestTemplate restTemplate = new RestTemplate();
    private final DynamicJwtDecoder jwtDecoder;
    private final KeycloakClientService clientService;
    private final ObjectMapper objectMapper;

    private static final Logger logger = LoggerFactory.getLogger(KeycloakClientController.class);
    // ------------------- TOKEN ----------------------------------------------------------------------------------------------------------------------------
    @PostMapping("/token")
    public ResponseEntity<Map<String, Object>> getToken(
            @RequestParam String realm,
            @RequestParam String username,
            @RequestParam String password,
            @RequestParam(name = "client_id") String clientId,
            @RequestParam(name = "client_secret", required = false) String clientSecret) {

        try {
            Map<String, Object> token = clientService.getMyRealmToken(username, password, clientId, clientSecret, realm);
            return ResponseEntity.ok(token);
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized", "message", e.getMessage()));
        }
    }


    @PostMapping("/{realm}/login")
    public ResponseEntity<Map<String, Object>> login(
            @PathVariable String realm,
            @RequestBody Map<String, String> credentials) {

        logger.info("🔹 Login request received for realm: {}", realm);
        logger.info("🔹 Received credential keys: {}", credentials.keySet());

        try {
            String username = credentials.get("username");
            String password = credentials.get("password");
            String clientId = credentials.getOrDefault("client_id", "product-service");
            String clientSecret = credentials.getOrDefault("client_secret", null);

            logger.info("🔹 Authenticating user '{}' with clientId '{}'", username, clientId);

            // Get Keycloak token
            Map<String, Object> tokenMap = clientService.getMyRealmToken(username, password, clientId, clientSecret, realm);
            logger.info("🔹 Keycloak response token map: {}", tokenMap);

            String keycloakToken = (String) tokenMap.get("access_token");
            if (keycloakToken == null) {
                logger.warn("⚠️ Invalid credentials or no token returned by Keycloak");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid credentials"));
            }

            // Return token only
            Map<String, Object> response = new HashMap<>();
            response.put("access_token", keycloakToken);
            response.put("expires_in", tokenMap.get("expires_in"));
            response.put("token_type", tokenMap.get("token_type"));

            logger.info("✅ Returning Keycloak token to client: {}", keycloakToken);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("❌ Login failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Login failed", "message", e.getMessage()));
        }
    }

    @GetMapping("/validate")
    public ResponseEntity<Map<String, Object>> validateToken(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of(
                            "status", "INVALID",
                            "message", "Authorization header missing or malformed"
                    ));
        }

        String token = authHeader.substring(7).trim();

        try {
            Jwt decodedJwt = jwtDecoder.decode(token);
            Map<String, Object> claims = decodedJwt.getClaims();

            // --- Safe extraction of realm roles ---
            Map<String, Object> realmAccess = claims.get("realm_access") instanceof Map ?
                    (Map<String, Object>) claims.get("realm_access") : Map.of();
            List<String> realmRoles = realmAccess.get("roles") instanceof List ?
                    ((List<?>) realmAccess.get("roles")).stream()
                            .map(Object::toString)
                            .toList() : List.of();

            // --- Safe extraction of client roles ---
            Map<String, Object> resourceAccess = claims.get("resource_access") instanceof Map ?
                    (Map<String, Object>) claims.get("resource_access") : Map.of();
            List<String> clientRoles = new ArrayList<>();
            for (Map.Entry<String, Object> entry : resourceAccess.entrySet()) {
                if (!(entry.getValue() instanceof Map)) continue;
                Map<String, Object> clientMap = (Map<String, Object>) entry.getValue();
                if (clientMap.get("roles") instanceof List<?> rolesList) {
                    rolesList.forEach(r -> clientRoles.add(r.toString()));
                }
            }

            // Merge roles
            List<String> allRoles = new ArrayList<>(realmRoles);
            allRoles.addAll(clientRoles);

            // Extract realm
            String realm = claims.getOrDefault("iss", "").toString();
            if (realm.contains("/realms/")) {
                realm = realm.substring(realm.lastIndexOf("/realms/") + 8);
            }

            // Product = client_id
            // Product = azp (Authorized Party)
            String product = claims.getOrDefault("azp", "").toString();


            // Debug log
            System.out.println("🔹 Token validated. Realm: " + realm + ", Product: " + product + ", Roles: " + allRoles);

            return ResponseEntity.ok(Map.of(
                    "status", "VALID",
                    "realm", realm,
                    "product", product,
                    "roles", allRoles
            ));

        } catch (Exception e) {
            System.err.println("❌ Token validation failed: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of(
                            "status", "INVALID",
                            "message", "Token invalid or expired: " + e.getMessage()
                    ));
        }
    }


    @GetMapping("/token/validate")
    public ResponseEntity<String> validateToken(
            @RequestParam String realm,
            @RequestHeader("Authorization") String authHeader) {

        String token = authHeader.startsWith("Bearer ") ? authHeader.substring(7) : authHeader;
        boolean valid = clientService.validateToken(realm, token);
        return valid ? ResponseEntity.ok("Token is valid") : ResponseEntity.badRequest().body("Token is invalid");
    }

    // ------------------- SIGNUP -------------------
    @PostMapping("/signup")
    public ResponseEntity<String> signup(@RequestBody SignupRequest request) {
        logger.info("Received signup request at Identity Service: {}", request);
        try {
            clientService.signup(request);
            logger.info("Signup completed successfully for realm: {}", request.getRealmName());
            return ResponseEntity.ok("Realm, client, and admin user created successfully.");
        } catch (Exception e) {
            logger.error("Signup failed at Identity Service: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body("Signup failed: " + e.getMessage());
        }
    }

    // ------------------- REALM ----------------------------------------------------------------------------------------------------------------------------
    @PostMapping("/realm")
    public ResponseEntity<String> createRealm(@RequestParam String realmName) {
        try {
            String masterToken = clientService.getMyRealmToken("admin", "admin123", "admin-cli", null, "master")
                    .get("access_token").toString();
            clientService.createRealm(realmName, masterToken);
            return ResponseEntity.ok("Realm created successfully: " + realmName);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Failed to create realm: " + e.getMessage());
        }
    }
//-------------------------------------------------------------------------------------
    @GetMapping("/realms")
    public ResponseEntity<List<Map<String, Object>>> getAllRealms() {
        try {
            String masterToken = clientService.getMyRealmToken("admin", "admin123", "admin-cli", null, "master")
                    .get("access_token").toString();
            return ResponseEntity.ok(clientService.getAllRealms(masterToken));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    // ------------------- CLIENT -------------------
    @PostMapping("/{realm}/clients")
    public ResponseEntity<String> createClient(
            @PathVariable String realm,
            @RequestHeader("Authorization") String authorizationHeader,
            @RequestParam String clientId,
            @RequestParam(defaultValue = "true") boolean publicClient) {

        String token = authorizationHeader.startsWith("Bearer ")
                ? authorizationHeader.substring(7)
                : authorizationHeader;

        clientService.createClient(realm, clientId, publicClient, token);
        return ResponseEntity.ok("Client created successfully");
    }
//-------------------------------------------------------------------------------------------------------------------------------------------
    @GetMapping("/client/{realm}/{clientName}/uuid")
    public ResponseEntity<String> getClientUUID(
            @PathVariable String realm,
            @PathVariable String clientName) {
        try {
            String masterToken = clientService.getMyRealmToken("admin", "admin123", "admin-cli", null, "master")
                    .get("access_token").toString();
            String clientUUID = clientService.getClientUUID(realm, clientName, masterToken);
            return ResponseEntity.ok(clientUUID);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Failed to get client UUID: " + e.getMessage());
        }
    }
//------------------------------------------------------------------------------------------------------------------------------------------------------------
    @GetMapping("/clients/{realm}")
    public ResponseEntity<List<Map<String, Object>>> getAllClients(@PathVariable String realm) {
        try {
            String masterToken = clientService.getMyRealmToken("admin", "admin123", "admin-cli", null, "master")
                    .get("access_token").toString();
            return ResponseEntity.ok(clientService.getAllClients(realm, masterToken));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    // ------------------- USER -------------------
    @PostMapping("/{realm}/users")
    public ResponseEntity<String> createUser(
            @PathVariable String realm,
            @RequestHeader("Authorization") String authorizationHeader,
            @RequestBody Map<String, Object> userPayload) {

        // Extract token from the Authorization header
        String token = authorizationHeader.startsWith("Bearer ")
                ? authorizationHeader.substring(7)
                : authorizationHeader;

        try {
            // Pass the provided token to the service
            String userId = clientService.createUser(realm, token, userPayload);
            return ResponseEntity.ok(userId);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Failed to create user: " + e.getMessage());
        }
    }
//-----------------------------------------------------------------------------------------------------------------------------------------------------
    @GetMapping("/users/{realm}")
    public ResponseEntity<List<Map<String, Object>>> getAllUsers(@PathVariable String realm) {
        try {
            String masterToken = clientService.getMyRealmToken("admin", "admin123", "admin-cli", null, "master")
                    .get("access_token").toString();
            return ResponseEntity.ok(clientService.getAllUsers(realm, masterToken));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    // ------------------- ROLE -------------------
    @PostMapping("/{realm}/clients/{clientName}/roles")
    public ResponseEntity<String> createClientRoles(
            @PathVariable String realm,
            @PathVariable String clientName,
            @RequestHeader("Authorization") String authorizationHeader,
            @RequestBody List<RoleCreationRequest> roleRequests) {

        String token = authorizationHeader.startsWith("Bearer ")
                ? authorizationHeader.substring(7)
                : authorizationHeader;

        try {
            clientService.createClientRoles(realm, clientName, roleRequests, token);
            return ResponseEntity.ok("Roles created successfully for client: " + clientName);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to create roles: " + e.getMessage());
        }
    }

//----------------------------------------------------------------------------------------------------------------------------------------------
    @PutMapping("/role/{realm}/{client}/{roleName}")
    public ResponseEntity<String> updateRole(
            @PathVariable String realm,
            @PathVariable String client,
            @PathVariable String roleName,
            @RequestBody RoleCreationRequest role) {
        try {
            String masterToken = clientService.getMyRealmToken("admin", "admin123", "admin-cli", null, "master")
                    .get("access_token").toString();
            String clientUUID = clientService.getClientId(realm, client, masterToken);
            boolean ok = clientService.updateRole(realm, clientUUID, roleName, role, masterToken);
            return ok ? ResponseEntity.ok("Role updated successfully") :
                    ResponseEntity.badRequest().body("Failed to update role");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Failed to update role: " + e.getMessage());
        }
    }

    //-----------------------------------------------------------------------------------------------------------------------------
    @DeleteMapping("/role/{realm}/{client}/{roleName}")
    public ResponseEntity<String> deleteRole(
            @PathVariable String realm,
            @PathVariable String client,
            @PathVariable String roleName) {
        try {
            String masterToken = clientService.getMyRealmToken("admin", "admin123", "admin-cli", null, "master")
                    .get("access_token").toString();
            String clientUUID = clientService.getClientId(realm, client, masterToken);
            boolean ok = clientService.deleteRole(realm, clientUUID, roleName, masterToken);
            return ok ? ResponseEntity.ok("Role deleted successfully") :
                    ResponseEntity.badRequest().body("Failed to delete role");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Failed to delete role: " + e.getMessage());
        }
    }

    // ------------------- ASSIGN ROLE -------------------
    // ------------------- ASSIGN CLIENT ROLE -------------------
    @PostMapping("/{realm}/users/{username}/clients/{clientName}/roles")
    public ResponseEntity<String> assignClientRoleToUser(
            @PathVariable String realm,
            @PathVariable String username,
            @PathVariable String clientName,
            @RequestHeader("Authorization") String authorizationHeader,
            @RequestParam String roleName) {
        try {
            // Extract token from header
            String token = authorizationHeader.startsWith("Bearer ")
                    ? authorizationHeader.substring(7)
                    : authorizationHeader;

            // Delegate to service
            clientService.assignClientRole(realm, username, clientName, roleName, token);

            return ResponseEntity.ok("Role assigned successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Failed to assign role: " + e.getMessage());
        }
    }

}
