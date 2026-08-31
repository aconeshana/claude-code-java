package com.claudecode.ui.lanterna.plugin;

import com.googlecode.lanterna.TextColor;


interface PluginPanelHost {

    /** Appends one line to the change log flushed on close (PermissionsPanel pattern). */
    void record(String line, TextColor color);


    void finish(String resultMessage);


    void closePanel();


    void switchToDiscover(String targetMarketplace, String targetPlugin);


    void switchToInstalled(String targetPlugin, String targetMarketplace, String action);


    void switchToMarketplaces(String targetMarketplace, String action);

    /** Requests a repaint after a state change (possibly from a background thread). */
    void refresh();

/** Publishes state changes onto Lanterna's GUI thread. */
    default void postToGui(Runnable action) { action.run(); }
}
