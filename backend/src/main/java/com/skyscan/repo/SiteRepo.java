package com.skyscan.repo;
import com.skyscan.domain.Site;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.UUID;
public interface SiteRepo extends JpaRepository<Site, UUID> {
    List<Site> findByOrganizationId(UUID organizationId);
}
