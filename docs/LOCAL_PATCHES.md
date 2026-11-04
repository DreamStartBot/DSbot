# 本地改动清单

这个文件记录 Microbot 相对上游 RuneLite 的所有改动，用于在拉取上游更新后快速定位冲突并决定如何覆盖。

所有改动的位置都用统一的注释标记包起来了：

```java
// ===================== [MICROBOT 本地改动] <主题> =====================
// ... 中文说明 ...
// ===================== [MICROBOT 本地改动 END] =====================
```

标记内的内容一律是中文注释，写明了"上游原本是什么、本地改成什么、冲突时怎么覆盖"。

命令行快速定位：

```powershell
rg -n "MICROBOT 本地改动" runelite-client/
```

---

## 一、改动了上游文件的（拉取上游更新后需要处理）

### 1. `runelite-client/src/main/java/net/runelite/client/RuneLite.java`

启动入口，负责注册启动参数、构建 Guice 模块、启动插件。

| 位置 | 改动内容 | 冲突时如何覆盖 |
|---|---|---|
| `main()` 中的参数注册区 | 新增 `--proxy-type/--proxy-host/--proxy-port/--proxy-user/--proxy-pass` 五个代理分项参数 | 保留整段。上游若也加了同名参数，取上游的再合并本地逻辑 |
| `main()` 中的参数注册区 | 新增 `--session-id/--character-id/--display-name` 三个 Jagex 会话参数 | 同上 |
| `main()` 中的参数注册区 | 新增 `--index` 自定义窗口标题参数 | 保留整段。该参数不是敏感参数，不要加进敏感参数列表 |
| `main()` 中的 `new RuneLiteModule(...)` | 多传三个 Jagex 参数 + 一个 `windowTitleOverride` | 保留。若上游改了 `RuneLiteModule` 构造签名，这里要跟着改 |
| 代理调度分支 | `--proxy-host` 优先于 `--proxy` | 保留整段，规则见 `ProxyConfiguration.setupProxy` 顶部注释 |
| 字段声明区 | 新增三个 `@Named` 字段 + `JagexSessionInjector` 字段 | 保留 |
| `start()` 中 `client.unblockStartup()` 之后 | 调用 `setJagexSession()`；给了 `--session-id` 时跳过 `setCharacter()` | 保留。注入时机必须仍在 `unblockStartup()` 之后 |
| 文件末尾 | 新增 `setJagexSession()` 方法 | 保留 |

### 2. `runelite-client/src/main/java/net/runelite/client/RuneLiteDebug.java`

调试启动入口，参数与 `RuneLite` 保持一致。

| 位置 | 改动内容 | 冲突时如何覆盖 |
|---|---|---|
| import 区 | 新增 `javax.inject.Named`、`JagexSessionInjector` | 保留 |
| 字段声明区 | 新增三个 `@Named` 字段 + `JagexSessionInjector` 字段 | 保留 |
| `main()` 中的参数注册区 | 新增五个代理分项参数 + 三个 Jagex 会话参数 + `--index` 窗口标题参数 | 保留 |
| `main()` 中的 `new RuneLiteModule(...)` | 多传三个 Jagex 参数 + 一个 `windowTitleOverride` | 保留 |
| 代理调度分支 | `--proxy-host` 优先于 `--proxy` | 保留 |
| `buildHttpClient()` 之前 | **补上了 `ProxyConfiguration.setupProxy(...)` 调用**（上游 `RuneLiteDebug` 从来没调用过，代理在这条路径上一直是失效的） | 保留。这是一处上游 bug 修复，上游若已修复则删掉本地调用 |
| `client.unblockStartup()` 之后 | 调用 `jagexSessionInjector.inject(...)` | 保留 |

### 3. `runelite-client/src/main/java/net/runelite/client/RuneLiteModule.java`

Guice 模块，负责把启动参数绑定给 `RuneLite`。

| 位置 | 改动内容 | 冲突时如何覆盖 |
|---|---|---|
| 构造字段区 | 新增 `jagexSessionId`、`jagexCharacterId`、`jagexDisplayName` 三个字段 | 保留 |
| 构造字段区 | 新增 `windowTitleOverride` 字段，**放在 `noupdate` 之后** | 保留。本类用 `@AllArgsConstructor`，**字段顺序就是构造参数顺序**，挪动位置会让 `new RuneLiteModule(...)` 的传参错位 |
| `configure()` | 新增三个 `@Named` 绑定 | 保留 |
| `configure()` | 新增 `bindConstant().annotatedWith(Names.named("windowTitleOverride"))` | 保留 |

### 4. `runelite-client/src/main/java/net/runelite/client/proxy/ProxyConfiguration.java`

代理配置。

| 位置 | 改动内容 | 冲突时如何覆盖 |
|---|---|---|
| `setupProxy()` 顶部 | 本地新增了 `-proxy-type` 参数，上游原本见到它就弹窗退出 | 保留整段。完整合并规则写在该方法顶部注释里 |
| `setupProxy()` 内 `-proxy-type` 分支 | 改为：只有值不是 socks/socks5 时才拒绝 | 保留。**不能退回上游的"无条件退出"**，那会破坏"游戏流量必须走 SOCKS"的约束 |
| `setupProxy()` 内 `-proxy` 缺失分支 | 新增：只给了 `-proxy-type` 而没有地址时，提示并退出 | 保留 |
| 新增方法 `setupProxyFromParts(...)` | 分项参数入口，不做 URI 解析 | 保留 |
| 新增方法 `parsePort(String)` | 分项参数的端口解析 | 保留 |

