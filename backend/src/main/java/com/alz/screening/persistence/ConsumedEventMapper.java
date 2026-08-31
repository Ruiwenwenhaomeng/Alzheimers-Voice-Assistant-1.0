package com.alz.screening.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConsumedEventMapper {

    @Insert("""
            INSERT IGNORE INTO consumed_event(consumer_name,event_id,processed_at)
            VALUES(#{consumerName},#{eventId},NOW(3))
            """)
    int claim(@Param("consumerName") String consumerName, @Param("eventId") String eventId);
}
