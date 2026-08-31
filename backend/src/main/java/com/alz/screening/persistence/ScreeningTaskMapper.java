package com.alz.screening.persistence;

import com.alz.screening.domain.ScreeningTask;
import com.alz.screening.domain.ScreeningTaskStatus;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ScreeningTaskMapper {

    @Insert("""
            INSERT INTO screening_task
              (id,user_id,audio_record_id,audio_name,idempotency_key,status,current_stage,
               progress,model_version,attempt_count,version,trace_id,requested_at,updated_at)
            VALUES
              (#{id},#{userId},#{audioRecordId},#{audioName},#{idempotencyKey},#{status},#{currentStage},
               #{progress},#{modelVersion},#{attemptCount},0,#{traceId},NOW(3),NOW(3))
            """)
    void insert(ScreeningTask task);

    @Select("""
            SELECT id,user_id,audio_record_id,audio_name,idempotency_key,status,current_stage,
                   progress,model_version,attempt_count,version,error_code,error_message,trace_id,
                   requested_at,started_at,completed_at,updated_at
            FROM screening_task WHERE id=#{id}
            """)
    @Results(id = "screeningTaskMap", value = {
            @Result(column = "user_id", property = "userId"),
            @Result(column = "audio_record_id", property = "audioRecordId"),
            @Result(column = "audio_name", property = "audioName"),
            @Result(column = "idempotency_key", property = "idempotencyKey"),
            @Result(column = "status", property = "status", javaType = ScreeningTaskStatus.class),
            @Result(column = "current_stage", property = "currentStage"),
            @Result(column = "model_version", property = "modelVersion"),
            @Result(column = "attempt_count", property = "attemptCount"),
            @Result(column = "error_code", property = "errorCode"),
            @Result(column = "error_message", property = "errorMessage"),
            @Result(column = "trace_id", property = "traceId"),
            @Result(column = "requested_at", property = "requestedAt"),
            @Result(column = "started_at", property = "startedAt"),
            @Result(column = "completed_at", property = "completedAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    ScreeningTask findById(String id);

    @Select("""
            SELECT id,user_id,audio_record_id,audio_name,idempotency_key,status,current_stage,
                   progress,model_version,attempt_count,version,error_code,error_message,trace_id,
                   requested_at,started_at,completed_at,updated_at
            FROM screening_task WHERE id=#{id} AND user_id=#{userId}
            """)
    @org.apache.ibatis.annotations.ResultMap("screeningTaskMap")
    ScreeningTask findOwned(@Param("id") String id, @Param("userId") Long userId);

    @Select("""
            SELECT id,user_id,audio_record_id,audio_name,idempotency_key,status,current_stage,
                   progress,model_version,attempt_count,version,error_code,error_message,trace_id,
                   requested_at,started_at,completed_at,updated_at
            FROM screening_task WHERE user_id=#{userId} AND idempotency_key=#{key} LIMIT 1
            """)
    @org.apache.ibatis.annotations.ResultMap("screeningTaskMap")
    ScreeningTask findByIdempotencyKey(@Param("userId") Long userId, @Param("key") String key);

    @Select("""
            SELECT id,user_id,audio_record_id,audio_name,idempotency_key,status,current_stage,
                   progress,model_version,attempt_count,version,error_code,error_message,trace_id,
                   requested_at,started_at,completed_at,updated_at
            FROM screening_task WHERE audio_record_id=#{audioRecordId} LIMIT 1
            """)
    @org.apache.ibatis.annotations.ResultMap("screeningTaskMap")
    ScreeningTask findByAudioRecordId(Long audioRecordId);

    @Select("""
            SELECT COUNT(*) FROM screening_task
            WHERE user_id=#{userId}
              AND status IN ('QUEUED','TRANSCRIBING','FEATURE_EXTRACTING','LLM_ANALYZING',
                             'RESULT_PERSISTING','PDF_QUEUED','PDF_GENERATING','RETRY_WAIT','CANCEL_REQUESTED')
            """)
    int countActiveByUser(Long userId);

    @Select("""
            <script>
            SELECT id,user_id,audio_record_id,audio_name,idempotency_key,status,current_stage,
                   progress,model_version,attempt_count,version,error_code,error_message,trace_id,
                   requested_at,started_at,completed_at,updated_at
            FROM screening_task
            WHERE user_id=#{userId}
            <if test='activeOnly'>
              AND status IN ('QUEUED','TRANSCRIBING','FEATURE_EXTRACTING','LLM_ANALYZING',
                             'RESULT_PERSISTING','PDF_QUEUED','PDF_GENERATING','RETRY_WAIT','CANCEL_REQUESTED')
            </if>
            ORDER BY requested_at DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    @org.apache.ibatis.annotations.ResultMap("screeningTaskMap")
    List<ScreeningTask> listOwned(@Param("userId") Long userId,
                                  @Param("activeOnly") boolean activeOnly,
                                  @Param("offset") int offset,
                                  @Param("limit") int limit);

    @Update("""
            UPDATE screening_task
            SET status=#{status}, current_stage=#{stage}, progress=#{progress},
                started_at=COALESCE(started_at,NOW(3)), version=version+1, updated_at=NOW(3)
            WHERE id=#{taskId}
              AND attempt_count=#{attempt}
              AND progress <= #{progress}
              AND status NOT IN ('COMPLETED','FAILED','CANCELLED','CANCEL_REQUESTED')
            """)
    int advance(@Param("taskId") String taskId,
                @Param("attempt") int attempt,
                @Param("status") String status,
                @Param("stage") String stage,
                @Param("progress") int progress);

    @Update("""
            UPDATE screening_task
            SET status='PDF_QUEUED',current_stage='PDF',progress=85,model_version=#{modelVersion},
                error_code=NULL,error_message=NULL,version=version+1,updated_at=NOW(3)
            WHERE id=#{taskId}
              AND attempt_count=#{attempt}
              AND status NOT IN ('COMPLETED','FAILED','CANCELLED','CANCEL_REQUESTED')
            """)
    int markPdfQueued(@Param("taskId") String taskId,
                      @Param("attempt") int attempt,
                      @Param("modelVersion") String modelVersion);

    @Update("""
            UPDATE screening_task
            SET status='COMPLETED',current_stage='COMPLETED',progress=100,completed_at=NOW(3),
                error_code=NULL,error_message=NULL,version=version+1,updated_at=NOW(3)
            WHERE id=#{taskId} AND attempt_count=#{attempt}
              AND status IN ('PDF_QUEUED','PDF_GENERATING')
            """)
    int markCompleted(@Param("taskId") String taskId, @Param("attempt") int attempt);

    @Update("""
            UPDATE screening_task
            SET status='FAILED',current_stage=#{stage},error_code=#{errorCode},error_message=#{errorMessage},
                version=version+1,updated_at=NOW(3)
            WHERE id=#{taskId} AND attempt_count=#{attempt}
              AND status NOT IN ('COMPLETED','CANCELLED','CANCEL_REQUESTED')
            """)
    int markFailed(@Param("taskId") String taskId,
                   @Param("attempt") int attempt,
                   @Param("stage") String stage,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE screening_task
            SET status='CANCEL_REQUESTED',current_stage='CANCELLATION',version=version+1,updated_at=NOW(3)
            WHERE id=#{taskId}
              AND status NOT IN ('COMPLETED','FAILED','CANCELLED','CANCEL_REQUESTED')
            """)
    int requestCancellation(String taskId);

    @Update("""
            UPDATE screening_task
            SET status='CANCELLED',current_stage='CANCELLED',completed_at=NOW(3),
                version=version+1,updated_at=NOW(3)
            WHERE id=#{taskId} AND attempt_count=#{attempt}
              AND status NOT IN ('COMPLETED','FAILED','CANCELLED')
            """)
    int markCancelled(@Param("taskId") String taskId, @Param("attempt") int attempt);

    @Update("""
            UPDATE screening_task
            SET status='CANCELLED',current_stage='CANCELLED',completed_at=NOW(3),
                version=version+1,updated_at=NOW(3)
            WHERE id=#{taskId} AND status IN ('QUEUED','RETRY_WAIT')
            """)
    int markCancelledIfNotStarted(String taskId);

    @Update("""
            UPDATE screening_task
            SET status='CANCELLED',current_stage='CANCELLED',completed_at=NOW(3),
                version=version+1,updated_at=NOW(3)
            WHERE status='CANCEL_REQUESTED' AND updated_at < #{cutoff}
            """)
    int markTimedOutCancellations(java.time.LocalDateTime cutoff);

    @Update("""
            UPDATE screening_task
            SET status='QUEUED',current_stage='QUEUED',progress=0,attempt_count=attempt_count+1,
                error_code=NULL,error_message=NULL,started_at=NULL,completed_at=NULL,
                requested_at=NOW(3),version=version+1,updated_at=NOW(3)
            WHERE id=#{taskId} AND status IN ('FAILED','CANCELLED')
            """)
    int retryTerminal(String taskId);

    @Select("""
            SELECT COUNT(*) FROM screening_task
            WHERE user_id=#{userId} AND audio_name=#{audioName}
              AND status IN ('QUEUED','TRANSCRIBING','FEATURE_EXTRACTING','LLM_ANALYZING',
                             'RESULT_PERSISTING','PDF_QUEUED','PDF_GENERATING','RETRY_WAIT','CANCEL_REQUESTED')
            """)
    int countActiveByAudioName(@Param("audioName") String audioName, @Param("userId") Long userId);
}
