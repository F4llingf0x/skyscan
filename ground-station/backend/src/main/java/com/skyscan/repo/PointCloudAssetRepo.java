package com.skyscan.repo;
import com.skyscan.domain.PointCloudAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.UUID;
public interface PointCloudAssetRepo extends JpaRepository<PointCloudAsset, UUID> {
    List<PointCloudAsset> findByMissionSiteIdOrderByCreatedAtDesc(UUID siteId);
    List<PointCloudAsset> findByMissionIdOrderByCreatedAtDesc(UUID missionId);
}
