package com.alz.mapper;

import com.alz.entity.PdfReport;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface PdfReportMapper {

    @Insert("""
        INSERT INTO pdf_report(screening_task_id,audio_name,pdf_name,file_sha256,file_size,user_id,create_time)
        VALUES(#{screeningTaskId},#{audioName},#{pdfName},#{fileSha256},#{fileSize},#{userId},NOW())
        ON DUPLICATE KEY UPDATE pdf_name=VALUES(pdf_name),file_sha256=VALUES(file_sha256),
                                file_size=VALUES(file_size)
    """)
    void insert(PdfReport report);

    @Select("""
    SELECT
        id,
        screening_task_id AS screeningTaskId,
        audio_name AS audioName,
        user_id AS userId,
        pdf_name AS pdfName,
        file_sha256 AS fileSha256,
        file_size AS fileSize,
        create_time AS createTime
    FROM pdf_report
    WHERE user_id = #{userId}
    ORDER BY create_time DESC
    """)
    List<PdfReport> listByUser(@Param("userId") Long userId);

    // 根据音频名查询PDF
    @Select("""
        SELECT pdf_name
        FROM pdf_report
        WHERE audio_name = #{audioName}
        LIMIT 1
    """)
    String findPdfByAudio(String audioName);

    // 删除PDF记录
    @Delete("""
        DELETE FROM pdf_report
        WHERE audio_name = #{audioName}
    """)
    void deleteByAudio(String audioName);

    @Select("""
    SELECT 
        id AS id,
        audio_name AS audioName,
        pdf_name AS pdfName,
        user_id AS userId,
        create_time AS createTime
    FROM pdf_report
    WHERE pdf_name = #{pdfName}
    LIMIT 1
    """)
    PdfReport findByPdfName(String pdfName);

    @Select("""
            SELECT id,screening_task_id AS screeningTaskId,audio_name AS audioName,
                   pdf_name AS pdfName,file_sha256 AS fileSha256,file_size AS fileSize,
                   user_id AS userId,create_time AS createTime
            FROM pdf_report WHERE screening_task_id=#{taskId} LIMIT 1
            """)
    PdfReport findByScreeningTaskId(String taskId);

    @Delete("DELETE FROM pdf_report WHERE audio_name = #{audioName}")
    void deletepdfByAudio(String audioName);
}
