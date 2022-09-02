package ru.coolsoft.common;

public interface Supplier<T, P> {
    T get(P param);
}
