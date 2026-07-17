package com.skyscan.seed;

import com.skyscan.domain.*;
import com.skyscan.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds a demo world on first boot:
 *   admin / password      (ADMIN)
 *   operator / password   (OPERATOR)
 *   customer / password   (CUSTOMER, Acme Quarries)
 * plus a sample site near Budapest so the whole flow is clickable immediately.
 */
@Component
@Order(10)   // base world first; DemoMissionSeeder (@Order(20)) builds on it
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserRepo users;
    private final OrganizationRepo orgs;
    private final SiteRepo sites;
    private final PasswordEncoder encoder;

    public DataSeeder(UserRepo users, OrganizationRepo orgs, SiteRepo sites, PasswordEncoder encoder) {
        this.users = users; this.orgs = orgs; this.sites = sites; this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        if (users.count() > 0) return;

        Organization acme = new Organization();
        acme.setName("Acme Quarries Kft.");
        acme.setContactEmail("ops@acme-quarries.example");
        acme = orgs.save(acme);

        user("admin", "SkyScan Admin", Role.ADMIN, null);
        user("operator", "Field Operator", Role.OPERATOR, null);
        user("customer", "Acme Customer", Role.CUSTOMER, acme);

        Site site = new Site();
        site.setName("Kőbánya Quarry — North Pit");
        site.setDescription("Active quarry; monthly volumetric survey. Demo site pre-seeded for the sample flow.");
        site.setOrganization(acme);
        site.setCenterLat(47.4794);
        site.setCenterLon(19.1385);
        sites.save(site);

        log.info("Seeded demo users (admin/operator/customer, password: 'password') and demo site '{}'", site.getName());
    }

    private void user(String username, String display, Role role, Organization org) {
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash(encoder.encode("password"));
        u.setDisplayName(display);
        u.setRole(role);
        u.setOrganization(org);
        users.save(u);
    }
}
