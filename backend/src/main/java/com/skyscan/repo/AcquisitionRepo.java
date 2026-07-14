package com.skyscan.repo;
import com.skyscan.domain.Acquisition;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.UUID;
public interface AcquisitionRepo extends JpaRepository<Acquisition, UUID> {
    List<Acquisition> findByMissionIdOrderByCapturedAtAsc(UUID missionId);
}
