# FPS Control 使用说明

Microbot 使用上游 RuneLite 自带的 **FPS Control** 插件来限制客户端的绘制帧率，不需要额外的插件。

本项目由 `microbot Performance` 插件自动启用 FPS Control，但**限速开关和目标帧率仍需在下面的面板里自己设置**。

## 启用步骤

FPS Control 有两道开关，**两个都要打开才会生效**：

1. 在 Community Plugins 面板里找到 **FPS Control**，把它启用。
   该插件 `enabledByDefault = false`，默认是关的。
2. 在插件的设置面板里勾选 **Limit global FPS**。
   这一项默认也是关闭的。只启用插件而不勾它，限速不会生效。

然后设置 **Global FPS target**，取值范围 1 到 360，默认是 50。

想看当前实际帧率的话，**Draw FPS indicator** 默认是开的，会在画面右上角显示帧数。

## 它是怎么工作的

插件在每次画面绘制完成之后休眠一段时间，从而降低客户端每秒画出的帧数。上游的注释说明了这一点：

```40:41:runelite-client/src/main/java/net/runelite/client/plugins/fps/FpsDrawListener.java
 * Enforcing FPS in the draw code does not impact the client engine's ability to run including its audio,
 * even when forced to 1 FPS with this plugin.
```

也就是说：**游戏逻辑、客户端线程和音频都不受影响**，减少的只是画出来的帧数。脚本读取客户端数据做判定不受任何影响，只是你自己在屏幕上看到的画面刷新变慢了。

休眠时长是动态调整的：插件会采样最近几帧的实际间隔，每次只增减 1 毫秒，逐步逼近目标值。所以刚启用时帧率会有几秒的收敛过程。

## 窗口失焦时的行为

**Limit FPS unfocused** 这一项默认关闭。默认情况下，窗口失焦（alt-tab 出去）时**不会限速**，帧率会立刻回到不限制的水平，CPU 占用也跟着上去。

跑脚本时如果经常切出去做别的事，建议把它也打开，并单独设置 **Unfocused FPS target**。

## 和游戏内 FPS 设置的关系

Microbot 里有三个容易混淆的 FPS 相关机制，它们作用在不同层次：

| 机制 | 作用 | 取值 |
|---|---|---|
| 游戏内 Settings → Game FPS Limit | 游戏引擎自己的渲染节奏 | 20/30/40/50/60 固定档 |
| GPU 插件的 Unlock FPS | 解除相机移动的 50 FPS 上限 | 1-999 任意 |
| FPS Control（本插件） | 客户端重绘频率 | 1-360 任意 |

- 游戏内设置决定**画面流畅度**。调低会让相机旋转变顿。
- GPU 插件的 Unlock FPS 在 Microbot 里**默认已开启**，目标 60 帧，所以游戏内那个下拉框选什么基本不影响实际帧率了。
- FPS Control 管的是**CPU 占用**。它能让画面变卡，但能让脚本跑得更省资源。

三者同时生效时，实际帧率取**最严格**的那个。GPU 插件的 Unlock FPS 不受影响，仍然可以让相机移动保持流畅。

## 配置是持久化的

FPS Control 的设置写在 profile 配置文件里，**重启客户端后仍然保留**，不需要每次重新开启。

## 注意事项

- 这是全局设置，对所有脚本和所有账号都生效。如果你需要"只在某个脚本运行时限速、脚本停了自动恢复"，FPS Control 做不到这一点，它只认面板里的配置。
- 如果同时用其他插件限帧，实际帧率会取更严的那个。
- 面板里的选项名称是英文的，对应关系为：Limit global FPS = 全局限速开关，Global FPS target = 目标帧率，Limit FPS unfocused = 失焦时限速开关，Unfocused FPS target = 失焦时的目标帧率，Draw FPS indicator = 显示帧率数字。
