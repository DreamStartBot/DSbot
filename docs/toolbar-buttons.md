# Toolbar Buttons 插件

Microbot 的 **Toolbar Buttons** 插件在窗口右上角工具栏里加一组控制按钮。

插件默认启用，不需要手动打开。

## 它做什么

四个按钮放在同一个容器里，出现在右上角工具栏的**最左边**：

| 按钮 | 图标 | tooltip | 行为 |
|---|---|---|---|
| 启动 | ▶ 三角形 | `Start` | 打开脚本启动弹窗 |
| 暂停 | ‖ 双竖线 | `Pause` | 冻结正在跑的脚本 |
| 停止 | ■ 方块 | `Stop` | 停掉正在跑的脚本 |
| 重启 | ↻ 圆环箭头 | `Restart` | 重载 jar 并重启最后启动的脚本 |

### 重启按钮做了什么

点「重启」会：**重载目录里所有 jar → 找到同一个脚本 → 启动它**。

这是开发调试的核心流程 —— 改完脚本代码、覆盖 jar、点重启，新代码直接生效。

**重载用的是 `MicrobotPluginManager.reloadSideLoadPlugins()`**，它做两件事：

1. `unloadSideLoadPlugins()` —— 停掉并卸载所有 side-load 脚本
2. `loadSideLoadPlugins()` —— 重新扫描目录加载一遍

**「停止脚本」是重载自带的那一步。** 正在跑的脚本会被打断 —— 这是必须的，
jar 被 ClassLoader 占着就加载不了新的。

**重载后拿到的是全新的插件实例**（新的 ClassLoader），所以不能拿旧引用，
只能按名字重新找一遍。

**怎么认准「同一个脚本」** —— 用两个条件：

| 条件 | 为什么 |
|---|---|
| 全限定类名 `getClass().getName()` | 类名一样但包名不一样的脚本天然分得开 |
| jar 内部名（jar 文件名去掉 `.jar`） | 同名同包的类装在两个 jar 里时靠这个区分 |

jar 内部名跨重载是稳定的 —— 重载会换 ClassLoader，但 jar 文件名不变。
所以 `MicrobotPluginManager.findSideLoadJarName(Plugin)` 能跨重载定位到同一个脚本。

**「最后启动的脚本」怎么记** —— 每次通过弹窗启动成功后记下它的类名和 jar 名。
启动多个脚本时记的是最后那个。
如果从没通过弹窗启动过（客户端启动时自动拉起的脚本），就用当前正在跑的那个当重启目标。

**找不到同名脚本时**（改了类名、jar 被换掉了）不启动任何东西，打开脚本启动弹窗让用户自己选。

**重启期间四个按钮全部变灰禁用**，重载完成自动恢复 —— 免得用户中途点别的把流程搅乱。

### 暂停按钮做了什么

点「暂停」会冻结所有正在跑的脚本，按钮原地变成 ⏵⏵ 的「继续」图标（tooltip 也变成 `Resume`），再点一次就恢复。

**microbot 本身就支持暂停**，有两个标志管着，两条路径都要设：

| 标志 | 管什么 |
|---|---|
| `Microbot.pauseAllScripts` | `Script.run()` 的守卫（第 142 行），所有继承 `Script` 的脚本都在这里停下 |
| `PluginPauseEvent.isPaused` | `BlockingEventManager` 里全局注册了它（第 60 行），脚本会被阻塞事件挡住 |

代码里用 `PluginPauseEvent.setPausedAll(paused)` 一次设两个。

**前提：脚本得继承 microbot 的 `Script`（或 `StateMachineScript`）。**
直接继承 RuneLite `Plugin` 自己写循环的脚本，`pauseAllScripts` 拦不住它。

**暂停是全局的** —— 这两个标志都是全局静态变量，做不到只暂停一个脚本。
所以工具栏的按钮组本来就是聚合状态（「有脚本在跑」），暂停跟着全局走是一致的。

**工具栏自己记一个「用户暂停」标志**，不直接读 `PluginPauseEvent.isPaused()`。
那个全局标志有很多写入者 —— antiban、breakhandler、cannon、inventory setup 这些都会临时把它设成 true 再恢复。
要是直接跟随，工具栏按钮会跟着那些临时暂停闪来闪去。

**副作用**：用户的暂停可能被别的组件吃掉。
比如 antiban 结束时执行 `compareAndSet(true, false)`，会把用户的暂停一起清了，脚本就莫名其妙继续跑了。
所以定时器每 500ms 检查一次，暂停被清了就补回来。

