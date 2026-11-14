package com.skyscan.repo;
import com.skyscan.domain.AnimalSighting;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.UUID;
public interface AnimalSightingRepo extends JpaRepository<AnimalSighting, UUID> {
    List<AnimalSighting> findByMissionIdOrderBySightedAtAsc(UUID missionId);
    long countByMissionId(UUID missionId);
}