### 5. `runelite-client/src/main/java/net/runelite/client/ui/ClientUI.java`

窗口与侧边栏的 UI 层，负责窗口标题的显示。

| 位置 | 改动内容 | 冲突时如何覆盖 |
|---|---|---|
| 字段声明区（`private final String title;` 之后） | 新增 `windowTitleOverride`、`windowTitleOverridden`、`windowTitleOverrideApplied` 三个字段 | 保留。`windowTitleOverrideApplied` 声明为 `volatile`，因为它在 EDT 上写、client 线程上读 |
| 构造函数 | 在 `@Named("runelite.title") String title` 之后追加 `@Named("windowTitleOverride") String windowTitleOverride` | 保留。**必须放在构造器最后**，否则会影响其它参数的注入顺序 |
| `onGameStateChanged` | 条件里加 `windowTitleOverridden` 短路：传了 `--index` 就不再追加玩家名 | 保留。只需保留这个短路条件 |
| `show()` 中 `frame.setVisible(true)` 之后 | 新增 `if (windowTitleOverridden) { scheduleWindowTitleOnLoggedIn(); }` | 保留。**触发点必须在 `setVisible(true)` 之后**，放在之前会在游戏加载失败时先显示出一个骗人的标题 |
| 新增方法 `scheduleWindowTitleOnLoggedIn()` | 用 `ClientThread.invokeLater(BooleanSupplier)` 轮询引擎状态，超时 60 秒后放弃 | 保留整个方法。`frame.setTitle` 必须在 EDT 上调用，方法内部已经用 `SwingUtilities.invokeLater` 切回去 |
| `updateFrameConfig` | 标题判断改成三分支：已应用的 override > 标题+玩家名 > 默认标题 | 保留整个三分支结构。**这一步不能省**，否则用户在设置面板改任何配置都会把标题重置回默认 |
| 字段声明区 | `CONFIG_GROUP` 从 `private` 改成包内可见，并新增 `CONFIG_CLIENT_CONSOLE_SHOW_LEVEL`、`CONFIG_CLIENT_CONSOLE_SHOW_LOGGER` 两个键 | 保留。`LogConsolePanel` 要往同一个配置组里写开关状态 |
| 构造函数之后，`content`/`clientPanel` 的创建处 | `new LogConsolePanel()` 改成 `new LogConsolePanel(configManager)` | 保留。日志框的显示开关要持久化，得拿到配置管理器 |
| `initializeConsoleLogging()` 末尾 | 读配置里的两个开关，塞给 `ConsoleLogAppender` | 保留 |
| `setConsoleVisible(boolean)` | 隐藏时调用新增的 `shrinkToClientHeight()` | 保留。**这一步不能省**，见第 17 节 |
| 新增方法 `shrinkToClientHeight()` | 把窗口高度恢复到「显示 console 之前的高度」 | 保留整个方法 |

### 6. `runelite-client/src/main/java/net/runelite/client/plugins/microbot/externalplugins/MicrobotPluginManager.java`

side-load 脚本的加载与卸载。

**改动目的**：给工具栏的「启动」按钮提供「全量重新加载脚本 jar」的能力。
上游原本只有 `loadSideLoadPlugins()`，它是增量语义 —— 只加载目录里新出现的 jar，
已经加载过的按类名跳过，既不停掉也不卸载任何东西。

| 位置 | 改动内容 | 冲突时如何覆盖 |
|---|---|---|
| `loadSideLoadPlugins()` 之后 | **新增** `reloadSideLoadPlugins()` —— 停掉并卸载所有 side-load 脚本，再重新扫描目录加载一遍 | 保留整块。纯新增，没动上游原有逻辑 |
| 同上 | **新增** `unloadSideLoadPlugins()` —— 只做停掉+卸载，不重新加载 | 保留整块 |
| 同上 | **新增** `unloadSideLoadPlugin(String internalName)` —— 停掉+卸载单个脚本 | 保留整块 |
| 同上 | **新增** `findSideLoadPlugins(...)`（包私有静态）—— 找出某个 ClassLoader 加载的所有插件 | 保留整块。有测试 `MicrobotPluginManagerReloadTest` 守着 |
| 同上 | **新增** `stopAndRemove(Plugin)` —— 停插件并从插件管理器摘掉 | 保留整块 |
| 同上 | **新增** `describeLoadedExternalPlugins()` —— 把已加载的 external 插件列成日志用的字符串 | 保留整块 |
| `remove(MicrobotPluginManifest)` 内 | **替换**：上游内联的「停插件 + 摘除 + 关 ClassLoader」代码段，换成调用 `unloadSideLoadPlugin(internalName)` | 保留这一行调用，删掉上游那一大段内联代码 |
| `loadPlugins()` 内 | **替换**：「已加载」判断从按 Class 对象改成按全限定类名 | 保留。改回按 Class 对象比较会重新引入「重载后出现重复插件」的问题 |
| `loadSideLoadPlugins()` 内 | **替换**：判断「这个 jar 加载过没有」从查类名集合改成查 `loaders.containsKey(internalName)` | 保留这两行 |
| `loadSideLoadPlugin()` 内 | **替换**：同上 | 保留这一行 |
| `loadSideLoadPlugin()` 内 | **替换**：`new PluginJarClassLoader(pluginFile, ...)` 改成先复制到运行时目录再用副本建 ClassLoader，让原 jar 不被占用 | 保留这几行。改回直接用 `pluginFile` 会让原 jar 重新被锁住 |
| 字段声明区 | **新增** `runtimeCopies`（internalName -> 副本文件）和常量 `RUNTIME_PLUGIN_DIR` | 保留 |
| 新增方法 | **新增** `createRuntimeCopy(...)` / `deleteRuntimeCopy(...)` / `cleanupRuntimeCopies()` | 保留整个方法 |
| `shutdown()` 内 | **新增**：关闭所有 side-load 的 ClassLoader 并清空运行时副本目录 | 保留这几行 |

