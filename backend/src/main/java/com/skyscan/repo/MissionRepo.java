package com.skyscan.repo;
import com.skyscan.domain.Mission;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.UUID;
public interface MissionRepo extends JpaRepository<Mission, UUID> {
    List<Mission> findBySiteIdOrderByCreatedAtDesc(UUID siteId);
}
