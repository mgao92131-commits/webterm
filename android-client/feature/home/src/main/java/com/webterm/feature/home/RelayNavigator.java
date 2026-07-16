package com.webterm.feature.home;

/** Home 只依赖此导航契约；具体 relay Feature 由 app 装配。 */
public interface RelayNavigator {
    void navigateToRelay();
}
