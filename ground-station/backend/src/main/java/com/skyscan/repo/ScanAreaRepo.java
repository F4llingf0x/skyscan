package com.skyscan.repo;
import com.skyscan.domain.PassType; import com.skyscan.domain.ScanArea;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional; import java.util.UUID;
public interface ScanAreaRepo extends JpaRepository<ScanArea, UUID> {
    Optional<ScanArea> findByMissionIdAndPassType(UUID missionId, PassType passType);
}
