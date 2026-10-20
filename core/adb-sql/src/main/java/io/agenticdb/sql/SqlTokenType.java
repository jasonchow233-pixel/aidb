package io.agenticdb.sql;

/** SQL 词法单元类别。UI 只按类别上色，不解析语义。 */
public enum SqlTokenType {
    KEYWORD,
    IDENTIFIER,
    QUOTED_IDENTIFIER,
    STRING,
    NUMBER,
    COMMENT,
    PARAMETER,
    OPERATOR,
    PUNCTUATION,
    SEMICOLON,
    WHITESPACE,
    UNKNOWN;

    /** 注释与空白不参与「这条语句是否为空」的判断。 */
    public boolean significant() {
        return this != WHITESPACE && this != COMMENT;
    }
}
