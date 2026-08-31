package com.alz.screening.persistence;

import com.alz.screening.domain.OutboxEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OutboxEventMapper {

    @Insert("""
            INSERT INTO outbox_event
              (event_id,aggregate_type,aggregate_id,event_type,schema_version,payload,status,
               attempt_count,next_attempt_at,created_at)
            VALUES
              (#{eventId},#{aggregateType},#{aggregateId},#{eventType},#{schemaVersion},#{payload},
               'NEW',0,NOW(3),NOW(3))
            """)
    void insert(OutboxEvent event);

    @Select("""
            SELECT event_id AS eventId,aggregate_type AS aggregateType,aggregate_id AS aggregateId,
                   event_type AS eventType,schema_version AS schemaVersion,payload,status,
                   attempt_count AS attemptCount,next_attempt_at AS nextAttemptAt,
                   created_at AS createdAt,published_at AS publishedAt
            FROM outbox_event
            WHERE status IN ('NEW','FAILED') AND next_attempt_at <= NOW(3)
            ORDER BY created_at ASC LIMIT #{limit}
            """)
    List<OutboxEvent> findReady(@Param("limit") int limit);

    @Update("""
            UPDATE outbox_event SET status='PUBLISHED',published_at=NOW(3)
            WHERE event_id=#{eventId} AND status!='PUBLISHED'
            """)
    int markPublished(String eventId);

    @Update("""
            UPDATE outbox_event
            SET status='FAILED',attempt_count=attempt_count+1,next_attempt_at=#{nextAttemptAt}
            WHERE event_id=#{eventId} AND status!='PUBLISHED'
            """)
    int markFailed(@Param("eventId") String eventId,
                   @Param("nextAttemptAt") LocalDateTime nextAttemptAt);
}
