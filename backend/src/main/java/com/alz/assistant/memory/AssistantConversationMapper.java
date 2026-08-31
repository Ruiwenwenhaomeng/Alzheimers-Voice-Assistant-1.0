package com.alz.assistant.memory;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AssistantConversationMapper {

    @Insert("""
            INSERT INTO assistant_conversation
              (id,owner_type,owner_key,title,user_turn_count,summary_up_to_turn,rolling_summary,generation_status)
            VALUES (#{id},#{ownerType},#{ownerKey},#{title},0,0,'','IDLE')
            """)
    void insert(AssistantConversation conversation);

    @Select("""
            SELECT * FROM assistant_conversation
            WHERE owner_type=#{ownerType} AND owner_key=#{ownerKey}
            ORDER BY updated_at DESC LIMIT 100
            """)
    List<AssistantConversation> listOwned(@Param("ownerType") String ownerType,
                                          @Param("ownerKey") String ownerKey);

    @Select("""
            SELECT * FROM assistant_conversation
            WHERE id=#{id} AND owner_type=#{ownerType} AND owner_key=#{ownerKey}
            """)
    AssistantConversation findOwned(@Param("id") String id,
                                    @Param("ownerType") String ownerType,
                                    @Param("ownerKey") String ownerKey);

    @Update("""
            UPDATE assistant_conversation
            SET generation_status='GENERATING', generation_started_at=NOW(3),
                user_turn_count=user_turn_count+1, updated_at=NOW(3)
            WHERE id=#{id} AND owner_type=#{ownerType} AND owner_key=#{ownerKey}
              AND user_turn_count < #{maxTurns}
              AND (generation_status='IDLE' OR generation_started_at < TIMESTAMPADD(SECOND,-#{timeoutSeconds},NOW(3)))
            """)
    int acquireTurn(@Param("id") String id,
                    @Param("ownerType") String ownerType,
                    @Param("ownerKey") String ownerKey,
                    @Param("maxTurns") int maxTurns,
                    @Param("timeoutSeconds") int timeoutSeconds);

    @Update("""
            UPDATE assistant_conversation
            SET title=#{title}, generation_status='IDLE', generation_started_at=NULL, updated_at=NOW(3)
            WHERE id=#{id}
            """)
    void completeTurn(@Param("id") String id, @Param("title") String title);

    @Update("""
            UPDATE assistant_conversation
            SET generation_status='IDLE', generation_started_at=NULL, updated_at=NOW(3)
            WHERE id=#{id}
            """)
    void release(String id);

    @Update("""
            UPDATE assistant_conversation
            SET rolling_summary=#{summary}, summary_up_to_turn=#{upToTurn}, updated_at=NOW(3)
            WHERE id=#{id} AND summary_up_to_turn < #{upToTurn}
            """)
    int updateSummary(@Param("id") String id,
                      @Param("summary") String summary,
                      @Param("upToTurn") int upToTurn);

    @Update("""
            UPDATE assistant_conversation SET title=#{title},updated_at=NOW(3)
            WHERE id=#{id} AND owner_type=#{ownerType} AND owner_key=#{ownerKey}
            """)
    int rename(@Param("id") String id, @Param("ownerType") String ownerType,
               @Param("ownerKey") String ownerKey, @Param("title") String title);

    @org.apache.ibatis.annotations.Delete("""
            DELETE FROM assistant_conversation
            WHERE id=#{id} AND owner_type=#{ownerType} AND owner_key=#{ownerKey}
            """)
    int deleteOwned(@Param("id") String id, @Param("ownerType") String ownerType,
                    @Param("ownerKey") String ownerKey);
}
