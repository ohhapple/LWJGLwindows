/*
 * Copyright (C) 2026 ohhapple
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/lgpl-3.0.html>.
 *
 * SPDX-License-Identifier: LGPL-3.0
 */

package com.ohhapple.gui.util;

/**
 * Minecraft 主线程访问接口，仅需提供在主线程执行任务的能力。
 * 不再提供 getMainWindowHandle()，窗口句柄完全通过 GLFW 获取。
 */
public interface IMinecraftAccess {
    void execute(Runnable task);
}