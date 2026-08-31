package com.alz.mapper;

import com.alz.entity.UserProfile;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UserProfileMapper {

    @Select("SELECT * FROM user_profile WHERE user_id = #{userId}")
    UserProfile findByUserId(Long userId);

    @Insert("INSERT INTO user_profile(user_id,name,gender,age,phone) VALUES(#{userId},#{name},#{gender},#{age},#{phone})")
    void insert(UserProfile profile);

    @Update("UPDATE user_profile SET name=#{name}, gender=#{gender}, age=#{age}, phone=#{phone}, medicalhistory=#{medicalHistory} WHERE user_id=#{userId}")
    void update(UserProfile profile);

    @Update("UPDATE user_profile SET name=#{name}, gender=#{gender}, age=#{age}, phone=#{phone}, medicalhistory=#{medicalHistory}, mmse=#{mmse}, moca=#{moca}, hkbc=#{hkbc} WHERE user_id=#{userId}")
    void update_admin(UserProfile profile);

    @Delete("DELETE FROM user_profile WHERE user_id = #{userId}")
    void deleteByUserId(Long userId);
}