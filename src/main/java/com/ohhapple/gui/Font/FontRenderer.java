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

package com.ohhapple.gui.Font;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.freetype.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 独立窗口字体渲染器
 * 特性：
 * - 支持动态指定字体大小（像素）
 * - 纹理格式 GL_ALPHA，完美透明背景
 * - 像素对齐 (UNPACK_ALIGNMENT=1)，彻底消除扭曲
 * - 强制像素宽高相等，无拉伸
 * - 整数坐标 + GL_NEAREST，边缘锐利
 * - 按 (字符, 字号) 双键缓存，永不崩溃
 */
public class FontRenderer {
    private static long library;
    private static FT_Face face;
    private static ByteBuffer fontBuffer;   // 必须静态持有

    // 默认字体大小（像素）
    public static final int DEFAULT_FONT_SIZE = 24;

    private static float ascender;
    private static float descender;
    @SuppressWarnings("unused")
    private static float lineHeight;

    // 缓存键：字符 + 像素大小
    private static final class GlyphKey {
        final char c;
        final int size;

        GlyphKey(char c, int size) {
            this.c = c;
            this.size = size;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GlyphKey)) return false;
            GlyphKey that = (GlyphKey) o;
            return c == that.c && size == that.size;
        }

        @Override
        public int hashCode() {
            return Objects.hash(c, size);
        }
    }

    private static final ConcurrentHashMap<GlyphKey, Glyph> glyphCache = new ConcurrentHashMap<>();

    private static volatile boolean initialized = false;
    private static final Object initLock = new Object();

    private static class Glyph {
        final int textureId;
        final int width;
        final int height;
        final int bearingX;
        final int bearingY;
        final int advanceX;

        Glyph(int textureId, int width, int height, int bearingX, int bearingY, int advanceX) {
            this.textureId = textureId;
            this.width = width;
            this.height = height;
            this.bearingX = bearingX;
            this.bearingY = bearingY;
            this.advanceX = (advanceX > 0) ? advanceX : (bearingX + width);
        }
    }

    public static void init() {
        if (initialized) return;
        synchronized (initLock) {
            if (initialized) return;

            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer libBuf = stack.mallocPointer(1);
                int error = FreeType.FT_Init_FreeType(libBuf);
                if (error != 0) throw new RuntimeException("FT_Init_FreeType 失败，错误码: " + error);
                library = libBuf.get(0);
            }

            fontBuffer = loadFontFile();
            if (fontBuffer == null) throw new RuntimeException("无法加载字体文件: assets/carpetplus/font/simhei.ttf");

            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer faceBuf = stack.mallocPointer(1);
                int error = FreeType.FT_New_Memory_Face(library, fontBuffer, 0, faceBuf);
                if (error != 0) throw new RuntimeException("FT_New_Memory_Face 失败，错误码: " + error);
                face = FT_Face.create(faceBuf.get(0));
            }

            // 注意：不再设置全局像素大小，而是在加载具体字符时动态设置
            // 但我们需要获取字体度量信息（升部、降部）——使用默认大小先初始化一次
            FreeType.FT_Set_Pixel_Sizes(face, DEFAULT_FONT_SIZE, DEFAULT_FONT_SIZE);
            FT_Size size = face.size();
            FT_Size_Metrics metrics = size.metrics();
            ascender = ((float) metrics.ascender()) / 64f;
            descender = ((float) metrics.descender()) / 64f;
            lineHeight = ascender - descender;

            // 预生成 ASCII 字符（默认大小）
            for (int c = 32; c < 127; c++) getOrCreateGlyph((char) c, DEFAULT_FONT_SIZE);

            initialized = true;
        }
    }

    public static void cleanup() {
        if (!initialized) return;
        for (Glyph glyph : glyphCache.values()) {
            if (glyph.textureId != 0) GL11.glDeleteTextures(glyph.textureId);
        }
        glyphCache.clear();
        if (face != null) { FreeType.FT_Done_Face(face); face = null; }
        if (library != 0) { FreeType.FT_Done_FreeType(library); library = 0; }
        fontBuffer = null;
        initialized = false;
    }

    // ----------------------------------------------------------------------
    // 公开渲染方法（支持自定义字号）
    // ----------------------------------------------------------------------

    /**
     * 使用默认字号 (24) 绘制居中文本
     */
    public static void drawText(long win, String text, float x, float y, float r, float g, float b) {
        drawText(win, text, x, y, DEFAULT_FONT_SIZE, r, g, b);
    }

    /**
     * 指定字号绘制居中文本
     */
    public static void drawText(long win, String text, float x, float y, int fontSize, float r, float g, float b) {
        if (!initialized || text == null || text.isEmpty()) return;
        float totalWidth = calculateTextWidth(text, fontSize);
        float startX = x - totalWidth / 2f;
        // 基线对齐：需要根据当前字号重新计算度量值
        float baselineY = y + (getAscender(fontSize) + getDescender(fontSize)) / 2f;
        drawStringInternal(text, startX, baselineY, fontSize, r, g, b);
    }

    /**
     * 使用默认字号 (24) 绘制左对齐文本
     */
    public static void drawLeftAlignedText(long win, String text, float x, float y, float r, float g, float b) {
        drawLeftAlignedText(win, text, x, y, DEFAULT_FONT_SIZE, r, g, b);
    }

    /**
     * 指定字号绘制左对齐文本
     */
    public static void drawLeftAlignedText(long win, String text, float x, float y, int fontSize, float r, float g, float b) {
        if (!initialized || text == null || text.isEmpty()) return;
        float baselineY = y + (getAscender(fontSize) + getDescender(fontSize)) / 2f;
        drawStringInternal(text, x, baselineY, fontSize, r, g, b);
    }

    /**
     * 使用默认字号 (24) 计算文本宽度
     */
    public static int calculateTextWidth(String text) {
        return calculateTextWidth(text, DEFAULT_FONT_SIZE);
    }

    /**
     * 指定字号计算文本宽度
     */
    public static int calculateTextWidth(String text, int fontSize) {
        if (text == null || text.isEmpty()) return 0;
        int width = 0;
        for (char c : text.toCharArray()) {
            Glyph glyph = getOrCreateGlyph(c, fontSize);
            width += glyph.advanceX;
        }
        return width;
    }

    // ----------------------------------------------------------------------
    // 内部实现
    // ----------------------------------------------------------------------

    private static ByteBuffer loadFontFile() {
        InputStream is = FontRenderer.class.getResourceAsStream("/assets/carpetplus/font/simhei.ttf");
        if (is == null) {
            is = FontRenderer.class.getClassLoader().getResourceAsStream("assets/carpetplus/font/simhei.ttf");
        }
        if (is == null) return null;
        try (InputStream stream = is) {
            byte[] bytes = stream.readAllBytes();
            ByteBuffer buffer = BufferUtils.createByteBuffer(bytes.length);
            buffer.put(bytes);
            buffer.flip();
            return buffer;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 获取特定字符在特定像素大小下的字形（缓存）
     */
    private static Glyph getOrCreateGlyph(char c, int size) {
        GlyphKey key = new GlyphKey(c, size);
        Glyph glyph = glyphCache.get(key);
        if (glyph != null) return glyph;

        synchronized (FontRenderer.class) {
            glyph = glyphCache.get(key);
            if (glyph != null) return glyph;

            if (face == null) throw new IllegalStateException("字体未初始化");

            // ★ 关键：设置当前需要的像素大小
            int error = FreeType.FT_Set_Pixel_Sizes(face, size, size);
            if (error != 0) {
                // 设置大小失败，回退到默认大小
                FreeType.FT_Set_Pixel_Sizes(face, DEFAULT_FONT_SIZE, DEFAULT_FONT_SIZE);
            }

            int glyphIndex = FreeType.FT_Get_Char_Index(face, c);
            if (glyphIndex == 0) {
                glyphIndex = FreeType.FT_Get_Char_Index(face, '?');
                if (glyphIndex == 0) return getOrCreateGlyph(' ', size);
            }

            error = FreeType.FT_Load_Glyph(face, glyphIndex, FreeType.FT_LOAD_DEFAULT);
            if (error != 0) return getOrCreateGlyph(' ', size);

            FT_GlyphSlot slot = face.glyph();
            error = FreeType.FT_Render_Glyph(slot, FreeType.FT_RENDER_MODE_NORMAL);
            if (error != 0) return getOrCreateGlyph(' ', size);

            FT_Bitmap bitmap = slot.bitmap();
            int width = bitmap.width();
            int height = bitmap.rows();
            int bearingX = slot.bitmap_left();
            int bearingY = slot.bitmap_top();
            int advanceX = (int) (slot.advance().x() >> 6);

            int textureId = 0;
            if (width > 0 && height > 0) {
                ByteBuffer buffer = bitmap.buffer(width * height);
                if (buffer != null) {
                    textureId = GL11.glGenTextures();
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

                    GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
                    GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_ALPHA, width, height, 0,
                            GL11.GL_ALPHA, GL11.GL_UNSIGNED_BYTE, buffer);
                    GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
                }
            }

            glyph = new Glyph(textureId, width, height, bearingX, bearingY, advanceX);
            glyphCache.put(key, glyph);
            return glyph;
        }
    }

    /**
     * 内部绘制字符串（已处理基线）
     */
    private static void drawStringInternal(String text, float startX, float baselineY, int fontSize,
                                           float r, float g, float b) {
        GL11.glColor4f(r, g, b, 1.0f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        int penX = Math.round(startX);
        int baseline = Math.round(baselineY);

        for (char ch : text.toCharArray()) {
            Glyph glyph = getOrCreateGlyph(ch, fontSize);
            if (glyph.textureId != 0 && glyph.width > 0 && glyph.height > 0) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, glyph.textureId);

                int x1 = penX + glyph.bearingX;
                int y1 = baseline - glyph.bearingY;
                int x2 = x1 + glyph.width;
                int y2 = y1 + glyph.height;

                GL11.glBegin(GL11.GL_QUADS);
                GL11.glTexCoord2f(0, 0); GL11.glVertex2i(x1, y1);
                GL11.glTexCoord2f(1, 0); GL11.glVertex2i(x2, y1);
                GL11.glTexCoord2f(1, 1); GL11.glVertex2i(x2, y2);
                GL11.glTexCoord2f(0, 1); GL11.glVertex2i(x1, y2);
                GL11.glEnd();
            }
            penX += glyph.advanceX;
        }

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1, 1, 1, 1);
    }

    /**
     * 获取特定字号下的升部（像素）
     */
    private static float getAscender(int fontSize) {
        // 由于我们动态设置大小，度量值也需要缩放
        // 简单方案：使用默认大小度量值按比例缩放
        return ascender * fontSize / DEFAULT_FONT_SIZE;
    }

    /**
     * 获取特定字号下的降部（像素）
     */
    private static float getDescender(int fontSize) {
        return descender * fontSize / DEFAULT_FONT_SIZE;
    }
}