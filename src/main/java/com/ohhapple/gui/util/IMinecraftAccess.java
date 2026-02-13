/*
 * Copyright (C) 2026 ohhapple
 */

package com.ohhapple.gui.util;

/**
 * Minecraft 主线程访问接口，仅需提供在主线程执行任务的能力。
 * 不再提供 getMainWindowHandle()，窗口句柄完全通过 GLFW 获取。
 */
public interface IMinecraftAccess {
    void execute(Runnable task);
}