**四条必须一起维护的约束**（改这个文件时注意）：

1. **判定归属要用 ClassLoader，不能用类名。**
   `MicrobotPluginManager` 维护了一张 `internalName -> ClassLoader` 表，每个 side-load 的 jar 一个 ClassLoader。
   上游其它地方（`manifestMap` 的 key、`getPluginManifest`）都假定「jar 文件名 == 插件类名」，
   这个假定在**一个 jar 里装多个脚本**时不成立。

   按类名比对会导致两个后果：
   - 卸载时一个都卸不掉（旧插件还挂在插件管理器上，ClassLoader 却被关掉）
   - 重新加载时重复加载（「这个 jar 加载过没有」的判断同样拿类名比 jar 名，永远返回"没加载过"）

   这两条合起来，就是「点开始之后脚本数量不对」的根因。

2. **停插件必须在 EDT 上做。**
   `PluginManager.stopPlugin` 里有 `assert SwingUtilities.isEventDispatchThread()`，
   所以 `stopAndRemove` 内部自己用 `invokeAndWait` 切线程。

3. **必须关掉 ClassLoader 才能释放 jar 文件句柄。**
   Windows 上 jar 被占用会导致同一个包再加载失败。

4. **`loadPlugins` 的「已加载」判断要用全限定类名，不能用简单名。**
   用简单名会误伤 `a.Foo` 和 `b.Foo` 这种同名不同包的插件。

5. **加载脚本要用运行时副本，不要直接用原 jar。**
   `URLClassLoader` 打开 jar 时会一直持有文件句柄，Windows 上原 jar 会被锁住、覆盖不了。
   改成复制一份到 `~/.runelite/microbot-plugins-runtime/` 再用副本加载，原 jar 复制完就没人占着了。

   不用「加载完就 close ClassLoader」的方案，是因为 close 之后运行时再想加载 jar 里的类
   （懒加载的依赖库）会失败，读 jar 里的资源文件也会失效。

   副本在脚本卸载时删除，客户端退出时清空整个目录。

**异常处理的差异**：上游 `remove()` 那段在非 EDT 线程上用 `invokeAndWait`，
失败时把异常包成 `RuntimeException` 往上抛，会中断后面的删 jar 文件流程；
本地改成记一条 warn 日志后继续，流程不中断。

**诊断日志**：重载路径上会打印重载前后的插件列表、每个 jar 卸了几个插件、加载结果。
排查脚本数量不对时直接看这几行。

### 7. `runelite-client/src/main/java/net/runelite/client/rs/auth/JagexSessionInjector.java`

**新增文件**，但位于上游的 `rs/auth` 包内，所以列在这里。

上游日后若也在这个包下加入功能相近的类，两者可以共存；若出现同名类冲突，保留本类，把上游实现改名或做合并。

### 8. `.run/Runelite with proxy.run.xml`

IDE 运行配置，改成用分项代理参数。冲突时直接照上游改法重写一遍即可，这个文件不影响打包。

### 9. `runelite-client/src/test/java/net/runelite/client/plugins/PluginManagerTest.java`

上游测试文件。`RuneLiteModule` 的构造参数是按字段顺序生成的（`@AllArgsConstructor`），本地给它加过字段，所以这里的构造调用必须同步补齐，否则**测试代码编译不过**。

| 位置 | 改动内容 | 冲突时如何覆盖 |
|---|---|---|
| `setUp()` 中的 `new RuneLiteModule(...)` | 补齐本地新增的构造参数：`jagexSessionId`、`jagexCharacterId`、`jagexDisplayName`、`windowTitleOverride` | 保留。改动处有中文注释写明当前的完整参数顺序 |

**以后每给 `RuneLiteModule` 加一个字段，都要回来补这里。** 漏了的话 `:client:compileTestJava` 会直接失败。

### 10. `runelite-client/src/main/java/net/runelite/client/plugins/microbot/InputSelector.java`

右上角「Enable Input / Disable Input」开关。

**改动目的**：让「禁用输入」可以被外部按脚本状态开关，而不只是用户手动点。

