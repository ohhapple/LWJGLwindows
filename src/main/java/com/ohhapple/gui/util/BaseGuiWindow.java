/*
 * Copyright (C) 2026 ohhapple
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

    // -------------------- 窗口图标支持 --------------------
    protected static String windowIconPath = null;
    public static void setWindowIcon(String resourcePath) { windowIconPath = resourcePath; }

    // -------------------- 窗口背景色（新增）--------------------
    private float bgR = 0.1f, bgG = 0.1f, bgB = 0.15f; // 默认深蓝灰
    public void setBackgroundColor(float r, float g, float b) { bgR = r; bgG = g; bgB = b; }

    // -------------------- 构造方法 --------------------
    public BaseGuiWindow(IMinecraftAccess mc, String title, int width, int height) {
        this.mc = mc;
        this.title = title;
        this.windowWidth = width;
        this.windowHeight = height;

        mc.execute(() -> {
            long handle = GLFW.glfwGetCurrentContext();
            mcWindowHandle.set(handle);
            handleFetched.set(true);
            synchronized (handleLock) { handleLock.notifyAll(); }
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

    public final boolean isOpen() { return isOpen.get() && isCreated.get(); }

    // -------------------- 子类必须实现的钩子 --------------------
    protected abstract void initUIComponents();
    protected void relayoutComponents(int width, int height) {}

    // -------------------- 组件管理 --------------------
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
        if (mainWindow == 0) { isOpen.set(false); return; }

        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 1);
        GLFW.glfwWindowHint(GLFW.GLFW_ALPHA_BITS, 8);
        GLFW.glfwWindowHint(GLFW.GLFW_SAMPLES, 4);

        long newWindow = GLFW.glfwCreateWindow(windowWidth, windowHeight, title, 0, mainWindow);
        if (newWindow == 0) { isOpen.set(false); return; }

        // 设置窗口图标（如果已指定）
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

    private void setWindowIconInternal(long windowHandle, String iconPath) {
        if (GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_COCOA) {
            System.out.println("[GUI] macOS 窗口不支持自定义图标，已跳过"); return;
        }
        if (GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND) {
            GLFW.glfwWindowHintString(GLFW.GLFW_WAYLAND_APP_ID, "carpetplus");
            System.out.println("[GUI] Wayland 平台：设置 APP_ID = carpetplus"); return;
        }
        try (IconLoader icon = IconLoader.loadFromResources(iconPath)) {
            GLFWImage glfwImage = GLFWImage.malloc();
            glfwImage.set(icon.getWidth(), icon.getHeight(), icon.getPixels());
            GLFWImage.Buffer imageBuffer = GLFWImage.malloc(1);
            imageBuffer.put(0, glfwImage);
            GLFW.glfwSetWindowIcon(windowHandle, imageBuffer);
            glfwImage.free(); imageBuffer.free();
        } catch (Exception e) {
            System.err.println("[GUI] 设置窗口图标失败: " + e.getMessage());
        }
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
        // ★ 使用可自定义的背景色
        GL11.glClearColor(bgR, bgG, bgB, 1.0f);

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
            lastMouseX = (float) x; lastMouseY = (float) y;
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
                if (key == GLFW.GLFW_KEY_ESCAPE) { close(); focusedComponent = null; }
            }
        });

        GLFW.glfwSetCharCallback(handle, (w, codepoint) -> {
            for (UIComponent comp : uiComponents) {
                if (comp.handleCharTyped(codepoint, handle)) return;
            }
        });

        GLFW.glfwSetWindowSizeCallback(handle, (w, width, height) -> {
            windowWidth = width; windowHeight = height;
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
                            hoveredComponent = child; break;
                        }
                    }
                }
                break;
            }
        }
    }

    // ======================================================================
    //  UI 组件系统（全部 public，与原风格一致）
    // ======================================================================
    public abstract class UIComponent {
        protected int x, y, width, height;
        protected boolean visible = true;

        public UIComponent(int x, int y, int w, int h) { this.x = x; this.y = y; width = w; height = h; }
        public boolean isHovered(float mx, float my) { return visible && mx >= x && mx <= x + width && my >= y && my <= y + height; }
        public abstract void render(long windowHandle, boolean hovered);
        public boolean handleMouseClick(float mx, float my, int button, int action, int mods, long windowHandle) { return false; }
        public boolean handleMouseMove(float mx, float my, long windowHandle) { return false; }
        public boolean handleMouseScroll(float mx, float my, float xOffset, float yOffset, long windowHandle) { return false; }
        public boolean handleKeyPress(int key, int scancode, int action, int mods, long windowHandle) { return false; }
        public boolean handleCharTyped(int codepoint, long windowHandle) { return false; }
        public int getX() { return x; } public int getY() { return y; }
        public int getWidth() { return width; } public int getHeight() { return height; }
        public void setX(int x) { this.x = x; } public void setY(int y) { this.y = y; }
        public void setWidth(int w) { width = w; } public void setHeight(int h) { height = h; }
        public boolean isVisible() { return visible; } public void setVisible(boolean v) { visible = v; }
    }

    public interface Clickable { void onClick(); }

    // ==================== 增强版按钮（全参数构造，支持悬浮边框色）====================
    public class Button extends UIComponent implements Clickable {
        private final String text;
        private final Runnable action;
        private int fontSize = 24;
        private float textR = 1f, textG = 1f, textB = 1f;

        // 背景色（普通/悬浮）
        private float bgR = 0.3f, bgG = 0.4f, bgB = 0.6f;
        private float bgHoverR = 0.4f, bgHoverG = 0.6f, bgHoverB = 0.8f;
        private float bgAlpha = 0.9f;

        // 边框色（普通/悬浮）
        private float borderR = 1f, borderG = 1f, borderB = 1f;
        private float borderHoverR = 1f, borderHoverG = 1f, borderHoverB = 1f;
        private float borderAlpha = 1f;

        // ---------- 构造方法重载 ----------
        public Button(int x, int y, int w, int h, String t, Runnable a) { super(x,y,w,h); text = t; action = a; }
        public Button(int x, int y, int w, int h, String t, Runnable a, int fontSize) { this(x,y,w,h,t,a); this.fontSize = fontSize; }
        public Button(int x, int y, int w, int h, String t, Runnable a, int fontSize, float tr, float tg, float tb) { this(x,y,w,h,t,a,fontSize); setTextColor(tr,tg,tb); }

        // ★★★ 完整构造方法：普通背景、悬浮背景、普通边框、悬浮边框 ★★★
        public Button(int x, int y, int w, int h, String t, Runnable a,//x坐标 y坐标 宽度 高度 点击执行事件
                      int fontSize, float tr, float tg, float tb,//文字大小 文字颜色
                      float bgR, float bgG, float bgB,//背景颜色
                      float bgHoverR, float bgHoverG, float bgHoverB,//悬浮背景颜色
                      float borderR, float borderG, float borderB,//边框颜色
                      float borderHoverR, float borderHoverG, float borderHoverB)//悬浮边框颜色
        {
            this(x,y,w,h,t,a,fontSize,tr,tg,tb);
            setBgColor(bgR, bgG, bgB);
            setBgHoverColor(bgHoverR, bgHoverG, bgHoverB);
            setBorderColor(borderR, borderG, borderB);
            setBorderHoverColor(borderHoverR, borderHoverG, borderHoverB);
        }

        @Override
        public void render(long win, boolean hovered) {
            if (!visible) return;
            // 背景
            if (hovered) GL11.glColor4f(bgHoverR, bgHoverG, bgHoverB, bgAlpha);
            else GL11.glColor4f(bgR, bgG, bgB, bgAlpha);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2i(x, y); GL11.glVertex2i(x+width, y);
            GL11.glVertex2i(x+width, y+height); GL11.glVertex2i(x, y+height);
            GL11.glEnd();
            // 边框
            if (hovered) GL11.glColor4f(borderHoverR, borderHoverG, borderHoverB, borderAlpha);
            else GL11.glColor4f(borderR, borderG, borderB, borderAlpha);
            GL11.glLineWidth(1.5f);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2i(x, y); GL11.glVertex2i(x+width, y);
            GL11.glVertex2i(x+width, y+height); GL11.glVertex2i(x, y+height);
            GL11.glEnd();
            // 文字（自动截断）
            int maxTextWidth = width - 2; // 左右内边距各1px
            String displayText = text;
            if (FontRenderer.calculateTextWidth(text, fontSize) > maxTextWidth)
                displayText = truncateText(text, maxTextWidth, fontSize);
            FontRenderer.drawText(win, displayText, x + width/2f, y + height/2f, fontSize, textR, textG, textB);
        }

        private String truncateText(String orig, int maxW, int fs) {
            if (orig.isEmpty()) return orig;
            String ellipsis = ".";
            int ellipsisW = FontRenderer.calculateTextWidth(ellipsis, fs);
            int available = maxW - ellipsisW;
            if (available <= 0) return ellipsis;
            for (int i = orig.length(); i > 0; i--) {
                String sub = orig.substring(0, i);
                if (FontRenderer.calculateTextWidth(sub, fs) <= available) return sub + ellipsis;
            }
            return ellipsis;
        }

        @Override public boolean handleMouseClick(float mx, float my, int btn, int act, int mods, long win) {
            if (visible && btn == GLFW.GLFW_MOUSE_BUTTON_LEFT && act == GLFW.GLFW_PRESS && isHovered(mx, my)) {
                onClick(); return true;
            }
            return false;
        }
        @Override public void onClick() { if (action != null) action.run(); }

        // ---------- 样式设置器 ----------
        public void setFontSize(int size) { fontSize = size; }
        public void setTextColor(float r, float g, float b) { textR = r; textG = g; textB = b; }
        public void setBgColor(float r, float g, float b) { bgR = r; bgG = g; bgB = b; }
        public void setBgHoverColor(float r, float g, float b) { bgHoverR = r; bgHoverG = g; bgHoverB = b; }
        public void setBorderColor(float r, float g, float b) { borderR = r; borderG = g; borderB = b; }
        public void setBorderHoverColor(float r, float g, float b) { borderHoverR = r; borderHoverG = g; borderHoverB = b; }
        public void setBgAlpha(float a) { bgAlpha = a; }
        public void setBorderAlpha(float a) { borderAlpha = a; }
    }

    // ==================== 增强版文本框（保持原样）====================
    public class TextField extends UIComponent implements Clickable {
        private final String placeholder;
        private final StringBuilder text = new StringBuilder();
        private Consumer<Boolean> focusListener;
        private Consumer<String> enterListener;
        private int fontSize = 24;
        private float textR = 1f, textG = 1f, textB = 1f;
        private float placeholderR = 0.6f, placeholderG = 0.6f, placeholderB = 0.6f;

        // 背景色
        private float bgR = 0.15f, bgG = 0.15f, bgB = 0.2f, bgAlpha = 0.9f;
        // 边框色（普通/悬浮/焦点）
        private float borderR = 0.4f, borderG = 0.4f, borderB = 0.6f, borderAlpha = 1f;
        private float borderHoverR = 0.6f, borderHoverG = 0.6f, borderHoverB = 0.8f;
        private float borderFocusR = 0.4f, borderFocusG = 0.8f, borderFocusB = 1.0f;

        private int cursorPosition = 0, selectionStart = -1;
        private boolean isDragging = false;
        private long lastBlinkTime = System.currentTimeMillis();
        private boolean cursorVisible = true;

        // ---------- 构造方法重载 ----------
        public TextField(int x, int y, int w, int h, String p) { super(x,y,w,h); placeholder = p; }
        public TextField(int x, int y, int w, int h, String p, int fontSize) { this(x,y,w,h,p); this.fontSize = fontSize; }
        public TextField(int x, int y, int w, int h, String p, int fontSize, float tr, float tg, float tb) { this(x,y,w,h,p,fontSize); setTextColor(tr,tg,tb); }

        // 全参数构造（背景、边框颜色等）
        public TextField(int x, int y, int w, int h, String p, int fontSize,//x坐标 y坐标 宽度w 高度h 默认显示提示文字 输入文字大小
                         float tr, float tg, float tb, // 输入文字颜色
                         float pr, float pg, float pb,// 占位符颜色
                         float bgR, float bgG, float bgB,// 背景颜色
                         float borderR, float borderG, float borderB,//普通边框颜色
                         float borderHoverR, float borderHoverG, float borderHoverB,//悬浮边框颜色
                         float borderFocusR, float borderFocusG, float borderFocusB)//焦点边框颜色
        {
            this(x,y,w,h,p,fontSize,tr,tg,tb);
            setPlaceholderColor(pr, pg, pb);
            setBgColor(bgR, bgG, bgB);
            setBorderColor(borderR, borderG, borderB);
            setBorderHoverColor(borderHoverR, borderHoverG, borderHoverB);
            setBorderFocusColor(borderFocusR, borderFocusG, borderFocusB);
        }

        @Override
        public void render(long win, boolean hovered) {
            if (!visible) return;
            // 背景
            GL11.glColor4f(bgR, bgG, bgB, bgAlpha);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2i(x, y); GL11.glVertex2i(x+width, y);
            GL11.glVertex2i(x+width, y+height); GL11.glVertex2i(x, y+height);
            GL11.glEnd();
            // 边框
            if (focusedComponent == this) GL11.glColor4f(borderFocusR, borderFocusG, borderFocusB, borderAlpha);
            else if (hovered) GL11.glColor4f(borderHoverR, borderHoverG, borderHoverB, borderAlpha);
            else GL11.glColor4f(borderR, borderG, borderB, borderAlpha);
            GL11.glLineWidth(1.5f);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2i(x, y); GL11.glVertex2i(x+width, y);
            GL11.glVertex2i(x+width, y+height); GL11.glVertex2i(x, y+height);
            GL11.glEnd();

            int tx = x + 15, ty = y + height/2;
            String fullText = text.toString();
            String display = fullText.isEmpty() ? placeholder : fullText;

            if (focusedComponent == this && hasSelection()) {
                int selStart = getSelectionBegin(), selEnd = getSelectionEnd();
                String beforeSel = fullText.substring(0, selStart);
                String selText = fullText.substring(selStart, selEnd);
                float beforeW = FontRenderer.calculateTextWidth(beforeSel, fontSize);
                float selW = FontRenderer.calculateTextWidth(selText, fontSize);
                GL11.glColor4f(0.2f, 0.4f, 0.9f, 0.4f);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2i(tx + (int) beforeW, y+5);
                GL11.glVertex2i(tx + (int)(beforeW+selW), y+5);
                GL11.glVertex2i(tx + (int)(beforeW+selW), y+height-5);
                GL11.glVertex2i(tx + (int) beforeW, y+height-5);
                GL11.glEnd();
            }

            if (fullText.isEmpty()) {
                FontRenderer.drawLeftAlignedText(win, display, tx, ty, fontSize, placeholderR, placeholderG, placeholderB);
            } else {
                FontRenderer.drawLeftAlignedText(win, display, tx, ty, fontSize, textR, textG, textB);
            }

            if (focusedComponent == this && cursorVisible) {
                String beforeCursor = fullText.substring(0, Math.min(cursorPosition, fullText.length()));
                float cursorX = tx + FontRenderer.calculateTextWidth(beforeCursor, fontSize);
                GL11.glColor3f(1,1,1);
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glVertex2i((int)cursorX, y+10);
                GL11.glVertex2i((int)cursorX+2, y+10);
                GL11.glVertex2i((int)cursorX+2, y+height-10);
                GL11.glVertex2i((int)cursorX, y+height-10);
                GL11.glEnd();
            }
        }

        // ---------- 原有功能（光标、选择、剪贴板等）全部保留 ----------
        public void updateBlink() { long now = System.currentTimeMillis(); if (now - lastBlinkTime > 500) { cursorVisible = !cursorVisible; lastBlinkTime = now; } }
        private void resetBlink() { cursorVisible = true; lastBlinkTime = System.currentTimeMillis(); }
        public void addChar(char c) { if (hasSelection()) deleteSelection(); text.insert(cursorPosition, c); cursorPosition++; selectionStart = -1; resetBlink(); }
        public void backspace() { if (hasSelection()) deleteSelection(); else if (cursorPosition > 0) { text.deleteCharAt(cursorPosition-1); cursorPosition--; selectionStart = -1; } resetBlink(); }
        public void insertString(String str) { if (hasSelection()) deleteSelection(); for (char c : str.toCharArray()) { if (c >= 32 && c != 127) { text.insert(cursorPosition, c); cursorPosition++; } } selectionStart = -1; resetBlink(); }
        public void deleteSelection() { if (!hasSelection()) return; int start = getSelectionBegin(), end = getSelectionEnd(); text.delete(start, end); cursorPosition = start; selectionStart = -1; resetBlink(); }
        public String getSelectedText() { if (!hasSelection()) return ""; return text.substring(getSelectionBegin(), getSelectionEnd()); }
        public void selectAll() { cursorPosition = text.length(); selectionStart = 0; resetBlink(); }
        public void moveCursorLeft(boolean shift) { if (cursorPosition > 0) { if (!shift) { cursorPosition--; selectionStart = -1; } else { if (selectionStart == -1) selectionStart = cursorPosition; cursorPosition--; } } resetBlink(); }
        public void moveCursorRight(boolean shift) { if (cursorPosition < text.length()) { if (!shift) { cursorPosition++; selectionStart = -1; } else { if (selectionStart == -1) selectionStart = cursorPosition; cursorPosition++; } } resetBlink(); }
        public void setCursorPosition(int pos) { int newPos = Math.max(0, Math.min(pos, text.length())); if (isDragging) { cursorPosition = newPos; } else { cursorPosition = newPos; selectionStart = -1; } resetBlink(); }
        public void setSelectionStart(int pos) { selectionStart = Math.max(0, Math.min(pos, text.length())); }
        public int getCharIndexAtPosition(float mx) { float localX = mx - this.x; float textStartX = 15; if (localX <= textStartX) return 0; String cur = text.toString(); float currentX = 0; for (int i = 0; i < cur.length(); i++) { char c = cur.charAt(i); float cw = FontRenderer.calculateTextWidth(String.valueOf(c), fontSize); if (localX - textStartX <= currentX + cw/2) return i; currentX += cw; } return cur.length(); }
        public boolean hasSelection() { return selectionStart != -1 && selectionStart != cursorPosition; }
        public int getSelectionBegin() { return Math.min(selectionStart, cursorPosition); }
        public int getSelectionEnd() { return Math.max(selectionStart, cursorPosition); }

        @Override public boolean handleMouseClick(float mx, float my, int btn, int act, int mods, long win) {
            if (!visible) return false;
            if (btn == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                if (act == GLFW.GLFW_PRESS && isHovered(mx, my)) {
                    focusedComponent = TextField.this;
                    if (focusListener != null) focusListener.accept(true);
                    int idx = getCharIndexAtPosition(mx);
                    setCursorPosition(idx);
                    setSelectionStart(idx);
                    isDragging = true;
                    return true;
                } else if (act == GLFW.GLFW_RELEASE) { isDragging = false; }
            }
            return false;
        }
        @Override public boolean handleMouseMove(float mx, float my, long win) {
            if (isDragging && focusedComponent == this) { setCursorPosition(getCharIndexAtPosition(mx)); return true; }
            return false;
        }
        @Override public boolean handleKeyPress(int key, int sc, int act, int mods, long win) {
            if (focusedComponent != this || (act != GLFW.GLFW_PRESS && act != GLFW.GLFW_REPEAT)) return false;
            boolean ctrl = (mods & GLFW.GLFW_MOD_CONTROL) != 0, shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
            switch (key) {
                case GLFW.GLFW_KEY_A: if (ctrl) { selectAll(); return true; } break;
                case GLFW.GLFW_KEY_C: if (ctrl && hasSelection()) { GLFW.glfwSetClipboardString(win, getSelectedText()); return true; } break;
                case GLFW.GLFW_KEY_X: if (ctrl && hasSelection()) { GLFW.glfwSetClipboardString(win, getSelectedText()); deleteSelection(); return true; } break;
                case GLFW.GLFW_KEY_V: if (ctrl) { String clip = GLFW.glfwGetClipboardString(win); if (clip != null) insertString(clip); return true; } break;
                case GLFW.GLFW_KEY_LEFT: moveCursorLeft(shift); return true;
                case GLFW.GLFW_KEY_RIGHT: moveCursorRight(shift); return true;
                case GLFW.GLFW_KEY_BACKSPACE: backspace(); return true;
                case GLFW.GLFW_KEY_ENTER: if (enterListener != null) enterListener.accept(getText()); return true;
            }
            return false;
        }
        @Override public boolean handleCharTyped(int code, long win) { if (focusedComponent == this && code >= 32) { addChar((char)code); return true; } return false; }
        @Override public void onClick() {}

        // ---------- 样式设置器 ----------
        public void setFontSize(int size) { fontSize = size; }
        public void setTextColor(float r, float g, float b) { textR = r; textG = g; textB = b; }
        public void setPlaceholderColor(float r, float g, float b) { placeholderR = r; placeholderG = g; placeholderB = b; }
        public void setBgColor(float r, float g, float b) { bgR = r; bgG = g; bgB = b; }
        public void setBgAlpha(float a) { bgAlpha = a; }
        public void setBorderColor(float r, float g, float b) { borderR = r; borderG = g; borderB = b; }
        public void setBorderHoverColor(float r, float g, float b) { borderHoverR = r; borderHoverG = g; borderHoverB = b; }
        public void setBorderFocusColor(float r, float g, float b) { borderFocusR = r; borderFocusG = g; borderFocusB = b; }
        public void setBorderAlpha(float a) { borderAlpha = a; }

        public void setFocusListener(Consumer<Boolean> l) { focusListener = l; }
        public void setEnterListener(Consumer<String> l) { enterListener = l; }
        public void loseFocus() { if (focusListener != null) focusListener.accept(false); isDragging = false; }
        public String getText() { return text.toString(); }
        public void setText(String t) { text.setLength(0); text.append(t); cursorPosition = text.length(); selectionStart = -1; }
        public boolean isDragging() { return isDragging; }
        public void setDragging(boolean d) { isDragging = d; }
        public int getFontSize() { return fontSize; }
    }

    // ==================== 滑块（Slider）- 点击全组件区域跳转 ====================
    public class Slider extends UIComponent {
        private float value = 0.5f;
        private boolean dragging = false;
        private Consumer<Float> changeListener;

        // ---------- 尺寸策略 ----------
        private Integer fixedTrackHeight = null;
        private Integer fixedThumbWidth = null;
        private Integer fixedThumbHeight = null;
        private float trackHeightPercent = 0.8f;      // ★ 默认轨道高度 = 组件高度 * 80%
        private float thumbHeightPercent = 1.0f;       // ★ 默认滑块高度 = 组件高度 * 100%
        private float thumbWidthPercent = 0.8f;        // 默认滑块宽度 = 组件高度 * 80%

        // ---------- 颜色 ----------
        private float trackR = 0.3f, trackG = 0.3f, trackB = 0.4f, trackAlpha = 0.8f;
        private float fillR = 0.2f, fillG = 0.6f, fillB = 1.0f, fillAlpha = 0.9f;
        private float thumbR = 0.9f, thumbG = 0.9f, thumbB = 0.9f, thumbAlpha = 1.0f;
        private float thumbHoverR = 1.0f, thumbHoverG = 1.0f, thumbHoverB = 1.0f, thumbHoverAlpha = 1.0f;

        // ---------- 数值显示 ----------
        private boolean showValue = true;
        private int valueFontSize = 16;
        private float valueR = 1f, valueG = 1f, valueB = 1f;
        private int valueOffsetX = 10;

        // ---------- 构造方法重载 ----------
        public Slider(int x, int y, int width, int height) { super(x,y,width,height); }
        public Slider(int x, int y, int width, int height, float initialValue) { this(x,y,width,height); setValue(initialValue); }
        public Slider(int x, int y, int width, int height, float initialValue, Consumer<Float> listener) { this(x,y,width,height,initialValue); this.changeListener = listener; }

        // 全参数构造（颜色、尺寸、显示选项）
        public Slider(int x, int y, int width, int height, float initialValue, Consumer<Float> listener,//x坐标 y坐标 宽度 高度 初始值百分比0-1 监听器
                      float trackR, float trackG, float trackB, //轨道颜色
                      float fillR, float fillG, float fillB,//填充颜色
                      float thumbR, float thumbG, float thumbB, //滑块颜色
                      float thumbHoverR, float thumbHoverG, float thumbHoverB,//滑块悬浮颜色
                      Integer trackHeight, Integer thumbWidth, Integer thumbHeight,//固定轨道高度像素 固定滑块宽度像素 固定滑块高度像素 传入null按百分比生成
                      boolean showValue, int valFontSize, float valR, float valG, float valB) //是否显示数值 数值字体大小 数值颜色
        {
            this(x,y,width,height,initialValue,listener);
            setTrackColor(trackR, trackG, trackB);
            setFillColor(fillR, fillG, fillB);
            setThumbColor(thumbR, thumbG, thumbB);
            setThumbHoverColor(thumbHoverR, thumbHoverG, thumbHoverB);
            if (trackHeight != null) fixedTrackHeight = trackHeight;
            if (thumbWidth != null) fixedThumbWidth = thumbWidth;
            if (thumbHeight != null) fixedThumbHeight = thumbHeight;
            setShowValue(showValue);
            setValueFontSize(valFontSize);
            setValueColor(valR, valG, valB);
        }

        // ---------- 当前有效尺寸（根据固定值或百分比计算）----------
        private int getCurrentTrackHeight() {
            if (fixedTrackHeight != null) return fixedTrackHeight;
            return Math.max(2, (int)(height * trackHeightPercent));
        }
        private int getCurrentThumbWidth() {
            if (fixedThumbWidth != null) return fixedThumbWidth;
            return Math.max(4, (int)(height * thumbWidthPercent));
        }
        private int getCurrentThumbHeight() {
            if (fixedThumbHeight != null) return fixedThumbHeight;
            return Math.max(6, (int)(height * thumbHeightPercent));
        }

        @Override
        public void render(long win, boolean hovered) {
            if (!visible) return;

            int trackY = y + (height - getCurrentTrackHeight()) / 2;
            int thumbX = getThumbX();
            int thumbY = y + (height - getCurrentThumbHeight()) / 2;
            int thumbW = getCurrentThumbWidth();
            int thumbH = getCurrentThumbHeight();

            // 轨道背景
            GL11.glColor4f(trackR, trackG, trackB, trackAlpha);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2i(x, trackY);
            GL11.glVertex2i(x + width, trackY);
            GL11.glVertex2i(x + width, trackY + getCurrentTrackHeight());
            GL11.glVertex2i(x, trackY + getCurrentTrackHeight());
            GL11.glEnd();

            // 已填充部分（从左侧到滑块中心）
            GL11.glColor4f(fillR, fillG, fillB, fillAlpha);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2i(x, trackY);
            GL11.glVertex2i(thumbX, trackY);
            GL11.glVertex2i(thumbX, trackY + getCurrentTrackHeight());
            GL11.glVertex2i(x, trackY + getCurrentTrackHeight());
            GL11.glEnd();

            // 滑块
            if (dragging || isHoveredOnThumb(lastMouseX, lastMouseY)) {
                GL11.glColor4f(thumbHoverR, thumbHoverG, thumbHoverB, thumbHoverAlpha);
            } else {
                GL11.glColor4f(thumbR, thumbG, thumbB, thumbAlpha);
            }
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2i(thumbX - thumbW/2, thumbY);
            GL11.glVertex2i(thumbX + thumbW/2, thumbY);
            GL11.glVertex2i(thumbX + thumbW/2, thumbY + thumbH);
            GL11.glVertex2i(thumbX - thumbW/2, thumbY + thumbH);
            GL11.glEnd();

            // 数值显示
            if (showValue) {
                String valText = String.format("%.0f%%", value * 100);
                int textX = x + width + valueOffsetX;
                int textY = y + height / 2;
                FontRenderer.drawLeftAlignedText(win, valText, textX, textY, valueFontSize, valueR, valueG, valueB);
            }
        }

        // 滑块中心 X 坐标（基于当前滑块宽度）
        private int getThumbX() {
            int thumbW = getCurrentThumbWidth();
            int minX = x + thumbW / 2;
            int maxX = x + width - thumbW / 2;
            return minX + (int)((maxX - minX) * value);
        }

        // 鼠标是否在滑块上（使用当前滑块尺寸）
        private boolean isHoveredOnThumb(float mx, float my) {
            int thumbX = getThumbX();
            int thumbW = getCurrentThumbWidth();
            int thumbH = getCurrentThumbHeight();
            int thumbY = y + (height - thumbH) / 2;
            return mx >= thumbX - thumbW/2 && mx <= thumbX + thumbW/2 && my >= thumbY && my <= thumbY + thumbH;
        }

        // 根据鼠标 X 计算值（考虑滑块宽度边界）
        private float getValueFromMouseX(float mx) {
            int thumbW = getCurrentThumbWidth();
            int minX = x + thumbW / 2;
            int maxX = x + width - thumbW / 2;
            if (mx <= minX) return 0f;
            if (mx >= maxX) return 1f;
            return (mx - minX) / (maxX - minX);
        }

        @Override
        public boolean handleMouseClick(float mx, float my, int btn, int act, int mods, long win) {
            if (!visible) return false;
            if (btn == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                if (act == GLFW.GLFW_PRESS) {
                    // 优先判断滑块拖拽
                    if (isHoveredOnThumb(mx, my)) {
                        dragging = true;
                        return true;
                    }
                    // ★★★ 整个组件区域点击均可跳转 ★★★
                    else if (isHovered(mx, my)) {
                        float newVal = getValueFromMouseX(mx);
                        setValue(newVal);
                        if (changeListener != null) changeListener.accept(value);
                        return true;
                    }
                } else if (act == GLFW.GLFW_RELEASE) {
                    dragging = false;
                }
            }
            return false;
        }

        @Override
        public boolean handleMouseMove(float mx, float my, long win) {
            if (!visible) return false;
            if (dragging) {
                float newVal = getValueFromMouseX(mx);
                setValue(newVal);
                if (changeListener != null) changeListener.accept(value);
                return true;
            }
            return false;
        }

        // ---------- 样式设置器 ----------
        public void setValue(float val) { this.value = Math.max(0, Math.min(1, val)); }
        public float getValue() { return value; }
        public void setChangeListener(Consumer<Float> l) { changeListener = l; }

        public void setTrackColor(float r, float g, float b) { trackR = r; trackG = g; trackB = b; }
        public void setTrackAlpha(float a) { trackAlpha = a; }
        public void setFillColor(float r, float g, float b) { fillR = r; fillG = g; fillB = b; }
        public void setFillAlpha(float a) { fillAlpha = a; }
        public void setThumbColor(float r, float g, float b) { thumbR = r; thumbG = g; thumbB = b; }
        public void setThumbAlpha(float a) { thumbAlpha = a; }
        public void setThumbHoverColor(float r, float g, float b) { thumbHoverR = r; thumbHoverG = g; thumbHoverB = b; }
        public void setThumbHoverAlpha(float a) { thumbHoverAlpha = a; }

        public void setFixedTrackHeight(int px) { fixedTrackHeight = px; }
        public void setFixedThumbWidth(int px) { fixedThumbWidth = px; }
        public void setFixedThumbHeight(int px) { fixedThumbHeight = px; }
        public void setTrackHeightPercent(float p) { fixedTrackHeight = null; trackHeightPercent = p; }
        public void setThumbHeightPercent(float p) { fixedThumbHeight = null; thumbHeightPercent = p; }
        public void setThumbWidthPercent(float p) { fixedThumbWidth = null; thumbWidthPercent = p; }

        public void setShowValue(boolean show) { showValue = show; }
        public void setValueFontSize(int sz) { valueFontSize = sz; }
        public void setValueColor(float r, float g, float b) { valueR = r; valueG = g; valueB = b; }
        public void setValueOffsetX(int off) { valueOffsetX = off; }
    }

    // ==================== 滚动容器（带背景/边框颜色自定义）====================
    public class ScrollContainer extends UIComponent {
        private final List<UIComponent> children = new CopyOnWriteArrayList<>();
        private final AtomicInteger scrollOffset = new AtomicInteger(0);
        private volatile int totalContentHeight = 0;
        private volatile boolean draggingScrollbar = false;

        // 样式字段
        private float bgR = 0.18f, bgG = 0.18f, bgB = 0.25f, bgAlpha = 0.9f;
        private float borderR = 0.5f, borderG = 0.5f, borderB = 0.7f, borderAlpha = 1f;

        public ScrollContainer(int x, int y, int w, int h) { super(x,y,w,h); }
        // 重载：指定背景色、边框色
        public ScrollContainer(int x, int y, int w, int h, //x坐标 y坐标 宽度 高度
                               float bgR, float bgG, float bgB, //背景色
                               float borderR, float borderG, float borderB) //边框色
        {
            this(x,y,w,h);
            setBgColor(bgR, bgG, bgB);
            setBorderColor(borderR, borderG, borderB);
        }

        @Override
        public void render(long win, boolean hovered) {
            if (!visible) return;
            // 背景
            GL11.glColor4f(bgR, bgG, bgB, bgAlpha);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2i(x, y); GL11.glVertex2i(x+width, y);
            GL11.glVertex2i(x+width, y+height); GL11.glVertex2i(x, y+height);
            GL11.glEnd();
            // 边框
            GL11.glColor4f(borderR, borderG, borderB, borderAlpha);
            GL11.glLineWidth(1.5f);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2i(x, y); GL11.glVertex2i(x+width, y);
            GL11.glVertex2i(x+width, y+height); GL11.glVertex2i(x, y+height);
            GL11.glEnd();

            // 裁剪区域
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
            GL11.glVertex2i(sx, y); GL11.glVertex2i(sx+sw, y);
            GL11.glVertex2i(sx+sw, y+height); GL11.glVertex2i(sx, y+height);
            GL11.glEnd();
            if (draggingScrollbar) GL11.glColor4f(0.7f, 0.8f, 1.0f, 1f);
            else GL11.glColor4f(borderR, borderG, borderB, borderAlpha);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2i(sx, sy); GL11.glVertex2i(sx+sw, sy);
            GL11.glVertex2i(sx+sw, sy+sh); GL11.glVertex2i(sx, sy+sh);
            GL11.glEnd();
        }

        @Override public boolean handleMouseClick(float mx, float my, int btn, int act, int mods, long win) {
            if (!visible) return false;
            if (btn == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                if (act == GLFW.GLFW_PRESS && isOverScrollbar(mx, my)) { draggingScrollbar = true; return true; }
                else if (act == GLFW.GLFW_RELEASE) { draggingScrollbar = false; }
            }
            float localX = mx - x, localY = my - y + scrollOffset.get();
            for (int i = children.size()-1; i>=0; i--) {
                UIComponent child = children.get(i);
                if (child.isVisible() && child.isHovered(localX, localY)) {
                    if (child.handleMouseClick(localX, localY, btn, act, mods, win)) return true;
                }
            }
            return false;
        }
        @Override public boolean handleMouseMove(float mx, float my, long win) {
            if (!visible) return false;
            if (draggingScrollbar) { setScrollOffsetFromY(my); return true; }
            float localX = mx - x, localY = my - y + scrollOffset.get();
            for (int i = children.size()-1; i>=0; i--) {
                UIComponent child = children.get(i);
                if (child.isVisible()) { if (child.handleMouseMove(localX, localY, win)) return true; }
            }
            return false;
        }
        @Override public boolean handleMouseScroll(float mx, float my, float xOff, float yOff, long win) {
            if (isHovered(mx, my)) { scroll((int)(yOff * -50)); return true; }
            return false;
        }

        public void scroll(int delta) { int newOff = scrollOffset.get() + delta; int maxOff = Math.max(0, totalContentHeight - height); scrollOffset.set(Math.max(0, Math.min(newOff, maxOff))); }
        public void setScrollOffsetFromY(float mouseY) {
            if (totalContentHeight <= height) return;
            int sh = Math.max(25, (int)((float)height / totalContentHeight * height));
            int trackH = height - sh;
            float relY = mouseY - y - (sh / 2f);
            relY = Math.max(0, Math.min(relY, trackH));
            float ratio = relY / trackH;
            scrollOffset.set((int)(ratio * (totalContentHeight - height)));
            clampScrollOffset();
        }
        public void clampScrollOffset() { int maxOff = Math.max(0, totalContentHeight - height); int cur = scrollOffset.get(); if (cur > maxOff) scrollOffset.set(maxOff); else if (cur < 0) scrollOffset.set(0); }
        public boolean isOverScrollbar(float mx, float my) {
            if (!visible || totalContentHeight <= height) return false;
            int sw = 8, sx = x + width - sw - 6;
            float ratio = (totalContentHeight - height == 0) ? 0 : (float) scrollOffset.get() / (totalContentHeight - height);
            int sh = Math.max(25, (int)((float)height / totalContentHeight * height));
            int sy = y + (int)(ratio * (height - sh));
            return mx >= sx && mx <= sx+sw && my >= sy && my <= sy+sh;
        }

        public void addChild(UIComponent child) { children.add(child); updateTotalContentHeight(); clampScrollOffset(); }
        public void removeChild(UIComponent child) { children.remove(child); updateTotalContentHeight(); clampScrollOffset(); }
        public void clearChildren() { children.clear(); scrollOffset.set(0); totalContentHeight = 0; }
        public void updateTotalContentHeight() { int maxY = 0; for (UIComponent ch : children) maxY = Math.max(maxY, ch.getY() + ch.getHeight() + 5); totalContentHeight = maxY; clampScrollOffset(); }

        public List<UIComponent> getChildren() { return children; }
        public int getScrollOffset() { return scrollOffset.get(); }
        public void setScrollOffset(int off) { scrollOffset.set(off); clampScrollOffset(); }

        // 样式设置
        public void setBgColor(float r, float g, float b) { bgR = r; bgG = g; bgB = b; }
        public void setBgAlpha(float a) { bgAlpha = a; }
        public void setBorderColor(float r, float g, float b) { borderR = r; borderG = g; borderB = b; }
        public void setBorderAlpha(float a) { borderAlpha = a; }
    }
}