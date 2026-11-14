package com.skyscan.repo;
import com.skyscan.domain.Plant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.UUID;
public interface PlantRepo extends JpaRepository<Plant, UUID> {
    List<Plant> findByMissionIdOrderByLabelAsc(UUID missionId);
    long countByMissionId(UUID missionId);
}
