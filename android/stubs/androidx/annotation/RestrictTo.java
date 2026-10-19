package androidx.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** 编译期占位：Shizuku 的 API 引用了它，运行时不需要 */
@Retention(RetentionPolicy.CLASS)
public @interface RestrictTo {
    Scope[] value();

    enum Scope {
        LIBRARY,
        LIBRARY_GROUP,
        LIBRARY_GROUP_PREFIX,
        SUBCLASSES,
        TESTS
    }
}
