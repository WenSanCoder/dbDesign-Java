package com.zjut.edusystem.common;

import java.util.List;

public record CrudDefinition(
        String table,
        String idColumn,
        List<String> writableColumns,
        String listSql,
        String orderBy
) {
}
