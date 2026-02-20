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
 * SPDX-License-Identifier: LGPL-3.0-
 */

package com.ohhapple.gui.util;


import java.util.WeakHashMap;
import java.util.function.Consumer;

public final class GuiWindows {
    protected static final WeakHashMap<BaseGuiWindow, String> WINDOWS = new WeakHashMap<>();
    private GuiWindows() {}

    public static BaseGuiWindow create(String title, int width, int height,
                                       Consumer<BaseGuiWindow> initializer) {
        BaseGuiWindow win = new BaseGuiWindow(title, width, height) {
            @Override protected void initUIComponents() { initializer.accept(this); }
        };
        WINDOWS.put(win, title);
        return win;
    }

    public static BaseGuiWindow create(String title, Consumer<BaseGuiWindow> initializer) {
        return create(title, 800, 600, initializer);
    }

    public static BaseGuiWindow open(String title, int width, int height,
                                     Consumer<BaseGuiWindow> initializer) {
        BaseGuiWindow win = create(title, width, height, initializer);
        win.open();
        return win;
    }

    public static BaseGuiWindow open(String title, Consumer<BaseGuiWindow> initializer) {
        return open(title, 800, 600, initializer);
    }

    public static void closeAllwindows() {
        new WeakHashMap<>(WINDOWS).keySet().forEach(BaseGuiWindow::close);
    }

    public static WeakHashMap<BaseGuiWindow, String> getWindows() {
        return new WeakHashMap<>(WINDOWS);
    }

    /**
     * 同步关闭所有已打开的窗口，等待它们彻底销毁
     * 适合在程序退出前调用
     */
    public static void shutdown() {new WeakHashMap<>(WINDOWS).keySet().forEach(BaseGuiWindow::closeAndWait);}
}