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

import com.ohhapple.gui.Font.FontRenderer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public abstract class BaseGuiWindow {

    // -------------------- 依赖注入 --------------------
    protected final IMinecraftAccess mc;
    protected final String title;
    public int windowWidth;
    public int windowHeight;

    // -------------------- 窗口状态 --------------------
    private final AtomicBoolean isOpen = new AtomicBoolean(false);
    private final AtomicBoolean isCreated = new AtomicBoolean(false);
    private final AtomicLong windowHandle = new AtomicLong(0L);
    private Thread renderThread;

    // -------------------- 主窗口句柄 --------------------
    private final AtomicLong mcWindowHandle = new AtomicLong(0L);
    private final AtomicBoolean handleFetched = new AtomicBoolean(false);
    private final Object handleLock = new Object();

    // -------------------- UI 组件管理 --------------------
    protected final List<UIComponent> uiComponents = new CopyOnWriteArrayList<>();
    protected UIComponent hoveredComponent;

    // ★★★★★ 完全还原原始：public 字段，外部可直接读写 ★★★★★
    public UIComponent focusedComponent;

    protected float lastMouseX, lastMouseY;

    // 新增：自定义图标路径（默认为空，不设置）
    protected static String windowIconPath = null;

    // -------------------- 构造方法 --------------------
    public BaseGuiWindow(IMinecraftAccess mc, String title, int width, int height) {
        this.mc = mc;
        this.title = title;
        this.windowWidth = width;
        this.windowHeight = height;

        // 完全复刻 IndependentGuiWindow.getMcWindowHandleLater()
        mc.execute(() -> {
            long handle = GLFW.glfwGetCurrentContext();
            mcWindowHandle.set(handle);
            handleFetched.set(true);
            synchronized (handleLock) {
                handleLock.notifyAll();
            }
        });
    }

    // -------------------- 公共生命周期控制 --------------------
    public final void open() {
        if (isOpen.getAndSet(true)) return;
        mc.execute(this::createWindowInRenderThread);
    }

    public final void close() {
        if (!isOpen.getAndSet(false)) return;
        if (windowHandle.get() != 0) GLFW.glfwPostEmptyEvent();
        mc.execute(this::destroyWindowInRenderThread);
    }

    public final boolean isOpen() {
        return isOpen.get() && isCreated.get();
    }

    // -------------------- 子类必须实现的钩子 --------------------
    protected abstract void initUIComponents();

    // -------------------- 可选钩子 --------------------
    protected void relayoutComponents(int width, int height) {}

    // -------------------- 组件管理（public）--------------------
    public final void addComponent(UIComponent comp) { uiComponents.add(comp); }
    public final void removeComponent(UIComponent comp) { uiComponents.remove(comp); }
    public final void clearComponents() { uiComponents.clear(); }

    // -------------------- 窗口创建（内部）--------------------
    private void createWindowInRenderThread() {
        if (!handleFetched.get()) {
            synchronized (handleLock) {
                try { handleLock.wait(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }

        long mainWindow = mcWindowHandle.get();
        if (mainWindow == 0) {
            isOpen.set(false);
            return;
        }

        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 1);
        GLFW.glfwWindowHint(GLFW.GLFW_ALPHA_BITS, 8);
        GLFW.glfwWindowHint(GLFW.GLFW_SAMPLES, 4);

        long newWindow = GLFW.glfwCreateWindow(windowWidth, windowHeight, title, 0, mainWindow);
        if (newWindow == 0) {
            isOpen.set(false);
            return;
        }

        // --- 在此处插入图标设置（窗口创建后、显示前）---
        if (windowIconPath != null && !windowIconPath.isEmpty()) {
            setWindowIconInternal(newWindow, windowIconPath);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer mX = stack.mallocInt(1), mY = stack.mallocInt(1);
            IntBuffer mW = stack.mallocInt(1), mH = stack.mallocInt(1);
            IntBuffer wW = stack.mallocInt(1), wH = stack.mallocInt(1);
            long monitor = GLFW.glfwGetPrimaryMonitor();
            GLFW.glfwGetMonitorWorkarea(monitor, mX, mY, mW, mH);
            GLFW.glfwGetWindowSize(newWindow, wW, wH);
            int posX = mX.get(0) + (mW.get(0) - wW.get(0)) / 2;
            int posY = mY.get(0) + (mH.get(0) - wH.get(0)) / 2;
            GLFW.glfwSetWindowPos(newWindow, posX, posY);
        }

        windowHandle.set(newWindow);
        isCreated.set(true);
        GLFW.glfwShowWindow(newWindow);

        initUIComponents();
        relayoutComponents(windowWidth, windowHeight);

        startRenderLoop();
    }

    private void startRenderLoop() {
        renderThread = new Thread(this::renderLoop, "GUI-" + title);
        renderThread.setDaemon(true);
        renderThread.start();
    }

    private void renderLoop() {
        long handle = windowHandle.get();
        if (handle == 0) return;

        GLFW.glfwMakeContextCurrent(handle);
        GL.createCapabilities();
        GLFW.glfwSwapInterval(1);

        FontRenderer.init();

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glClearColor(0.1f, 0.1f, 0.15f, 1.0f);

        setupInputCallbacks(handle);

        while (isOpen.get() && !GLFW.glfwWindowShouldClose(handle)) {
            if (GLFW.glfwGetCurrentContext() != handle) {
                GLFW.glfwMakeContextCurrent(handle);
            }

            GL11.glViewport(0, 0, windowWidth, windowHeight);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glLoadIdentity();
            GL11.glOrtho(0, windowWidth, windowHeight, 0, -1, 1);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glLoadIdentity();

            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            renderAll(handle);

            GLFW.glfwSwapBuffers(handle);
            GLFW.glfwPollEvents();

            try { Thread.sleep(16); } catch (InterruptedException e) { break; }
        }

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
    }

    private void renderAll(long handle) {
        // 更新文本框光标闪烁
        for (UIComponent comp : uiComponents) {
            if (comp instanceof TextField) ((TextField) comp).updateBlink();
            if (comp instanceof ScrollContainer) {
                for (UIComponent child : ((ScrollContainer) comp).getChildren()) {
                    if (child instanceof TextField) ((TextField) child).updateBlink();
                }
            }
        }
        for (UIComponent comp : uiComponents) {
            comp.render(handle, comp == hoveredComponent);
        }
    }

    private void destroyWindowInRenderThread() {
        long handle = windowHandle.get();
        if (handle == 0) return;
        if (renderThread != null && renderThread.isAlive()) {
            try { renderThread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        GLFW.glfwDestroyWindow(handle);
        windowHandle.set(0L);
        isCreated.set(false);
        FontRenderer.cleanup();
    }

    private void setupInputCallbacks(long handle) {
        GLFW.glfwSetWindowCloseCallback(handle, w -> close());
        GLFW.glfwSetCursorPosCallback(handle, (w, x, y) -> {
            lastMouseX = (float) x;
            lastMouseY = (float) y;
            updateHoveredComponent();
            for (UIComponent comp : uiComponents) {
                if (comp.handleMouseMove(lastMouseX, lastMouseY, handle)) break;
            }
        });
        GLFW.glfwSetMouseButtonCallback(handle, (w, btn, act, mods) -> {
            for (UIComponent comp : uiComponents) {
                if (comp.handleMouseClick(lastMouseX, lastMouseY, btn, act, mods, handle)) return;
            }
        });
        GLFW.glfwSetScrollCallback(handle, (w, xOff, yOff) -> {
            for (UIComponent comp : uiComponents) {
                if (comp.handleMouseScroll(lastMouseX, lastMouseY, (float) xOff, (float) yOff, handle)) break;
            }
        });

        GLFW.glfwSetKeyCallback(handle, (w, key, scancode, act, mods) -> {
            for (UIComponent comp : uiComponents) {
                if (comp.handleKeyPress(key, scancode, act, mods, handle)) return;
            }
            if (act == GLFW.GLFW_PRESS || act == GLFW.GLFW_REPEAT) {
                if (key == GLFW.GLFW_KEY_ESCAPE) {
                    close();
                    focusedComponent = null;
                }
            }
        });

        GLFW.glfwSetCharCallback(handle, (w, codepoint) -> {
            for (UIComponent comp : uiComponents) {
                if (comp.handleCharTyped(codepoint, handle)) return;
            }
        });

        GLFW.glfwSetWindowSizeCallback(handle, (w, width, height) -> {
            windowWidth = width;
            windowHeight = height;
            relayoutComponents(width, height);
        });
    }

    private void updateHoveredComponent() {
        hoveredComponent = null;
        for (UIComponent comp : uiComponents) {
            if (comp.isHovered(lastMouseX, lastMouseY)) {
                hoveredComponent = comp;
                if (comp instanceof ScrollContainer sc) {
                    float localX = lastMouseX - sc.getX();
                    float localY = lastMouseY - sc.getY() + sc.getScrollOffset();
                    for (UIComponent child : sc.getChildren()) {
                        if (child.isHovered(localX, localY)) {
                            hoveredComponent = child;
                            break;
                        }
                    }
                }
                break;
            }
        }
    }


    public static void setWindowIcon(String resourcePath) {
        windowIconPath = resourcePath;
    }

    // === 新增：内部图标设置方法（处理平台差异）===
    private void setWindowIconInternal(long windowHandle, String iconPath) {
        // macOS: 普通窗口不支持图标，直接跳过
        if (GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_COCOA) {
            System.out.println("[GUI] macOS 窗口不支持自定义图标，已跳过");
            return;
        }

        // Wayland: 需使用 APP_ID，而非 glfwSetWindowIcon
        if (GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND) {
            // LWJGL 3.3.4+ 支持此提示
            GLFW.glfwWindowHintString(GLFW.GLFW_WAYLAND_APP_ID, "carpetplus");
            System.out.println("[GUI] Wayland 平台：设置 APP_ID = carpetplus");
            return;
        }

        // Windows/X11: 正常加载图标
        try (IconLoader icon = IconLoader.loadFromResources(iconPath)) {
            // 创建 GLFWImage 对象
            GLFWImage glfwImage = GLFWImage.malloc();
            glfwImage.set(icon.getWidth(), icon.getHeight(), icon.getPixels());

            // 创建单图标的 Buffer
            GLFWImage.Buffer imageBuffer = GLFWImage.malloc(1);
            imageBuffer.put(0, glfwImage);

            // 设置窗口图标
            GLFW.glfwSetWindowIcon(windowHandle, imageBuffer);

            // 释放临时内存
            glfwImage.free();
            imageBuffer.free();

            System.out.println("[GUI] 窗口图标已设置: " + iconPath);
        } catch (Exception e) {
            System.err.println("[GUI] 设置窗口图标失败: " + e.getMessage());
        }
    }

    // ======================================================================
    //  UI 组件系统（全部 public，与原始 IndependentGuiWindow 完全一致）
    // ======================================================================
    public abstract class UIComponent {
        protected int x, y, width, height;
        protected boolean visible = true;

        public UIComponent(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            width = w;
            height = h;
        }

        public boolean isHovered(float mx, float my) {
            return visible && mx >= x && mx <= x + width && my >= y && my <= y + height;
        }

        public abstract void render(long windowHandle, boolean hovered);

        public boolean handleMouseClick(float mx, float my, int button, int action, int mods, long windowHandle) { return false; }
        public boolean handleMouseMove(float mx, float my, long windowHandle) { return false; }
        public boolean handleMouseScroll(float mx, float my, float xOffset, float yOffset, long windowHandle) { return false; }
        public boolean handleKeyPress(int key, int scancode, int action, int mods, long windowHandle) { return false; }
        public boolean handleCharTyped(int codepoint, long windowHandle) { return false; }

        public int getX() { return x; }
        public int getY() { return y; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public void setX(int x) { this.x = x; }
        public void setY(int y) { this.y = y; }
        public void setWidth(int w) { width = w; }
        public void setHeight(int h) { height = h; }
        public boolean isVisible() { return visible; }
        public void setVisible(boolean v) { visible = v; }
    }

    public interface Clickable {
        void onClick();
    }


    // -------------------- 按钮组件（与原始完全一致）--------------------
    public class Button extends UIComponent implements Clickable {
        private final String text;
        private final Runnable action;
        private int fontSize = 24;

        public Button(int x, int y, int w, int h, String t, Runnable a) {
            super(x, y, w, h);
            text = t;
            action = a;
        }

        @Override
        public void render(long win, boolean hovered) {
            if (!visible) return;
            GL11.glColor4f(hovered ? 0.4f : 0.3f, hovered ? 0.6f : 0.4f, hovered ? 0.8f : 0.6f, 0.9f);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2i(x, y);
            GL11.glVertex2i(x + width, y);
            GL11.glVertex2i(x + width, y + height);
            GL11.glVertex2i(x, y + height);
            GL11.glEnd();
            GL11.glColor3f(1, 1, 1);
            GL11.glLineWidth(1.5f);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2i(x, y);
            GL11.glVertex2i(x + width, y);
            GL11.glVertex2i(x + width, y + height);
            GL11.glVertex2i(x, y + height);
            GL11.glEnd();
            FontRenderer.drawText(win, text, x + width / 2f, y + height / 2f, fontSize, 1, 1, 1);
        }

        @Override
        public boolean handleMouseClick(float mx, float my, int button, int action, int mods, long win) {
            if (visible && button == GLFW.GLFW_MOUSE_BUTTON_LEFT && action == GLFW.GLFW_PRESS && isHovered(mx, my)) {
                onClick();
                return true;
            }
            return false;
        }

        @Override
        public void onClick() {
            if (action != null) action.run();
        }

        public void setFontSize(int size) { fontSize = size; }
        public int getFontSize() { return fontSize; }
    }

    // -------------------- 文本框组件（与原始完全一致）--------------------
    public class TextField extends UIComponent implements Clickable {
        private final String placeholder;
        private final StringBuilder text = new StringBuilder();
        private Consumer<Boolean> focusListener;
        private Consumer<String> enterListener;
        private int fontSize = 24;
        private int cursorPosition = 0;
        private int selectionStart = -1;
        private boolean isDragging = false;
        private long lastBlinkTime = System.currentTimeMillis();
        private boolean cursorVisible = true;

        public TextField(int x, int y, int w, int h, String p) {
            super(x, y, w, h);
            placeholder = p;
        }

        @Override
        public void render(long win, boolean hovered) {
            if (!visible) return;
            GL11.glColor4f(0.15f, 0.15f, 0.2f, 0.9f);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2i(x, y);
            GL11.glVertex2i(x + width, y);
            GL11.glVertex2i(x + width, y + height);
            GL11.glVertex2i(x, y + height);
            GL11.glEnd();

            if (focusedComponent == this) GL11.glColor3f(0.4f, 0.8f, 1.0f);
            else if (hovered) GL11.glColor3f(0.6f, 0.6f, 0.8f);
            else GL11.glColor3f(0.4f, 0.4f, 0.6f);
            GL11.glLineWidth(1.5f);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2i(x, y);
            GL11.glVertex2i(x + width, y);
            GL11.glVertex2i(x + width, y + height);
            GL11.glVertex2i(x, y + height);
            GL11.glEnd();

            int tx = x + 15;
            int ty = y + height / 2;
            String fullText = text.toString();
            String display = fullText.isEmpty() ? placeholder : fullText;
            float textColor = fullText.isEmpty() ? 0.6f : 1.0f;

            if (focusedComponent == this && hasSelection()) {
                int selStart = getSelectionBegin();
                int selEnd = getSelectionEnd();
                String beforeSel = fullText.substring(0, selStart);
                String selText = fullText.substring(selStart, selEnd);
                float beforeWidth = FontRenderer.calculateTextWidth(beforeSel, fontSize);
                float selWidth = FontRenderer.calculateTextWidth(selText, fontSize);
                GL11.glColor4f(0.2f, 0.4f, 0.9f, 0.4f);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2i(tx + (int) beforeWidth, y + 5);
                GL11.glVertex2i(tx + (int) (beforeWidth + selWidth), y + 5);
                GL11.glVertex2i(tx + (int) (beforeWidth + selWidth), y + height - 5);
                GL11.glVertex2i(tx + (int) beforeWidth, y + height - 5);
                GL11.glEnd();
            }

            FontRenderer.drawLeftAlignedText(win, display, tx, ty, fontSize, textColor, textColor, textColor);

            if (focusedComponent == this && cursorVisible) {
                String textBeforeCursor = fullText.substring(0, Math.min(cursorPosition, fullText.length()));
                float cursorX = tx + FontRenderer.calculateTextWidth(textBeforeCursor, fontSize);
                GL11.glColor3f(1, 1, 1);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2i((int) cursorX, y + 10);
                GL11.glVertex2i((int) cursorX + 2, y + 10);
                GL11.glVertex2i((int) cursorX + 2, y + height - 10);
                GL11.glVertex2i((int) cursorX, y + height - 10);
                GL11.glEnd();
            }
        }

        public void updateBlink() {
            long now = System.currentTimeMillis();
            if (now - lastBlinkTime > 500) {
                cursorVisible = !cursorVisible;
                lastBlinkTime = now;
            }
        }

        private void resetBlink() {
            cursorVisible = true;
            lastBlinkTime = System.currentTimeMillis();
        }

        public void addChar(char c) {
            if (hasSelection()) deleteSelection();
            text.insert(cursorPosition, c);
            cursorPosition++;
            selectionStart = -1;
            resetBlink();
        }

        public void backspace() {
            if (hasSelection()) deleteSelection();
            else if (cursorPosition > 0) {
                text.deleteCharAt(cursorPosition - 1);
                cursorPosition--;
                selectionStart = -1;
            }
            resetBlink();
        }

        public void insertString(String str) {
            if (hasSelection()) deleteSelection();
            for (char c : str.toCharArray()) {
                if (c >= 32 && c != 127) {
                    text.insert(cursorPosition, c);
                    cursorPosition++;
                }
            }
            selectionStart = -1;
            resetBlink();
        }

        public void deleteSelection() {
            if (!hasSelection()) return;
            int start = getSelectionBegin();
            int end = getSelectionEnd();
            text.delete(start, end);
            cursorPosition = start;
            selectionStart = -1;
            resetBlink();
        }

        public String getSelectedText() {
            if (!hasSelection()) return "";
            int start = getSelectionBegin();
            int end = getSelectionEnd();
            return text.substring(start, end);
        }

        public void selectAll() {
            cursorPosition = text.length();
            selectionStart = 0;
            resetBlink();
        }

        public void moveCursorLeft(boolean shiftHeld) {
            if (cursorPosition > 0) {
                if (!shiftHeld) {
                    cursorPosition--;
                    selectionStart = -1;
                } else {
                    if (selectionStart == -1) selectionStart = cursorPosition;
                    cursorPosition--;
                }
            }
            resetBlink();
        }

        public void moveCursorRight(boolean shiftHeld) {
            if (cursorPosition < text.length()) {
                if (!shiftHeld) {
                    cursorPosition++;
                    selectionStart = -1;
                } else {
                    if (selectionStart == -1) selectionStart = cursorPosition;
                    cursorPosition++;
                }
            }
            resetBlink();
        }

        public void setCursorPosition(int pos) {
            int newPos = Math.max(0, Math.min(pos, text.length()));
            if (isDragging) {
                cursorPosition = newPos;
            } else {
                cursorPosition = newPos;
                selectionStart = -1;
            }
            resetBlink();
        }

        public void setSelectionStart(int pos) {
            selectionStart = Math.max(0, Math.min(pos, text.length()));
        }

        public int getCharIndexAtPosition(float mouseX) {
            float localX = mouseX - this.x;
            float textStartX = 15;
            if (localX <= textStartX) return 0;
            String currentText = text.toString();
            float currentX = 0;
            for (int i = 0; i < currentText.length(); i++) {
                char c = currentText.charAt(i);
                float charWidth = FontRenderer.calculateTextWidth(String.valueOf(c), fontSize);
                if (localX - textStartX <= currentX + charWidth / 2) {
                    return i;
                }
                currentX += charWidth;
            }
            return currentText.length();
        }

        public boolean hasSelection() {
            return selectionStart != -1 && selectionStart != cursorPosition;
        }

        public int getSelectionBegin() {
            return Math.min(selectionStart, cursorPosition);
        }

        public int getSelectionEnd() {
            return Math.max(selectionStart, cursorPosition);
        }

        @Override
        public boolean handleMouseClick(float mx, float my, int button, int action, int mods, long win) {
            if (!visible) return false;
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                if (action == GLFW.GLFW_PRESS && isHovered(mx, my)) {
                    focusedComponent = TextField.this;
                    if (focusListener != null) focusListener.accept(true);
                    int clickIndex = getCharIndexAtPosition(mx);
                    setCursorPosition(clickIndex);
                    setSelectionStart(clickIndex);
                    isDragging = true;
                    return true;
                } else if (action == GLFW.GLFW_RELEASE) {
                    isDragging = false;
                }
            }
            return false;
        }

        @Override
        public boolean handleMouseMove(float mx, float my, long win) {
            if (isDragging && focusedComponent == this) {
                setCursorPosition(getCharIndexAtPosition(mx));
                return true;
            }
            return false;
        }

        @Override
        public boolean handleKeyPress(int key, int scancode, int action, int mods, long win) {
            if (focusedComponent != this || (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_REPEAT)) return false;
            boolean ctrl = (mods & GLFW.GLFW_MOD_CONTROL) != 0;
            boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
            switch (key) {
                case GLFW.GLFW_KEY_A:
                    if (ctrl) { selectAll(); return true; }
                    break;
                case GLFW.GLFW_KEY_C:
                    if (ctrl && hasSelection()) {
                        GLFW.glfwSetClipboardString(win, getSelectedText());
                        return true;
                    }
                    break;
                case GLFW.GLFW_KEY_X:
                    if (ctrl && hasSelection()) {
                        GLFW.glfwSetClipboardString(win, getSelectedText());
                        deleteSelection();
                        return true;
                    }
                    break;
                case GLFW.GLFW_KEY_V:
                    if (ctrl) {
                        String clip = GLFW.glfwGetClipboardString(win);
                        if (clip != null) insertString(clip);
                        return true;
                    }
                    break;
                case GLFW.GLFW_KEY_LEFT:
                    moveCursorLeft(shift);
                    return true;
                case GLFW.GLFW_KEY_RIGHT:
                    moveCursorRight(shift);
                    return true;
                case GLFW.GLFW_KEY_BACKSPACE:
                    backspace();
                    return true;
                case GLFW.GLFW_KEY_ENTER:
                    if (enterListener != null) enterListener.accept(getText());
                    return true;
            }
            return false;
        }

        @Override
        public boolean handleCharTyped(int codepoint, long win) {
            if (focusedComponent == this && codepoint >= 32) {
                addChar((char) codepoint);
                return true;
            }
            return false;
        }

        @Override
        public void onClick() {}

        public void setFocusListener(Consumer<Boolean> l) { focusListener = l; }
        public void setEnterListener(Consumer<String> l) { enterListener = l; }
        public void loseFocus() {
            if (focusListener != null) focusListener.accept(false);
            isDragging = false;
        }

        public String getText() { return text.toString(); }
        public void setText(String t) {
            text.setLength(0);
            text.append(t);
            cursorPosition = text.length();
            selectionStart = -1;
        }
        public boolean isDragging() { return isDragging; }
        public void setDragging(boolean d) { isDragging = d; }
        public void setFontSize(int size) { fontSize = size; }
        public int getFontSize() { return fontSize; }
    }

    // -------------------- 滚动容器组件（仅修复子组件点击事件）--------------------
    public class ScrollContainer extends UIComponent {
        private final List<UIComponent> children = new CopyOnWriteArrayList<>();
        private final AtomicInteger scrollOffset = new AtomicInteger(0);
        private volatile int totalContentHeight = 0;
        private volatile boolean draggingScrollbar = false;

        public ScrollContainer(int x, int y, int w, int h) {
            super(x, y, w, h);
        }

        @Override
        public void render(long win, boolean hovered) {
            if (!visible) return;
            GL11.glColor4f(0.18f, 0.18f, 0.25f, 0.9f);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2i(x, y);
            GL11.glVertex2i(x + width, y);
            GL11.glVertex2i(x + width, y + height);
            GL11.glVertex2i(x, y + height);
            GL11.glEnd();
            GL11.glColor3f(0.5f, 0.5f, 0.7f);
            GL11.glLineWidth(1.5f);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2i(x, y);
            GL11.glVertex2i(x + width, y);
            GL11.glVertex2i(x + width, y + height);
            GL11.glVertex2i(x, y + height);
            GL11.glEnd();

            GL11.glPushMatrix();
            GL11.glTranslatef(x, y - scrollOffset.get(), 0);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(x, windowHeight - (y + height), width, height);
            for (UIComponent child : children) {
                int childScreenY = y + child.getY() - scrollOffset.get();
                if (childScreenY + child.getHeight() > y && childScreenY < y + height) {
                    child.render(win, child == hoveredComponent);
                }
            }
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glPopMatrix();

            if (totalContentHeight > height) drawScrollbar(win);
        }

        private void drawScrollbar(long win) {
            int sw = 8;
            int sx = x + width - sw - 6;
            float ratio = (totalContentHeight - height == 0) ? 0 : (float) scrollOffset.get() / (totalContentHeight - height);
            int sh = Math.max(25, (int) ((float) height / totalContentHeight * height));
            int sy = y + (int) (ratio * (height - sh));
            GL11.glColor4f(0.1f, 0.1f, 0.15f, 0.8f);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2i(sx, y);
            GL11.glVertex2i(sx + sw, y);
            GL11.glVertex2i(sx + sw, y + height);
            GL11.glVertex2i(sx, y + height);
            GL11.glEnd();
            if (draggingScrollbar) GL11.glColor4f(0.7f, 0.8f, 1.0f, 0.9f);
            else GL11.glColor4f(0.5f, 0.6f, 0.8f, 0.9f);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2i(sx, sy);
            GL11.glVertex2i(sx + sw, sy);
            GL11.glVertex2i(sx + sw, sy + sh);
            GL11.glVertex2i(sx, sy + sh);
            GL11.glEnd();
        }

        // ★ 唯一修复：子组件点击事件转发（坐标转换 + 可见性检查）
        @Override
        public boolean handleMouseClick(float mx, float my, int button, int action, int mods, long win) {
            if (!visible) return false;
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                if (action == GLFW.GLFW_PRESS && isOverScrollbar(mx, my)) {
                    draggingScrollbar = true;
                    return true;
                } else if (action == GLFW.GLFW_RELEASE) {
                    draggingScrollbar = false;
                }
            }

            float localX = mx - this.x;
            float localY = my - this.y + scrollOffset.get();

            for (int i = children.size() - 1; i >= 0; i--) {
                UIComponent child = children.get(i);
                if (child.isVisible() && child.isHovered(localX, localY)) {
                    if (child.handleMouseClick(localX, localY, button, action, mods, win)) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public boolean handleMouseMove(float mx, float my, long win) {
            if (!visible) return false;
            if (draggingScrollbar) {
                setScrollOffsetFromY(my);
                return true;
            }
            float localX = mx - this.x;
            float localY = my - this.y + scrollOffset.get();
            for (int i = children.size() - 1; i >= 0; i--) {
                UIComponent child = children.get(i);
                if (child.isVisible()) {
                    if (child.handleMouseMove(localX, localY, win)) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public boolean handleMouseScroll(float mx, float my, float xOff, float yOff, long win) {
            if (isHovered(mx, my)) {
                scroll((int) (yOff * -50));
                return true;
            }
            return false;
        }

        public void scroll(int delta) {
            int newOff = scrollOffset.get() + delta;
            int maxOff = Math.max(0, totalContentHeight - height);
            newOff = Math.max(0, Math.min(newOff, maxOff));
            scrollOffset.set(newOff);
        }

        public void setScrollOffsetFromY(float mouseY) {
            if (totalContentHeight <= height) return;
            int sh = Math.max(25, (int) ((float) height / totalContentHeight * height));
            int trackHeight = height - sh;
            float relativeY = mouseY - y - (sh / 2f);
            relativeY = Math.max(0, Math.min(relativeY, trackHeight));
            float ratio = relativeY / trackHeight;
            int newOffset = (int) (ratio * (totalContentHeight - height));
            scrollOffset.set(newOffset);
            clampScrollOffset();
        }

        public void clampScrollOffset() {
            int maxOff = Math.max(0, totalContentHeight - height);
            int cur = scrollOffset.get();
            if (cur > maxOff) scrollOffset.set(maxOff);
            else if (cur < 0) scrollOffset.set(0);
        }

        public boolean isOverScrollbar(float mx, float my) {
            if (!visible || totalContentHeight <= height) return false;
            int sw = 8;
            int sx = x + width - sw - 6;
            float ratio = (totalContentHeight - height == 0) ? 0 : (float) scrollOffset.get() / (totalContentHeight - height);
            int sh = Math.max(25, (int) ((float) height / totalContentHeight * height));
            int sy = y + (int) (ratio * (height - sh));
            return mx >= sx && mx <= sx + sw && my >= sy && my <= sy + sh;
        }

        public void addChild(UIComponent child) {
            children.add(child);
            updateTotalContentHeight();
            clampScrollOffset();
        }

        public void removeChild(UIComponent child) {
            children.remove(child);
            updateTotalContentHeight();
            clampScrollOffset();
        }

        public void clearChildren() {
            children.clear();
            scrollOffset.set(0);
            totalContentHeight = 0;
        }

        public void updateTotalContentHeight() {
            int maxY = 0;
            for (UIComponent ch : children) {
                maxY = Math.max(maxY, ch.getY() + ch.getHeight() + 5);
            }
            totalContentHeight = maxY;
            clampScrollOffset();
        }

        public List<UIComponent> getChildren() { return children; }
        public int getScrollOffset() { return scrollOffset.get(); }
        public void setScrollOffset(int off) { scrollOffset.set(off); clampScrollOffset(); }
        public boolean isDragging() { return draggingScrollbar; }
        public void setDragging(boolean d) { draggingScrollbar = d; }
    }
}