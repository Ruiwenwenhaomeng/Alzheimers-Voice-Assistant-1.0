package com.alz.mapper;

import com.alz.entity.User;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserMapper {

    @Select("select * from user where username = #{username}")
    User findByUsername(String username);

    @Insert("insert into user(username,password,role) values(#{username},#{password},#{role})")
    void insert(User user);

    @Select("SELECT * FROM user")
    @Results({
            @Result(column="create_time", property="createTime", javaType= LocalDateTime.class,
                    typeHandler=org.apache.ibatis.type.LocalDateTimeTypeHandler.class)
    })
    List<User> listAll();

    // 删除用户
    @Delete("DELETE FROM user WHERE id = #{id}")
    void deleteById(Long id);

    // 修改用户
    @Update("UPDATE user SET username = #{username}, role = #{role} WHERE id = #{id}")
    void update(User user);

    @Select("SELECT * FROM user WHERE id = #{id}")
    User selectById(@Param("id") Long id);

    @Update("UPDATE user SET username = #{username}, password = #{password}, role = #{role} WHERE id = #{id}")
    void updateById(User user);
}