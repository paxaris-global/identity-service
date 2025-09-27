package com.paxaris.identity_service.controller;

import com.paxaris.identity_service.dto.RoleCreationRequest;
import com.paxaris.identity_service.dto.SignupRequest;
import com.paxaris.identity_service.service.KeycloakClientService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/keycloak")
@RequiredArgsConstructor
public class KeycloakClientController {

    private final KeycloakClientService clientService;
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
            @RequestParam String username,
            @RequestParam String password,
            @RequestParam String clientId,
            @RequestParam(required = false) String clientSecret) {

        try {
            Map<String, Object> token = clientService.getRealmToken(
                    realm, username, password, clientId, clientSecret
            );
            return ResponseEntity.ok(token);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of(
                            "error", "Unauthorized",
                            "message", e.getMessage()
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
        logger.info("Received signup request at Identity Service: {}", request); // log request
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