**点「停止」时**暂停标志会跟着清掉 —— 脚本都没了，暂停没意义。
（`Script.shutdown()` 也会清 `pauseAllScripts`，双保险。）

### 停止按钮做了什么

点「停止」会停掉**所有正在运行的外部脚本**：

- 只认 `isExternal = true` 的插件，客户端内置插件不动 —— 那些是客户端自己的功能，停掉会影响别的插件
- 停之前先把配置里的启用开关关掉（`setPluginEnabled(false)`），
  不然重启客户端它又被自动拉起来
- 用的是 `PluginManager.stopPlugin()`，它会调插件的 `shutDown()`、
  从 eventBus 注销、并发出 `PluginChanged` 事件（按钮状态就跟着它刷新）

**停掉不等于卸载。** 插件还留在插件管理器里，弹窗里还能选到它。
下次点「启动」时会走一遍全量重载（停 + 卸载 + 重新加载 jar），那才会真正换成新的 jar。

停完之后按钮回到「启动 + 重启」。

**注意**：`PluginManager.stopPlugin()` 断言必须在 EDT 上调用。
按钮点击本来就在 EDT 上，直接调就行。

### 禁用输入

脚本跑着的时候，用户输入会被锁住 —— 游戏画布收不到鼠标和键盘，
手放在鼠标上不会误点。

**用的是右上角那个现成的开关**，不是自己造的一套拦截。
`InputSelector` 里的「Disable Input / Enable Input」按钮做的是：

```java
ClientUI.getClient().setEnabled(false);   // client 组件的 peer 不再收 OS 事件
Microbot.getClient().getCanvas().setFocusable(false);   // 画布不再抢焦点
```

`InputSelector` 以前是 `new InputSelector(clientToolbar)` 这样创建的，
引用直接丢了，外部拿不到。现在改成了 `@Singleton` + `@Inject`，
`MicrobotPlugin` 和 `ToolbarButtonsPlugin` 注入的是同一个实例。

**什么时候锁、什么时候放：**

| 状态 | 输入 |
|---|---|
| 外部脚本在跑 | 锁 |
| 脚本停了 | 放 |
| 脚本暂停了 | 放 |
| 从暂停点继续 | 重新锁 |

**「脚本在跑」包含自动启动的脚本**，不只是通过弹窗手动启动的。
状态刷新本来就有 500ms 的定时兜底，自动启动的脚本也会被算进去。

**用户手动解锁不会被覆盖。** 这靠两个变量分开：

- `inputLockRequested` —— 我方的意图
- 右上角按钮的实际状态

状态刷新只在**意图变了**的时候才去动那个开关。所以用户手动点过
Enable Input 之后，脚本还在跑的情况下，那个 500ms 的刷新不会把他的
操作盖回去。

例外是「从暂停点继续」：暂停期间意图已经是 false，继续后变回 true，
所以会重新锁上。

**锁上之后工具栏还是能点的。** `client` 组件是游戏的那个组件，
工具栏和侧边栏是 JFrame 的直接子组件，不在它底下，所以暂停和停止
随时能按。

**脚本自己的输入不受影响。** 真实用户输入走 OS 事件投递，被 peer 拦掉；
脚本的输入是直接派发到画布的监听器上的，不经过那条路。

> 历史上不是这样的。2024 年那次「block canvas focus」的改动同时动了
> `Rs2Keyboard`，因为那时候脚本用 `canvas.dispatchEvent` 走 AWT 管道，
> 会被 `setFocusable(false)` 拦下。现在的 `Rs2Keyboard` 改成手动遍历
> `canvas.getKeyListeners()`，不依赖焦点状态，那段适配已经不需要了。

### 四个按钮什么时候显示哪个

按钮组有三个状态，**跟着脚本的实际运行情况走**：

| 状态 | 显示的按钮 |
|---|---|
| 从没跑过脚本 | 启动 |
| 有脚本在跑 | 暂停、停止、重启 |
| 有脚本在跑但被暂停了 | **继续**、停止、重启 |
| 跑过但现在停了 | 启动、重启 |

暂停状态下按钮组不变，只是暂停那个按钮的图标换成 ⏵⏵。

判断依据：

- **「有脚本在跑」** —— `PluginManager.getActivePlugins()` 里有 `isExternal = true` 的插件。
  客户端内置的插件不算，它们跟脚本的启停没关系。
- **「跑过」** —— 只记在内存里，本次客户端运行期间启动过就算跑过。
  重启客户端就重新算"没跑过"。不写配置，因为调试时每次重启客户端都从干净状态开始更直观。

状态变化的时机：

