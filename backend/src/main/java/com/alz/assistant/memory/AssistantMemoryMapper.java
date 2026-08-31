package com.alz.assistant.memory;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AssistantMemoryMapper {
    @Insert("""
            INSERT IGNORE INTO assistant_memory(conversation_id,from_turn,to_turn,summary)
            VALUES(#{conversationId},#{fromTurn},#{toTurn},#{summary})
            """)
    int insert(@Param("conversationId") String conversationId,
               @Param("fromTurn") int fromTurn,
               @Param("toTurn") int toTurn,
               @Param("summary") String summary);
}
