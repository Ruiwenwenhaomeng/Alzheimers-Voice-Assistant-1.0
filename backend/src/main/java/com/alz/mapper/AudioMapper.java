package com.alz.mapper;

import com.alz.entity.AudioFullInfo;
import com.alz.entity.AudioRecord;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface AudioMapper {

    @Insert("""
            INSERT INTO audio_record
                (user_id,file_path,duration,image_name,consent_version,consent_time,task_type)
            VALUES
                (#{userId},#{filePath},#{duration},#{imageName},#{consentVersion},
                 CASE WHEN #{consentVersion} IS NULL THEN NULL ELSE NOW() END,#{taskType})
            """)
    void insert(AudioRecord record);

    @Select("SELECT * FROM audio_record")
    @Results({
            @Result(column="id", property="id"),
            @Result(column="user_id", property="userId"),
            @Result(column="file_path", property="filePath"),
            @Result(column="duration", property="duration"),
            @Result(column="upload_time", property="uploadTime"),
            @Result(column="image_name", property="imageName"),
            @Result(column="consent_version", property="consentVersion"),
            @Result(column="consent_time", property="consentTime"),
            @Result(column="task_type", property="taskType")
    })
    List<AudioRecord> listAll();

    @Delete("DELETE FROM audio_record WHERE id = #{id}")
    void deleteById(Long id);

    @Delete("DELETE FROM audio_record WHERE file_path = #{filePath} AND user_id = #{userId}")
    int deleteByFilePathAndUserId(@Param("filePath") String filePath,
                                  @Param("userId") Long userId);

    @Select("SELECT id, user_id AS userId, file_path AS filePath, duration, upload_time AS uploadTime, " +
            "image_name AS imageName, consent_version AS consentVersion, consent_time AS consentTime, task_type AS taskType " +
            "FROM audio_record WHERE user_id = #{userId} ORDER BY upload_time DESC")
    List<AudioRecord> findByUserId(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM audio_record WHERE file_path = #{filePath} AND user_id = #{userId}")
    Long countByFilePathAndUserId(@Param("filePath") String filePath,
                                  @Param("userId") Long userId);

    @Select("""
            SELECT id,user_id AS userId,file_path AS filePath,duration,upload_time AS uploadTime,
                   image_name AS imageName,consent_version AS consentVersion,
                   consent_time AS consentTime,task_type AS taskType
            FROM audio_record WHERE id=#{id} AND user_id=#{userId} LIMIT 1
            """)
    AudioRecord findOwnedById(@Param("id") Long id, @Param("userId") Long userId);

    @Select("""
            SELECT id,user_id AS userId,file_path AS filePath,duration,upload_time AS uploadTime,
                   image_name AS imageName,consent_version AS consentVersion,
                   consent_time AS consentTime,task_type AS taskType
            FROM audio_record WHERE file_path=#{filePath} AND user_id=#{userId} LIMIT 1
            """)
    AudioRecord findOwnedByFilePath(@Param("filePath") String filePath,
                                    @Param("userId") Long userId);

    // 新增：按 username 和 name 查询完整音频信息
    @Select({
            "<script>",
            "SELECT a.id,",
            "       a.user_id AS userId,",
            "       u.username AS username,",
            "       p.name AS name,",
            "       a.file_path AS filePath,",
            "       a.duration,",
            "       a.upload_time AS uploadTime,",
            "       a.image_name AS imageName",
            "FROM audio_record a",
            "LEFT JOIN user u ON a.user_id = u.id",
            "LEFT JOIN user_profile p ON u.id = p.user_id",
            "WHERE a.user_id != 0",   // 只显示普通用户上传的音频
            "<if test='username != null and username != \"\"'>",
            "  AND u.username LIKE CONCAT('%', #{username}, '%')",
            "</if>",
            "<if test='name != null and name != \"\"'>",
            "  AND p.name LIKE CONCAT('%', #{name}, '%')",
            "</if>",
            "ORDER BY a.upload_time DESC",
            "</script>"
    })
    List<AudioFullInfo> listFull(@Param("username") String username,
                                 @Param("name") String name);
}