- 脚本启动或停止时，`PluginManager` 会发 `PluginChanged` 事件，插件监听它来刷新按钮，
  所以状态是实时的，不用轮询。
- 刚装上按钮时也会检查一次 —— 客户端启动时 side-load 的脚本可能已经被 `startPlugins()` 拉起来了。
- 兜底：装按钮的定时器每 500ms 也会检查一次。事件万一没送到，按钮不至于永远停在旧状态。
- 启动脚本成功后，弹窗会主动回调一次刷新 —— 这是最确定的一条路径。

**订阅事件要用 RuneLite 自己的注解**：

```java
import net.runelite.client.eventbus.Subscribe;   // 不是 com.google.common.eventbus.Subscribe
```

RuneLite 的 `EventBus` 只认自己那个 `@Subscribe`，而且要求**方法名必须是 `on` + 事件类名**
（比如 `onPluginChanged`）。用错注解的话 `eventBus.register()` 会直接跳过这个方法，
不报错，事件就是收不到。

**注意**：因为外部脚本默认是启用的（`enabledByDefault` 默认 true），
而 `RuneLite` 里 `microbotPluginManager.loadSideLoadPlugins()` 在 `pluginManager.startPlugins()` **之前**执行，
所以客户端一启动，side-load 的脚本就已经在跑了 —— 这时显示的是暂停、停止、重启，而不是只有启动。

想让它启动时不自动跑，脚本的 `@PluginDescriptor` 里加 `enabledByDefault = false`。

图标是用 `Graphics2D` 现画的 16px 白色图形，没有用图片资源 —— 这样三个图标风格一定一致，也不用往仓库里加二进制文件。

## 脚本启动弹窗

点「启动」按钮会弹出一个模态对话框：

- 顶部：提示文字
- 中间：可滚动的脚本列表，列出所有从 jar 包加载的脚本
- 底部：状态文字 + 「启动」「关闭」两个按钮

选中一个脚本点「启动」，就会调 `Microbot.startPlugin(script)` 把它跑起来。

**弹窗不会自动关闭**，底部会显示结果：

- `已启动 XXX`
- `启动失败：XXX（可能已经在运行）`

### 弹窗之前会全量重载一次 jar

点「启动」之后、弹窗出现之前，会先**全量重新加载**一遍 side-load 目录里的脚本 jar：

1. 停掉并卸载当前所有从 side-load 目录加载的脚本
2. 关掉它们的 ClassLoader
3. 重新扫描目录，把所有 jar 重新加载一遍

所以新装上的脚本能立刻出现在列表里。

**这一步会打断正在运行的脚本。** 重载之后所有脚本都是"已加载但未运行"的状态，要跑哪个由你在弹窗里选。

重载放在后台线程做，界面上不会卡住；这期间「启动」按钮是灰的，重载完才恢复并弹出对话框。

### 原 jar 不会被占用

`URLClassLoader` 打开 jar 时会一直持有文件句柄，Windows 上原 jar 就被锁住，
覆盖不了 —— 调试时反复替换脚本 jar 很不方便。

所以加载脚本时改成**先把原 jar 复制到运行时目录，再用副本加载**：

- 原 jar：`~/.runelite/microbot-plugins/`（你放脚本的地方）
- 运行时副本：`~/.runelite/microbot-plugins-runtime/`

复制完成后原 jar 没有任何进程持有句柄，**随时可以覆盖**。

用副本而不是"加载完就 close ClassLoader"，是因为 close 之后运行时再想加载 jar 里的类
（懒加载的依赖库）会失败，读 jar 里的资源文件也会失效。副本没有这个副作用。

副本在脚本卸载时删除，客户端退出时清空整个目录。

**注意**：覆盖原 jar 之后，要再点一次「启动」才会用上新代码 ——
正在跑的是副本里的旧版本，重载会重新复制一份新的。

### 列表里有哪些脚本

`PluginManager.getPlugins()` 里 `isExternal = true` 且没有 `hidden` 的插件 —— 也就是从 jar 包加载的那些。

显示名取 `@PluginDescriptor` 的 `name`，去掉里面的标签（`<col=...>`）和前缀方括号（`[Beta]` 之类）；没有名字就用类名。

### 每一项长什么样

每一项是一张文字卡片，三行：

```
银行脚本                                          ← 名称，加粗白字
自动到银行取钱并存到银行                            ← 描述，灰色小字
v2.3.4  ·  by Alice  ·  bank, money                ← 版本/作者/标签，更暗的小字
```

**这些信息全部取自脚本自己的 `@PluginDescriptor` 注解**，不查 Plugin Hub 的 manifest：

