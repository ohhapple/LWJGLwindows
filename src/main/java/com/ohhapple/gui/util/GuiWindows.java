/*
 * This file is part of the CarpetPlus project, licensed under the
 * GNU Lesser General Public License v3.0
 *
 * Copyright (C) 2026 ohhapple and contributors
 *
 * CarpetPlus is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * CarpetPlus is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with CarpetPlus. If not, see <https://www.gnu.org/licenses/>.
 */

package com.ohhapple.gui.util;


import java.util.function.Consumer;

public final class GuiWindows {
    private GuiWindows() {}

    public static BaseGuiWindow create(IMinecraftAccess mc, String title, int width, int height,
                                       Consumer<BaseGuiWindow> initializer) {
        return new BaseGuiWindow(mc, title, width, height) {
            @Override protected void initUIComponents() { initializer.accept(this); }
        };
    }

    public static BaseGuiWindow create(IMinecraftAccess mc, String title,
                                       Consumer<BaseGuiWindow> initializer) {
        return create(mc, title, 800, 600, initializer);
    }

    public static BaseGuiWindow open(IMinecraftAccess mc, String title, int width, int height,
                                     Consumer<BaseGuiWindow> initializer) {
        BaseGuiWindow win = create(mc, title, width, height, initializer);
        win.open();
        return win;
    }

    public static BaseGuiWindow open(IMinecraftAccess mc, String title,
                                     Consumer<BaseGuiWindow> initializer) {
        return open(mc, title, 800, 600, initializer);
    }
}