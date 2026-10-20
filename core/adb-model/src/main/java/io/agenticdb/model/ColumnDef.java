package io.agenticdb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 列定义。
 *
 * @param enumValues      枚举取值，形如 {@code 1=待支付,2=已支付}；非枚举为 null
 * @param charMaxLength   变长类型的最大长度；无长度概念为 null
 * @param defaultValue    DDL 里的默认值表达式
 * @param comment         数据库原生注释（走 OID 路径读，不是我们生成的）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ColumnDef(
        String name,
        ColumnType type,
        boolean nullable,
        boolean primaryKey,
        String defaultValue,
        String comment,
        String enumValues,
        Integer charMaxLength,
        Integer numericPrecision,
        Integer numericScale,
        boolean unique) {

    public boolean isEnum() {
        return enumValues != null && !enumValues.isBlank();
    }

    /** 是否需要详情面板（大字段延迟加载只对这类有意义）。 */
    public boolean possiblyLarge() {
        return type.possiblyLarge();
    }
}