# Minimal Toolbar 插件

Microbot 的 **Minimal Toolbar** 插件负责精简窗口右上角的那排图标。

插件默认启用，不需要手动打开。

## 它做什么

右上角工具栏里的按钮，只留下这三个：

| 按钮 | tooltip |
|---|---|
| 禁用/启用输入 | `Disable Input` / `Enable Input` |
| 显示/隐藏控制台日志 | `Show console` / `Hide console` |
| 折叠/展开侧边栏 | `Close sidebar` |

其余的全部隐藏，包括：

- 自动任务开关（`Enable/Disable 'Semi-Auto' Questing`）
- 问题反馈（`Report a problem`）

**左侧那一列插件图标不受影响。** Community Plugins、Antiban、Quest Helper、Inventory Setups、Shortest Path、Wiki、Hiscore 这些都照常显示。

禁用插件后，所有被隐藏的按钮会重新出现。

## 为什么不能直接 setVisible(false)

工具栏面板用的是 `DynamicGridLayout`，而它计算尺寸的时候**不区分组件是否可见**：

```java
// DynamicGridLayout.calculateSize
final int ncomponents = parent.getComponentCount();
for (int i = 0; i < ncomponents; i++)
{
    final Component comp = parent.getComponent(i);
    final Dimension d = sizer.apply(comp);   // 不检查 comp.isVisible()
    ...
}
```

所以 `setVisible(false)` 的按钮虽然不画了，位置照样被占着 —— 工具栏里会留下一段空白，保留的按钮被挤到左边。

这里是把按钮真的从容器里摘掉（`Container.remove`），摘完重算一次 `maximumSize`。

## 为什么用反射

右上角的按钮都放在 `net.runelite.client.ui.ClientToolbarPanel` 里。这个类是**包私有的**，它自己的 `add` / `remove` / `get` 方法也都是包私有的。

插件必须放在 `net.runelite.client.plugins` 包下才能被 `PluginManager` 扫描到，所以访问不到 `ClientToolbarPanel`，只能反射。

反射用到两样东西：

| 目标 | 用途 |
|---|---|
| `net.runelite.client.ui.ClientToolbarPanel`（类） | 找面板实例、拿字段、拿方法 |
| `entries` 字段（`Map<NavigationButton, Component>`） | 按钮表，key 是 `NavigationButton`，value 是容器里的组件 |

摘按钮时把 `entries` 里的记录和容器里的组件一起清掉。

## 恢复是怎么做的

走公开的 `ClientToolbar.addNavigation(NavigationButton)`。

因为摘按钮时已经把 `entries` 里的记录清掉了，重新添加时 `putIfAbsent` 会成功，生成一个**新的按钮实例**，位置按 `priority` 排回去。

用新的实例而不是把旧的加回去，是因为「禁用/启用输入」那个按钮在切换状态时本身就是移除旧的、加入新的。旧的实例可能已经被移除了，加回去会出现两个失效的按钮。

## 工具栏面板是怎么找到的

两条路：

1. 从 `ClientUI.getFrame().getJMenuBar()` 里找 —— 开了自定义标题栏时（Windows 上 `enableCustomChrome()` 默认返回 `true`），工具栏就在菜单栏里
2. 兜底：递归遍历整个组件树找 —— 没开自定义标题栏时，工具栏在侧边栏底部

两条路都找不到就什么都不做，不会报错。

这段反射逻辑抽在 `ToolbarAccess` 里，[Toolbar Buttons](toolbar-buttons.md) 插件也用它。

## 持续扫描

按钮不是一次加完的：

- 插件的加载顺序不确定
- 「禁用/启用输入」按钮切换状态时是移除旧的、加入新的

所以有一个每 500 毫秒跑一次的定时器在扫描。另外订阅了 `PluginChanged`，插件启停时立刻再扫一次。

## 禁用

在 Community Plugins 面板里禁用 Minimal Toolbar 即可。禁用后所有按钮恢复显示。

可以反复启用禁用，不会报错。插件内部的定时器跟随插件生命周期创建和销毁。