| 那一行 | 注解字段 | 没填的时候 |
|---|---|---|
| 名称 | `name` | 用类名 |
| 描述 | `description` | 那一行留空 |
| 版本 | `version` | 注解默认值是 `1.0.0`，会显示出来 |
| 作者 | `authors` | 默认值是 `{"Unknown"}`，等于没填，不显示 |
| 标签 | `tags` | 那一段不出现 |

描述会去掉标签、把换行压成空格，然后按显示宽度截断（中文算两个宽度）。

**所以想让自己的脚本显示得更丰富，就在 `@PluginDescriptor` 里把这些字段填上：**

```java
@PluginDescriptor(
    name = "银行脚本",
    description = "自动到银行取钱并存到银行",
    authors = {"你的名字"},
    version = "1.0.0",
    tags = {"bank", "money"},
    isExternal = true
)
```

没有图标，也没有运行状态标记 —— 这些信息在本地打包的脚本上没有可靠来源，显示不出来反而误导。

### 交互

还是一个 `JList`，只是渲染器换成了多行卡片，所以：

- 上下键选择、回车启动都还能用
- 选中项左边有一条品牌色的竖条
- 行高固定（58px），所有卡片对齐

### 启动是怎么做的

用 `Microbot.startPlugin(Plugin)`，它内部已经封装好了完整流程：

```java
getPluginManager().setPluginEnabled(plugin, true);   // 先打开配置开关
getPluginManager().startPlugin(plugin);               // 再启动
getPluginManager().startPlugins();                    // 拉起依赖的其它插件
```

第一步不能省：`PluginManager.startPlugin` 开头有个检查，
插件没在配置里打开就直接返回 false。

因为外部脚本在 side-load 时通常已经被自动启动过了，
所以对已经在跑的脚本点「启动」，`startPlugin` 会返回 false，
弹窗底部会显示「启动失败：XXX（可能已经在运行）」，弹窗保留着让用户看到原因。

**启动成功后弹窗直接关掉** —— 脚本已经在跑了，弹窗留着也没用。

### 弹窗在哪实现

`ScriptLauncherDialog`，和这个插件在同一个包。

弹窗本身不好测，所以筛选脚本和取显示名、描述、版本、作者、标签这些纯逻辑
拆成了静态方法，由 `ScriptLauncherDialogTest` 直接测。

## 全量重载是怎么实现的

重载能力放在 `MicrobotPluginManager` 里（`reloadSideLoadPlugins()` /
`unloadSideLoadPlugins()`），不在这个插件里 —— 那边才有 side-load 的
ClassLoader 表和停插件需要的上下文。

### 怎么认出哪些插件是从 jar 加载的

`MicrobotPluginManager` 维护了一张 `internalName -> ClassLoader` 的表，
每个 side-load 的 jar 一个 ClassLoader。

判定一个插件是不是从某个 jar 加载的，靠**它的 ClassLoader 就是这个 jar 的 ClassLoader**：

```java
x.getClass().getClassLoader() == loader
```

**注意不要拿 jar 文件名去和插件类名比。** 那是踩过的坑：

microbot 的其它地方（`manifestMap` 的 key、`getPluginManifest`）都假定
「jar 文件名 == internalName == 插件类名」。这个假定在**一个 jar 里装多个脚本**时
就不成立了 —— jar 名跟脚本类名根本对不上。

按类名比对会导致两个后果：

1. **卸载时一个都卸不掉**。旧插件还挂在插件管理器上，但 ClassLoader 已经被关掉。
2. **重新加载时会重复加载**。因为「这个 jar 加载过没有」的判断也是拿类名集合比 jar 名，
   永远返回"没加载过"。

结果就是重载后 pluginManager 里同时存在新旧两份同名插件，
弹窗里出现重复条目，默认列表里也会多出重名的脚本。

现在改成按 ClassLoader 判定，两个问题一起解决。

### 线程

`PluginManager.stopPlugin` 里有 `assert SwingUtilities.isEventDispatchThread()`，
停插件必须在 EDT 上做。所以：

- 停插件和卸载 → `SwingUtilities.invokeAndWait` 切到 EDT
- 加载 jar → 留在后台线程（读 jar、加载类比较慢）

`PluginManager.plugins` 是 `CopyOnWriteArrayList`，后台线程注册插件是安全的。

重载完会发 `ExternalPluginsChanged` 事件，所有订阅它的面板都用
`SwingUtilities.invokeLater` 刷新，所以在后台线程发事件也没问题。

### 测试

`MicrobotPluginManagerReloadTest` 测的是"认出哪些插件该被卸载"这个判定。

