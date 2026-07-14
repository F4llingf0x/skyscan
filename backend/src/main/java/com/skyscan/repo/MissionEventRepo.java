package com.skyscan.repo;
import com.skyscan.domain.MissionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.UUID;
public interface MissionEventRepo extends JpaRepository<MissionEvent, UUID> {
    List<MissionEvent> findTop100ByMissionIdOrderByAtDesc(UUID missionId);
}