上游原本是个一次性对象 —— `MicrobotPlugin` 里 `new InputSelector(clientToolbar);` 创建完就把引用丢了，
外部拿不到它，没法在脚本启停这种时机去开关。

| 位置 | 改动内容 | 冲突时如何覆盖 |
|---|---|---|
| 类声明 | 加 `@Singleton`，类注释说明禁用输入时工具栏仍可点 | 保留 `@Singleton` |
| 构造函数 | 加 `@Inject`，并且**构造里不再调 `startUp()`** | 保留 `@Inject` 和「构造里不做事」这个约定。Guice 创建实例的时机不保证在 EDT 上，而往工具栏加按钮要在 EDT 上做 |
| `addAndRemoveButtons()` 开头 | 新增空值检查：两个按钮都是 null 就直接返回 | 保留这个 if 块。`startUp()` 可能还没被调用过 |
| 文件末尾 | **新增** `setInputDisabled(boolean)` | 保留整个方法。走的是和用户手动点那两个按钮完全一样的路径，行为一致 |

**冲突时如何覆盖（整体）**：保留 `@Singleton`、`@Inject` 构造、显式的 `startUp()` 调用这三处。
上游若也把本类改成可注入，保留上游的注入方式，本地只保留 `startUp()` 的拆分。

**注意**：本类被 `MicrobotPlugin` 和 `ToolbarButtonsPlugin` 同时注入，两边拿到的必须是同一个实例，
否则工具栏的「禁用输入」按钮图标会和实际状态对不上。

### 11. `runelite-client/src/main/java/net/runelite/client/plugins/microbot/MicrobotPlugin.java`

**改动目的**：注入 `InputSelector` 单例，供工具栏按钮组使用。

| 位置 | 改动内容 | 冲突时如何覆盖 |
|---|---|---|
| 字段声明区（`clientToolbar` 之后） | **新增** `@Inject private InputSelector inputSelector;` | 保留这个字段 |
| `startUp()` 中原本 `new InputSelector(clientToolbar);` 的位置 | **替换**成 `inputSelector.startUp();` | 保留这一行，删掉上游的 `new InputSelector(clientToolbar);`。按钮挂载时机和上游一致，只是换成了显式调用 |

### 12. `runelite-client/src/main/java/net/runelite/client/plugins/microbot/externalplugins/MicrobotPluginManager.java`（补充）

| 位置 | 改动内容 | 冲突时如何覆盖 |
|---|---|---|
| `findSideLoadPlugins(...)` 之后 | **新增** `findSideLoadJarName(Plugin)` —— 找出这个插件是从哪个 side-load jar 加载的 | 保留整个方法。jar 的 internalName 跨重载稳定，用它定位「重载后的那一个」；光靠类名会有两个候选 |

### 13. `--script` 命令行启动指定脚本

**改动目的**：让脚本能直接从命令行启动，不用手动点工具栏的「启动」按钮。

上游的命令行参数只能影响窗口标题、代理、Jagex 登录这些，脚本一律得手动启。

**用法**：`--script "Auto Banker"`

**规则**（跟用户确认过的需求一致）：

1. 只在 side-load 目录加载的 jar 脚本里找，客户端内置插件不算
2. 名字精确匹配、忽略大小写；干净的显示名和注解里的原始名（带 HTML 前缀的那种）都认
3. 找不到或者有多个同名，都不启动，只记一条 error 日志，客户端照常起来
4. 不写进配置 —— 这次跑完，下次开客户端不会自动跑它
5. 启动时机：side-load 插件加载完就启动，不等登录
6. 传多次时只认最后一个，并记一条 warn

#### 涉及的文件

| 文件 | 位置 | 改动内容 | 冲突时如何覆盖 |
|---|---|---|---|
| `runelite-client/src/main/java/net/runelite/client/RuneLite.java` | 参数注册区 | **新增** `--script` 参数 | 保留整段 |
| 同上 | `logger` 初始化之后 | **新增**：取值，传多次时只认最后一个并记 warn | 保留这一段 |
| 同上 | `new RuneLiteModule(...)` | **新增**一个参数 `scriptName` | 保留这一行。参数顺序跟 `RuneLiteModule` 的字段顺序一一对应，挪位置会错位 |
| `runelite-client/src/main/java/net/runelite/client/RuneLiteDebug.java` | 同上三处 | 同上，debug 入口保持一致 | 保留 |
| `runelite-client/src/main/java/net/runelite/client/RuneLiteModule.java` | 字段声明区末尾 | **新增** `private final String scriptName;` | 保留。**这个类用 `@AllArgsConstructor`，字段顺序就是构造参数顺序，新字段必须放最后** |
| 同上 | `configure()` | **新增** `bind(String.class).annotatedWith(Names.named("scriptName")).toProvider(Providers.of(scriptName));` | 保留。用 `toProvider(Providers.of(...))` 而不是 `bindConstant` —— 后者不接受 null，而没传 `--script` 时就是 null |
| `runelite-client/src/main/java/net/runelite/client/plugins/microbot/MicrobotPlugin.java` | 字段声明区 | **新增** `@Inject private CommandLineScriptLauncher commandLineScriptLauncher;` | 保留这个字段 |
| 同上 | `startUp()` 中 `Rs2Walker.setSeasonalTransportHandlers(...)` 之后 | **新增** `commandLineScriptLauncher.launch();` | 保留这一行。放在这里是因为 `MicrobotPlugin` 是 always-on 插件，一定会被启动，而且到这一步 side-load 的 jar 早就加载完了 |
| `runelite-client/src/main/java/net/runelite/client/plugins/microbot/externalplugins/MicrobotPluginManager.java` | `findSideLoadJarName(...)` 之前 | **新增** `getSideLoadPlugins()` —— 返回所有 side-load jar 加载的插件 | 保留整个方法 |
| `runelite-client/src/test/java/net/runelite/client/plugins/PluginManagerTest.java` | `new RuneLiteModule(...)` | 补上 `scriptName` 参数 | 保留。**每给 `RuneLiteModule` 加一个字段都要回来补这里**，漏了测试编译不过 |

