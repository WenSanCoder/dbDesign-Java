package com.zjut.edusystem.common;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CrudService {
    private final NamedParameterJdbcTemplate jdbc;

    public CrudService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> list(CrudDefinition definition, Map<String, String> filters) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder(definition.listSql());
        boolean hasWhere = definition.listSql().toLowerCase().contains(" where ");
        for (Map.Entry<String, String> entry : filters.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!StringUtils.hasText(value) || !definition.writableColumns().contains(key)) {
                continue;
            }
            sql.append(hasWhere ? " AND " : " WHERE ");
            hasWhere = true;
            sql.append(definition.table()).append(".").append(key).append("::TEXT LIKE :").append(key);
            params.addValue(key, "%" + value.trim() + "%");
        }
        if (StringUtils.hasText(definition.orderBy())) {
            sql.append(" ORDER BY ").append(definition.orderBy());
        }
        return jdbc.queryForList(sql.toString(), params);
    }

    public Map<String, Object> get(CrudDefinition definition, Long id) {
        String sql = "SELECT * FROM " + definition.table() + " WHERE " + definition.idColumn() + " = :id";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw new BusinessException("记录不存在");
        }
        return rows.get(0);
    }

    public void create(CrudDefinition definition, Map<String, Object> body) {
        Map<String, Object> values = sanitize(definition, body);
        if (values.isEmpty()) {
            throw new BusinessException("没有可写入字段");
        }
        String columns = String.join(", ", values.keySet());
        String placeholders = values.keySet().stream().map(key -> ":" + key).collect(Collectors.joining(", "));
        String sql = "INSERT INTO " + definition.table() + " (" + columns + ") VALUES (" + placeholders + ")";
        jdbc.update(sql, new MapSqlParameterSource(values));
    }

    public void update(CrudDefinition definition, Long id, Map<String, Object> body) {
        Map<String, Object> values = sanitize(definition, body);
        if (values.isEmpty()) {
            throw new BusinessException("没有可更新字段");
        }
        String setClause = values.keySet().stream().map(key -> key + " = :" + key).collect(Collectors.joining(", "));
        values.put("id", id);
        String sql = "UPDATE " + definition.table() + " SET " + setClause + " WHERE " + definition.idColumn() + " = :id";
        int updated = jdbc.update(sql, new MapSqlParameterSource(values));
        if (updated == 0) {
            throw new BusinessException("记录不存在");
        }
    }

    public void delete(CrudDefinition definition, Long id) {
        String sql = "DELETE FROM " + definition.table() + " WHERE " + definition.idColumn() + " = :id";
        int deleted = jdbc.update(sql, new MapSqlParameterSource("id", id));
        if (deleted == 0) {
            throw new BusinessException("记录不存在");
        }
    }

    private Map<String, Object> sanitize(CrudDefinition definition, Map<String, Object> body) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String column : definition.writableColumns()) {
            if (body.containsKey(column)) {
                values.put(column, body.get(column));
            }
        }
        return values;
    }
}
