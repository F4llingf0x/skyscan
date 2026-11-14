package com.skyscan.auth;

import com.skyscan.domain.Role;
import com.skyscan.domain.Site;
import com.skyscan.domain.User;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Helper for pulling the authenticated user and enforcing customer data scoping. */
@Component
public class CurrentUser {

    public User require() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User user))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        return user;
    }

    public boolean isStaff(User user) {
        return user.getRole() == Role.ADMIN || user.getRole() == Role.OPERATOR;
    }

    /** Customers may only touch sites of their own organization. Staff see everything. */
    public void checkSiteAccess(User user, Site site) {
        if (isStaff(user)) return;
        if (user.getOrganization() == null
                || !user.getOrganization().getId().equals(site.getOrganization().getId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your site");
    }
}