#### 新增文件

| 文件 | 用途 |
|---|---|
| `runelite-client/src/main/java/net/runelite/client/plugins/microbot/CommandLineScriptLauncher.java` | 按名字查找并启动 side-load 脚本，负责「不持久化」的启用状态恢复 |
| `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/PluginNames.java` | 插件名字的显示名 / 匹配规则 |
| `runelite-client/src/test/java/net/runelite/client/plugins/microbot/CommandLineScriptLauncherTest.java` | 启动器的单元测试 |
| `runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/PluginNamesTest.java` | 名字处理的单元测试 |

#### 三个必须注意的实现细节

**1. `@Nullable` 一定要标在构造参数上。**
没传 `--script` 时绑定给的是 null，Guice 只在参数标了 `@Nullable` 时才允许注入 null。
不标的话它会拒绝创建实例 —— 那会让 `MicrobotPlugin` 整个加载失败，
表现为插件数量少一个（`PluginManagerTest.testLoadPlugins` 会挂）。编译期发现不了，只有运行时才炸。

**2. 「不持久化」靠临时改配置实现。**
`PluginManager.startPlugin` 要求插件处于 enabled 状态，而 `setPluginEnabled` 是会写配置的。
所以启动器先记下原状态，必要时临时打开，启完再恢复 —— 配置最终保持原样。

**3. 启动必须在 EDT 上。**
`PluginManager.startPlugin` 有 `assert SwingUtilities.isEventDispatchThread()`。
`MicrobotPlugin.startUp()` 本来就在 EDT 上，所以正常路径没问题；
启动器里额外做了一次检查兜底。

### 14. 鼠标速度插件

**改动目的**：单独控制鼠标移动的快慢，不连带改 antiban 的行为强度。

上游把「移动快慢」和「轨迹形状」绑在同一个 `ActivityIntensity` 档位上 ——
换档位会同时换掉轨迹模板和移动耗时。而那个档位还被 antiban 拿去决定行为强度
（`PASSIVE` ~ `EXTREME_AGGRESSIVE`），所以想单独调鼠标速度就只能连 antiban 一起改。

本地新增 `MouseSpeedPlugin`：只覆盖移动耗时，轨迹形状和 antiban 强度都不动。

**滑条映射**：1 → 1000ms，50 → 90ms，100 → 50ms，在毫秒的对数空间做分段插值。
锚点 90ms 正好是现有默认档（`EXTREME` → super fast gamer）的耗时，
所以滑条在默认值时行为跟插件没装一样。

**实际耗时**是基准值的 1~2 倍随机（`DefaultSpeedManager.getFlowWithTime` 里的
`time = T + random(0..T)`）。

#### 涉及的文件

| 文件 | 位置 | 改动内容 | 冲突时如何覆盖 |
|---|---|---|---|
| `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/mouse/naturalmouse/NaturalMouse.java` | 字段声明区 | **新增** `private volatile Long movementTimeOverrideMs;` | 保留这个字段 |
| 同上 | `setMovementTimeOverrideMs(...)` | **新增**：设置覆盖值，取消覆盖时清掉缓存的 factory | 保留这个方法 |
| 同上 | `getMovementTimeOverrideMs()` | **新增**：读覆盖值 | 保留这个方法 |
| 同上 | `applyMovementTimeOverride(...)` | **新增**：把覆盖值写到 factory 的 SpeedManager 上 | 保留这个方法 |
| 同上 | `getFactory()` | **新增**两处调用：缓存命中时重新应用、新建 factory 后应用 | 保留这两处调用 |

#### 新增文件

| 文件 | 用途 |
|---|---|
| `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/mouse/speed/MouseSpeed.java` | 脚本接口：读写速度、换算耗时。静态方法，脚本直接调 |
| `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/mouse/speed/MouseSpeedPlugin.java` | 插件本体。只是配置的入口，不持有状态 |
| `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/mouse/speed/MouseSpeedConfig.java` | 配置接口 |
| `runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/mouse/speed/MouseSpeedPluginTest.java` | 单元测试 |

#### 脚本接口

脚本直接调 `MouseSpeed`，不用经过插件面板：

