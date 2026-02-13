/*
 * Copyright (C) 2026 ohhapple
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