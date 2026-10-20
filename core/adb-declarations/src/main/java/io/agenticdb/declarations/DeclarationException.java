package io.agenticdb.declarations;

import java.util.List;

/**
 * 声明层校验失败。
 *
 * <p>构造时附上全部问题，而不是遇到第一条就抛 —— 一次报全，改的人不用来回试。
 */
public class DeclarationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<Issue> issues;

    public DeclarationException(List<Issue> issues) {
        super(Issue.formatAll(issues));
        this.issues = List.copyOf(issues);
    }

    public List<Issue> issues() {
        return issues;
    }
}