```java
import net.runelite.client.plugins.microbot.util.mouse.speed.MouseSpeed;

MouseSpeed.setSpeed(90);                       // 设为 90，立刻生效
MouseSpeed.setFollowAntibanIntensity(false);   // 关掉跟随，交给滑条
long ms = MouseSpeed.getBaseTimeMs();          // 读当前生效的基准耗时
int slider = MouseSpeed.getSpeed();            // 读当前滑条值
MouseSpeed.reset();                            // 恢复默认（速度 50，重新跟随 antiban）
```

**语义要点：**

- `setSpeed()` 表示「我明确要这个速度」，所以会**顺带关掉跟随 antiban**。
  否则跟随模式下速度由 antiban 档位说了算，滑条设了也没用 ——
  脚本写完发现没效果是最容易踩的坑。
- 想回到跟随状态用 `setFollowAntibanIntensity(true)`。
- 设置会**同时写回配置**，所以插件面板上的滑条和脚本设的值永远一致。
  脚本改完不需要再去面板点一下。
- 插件没启用时 `MouseSpeed` 仍然可用，这时设置只在内存里生效，不会被持久化
  （因为没有 `ConfigManager` 可写）。
- `getBaseTimeMs()` 返回的是**基准**耗时，不是实际耗时。实际耗时是它的 1~2 倍随机。
  跟随 antiban 时返回的是那个档位自己的耗时。

#### 四个必须注意的实现细节

**1. 插件不能注入 `NaturalMouse`，要用 `Microbot.getNaturalMouse()`。**
`NaturalMouse` 没有 `@Singleton`，注入会拿到另一个实例 —— 而脚本移动鼠标走的是
`Microbot.naturalMouse` 这个 static 字段。改到另一个实例上完全不生效。

**2. 覆盖值在每次 `getFactory()` 时重新应用。**
换 `ActivityIntensity` 档位会重建 factory，新的 factory 自带一个默认耗时的
SpeedManager，不重新应用就会把设置丢掉。缓存命中时也要应用 ——
覆盖值可能是 factory 建好之后才改的。

**3. 取消覆盖时必须清掉缓存的 factory。**
覆盖是直接改在 factory 的 SpeedManager 上的，只把覆盖值清空，
SpeedManager 里留着上一次写的耗时就回不去了（实测取消后仍按 1000ms 走）。
清掉缓存之后，下次 `getFactory()` 会按当前档位重建一个，耗时自然是档位自己的。

**4. 状态的唯一真相在配置里，`MouseSpeed` 只是运行时视图。**
`MouseSpeed` 有一份静态状态，但它每次改动都写回配置，配置变化时又同步回来。
这样插件面板上的滑条和脚本设的值不会打架。
写配置用的是 `ConfigManager.setConfiguration(groupName, key, value)` ——
`ConfigManager` **没有**按 `Class` 重载的版本，值也只收 `String`，
所以用 group 名加 key 名来写。

**包名**：放在 `net.runelite.client.plugins.microbot.util.mouse.speed` ——
命中 `MicrobotPluginManager.isMicrobotRelatedPlugin` 的 `.util` 白名单，
所以 Debug 模式（`RuneLiteDebug`）也能加载到。

### 15. `--index` 自定义窗口标题的已知问题

Microbot 的休息处理器（`BreakHandlerScript` / `BreakHandlerV2Script`）在插件启动时会抓 `ClientUI.getFrame().getTitle()` 当基准标题，而 `--index` 的标题是在引擎启动之后才应用的，两者存在竞态：

- 抓到默认标题时，休息结束后会把 `--index` 标题覆盖掉；
- 抓到 `--index` 标题时，最终会变成 `自定义标题 - Break: 00:30`。

当前**没有修**，这是已知的取舍。想修的话有两个方向：

1. 在 `ClientUI` 暴露一个"当前生效标题"的 getter，让两个 BreakHandler 改用它取基准和恢复标题；
2. 在 BreakHandler 恢复标题时判断当前是否处于 override 生效状态。

修改 `BreakHandlerScript` / `BreakHandlerV2Script` 时请在改动处加中文注释，并同步更新本节。

### 16. `runelite-client/src/main/java/net/runelite/client/plugins/devtools/BotMousePlugin.java` 和 `BotMouseOverlay.java`

**新增文件**，但位于上游的 `plugins/devtools` 包内，所以列在这里。

放在这个包里的原因是：需要调用 `DevToolsButton.setActive(boolean)` 把 DevTools 面板的 "Bot Mouse" 按钮关掉，而那个方法是**包私有**的。同包的 `MicrobotMouseOverlay`、`MicrobotClickOverlay` 也是这个情况。

上游日后若也在这个包下加入同名类，保留本地类即可；若上游把 `DevToolsButton.setActive` 改成 public，可以考虑把这两个类挪到 microbot 自己的包下。

**这两个文件没有修改任何现有文件**，上游更新时不会产生冲突。

### 17. 日志框（console）的显示与高度

上游的 `ClientUI` 工具栏上有个 "Show console" 按钮，点开会在游戏画布下面多出一块日志面板（`LogConsolePanel`，固定 160px 高）。这里改了两处行为。

#### 17.1 隐藏 console 之后窗口高度收不回去

**现象**：点 Show console，窗口变高；点 Hide console，日志框没了，**窗口还是那么高**，而且游戏画布被拉高了 160px，画面比例变形。

**根因**：`ClientUI.Layout.layout()` 里算高度用的是

