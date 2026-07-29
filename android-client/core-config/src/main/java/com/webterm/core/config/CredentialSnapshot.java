package com.webterm.core.config;

/** 一次认证握手使用的不可变凭据版本；Cookie 与 generation 必须原子读取。 */
public final class CredentialSnapshot {
    public final String cookie;
    public final long generation;

    public CredentialSnapshot(String cookie, long generation) {
        this.cookie = cookie == null ? "" : cookie;
        this.generation = generation;
    }
}
