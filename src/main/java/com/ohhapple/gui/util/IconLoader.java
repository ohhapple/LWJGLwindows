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

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * GLFW窗口图标加载工具
 * 使用STBImage解码PNG，输出RGBA格式的像素数据
 * 实现AutoCloseable以支持try-with-resources自动释放内存
 */
public class IconLoader implements AutoCloseable {

    private final ByteBuffer pixels;
    private final int width;
    private final int height;
    private boolean freed = false;

    public IconLoader(ByteBuffer pixels, int width, int height) {
        this.pixels = pixels;
        this.width = width;
        this.height = height;
    }

    public ByteBuffer getPixels() { return pixels; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }

    /**
     * 从资源路径加载PNG图标（如 "/assets/carpetplus/icon/icon_32.png"）
     */
    public static IconLoader loadFromResources(String resourcePath) {
        byte[] bytes;
        try (InputStream is = IconLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new RuntimeException("Icon resource does not exist: " + resourcePath);
            }
            bytes = is.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load icon resource", e);
        }

        ByteBuffer imageBuffer = ByteBuffer.allocateDirect(bytes.length);
        imageBuffer.put(bytes);
        imageBuffer.flip();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer comp = stack.mallocInt(1);

            // 强制加载为4通道RGBA
            ByteBuffer rgbaPixels = STBImage.stbi_load_from_memory(imageBuffer, w, h, comp, 4);
            if (rgbaPixels == null) {
                throw new RuntimeException("STBImage decoding failed: " + STBImage.stbi_failure_reason());
            }

            return new IconLoader(rgbaPixels, w.get(), h.get());
        }
    }

    /**
     * 释放像素内存，实现AutoCloseable接口
     */
    @Override
    public void close() {
        if (!freed && pixels != null) {
            STBImage.stbi_image_free(pixels);
            freed = true;
        }
    }

    /**
     * 确保内存被释放（防御性调用）
     */
    public void free() {
        close();
    }
}