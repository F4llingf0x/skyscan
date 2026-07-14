package com.skyscan.auth;

import com.skyscan.domain.User;
import com.skyscan.repo.UserRepo;
import io.jsonwebtoken.Claims;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record RefreshRequest(@NotBlank String refreshToken) {}

    private final UserRepo userRepo;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final CurrentUser current;

    public AuthController(UserRepo userRepo, PasswordEncoder encoder, JwtService jwt, CurrentUser current) {
        this.userRepo = userRepo; this.encoder = encoder; this.jwt = jwt; this.current = current;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest req) {
        User user = userRepo.findByUsername(req.username())
                .filter(u -> encoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Bad credentials"));
        return tokens(user);
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@RequestBody RefreshRequest req) {
        try {
            Claims claims = jwt.parse(req.refreshToken());
            if (!"refresh".equals(claims.get("typ", String.class))) throw new IllegalArgumentException();
            User user = userRepo.findByUsername(claims.getSubject()).orElseThrow();
            return tokens(user);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        User u = current.require();
        Map<String, Object> out = new HashMap<>();
        out.put("id", u.getId());
        out.put("username", u.getUsername());
        out.put("displayName", u.getDisplayName());
        out.put("role", u.getRole().name());
        out.put("organization", u.getOrganization() == null ? null : u.getOrganization().getName());
        return out;
    }

    private Map<String, Object> tokens(User user) {
        return Map.of(
                "accessToken", jwt.issueAccessToken(user),
                "refreshToken", jwt.issueRefreshToken(user),
                "user", Map.of("username", user.getUsername(), "displayName", user.getDisplayName(),
                        "role", user.getRole().name()));
    }
}
