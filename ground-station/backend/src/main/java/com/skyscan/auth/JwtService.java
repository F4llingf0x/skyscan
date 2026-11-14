package com.skyscan.auth;

import com.skyscan.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public JwtService(@Value("${skyscan.jwt.secret}") String secret,
                      @Value("${skyscan.jwt.access-ttl-minutes}") long accessMinutes,
                      @Value("${skyscan.jwt.refresh-ttl-hours}") long refreshHours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = Duration.ofMinutes(accessMinutes);
        this.refreshTtl = Duration.ofHours(refreshHours);
    }

    public String issueAccessToken(User user) { return issue(user, accessTtl, "access"); }
    public String issueRefreshToken(User user) { return issue(user, refreshTtl, "refresh"); }

    private String issue(User user, Duration ttl, String type) {
        Instant now = Instant.now();
        return Jwts.builder()
                .setSubject(user.getUsername())
                .claim("role", user.getRole().name())
                .claim("uid", user.getId().toString())
                .claim("typ", type)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** Returns claims if valid, throws JwtException otherwise. */
    public Claims parse(String token) {
        return Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
    }
}
