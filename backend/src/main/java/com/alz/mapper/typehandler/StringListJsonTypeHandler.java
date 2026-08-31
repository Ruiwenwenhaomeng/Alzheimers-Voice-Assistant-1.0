package com.alz.mapper.typehandler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public class StringListJsonTypeHandler extends BaseTypeHandler<List<String>> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index,
                                    List<String> parameter, JdbcType jdbcType) throws SQLException {
        try {
            statement.setString(index, OBJECT_MAPPER.writeValueAsString(parameter));
        } catch (JsonProcessingException exception) {
            throw new SQLException("无法序列化筛查明细", exception);
        }
    }

    @Override
    public List<String> getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return parse(resultSet.getString(columnName));
    }

    @Override
    public List<String> getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return parse(resultSet.getString(columnIndex));
    }

    @Override
    public List<String> getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return parse(statement.getString(columnIndex));
    }

    private List<String> parse(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(OBJECT_MAPPER.readValue(json, STRING_LIST));
        } catch (JsonProcessingException exception) {
            throw new SQLException("无法解析筛查明细", exception);
        }
    }
}
