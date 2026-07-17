package com.skyscan.repo;
import com.skyscan.domain.PlantFinding;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.UUID;
public interface PlantFindingRepo extends JpaRepository<PlantFinding, UUID> {
    List<PlantFinding> findByPlantIdOrderBySeverityAsc(UUID plantId);
    List<PlantFinding> findByPlantMissionIdOrderByPlantLabelAsc(UUID missionId);
}
