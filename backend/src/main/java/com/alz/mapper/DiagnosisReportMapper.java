package com.alz.mapper;

import com.alz.entity.DiagnosisReport;
import com.alz.entity.ScreeningRiskLevel;
import com.alz.mapper.typehandler.StringListJsonTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;

import java.util.List;

@Mapper
public interface DiagnosisReportMapper {

    // 查询当前用户报告数量
    @Select("SELECT COUNT(*) FROM diagnosis_report WHERE user_id = #{userId}")
    Long countByUser(@Param("userId") long userId);

    // 删除最旧的一条
    @Delete("""
    DELETE FROM diagnosis_report
    WHERE id IN (
        SELECT id FROM (
            SELECT id
            FROM diagnosis_report
            WHERE user_id = #{userId}
            ORDER BY create_time ASC
            LIMIT 1
            ) AS temp
        )
    """)
    void deleteOldest(@Param("userId") long userId);

    // 插入报告
    @Insert("""
        INSERT INTO diagnosis_report
        (user_id,audio_name,transcription,report,screening_id,screening_task_id,screening_status,
         risk_level,risk_score,quality_passed,quality_issues,feature_highlights,
         model_version,disclaimer_version,create_time)
        VALUES
        (#{userId},#{audioName},#{transcription},#{report},#{screeningId},#{screeningTaskId},#{screeningStatus},
         #{riskLevel},#{riskScore},#{qualityPassed},
         #{qualityIssues,typeHandler=com.alz.mapper.typehandler.StringListJsonTypeHandler},
         #{featureHighlights,typeHandler=com.alz.mapper.typehandler.StringListJsonTypeHandler},
         #{modelVersion},#{disclaimerVersion},NOW())
    """)
    void insertReport(DiagnosisReport report);

    @Select("""
    SELECT
        id, user_id, audio_name, transcription, report, create_time,
        screening_id, screening_task_id, screening_status, risk_level, risk_score, quality_passed,
        quality_issues, feature_highlights, model_version, disclaimer_version
    FROM diagnosis_report
    WHERE user_id = #{userId}
    ORDER BY create_time DESC
    """)
    @org.apache.ibatis.annotations.Results(id = "diagnosisReportMap", value = {
            @org.apache.ibatis.annotations.Result(column = "id", property = "id"),
            @org.apache.ibatis.annotations.Result(column = "user_id", property = "userId"),
            @org.apache.ibatis.annotations.Result(column = "audio_name", property = "audioName"),
            @org.apache.ibatis.annotations.Result(column = "transcription", property = "transcription"),
            @org.apache.ibatis.annotations.Result(column = "report", property = "report"),
            @org.apache.ibatis.annotations.Result(column = "create_time", property = "createTime"),
            @org.apache.ibatis.annotations.Result(column = "screening_id", property = "screeningId"),
            @org.apache.ibatis.annotations.Result(column = "screening_task_id", property = "screeningTaskId"),
            @org.apache.ibatis.annotations.Result(column = "screening_status", property = "screeningStatus"),
            @org.apache.ibatis.annotations.Result(column = "risk_level", property = "riskLevel", javaType = ScreeningRiskLevel.class),
            @org.apache.ibatis.annotations.Result(column = "risk_score", property = "riskScore"),
            @org.apache.ibatis.annotations.Result(column = "quality_passed", property = "qualityPassed"),
            @org.apache.ibatis.annotations.Result(column = "quality_issues", property = "qualityIssues", javaType = List.class, typeHandler = StringListJsonTypeHandler.class),
            @org.apache.ibatis.annotations.Result(column = "feature_highlights", property = "featureHighlights", javaType = List.class, typeHandler = StringListJsonTypeHandler.class),
            @org.apache.ibatis.annotations.Result(column = "model_version", property = "modelVersion"),
            @org.apache.ibatis.annotations.Result(column = "disclaimer_version", property = "disclaimerVersion")
    })
    List<DiagnosisReport> listByUser(Long userId);

    // 查询最旧的audioName
    @Select("""
    SELECT audio_name
    FROM diagnosis_report
    WHERE user_id = #{userId}
    ORDER BY create_time ASC
    LIMIT 1
    """)
    String findOldestAudioName(Long userId);

    @Select("""
    SELECT
        id, user_id, audio_name, transcription, report, create_time,
        screening_id, screening_task_id, screening_status, risk_level, risk_score, quality_passed,
        quality_issues, feature_highlights, model_version, disclaimer_version
    FROM diagnosis_report
    WHERE audio_name = #{audioName}
    LIMIT 1
    """)
    @org.apache.ibatis.annotations.ResultMap("diagnosisReportMap")
    DiagnosisReport findByAudioName(String audioName);

    @Select("""
    SELECT COUNT(*) 
    FROM diagnosis_report 
    WHERE audio_name = #{audioName} 
    """)
    Long countByAudioName(String audioName);

    @Select("""
            SELECT
                id,user_id,audio_name,transcription,report,create_time,
                screening_id,screening_task_id,screening_status,risk_level,risk_score,quality_passed,
                quality_issues,feature_highlights,model_version,disclaimer_version
            FROM diagnosis_report WHERE screening_task_id=#{taskId} LIMIT 1
            """)
    @org.apache.ibatis.annotations.ResultMap("diagnosisReportMap")
    DiagnosisReport findByScreeningTaskId(String taskId);

    @Select("SELECT COUNT(*) FROM diagnosis_report WHERE screening_task_id=#{taskId}")
    Long countByScreeningTaskId(String taskId);

    @Delete("DELETE FROM diagnosis_report WHERE audio_name = #{audioName}")
    void deleteByAudio(String audioName);
}