```java
int innerHeight = Math.max(content.getHeight() - insetHeight,
    Math.max(client.getMinimumSize().height, sidebar.getMinimumSize().height));
```

`content` 的高度只有变大过、从来没变小过。显示 console 时 `ClientPanel.getMinimumSize()` 涨了 160px 把窗口撑高；隐藏时这个最小值掉回 503，但 `Math.max` 那一项 `content.getHeight()` 还是撑高后的值，于是高度原地不动。上游没有任何保存原始尺寸的字段，也没有恢复逻辑。

**本地改法**：隐藏时先把 `content` 的高度压回最小，再把窗口恢复回去。

```java
content.setSize(content.getWidth(), clientMin.height + insets.top + insets.bottom);
content.setPreferredSize(content.getSize());
frame.revalidateMinimumSize();
frame.containedSetSize(new Dimension(oldBounds.width, targetHeight), oldBounds);
```

**必须先把 content 的高度压下去**，否则下一次布局的 `Math.max` 会把它又撑回去。

**目标高度是「显示 console 之前的高度」**（`consolePreShowHeight` 字段在显示时记录）。没有记录时退回「刚好装下游戏画布」的最小高度。宽度不动。

**布局是异步的**：`content.revalidate()` 触发的布局可能发生在缩窗口之后，那次布局会用撑高后的 content 高度把窗口又顶回去。所以隐藏时除了同步缩一次，还要用 `SwingUtilities.invokeLater` 在布局跑完之后再收一次（带 `consoleVisible` 守卫，避免用户快速切换时误伤）。

窗口最大化的时候不去改尺寸（内容高度照样压回去，画布不会变形）。

**冲突时如何覆盖**：保留 `shrinkToClientHeight()` 整个方法、`consolePreShowHeight` 字段，以及 `setConsoleVisible` 里的两次调用。

#### 17.2 日志内容太吵

上游的 `ConsoleLogAppender` 把格式写死成：

```
%d{HH:mm:ss} %-5level %logger{36} - %msg%n
```

每行都是 `14:18:37 INFO  n.r.c.p.m.t.ToolbarButtonsPlugin - ToolbarButtons: 已恢复用户输入`。

**本地改法**：默认只显示「时间 + 消息」，日志等级和类名做成可勾选的开关。

- 开关状态在 `ConsoleLogAppender` 里（两个 static boolean），pattern 按开关动态拼：
  - 两个都不开：`%d{HH:mm:ss} %msg%n`
  - 只开等级：`%d{HH:mm:ss} %-5level - %msg%n`
  - 只开类名：`%d{HH:mm:ss} %logger{36} - %msg%n`
  - 都开：跟上游一样
- 勾选框在日志框的**右键菜单**里（`Show log level` / `Show logger name`）。
- 状态存在配置里（`runelite` 组的 `clientConsoleShowLevel` / `clientConsoleShowLogger`），重开客户端还在。
- 改开关只影响之后的行，已经写进去的不回溯。

**只影响日志框**，文件日志（`~/.runelite/logs/client.log`）和控制台输出不受影响 —— 它们的格式由 `logback.xml` 决定，没有动。

**冲突时如何覆盖**：保留 `ConsoleLogAppender` 里的 `showLevel` / `showLogger` 两个字段、`buildPattern()`、`rebuildLayout()` 和 `LogConsolePanel` 里的两个勾选框。

#### 17.3 日志按等级着色

**现象**：日志框里所有文字都是同一个终端绿，看不出哪条是 ERROR、哪条是 WARN，出错的时候得靠找关键字。

**本地改法**：每行按自己的日志等级上色。

| 等级 | 颜色 | 取值 |
|---|---|---|
| ERROR | 红 | `(255, 85, 85)` |
| WARN | 黄 | `(240, 200, 60)` |
| INFO | 绿 | `(0, 255, 70)`（沿用原来的终端绿） |
| DEBUG / TRACE | 暗灰 | `(128, 128, 128)` |
| 没有等级 | 绿 | 脚本里直接 `System.out.println` 打出来的内容没有等级，按 INFO 的绿色处理 |

背景色是 `ColorScheme.DARK_GRAY_COLOR`（`(40, 40, 40)`），所以 DEBUG 的灰不能更暗，否则看不清。

**实现上动了三处**：

1. **`LogConsolePanel` 的组件从 `JTextArea` 换成 `JTextPane`。**
   `JTextArea` 只有一个前景色，没法一行一个颜色。`JTextPane` 走 `StyledDocument`，
   样式是文档的一部分，插入和裁剪都不会错位。
   `JTextPane` 没有 `JTextArea` 的 `setLineWrap`，但它默认就不换行（实测长行的
   preferred 宽度是内容宽度），排版跟之前一致。

2. **`ConsoleLogAppender` 的回调从 `Consumer<String>` 改成 `BiConsumer<Level, String>`。**
   等级得跟着行一起送到日志框，否则那边不知道该上什么色。
   `append()` 里改成 `logConsumer.accept(eventObject.getLevel(), formatted)`。

