package com.skyscan.repo;
import com.skyscan.domain.ProcessingJob;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.UUID;
public interface ProcessingJobRepo extends JpaRepository<ProcessingJob, UUID> {
    List<ProcessingJob> findByMissionIdOrderByCreatedAtDesc(UUID missionId);
}
