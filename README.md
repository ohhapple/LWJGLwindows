# 全参构造(也可以使用其它重载的构造方法，使用默认值创建页面)
~~~
        // 文本框全参构造
        public TextField(int x, int y, int w, int h, String p, int fontSize,//x坐标 y坐标 宽度w 高度h 默认显示提示文字 输入文字大小
                         float tr, float tg, float tb, // 输入文字颜色
                         float pr, float pg, float pb,// 占位符颜色
                         float bgR, float bgG, float bgB,// 背景颜色
                         float borderR, float borderG, float borderB,//边框颜色
                         float borderHoverR, float borderHoverG, float borderHoverB,//悬浮边框颜色
                         float borderFocusR, float borderFocusG, float borderFocusB) //焦点边框颜色

        // 滑块全参数构造
        public Slider(int x, int y, int width, int height, float initialValue, Consumer<Float> listener,//x坐标 y坐标 宽度 高度 初始值百分比0-1 滑动监听器输出value
                      float trackR, float trackG, float trackB, //轨道颜色
                      float fillR, float fillG, float fillB,//填充颜色
                      float thumbR, float thumbG, float thumbB, //滑块颜色
                      float thumbHoverR, float thumbHoverG, float thumbHoverB,//滑块悬浮颜色
                      Integer trackHeight, Integer thumbWidth, Integer thumbHeight,//固定轨道高度像素 固定滑块宽度像素 固定滑块高度像素 (可选:传入null按组件高度百分比生成)
                      boolean showValue, int valFontSize, float valR, float valG, float valB) //是否显示数值 数值字体大小 数值颜色

        // 按钮全参构造
        public Button(int x, int y, int w, int h, String t, Runnable a,//x坐标 y坐标 宽度 高度 点击执行事件
                      int fontSize, float tr, float tg, float tb,//文字大小 文字颜色
                      float bgR, float bgG, float bgB,//背景颜色
                      float bgHoverR, float bgHoverG, float bgHoverB,//悬浮背景颜色
                      float borderR, float borderG, float borderB,//边框颜色
                      float borderHoverR, float borderHoverG, float borderHoverB)//悬浮边框颜色

        // 滚动容器全参构造
        public ScrollContainer(int x, int y, int w, int h, //x坐标 y坐标 宽度 高度
                               float bgR, float bgG, float bgB, //背景色
                               float borderR, float borderG, float borderB) //边框色
~~~
# 示例(仅展示部分方法调用，其它方法自行探索)
minecraft客户端实例自行导入，该示例代码基于CarpetPlus项目
~~~
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

package com.ohhapple.carpetplus.gui.test;

import com.ohhapple.carpetplus.client.CarpetPLUSClient;
import com.ohhapple.carpetplus.gui.util.*;

public class ExamplePage {

    public static class MyMcAccess implements IMinecraftAccess {
        @Override
        public void execute(Runnable task) {
            CarpetPLUSClient.minecraftClient.execute(task);
        }
    }

    public static void openDemoPage() {
        IMinecraftAccess mc = task -> CarpetPLUSClient.minecraftClient.execute(task);

        // 可选：设置窗口图标
        BaseGuiWindow.setWindowIcon("/assets/carpetplus/icon.png");

        GuiWindows.open(mc, "演示窗口", 700, 600, win -> {
            // 可选：页面背景色rgb
            win.setBackgroundColor(0.1f, 0.1f, 0.15f);
            // 标题（自定义组件）- 使用 win.fontRenderer
            win.addComponent(win.new UIComponent(0, 0, win.windowWidth, 50) {
                @Override
                public void render(long handle, boolean hovered) {
                    win.fontRenderer.drawText(handle, "演示窗口",
                            win.windowWidth / 2f, 30, 32, 1, 1, 1);
                }
            });

            // ---- 音量滑块 ----
            BaseGuiWindow.Slider volumeSlider = win.new Slider(350, 150, 300, 15, 0.7f, value -> {
                System.out.println("当前音量: " + Math.round(value * 100) + "%");
            });
            volumeSlider.setShowValue(true);
            volumeSlider.setValueFontSize(18);
            volumeSlider.setValueColor(0.8f, 0.9f, 1.0f);
            volumeSlider.setFillColor(0.2f, 0.8f, 0.6f);
            win.addComponent(volumeSlider);

            // ---- 另一个滑块，不显示数值 ----
            BaseGuiWindow.Slider effectSlider = win.new Slider(350, 170, 300, 15, 0.3f);
            effectSlider.setShowValue(false);
            effectSlider.setFillColor(0.9f, 0.6f, 0.2f);
            effectSlider.setChangeListener(val -> {
                System.out.println("特效强度: " + val);
            });
            win.addComponent(effectSlider);

            // ----- 文本框：指定字体大小 22，文字颜色浅蓝色 -----
            BaseGuiWindow.TextField searchField = win.new TextField(50, 90, 600, 45, "搜索...", 22, 0.6f, 0.8f, 1.0f);
            searchField.setPlaceholderColor(0.5f, 0.5f, 0.5f);
            searchField.setEnterListener(text -> System.out.println("回车搜索：" + text));
            win.addComponent(searchField);
            // 可选：设置焦点
            win.focusedComponent = searchField;

            // ----- 按钮1：默认白色字体，自动截断长文本 -----
            BaseGuiWindow.Button searchBtn = win.new Button(50, 150, 120, 40, "这是一个非常非常长的搜索按钮文本",
                    () -> System.out.println("搜索：" + searchField.getText()));
            searchBtn.setFontSize(20);
            win.addComponent(searchBtn);

            // ----- 按钮2：指定字体大小28，文字颜色橙色 -----
            BaseGuiWindow.Button colorBtn = win.new Button(200, 150, 120, 45, "彩色按钮",
                    () -> System.out.println("彩色按钮点击"), 28, 1.0f, 0.6f, 0.2f);
            win.addComponent(colorBtn);

            // 滚动容器
            BaseGuiWindow.ScrollContainer resultList = win.new ScrollContainer(50, 210, 600, 300);
            for (int i = 0; i < 50; i++) {
                int idx = i;
                BaseGuiWindow.Button entry = win.new Button(0, i * 45, 580, 40,
                        "条目 #" + i + " 详细信息", () -> System.out.println("查看详情 " + idx), 18);
                resultList.addChild(entry);
            }
            win.addComponent(resultList);
        });
    }
}
~~~