3. **`LogConsolePanel` 的待写缓冲从 `StringBuilder` 改成按行存的 `PendingBuffer`。**
   原来所有来源的文本混在一个 `StringBuilder` 里，现在每行记住自己的等级。
   顺手记着总字符数，这样判断要不要裁剪不用每次都遍历一遍 ——
   高频日志下每来一行都遍历整个缓冲会变成平方级的开销。
   写进文档时，连续同等级的行合并成一次插入，减少文档改动次数。

`LogConsolePanel.append(String)` 保留着（等级为 null），给没有等级的内容用。

**冲突时如何覆盖**：
- 保留 `LogConsolePanel` 里的 `colorFor(Level)`、`attributesFor(Level)` 和四个颜色常量。
- 保留 `ConsoleLogAppender` 的 `BiConsumer<Level, String>` 回调签名。
- 保留 `PendingBuffer` 内部类。
- 组件换成 `JTextPane` 这一步别改回 `JTextArea`，否则着色直接失效。

---

## 二、新增文件（不会被上游覆盖，不存在覆盖问题）

| 文件 | 用途 |
|---|---|
| `runelite-client/src/main/java/net/runelite/client/plugins/microbot/performance/PerformancePlugin.java` | 自动启用 FPS Control 与 Low Detail，并通过客户端接口与声音事件静音 |
| `runelite-client/src/main/java/net/runelite/client/plugins/microbot/toolbar/ToolbarAccess.java` | 反射访问包私有的 `ClientToolbarPanel`，Minimal Toolbar 和 Toolbar Buttons 共用 |
| `runelite-client/src/main/java/net/runelite/client/plugins/microbot/toolbar/ToolbarButtonsPlugin.java` | 右上角工具栏的「启动 / 暂停 / 停止」三个按钮，装在同一个容器里 |
| `runelite-client/src/main/java/net/runelite/client/plugins/microbot/toolbar/ScriptLauncherDialog.java` | 脚本启动弹窗：列出 jar 包加载的脚本，选中后启动 |
| `runelite-client/src/test/java/net/runelite/client/plugins/microbot/toolbar/` | Toolbar Buttons 和脚本弹窗的测试 |
| `runelite-client/src/test/java/net/runelite/client/plugins/microbot/externalplugins/MicrobotPluginManagerReloadTest.java` | side-load 卸载判定（按 ClassLoader）的测试 |
| `runelite-client/src/test/java/net/runelite/client/plugins/microbot/externalplugins/MicrobotPluginManagerRuntimeCopyTest.java` | 运行时副本的测试：复制后原 jar 可以随意覆盖 |
| `runelite-client/src/test/java/net/runelite/client/plugins/microbot/externalplugins/ReloadFixturePlugin.java` | 上面那个测试用的 fixture，必须是独立顶层类 |
| `docs/bot-mouse.md` | Bot Mouse 插件的行为说明 |
| `docs/fps-control.md` | FPS Control 的启用步骤与注意事项 |
| `docs/performance-plugin.md` | Performance 插件的行为说明 |
| `docs/minimal-toolbar.md` | Minimal Toolbar 插件的行为说明 |
| `docs/toolbar-buttons.md` | Toolbar Buttons 插件与脚本启动弹窗的行为说明 |
| `docs/LOCAL_PATCHES.md` | 本地改动清单：Microbot 相对上游 RuneLite 的所有改动点和冲突覆盖方式 |
| `docs/changelog.md` | 改动日志，新条目在顶部 |
| `runelite-client/src/main/java/net/runelite/client/plugins/microbot/CommandLineScriptLauncher.java` | `--script` 命令行启动指定 side-load 脚本 |
| `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/PluginNames.java` | 插件名字的显示名与匹配规则 |
| `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/mouse/speed/MouseSpeed.java` | 鼠标速度的脚本接口：读写速度、换算耗时 |
| `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/mouse/speed/MouseSpeedPlugin.java` | 鼠标移动速度插件（滑条调快慢，不动 antiban 强度） |
| `runelite-client/src/main/java/net/runelite/client/plugins/microbot/util/mouse/speed/MouseSpeedConfig.java` | 鼠标速度插件的配置 |
| `runelite-client/src/test/java/net/runelite/client/plugins/microbot/CommandLineScriptLauncherTest.java` | `--script` 启动器的单元测试 |
| `runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/PluginNamesTest.java` | 名字处理的单元测试 |
| `runelite-client/src/test/java/net/runelite/client/plugins/microbot/util/mouse/speed/MouseSpeedPluginTest.java` | 鼠标速度映射与覆盖生效的单元测试 |
| `runelite-client/src/test/java/net/runelite/client/ui/ConsoleLogAppenderDisplayTest.java` | 日志框显示等级/类名开关的单元测试 |

---

## 三、拉取上游更新后的处理流程

1. 先编译，看哪些文件冲突：

   ```powershell
   .\gradlew.bat :client:compileJava
   ```

2. 对每个冲突文件，搜索本地标记，逐段确认：

   ```powershell
   rg -n "MICROBOT 本地改动" <冲突文件>
   ```

3. 按上面表格里的"冲突时如何覆盖"逐条处理。原则是：**保留本地语义，上游其余逻辑不动**。

4. 重新编译。

5. 检查代理与 Jagex 登录这两个功能是否仍然正常。它们依赖命令行参数，最容易在重构中悄悄失效。
