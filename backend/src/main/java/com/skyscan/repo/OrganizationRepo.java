package com.skyscan.repo;
import com.skyscan.domain.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional; import java.util.UUID;
public interface OrganizationRepo extends JpaRepository<Organization, UUID> {
    Optional<Organization> findByName(String name);
}
