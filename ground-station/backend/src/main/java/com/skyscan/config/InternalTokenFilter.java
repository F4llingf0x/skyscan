package com.skyscan.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Shared-secret guard for /internal/** (engine callbacks, connector event ingestion). */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    private final String token;

    public InternalTokenFilter(@Value("${skyscan.internal.token}") String token) { this.token = token; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (req.getRequestURI().startsWith("/internal/")) {
            String provided = req.getHeader("X-Internal-Token");
            if (provided == null || !provided.equals(token)) {
                res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or bad internal token");
                return;
            }
        }
        chain.doFilter(req, res);
    }
}
