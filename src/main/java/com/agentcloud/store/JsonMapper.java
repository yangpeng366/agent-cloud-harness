package com.agentcloud.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

public class JsonMapper implements ColumnMapper<Map<String, Object>> {
    public static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .setDefaultPropertyInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    public static String toJson(Object obj) {
        if (obj == null) return null;
        try { return MAPPER.writeValueAsString(obj); }
        catch (JsonProcessingException e) { throw new RuntimeException(e); }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try { return MAPPER.readValue(json, new TypeReference<Map<String, Object>>(){}); }
        catch (JsonProcessingException e) { throw new RuntimeException(e); }
    }

    public static java.util.List<String> listFromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try { return MAPPER.readValue(json, new TypeReference<java.util.List<String>>(){}); }
        catch (JsonProcessingException e) { throw new RuntimeException(e); }
    }

    @Override
    public Map<String, Object> map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        return fromJson(r.getString(columnNumber));
    }
}
