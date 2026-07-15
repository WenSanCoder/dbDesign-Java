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

    public Map<String, Object> page(
            CrudDefinition definition,
            Map<String, String> requestParams,
            List<String> keywordColumns,
            Map<String, String> filterColumns,
            String pageOrderBy
    ) {
        int page = normalizePositiveInteger(requestParams.get("page"), 1);
        int pageSize = Math.min(normalizePositiveInteger(requestParams.get("pageSize"), 10), 100);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", pageSize)
                .addValue("offset", (page - 1) * pageSize);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");

        String keyword = requestParams.get("keyword");
        if (StringUtils.hasText(keyword) && !keywordColumns.isEmpty()) {
            where.append(" AND (");
            for (int index = 0; index < keywordColumns.size(); index++) {
                if (index > 0) {
                    where.append(" OR ");
                }
                where.append("LOWER(CAST(resource_rows.")
                        .append(keywordColumns.get(index))
                        .append(" AS TEXT)) LIKE :keyword");
            }
            where.append(")");
            params.addValue("keyword", "%" + keyword.trim().toLowerCase() + "%");
        }

        filterColumns.forEach((requestKey, column) -> {
            String value = requestParams.get(requestKey);
            if (StringUtils.hasText(value)) {
                where.append(" AND CAST(resource_rows.")
                        .append(column)
                        .append(" AS TEXT) = :filter_")
                        .append(requestKey);
                params.addValue("filter_" + requestKey, value.trim());
            }
        });

        String wrappedSql = " FROM (" + definition.listSql() + ") resource_rows" + where;
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + wrappedSql, params, Long.class);
        List<Map<String, Object>> records = jdbc.queryForList(
                "SELECT resource_rows.*" + wrappedSql + " ORDER BY " + pageOrderBy + " LIMIT :limit OFFSET :offset",
                params
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total == null ? 0L : total);
        result.put("page", page);
        result.put("pageSize", pageSize);
        return result;
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

    private int normalizePositiveInteger(String value, int defaultValue) {
        if (!StringUtils.hasText(value)) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
