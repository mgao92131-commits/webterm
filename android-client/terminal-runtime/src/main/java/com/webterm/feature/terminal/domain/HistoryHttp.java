package com.webterm.feature.terminal.domain;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.inject.Qualifier;

/** 所有终端历史 Range 请求共享、但与登录/WS/文件传输隔离的 OkHttpClient。 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
public @interface HistoryHttp {}