测的时候有两个坑：

1. **`URLClassLoader` 是 parent-first 委托** —— 用 `loader.loadClass(X)` 造 fixture，
   拿回来的类还是父加载器加载的，`getClassLoader()` 根本对不上。
   所以测试里用一个自己 `defineClass` 的 ClassLoader 来模拟 side-load。

2. **fixture 必须是独立的顶层类** —— 嵌套类重新定义时会因为
   `InnerClasses` 属性对不上而抛 `IncompatibleClassChangeError`。

另外 `loadPlugins` 里的「已加载」判断也从按 Class 对象改成了按全限定类名。
原因：重载会新建 ClassLoader，同一个 jar 里的类会拿到新的 Class 对象，
按对象比较挡不住重复加载。用全限定名而不是简单名，
是为了避免 `a.Foo` 和 `b.Foo` 这种同名不同包的插件被误挡。

### 排查脚本数量不对时看日志

重载路径上加了诊断日志，日志里能直接看出问题在哪：

```
Reloading side-load plugins. Before: [ScriptA@PluginJarClassLoader, ScriptB@PluginJarClassLoader]
Unloaded side-load plugin jar MyTestScripts (2 plugin(s))
Loaded side-load jar MyTestScripts
Reloaded side-load plugins. After: [ScriptA@PluginJarClassLoader, ScriptB@PluginJarClassLoader]
```

- `Before` / `After` 列出重载前后的 external 插件
- `Unloaded side-load plugin jar <名字> (N plugin(s))` —— N 是这个 jar 卸掉的插件数
- `Loaded side-load jar <名字>` —— 加载成功
- `Side-load jar <名字> produced no plugins` —— jar 里没找到插件
- `Plugin <名字> was not loaded: <原因>` —— 被兼容性检查拦住了
- `Plugin <名字> is already loaded, skipping duplicate.` —— 按类名判定为已加载

如果 `Unloaded` 那一行的 N 是 0，说明这个 jar 的插件没被认出来。
如果 `After` 里的插件数量少于 `Before`，说明有插件在重载过程中掉了。

## 和 Minimal Toolbar 的关系

两个插件是独立的，可以分别开关：

| 插件 | 做什么 |
|---|---|
| [Minimal Toolbar](minimal-toolbar.md) | 隐藏右上角多余的按钮，只留三个白名单按钮 |
| Toolbar Buttons | 在右上角加这组启动/暂停/停止/重启按钮 |

两个插件不会互相干扰 —— 原因见下一节。

## 为什么用自定义容器，而不是 addNavigation

如果这几个按钮走 `ClientToolbar.addNavigation()`，它们会进
`ClientToolbarPanel` 内部的按钮表，而 Minimal Toolbar 正是遍历那张表来隐藏多余按钮的，
**会把这几个一起隐藏掉**。

所以这里自己建一个 `JPanel` 容器，直接加进工具栏面板。容器不在那张表里，
Minimal Toolbar 看不到它。

这个约束是 `ToolbarButtonsPluginTest.minimalToolbarDoesNotRemoveTheContainer`
这个用例守着的。

## 按钮的位置

容器是用 `panel.add(container, 0)` 加的，也就是插到工具栏最前面。

工具栏本身的按钮是按 `priority` 排序的（`ClientToolbarPanel` 内部是 `TreeMap`），
而自定义容器不参与那套排序，它的位置就是加进去时的顺序。

## 什么时候装上去

插件启动时客户端窗口可能还没建好，工具栏面板拿不到，所以有一个每 500 毫秒跑一次的定时器在重试，装上之后就不再重复安装。

禁用插件会把容器从工具栏里摘掉。

## 以后接功能的时候

「暂停」和「停止」两个按钮目前还没有动作。

另外，弹窗里还可以在后续加这些：

- 脚本运行状态。外部脚本在 side-load 时通常已经被自动启动了，所以现在点「启动」对已经在跑的脚本不会有反应 —— 想区分的话得在列表里显示状态
- 「启动」按钮在选中的脚本正在运行时变灰或隐藏
- 一个刷新按钮，用来加载运行中才被装上的新脚本

接功能的时候要注意 Swing 线程：`Microbot.startPlugin` 在 EDT 上是同步执行的，
但真正让脚本跑起来的逻辑通常在客户端线程上，别在 EDT 上做重活。

## 禁用

在 Community Plugins 面板里禁用 Toolbar Buttons 即可。禁用后这几个按钮会从工具栏消失。

可以反复启用禁用，不会报错。插件内部的定时器跟随插件生命周期创建和销毁。
