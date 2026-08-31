package com.alz.screening.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ScreeningArtifactMapper {

    @Insert("""
            INSERT INTO screening_task_artifact
              (task_id,artifact_type,storage_uri,sha256,content_version,created_at)
            VALUES(#{taskId},#{artifactType},#{storageUri},#{sha256},#{contentVersion},NOW(3))
            ON DUPLICATE KEY UPDATE storage_uri=VALUES(storage_uri),sha256=VALUES(sha256)
            """)
    void upsert(@Param("taskId") String taskId,
                @Param("artifactType") String artifactType,
                @Param("storageUri") String storageUri,
                @Param("sha256") String sha256,
                @Param("contentVersion") int contentVersion);
}
