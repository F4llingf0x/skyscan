package com.skyscan.site;

import com.skyscan.auth.CurrentUser;
import com.skyscan.domain.*;
import com.skyscan.dto.Dtos.*;
import com.skyscan.repo.OrganizationRepo;
import com.skyscan.repo.SiteRepo;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/sites")
public class SiteController {

    private final SiteRepo sites;
    private final OrganizationRepo orgs;
    private final CurrentUser current;

    public SiteController(SiteRepo sites, OrganizationRepo orgs, CurrentUser current) {
        this.sites = sites; this.orgs = orgs; this.current = current;
    }

    /** Customers see only their organization's sites; staff see all. */
    @GetMapping
    public List<SiteView> list() {
        User u = current.require();
        List<Site> result = current.isStaff(u)
                ? sites.findAll()
                : sites.findByOrganizationId(u.getOrganization().getId());
        return result.stream().map(SiteView::of).toList();
    }

    @GetMapping("/{id}")
    public SiteView get(@PathVariable UUID id) {
        Site site = sites.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Site not found"));
        current.checkSiteAccess(current.require(), site);
        return SiteView.of(site);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public SiteView create(@Valid @RequestBody CreateSiteRequest req) {
        Organization org = orgs.findById(req.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found"));
        Site s = new Site();
        s.setName(req.name());
        s.setDescription(req.description());
        s.setOrganization(org);
        s.setCenterLat(req.centerLat());
        s.setCenterLon(req.centerLon());
        return SiteView.of(sites.save(s));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable UUID id) { sites.deleteById(id); }
}
