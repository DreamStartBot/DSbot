package net.runelite.client.plugins.microbot.toolbar;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.microbot.InputSelector;
import net.runelite.client.plugins.microbot.externalplugins.MicrobotPluginManager;
import net.runelite.client.plugins.microbot.util.events.PluginPauseEvent;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.SwingUtil;

import javax.inject.Inject;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 在右上角工具栏加一组控制按钮。
 *
 * <p>四个按钮放在同一个容器里：启动、暂停、停止、重启。
 *
 * <p>目前只有「启动」接了功能，其它按钮点了没反应。
 * 「重启」是占位按钮，功能后面再补。
 *
 * <h2>为什么用自定义容器</h2>
 *
 * <p>如果这几个按钮走 {@code ClientToolbar.addNavigation()}，它们会进
 * {@code ClientToolbarPanel} 内部的按钮表，而
 * {@link MinimalToolbarPlugin} 正是遍历那张表来隐藏多余按钮的，
 * 会把这几个一起隐藏掉。
 *
 * <p>所以这里自己建一个容器，直接加进工具栏面板。容器不在那张表里，
 * 两个插件互不影响。
 *
 * <p>另外，工具栏用的 {@code DynamicGridLayout} 计算尺寸时不区分组件是否可见，
 * 隐藏的按钮照样占位置。加自定义容器时不用考虑这个 ——
 * 容器里的按钮一直是要显示的。
 */
@Slf4j
@PluginDescriptor(
		name = PluginDescriptor.Mocrosoft + "Toolbar Buttons",
		description = "Adds Start, Pause, Stop and Restart buttons to the toolbar.",
		tags = {"microbot", "toolbar"},
		enabledByDefault = true
)
public class ToolbarButtonsPlugin extends Plugin
{
	/**
	 * 图标的边长，和工具栏里其它图标一致。
	 */
	private static final int ICON_SIZE = 16;

	/**
	 * 按钮的边长，和工具栏里其它按钮一致。
	 */
	private static final int BUTTON_SIZE = 23;

	/**
	 * 安装尝试的间隔（毫秒）。
	 *
	 * <p>插件启动时客户端窗口可能还没建好，工具栏面板还拿不到，
	 * 所以要隔一会儿重试。
	 */
	private static final long INSTALL_RETRY_MS = 500;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private MicrobotPluginManager microbotPluginManager;

	@Inject
	private InputSelector inputSelector;

	/**
	 * 装了按钮的容器。禁用插件时把它从工具栏里摘掉。
	 */
	private JPanel container;

	/**
	 * 启动按钮。重载脚本期间把它变灰，防止重复点击。
	 */
	private JButton startButton;

	/**
	 * 暂停按钮。
	 */
	private JButton pauseButton;

	/**
	 * 停止按钮。
	 */
	private JButton stopButton;

	/**
	 * 重启按钮。
	 */
	private JButton restartButton;

	/**
	 * 暂停按钮在「暂停」状态下的图标。
	 */
	private ImageIcon pauseIcon;

	/**
	 * 暂停按钮在「继续」状态下的图标。
	 */
	private ImageIcon resumeIcon;

	/**
	 * 脚本是不是被用户按了暂停。
	 *
	 * <p>刻意不直接读 {@code PluginPauseEvent.isPaused()}。
	 * 那个全局标志有很多写入者 —— antiban、breakhandler、cannon 这些都会
	 * 临时把它设成 true 再恢复。要是直接跟随，工具栏按钮会跟着那些
	 * 临时暂停闪来闪去。
	 *
	 * <p>这里只记用户自己的意图，全局标志由 {@link #applyPause} 去设。
	 */
	private volatile boolean userPaused;

	/**
	 * 最后一次启动的脚本的全限定类名。
	 *
	 * <p>重启时靠它找回同一个脚本 —— 重载会换掉所有实例，
	 * 只能按名字重新找。
	 */
	private volatile String lastScriptClassName;

	/**
	 * 最后一次启动的脚本来自哪个 jar（jar 文件名去掉 .jar）。
	 *
	 * <p>类名一样但装在不同 jar 里的脚本要靠这个区分，
	 * 不然会启动错那一个。
	 */
	private volatile String lastScriptJarName;

	/**
	 * 是不是正在重启。
	 *
	 * <p>防止连点重启按钮启动好几个线程。
	 */
	private volatile boolean restarting;

	/**
	 * 我们是不是要求禁用用户输入。
	 *
	 * <p>刻意和实际状态分开。用户手动点右上角的 Enable Input 解锁，
	 * 不会改这个标志 —— 所以脚本还在跑的时候，那个定时刷新不会把
	 * 用户手动解锁的结果又盖回去。
	 */
	private volatile boolean inputLockRequested;

	/**
	 * 本次客户端运行期间有没有启动过脚本。
	 *
	 * <p>只记在内存里，不写配置 —— 重启客户端就重新算"没启动过"。
	 *
	 * <p>用来区分两个都显示「开始」按钮的状态：
	 * 从来没跑过（只有开始）vs 跑过但现在停了（开始 + 重启）。
	 */
	private volatile boolean scriptEverStarted;

	/**
	 * 上一次刷新时有没有脚本在跑。
	 *
	 * <p>用来判断状态有没有变，变了才打日志。
	 */
	private volatile boolean lastKnownRunning;

	/**
	 * 尝试安装的定时器。
	 *
	 * <p>刻意不写成 final 字段：插件实例在禁用之后会被复用，
	 * 而 shutDown 会终止这个执行器。复用同一个实例的话，
	 * 再次启动时往一个已终止的执行器提交任务会被拒绝。
	 */
	private ScheduledExecutorService executor;

	@Override
	protected void startUp()
	{
		executor = Executors.newSingleThreadScheduledExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "ToolbarButtonsPlugin");
			thread.setDaemon(true);
			return thread;
		});
		executor.scheduleWithFixedDelay(
				this::scheduleInstall, 0, INSTALL_RETRY_MS, TimeUnit.MILLISECONDS);
	}

	@Override
	protected void shutDown()
	{
		if (executor != null)
		{
			executor.shutdownNow();
			executor = null;
		}

		SwingUtilities.invokeLater(this::uninstall);
	}

	/**
	 * 把安装安排到 EDT 上执行。
	 */
	private void scheduleInstall()
	{
		try
		{
			SwingUtilities.invokeLater(() ->
			{
				install();
				// 兜底刷新按钮状态。
				// 事件驱动是主路径，但万一事件没送到，按钮就永远停在旧状态了 ——
				// 每 500ms 检查一次的成本可以忽略（activePlugins 就那么几个元素）。
				refreshButtonsFromState();
				// 把被别的组件吃掉的暂停补回来。
				// 用户的暂停标志是全局的，antiban、breakhandler 这些结束时
				// 会把 pauseAllScripts 恢复掉，顺带把用户的暂停也清了。
				restorePauseIfLost();
			});
		}
		catch (Exception e)
		{
			log.debug("ToolbarButtons: 安排安装失败", e);
		}
	}

	/**
	 * 用户的暂停被别的组件清掉了就补回来。
	 *
	 * <p>{@code Microbot.pauseAllScripts} 是全局的，antiban、breakhandler、
	 * cannon 这些组件都会临时把它设成 true 再恢复，恢复的时候会把用户的
	 * 暂停一起清了。不补回来的话，脚本会莫名其妙继续跑。
	 */
	void restorePauseIfLost()
	{
		if (!userPaused)
		{
			return;
		}

		if (!PluginPauseEvent.isPaused())
		{
			PluginPauseEvent.setPausedAll(true);
			log.debug("ToolbarButtons: 暂停被其它组件清掉了，补回来");
		}
	}

	/**
	 * 装上按钮容器。
	 */
	private void install()
	{
		if (container != null)
		{
			// 已经装过了
			return;
		}

		Container panel = ToolbarAccess.findPanel();
		if (panel == null)
		{
			// 客户端窗口还没建好，下个周期再试
			return;
		}

		try
		{
			installInto(panel);
			log.info("ToolbarButtons: 已添加启动/暂停/停止/重启按钮");
		}
		catch (Exception e)
		{
			log.warn("ToolbarButtons: 安装按钮失败", e);
		}
	}

	/**
	 * 把按钮容器加进工具栏面板。
	 *
	 * <p>拆出来是为了能单独测，不用真的建一个客户端窗口。
	 *
	 * @param panel 工具栏面板
	 */
	void installInto(Container panel)
	{
		if (container == null)
		{
			container = buildContainer();
			// 放最前面：这几个是主要操作，摆在工具栏左边更好找
			panel.add(container, 0);
		}

		// 按当前状态收一下按钮：脚本可能已经在跑了
		refreshButtonsFromState();

		panel.setMaximumSize(panel.getPreferredSize());
		panel.revalidate();
		panel.repaint();
	}

	/**
	 * 把按钮容器从工具栏面板里摘掉。
	 */
	void uninstall()
	{
		if (container == null)
		{
			return;
		}

		Container parent = container.getParent();
		if (parent != null)
		{
			parent.remove(container);
			parent.setMaximumSize(parent.getPreferredSize());
			parent.revalidate();
			parent.repaint();
		}
		container = null;
	}

	/**
	 * 造一个装着四个按钮的容器。
	 */
	private JPanel buildContainer()
	{
		JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setOpaque(true);
		// 容器本身不参与布局计算，交给里面的按钮
		panel.setPreferredSize(null);

		startButton = createButton("Start", Glyph.TRIANGLE, this::openScriptLauncher);
		panel.add(startButton);
		pauseButton = createButton("Pause", Glyph.PAUSE, this::togglePause);
		// 暂停时要换成「继续」的图标，先把两张图都备好
		pauseIcon = (ImageIcon) pauseButton.getIcon();
		resumeIcon = new ImageIcon(createIcon(Glyph.RESUME));
		panel.add(pauseButton);
		stopButton = createButton("Stop", Glyph.SQUARE, this::stopRunningScripts);
		panel.add(stopButton);
		restartButton = createButton("Restart", Glyph.RESTART, this::restartLastScript);
		panel.add(restartButton);

		return panel;
	}

	/**
	 * 停止当前在跑的脚本。
	 *
	 * <p>停掉所有正在运行的外部脚本。内置插件不动 ——
	 * 那些是客户端自己的功能，停掉会影响别的插件。
	 *
	 * <p>顺便把配置里的启用开关关掉，不然重启客户端它又被自动拉起来。
	 */
	private void stopRunningScripts()
	{
		if (pluginManager == null)
		{
			return;
		}

		List<Plugin> running = pluginManager.getActivePlugins().stream()
			.filter(ToolbarButtonsPlugin::isExternalScript)
			.collect(Collectors.toList());

		for (Plugin script : running)
		{
			try
			{
				pluginManager.setPluginEnabled(script, false);
				// PluginManager.stopPlugin 断言必须在 EDT 上调用，
				// 按钮点击本来就在 EDT 上，这里直接调就行。
				pluginManager.stopPlugin(script);
			}
			catch (PluginInstantiationException e)
			{
				log.warn("ToolbarButtons: 停止脚本失败 " + script.getClass().getSimpleName(), e);
			}
		}

		if (!running.isEmpty())
		{
			log.info("ToolbarButtons: 已停止 {} 个脚本", running.size());
		}

		// 按钮显示跟着实际状态走。
		// 即使没有脚本可停也刷一次 —— 脚本可能是刚通过别的方式停掉的，
		// 按钮状态还没跟上，点一下停止正好把它刷对。
		refreshButtonsFromState();
	}

	/**
	 * 暂停或继续脚本。
	 *
	 * <p>暂停是全局的 —— microbot 的暂停标志本来就是全局静态变量，
	 * 做不到只暂停一个脚本。
	 */
	private void togglePause()
	{
		applyPause(!userPaused);
		refreshButtonsFromState();
	}

	/**
	 * 设置全局暂停标志。
	 *
	 * <p>两个标志都要设，它们管的是不同的东西：
	 * <ul>
	 *   <li>{@code Microbot.pauseAllScripts} —— {@code Script.run()} 的守卫，
	 *       所有继承 {@code Script} 的脚本都会在这里停下</li>
	 *   <li>{@code PluginPauseEvent.isPaused} —— {@code BlockingEventManager}
	 *       里全局注册了它，脚本会被阻塞事件挡住</li>
	 * </ul>
	 */
	private void applyPause(boolean paused)
	{
		userPaused = paused;
		PluginPauseEvent.setPausedAll(paused);
	}

	/**
	 * 把暂停按钮的图标和提示切成「暂停」或「继续」。
	 */
	private void updatePauseButton()
	{
		if (pauseButton == null)
		{
			return;
		}

		if (userPaused)
		{
			pauseButton.setIcon(resumeIcon);
			pauseButton.setToolTipText("Resume");
		}
		else
		{
			pauseButton.setIcon(pauseIcon);
			pauseButton.setToolTipText("Pause");
		}
	}

	/**
	 * 脚本跑着就锁住用户输入，脚本停了或者暂停了就放开。
	 *
	 * <p>只有「意图变了」才动 {@link InputSelector}。
	 * 用户手动点过 Enable Input 解锁的时候，意图没变，
	 * 那个 500ms 的状态刷新就不会把他的操作盖掉。
	 *
	 * @param running 现在有没有外部脚本在跑
	 */
	private void applyInputLock(boolean running)
	{
		boolean wantLock = running && !userPaused;
		if (wantLock == inputLockRequested)
		{
			return;
		}

		inputLockRequested = wantLock;

		if (inputSelector == null)
		{
			return;
		}

		try
		{
			inputSelector.setInputDisabled(wantLock);
			log.info("ToolbarButtons: {}", wantLock ? "已禁用用户输入" : "已恢复用户输入");
		}
		catch (Exception e)
		{
			// 画布可能还没准备好，失败就下次再试
			log.debug("ToolbarButtons: 设置输入禁用状态失败", e);
			// 撤回意图，下次刷新会重来一遍
			inputLockRequested = !wantLock;
		}
	}

	/**
	 * 按当前状态更新四个按钮的显示。
	 *
	 * <p>三种状态：
	 * <ul>
	 *   <li>有脚本在跑 —— 显示暂停、停止、重启</li>
	 *   <li>跑过但现在停了 —— 显示开始、重启</li>
	 *   <li>从来没跑过 —— 只显示开始</li>
	 * </ul>
	 *
	 * <p>「开始」在跑的时候收起来，因为此时脚本已经起来了，
	 * 用户要的是暂停和停止。
	 *
	 * <p>脚本被暂停时按钮组不变，只是暂停按钮的图标换成「继续」。
	 *
	 * <p>这个方法有三个调用方：{@code PluginChanged} 事件（实时）、
	 * 启动弹窗的回调、和安装按钮的定时器（兜底）。
	 * 事件万一没送到，兜底能保证状态跟上。
	 */
	void refreshButtonsFromState()
	{
		boolean running = isExternalScriptRunning();
		if (running)
		{
			scriptEverStarted = true;
		}
		else if (userPaused)
		{
			// 脚本都没了，暂停就没意义了，把标志清掉，
			// 不然下次启动脚本会立刻被暂停住。
			applyPause(false);
		}

		// 输入锁定放在按钮检查前面 —— 按钮还没装上的时候也得能锁。
		applyInputLock(running);

		if (container == null || startButton == null)
		{
			return;
		}

		if (running != lastKnownRunning)
		{
			lastKnownRunning = running;
			log.info("ToolbarButtons: 脚本运行状态变成 {}，按钮刷新", running);
		}

		startButton.setVisible(!running);
		pauseButton.setVisible(running);
		stopButton.setVisible(running);
		restartButton.setVisible(running || scriptEverStarted);

		updatePauseButton();

		// 按钮显隐会改变容器大小，重新算一次，不然工具栏会留一条空白
		container.setPreferredSize(null);
		Container parent = container.getParent();
		if (parent != null)
		{
			parent.setMaximumSize(parent.getPreferredSize());
			// revalidate 只是"请求"重排，真正生效要等下一次绘制。
			// 这里直接强制排一次，免得按钮明明可见却没画出来。
			parent.doLayout();
			parent.revalidate();
			parent.repaint();
		}
		container.doLayout();
		container.revalidate();
		container.repaint();
	}

	/**
	 * 当前有没有外部脚本在跑。
	 */
	private boolean isExternalScriptRunning()
	{
		if (pluginManager == null)
		{
			return false;
		}

		return pluginManager.getActivePlugins().stream()
			.anyMatch(ToolbarButtonsPlugin::isExternalScript);
	}

	/**
	 * 判断一个插件是不是从 jar 加载的脚本。
	 *
	 * <p>客户端内置的插件不算，它们跟脚本的启停没关系。
	 */
	private static boolean isExternalScript(Plugin plugin)
	{
		PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
		return descriptor != null && descriptor.isExternal();
	}

	/**
	 * 脚本启动或停止时更新按钮显示。
	 *
	 * <p>{@code PluginManager} 在启停插件时会发这个事件，
	 * 所以状态是跟着实际运行情况走的，不用轮询。
	 */
	@Subscribe
	public void onPluginChanged(PluginChanged event)
	{
		if (!isExternalScript(event.getPlugin()))
		{
			return;
		}
		refreshButtonsFromState();
	}

	/**
	 * 打开脚本启动弹窗。
	 *
	 * <p>打开之前先全量重新加载一遍脚本 jar 包，这样新装上的脚本能出现在列表里。
	 * 重载会打断正在运行的脚本。
	 */
	private void openScriptLauncher()
	{
		if (startButton != null)
		{
			startButton.setEnabled(false);
		}

		// 重新加载要读 jar、加载类，比较慢，放到单独的线程里做，别卡住界面
		Thread thread = new Thread(this::reloadScriptsAndShowDialog, "ToolbarButtons-reload");
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * 后台重载脚本 jar 包，重载完再弹窗。
	 */
	private void reloadScriptsAndShowDialog()
	{
		try
		{
			// 停插件必须在 EDT 上做
			SwingUtilities.invokeAndWait(() -> microbotPluginManager.unloadSideLoadPlugins());
			// 加载 jar 本身不碰 Swing，可以留在后台线程
			microbotPluginManager.loadSideLoadPlugins();
		}
		catch (Exception e)
		{
			log.warn("ToolbarButtons: 重新加载脚本失败", e);
		}
		finally
		{
			SwingUtilities.invokeLater(() ->
			{
				if (startButton != null)
				{
					startButton.setEnabled(true);
				}
				showScriptLauncher();
			});
		}
	}

	/**
	 * 只弹窗，不重载。
	 */
	private void showScriptLauncher()
	{
		ScriptLauncherDialog.show(pluginManager, this::onScriptStarted);
	}

	/**
	 * 脚本启动成功了，记下启动的是哪一个。
	 *
	 * <p>重启的时候要靠这个找回同一个脚本。
	 */
	void onScriptStarted(Plugin script)
	{
		if (script == null)
		{
			return;
		}

		lastScriptClassName = script.getClass().getName();
		lastScriptJarName = microbotPluginManager.findSideLoadJarName(script);
		log.info("ToolbarButtons: 记住最后启动的脚本 {}", lastScriptClassName);

		refreshButtonsFromState();
	}

	/**
	 * 重启最后启动的那个脚本。
	 *
	 * <p>流程：重载目录里的 jar 包（这一步会把正在跑的脚本停掉），
	 * 再找到重载后的那一个脚本启动。找不到就打开弹窗让用户自己选。
	 */
	private void restartLastScript()
	{
		if (restarting)
		{
			return;
		}

		restarting = true;
		// 重载期间按钮全禁掉，免得用户中途点别的把流程搅乱
		setButtonsEnabled(false);

		// 重载要读 jar、加载类，比较慢，放到单独的线程里做，别卡住界面
		Thread thread = new Thread(this::doRestart, "ToolbarButtons-restart");
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * 后台重载并重启。
	 */
	void doRestart()
	{
		Plugin target = null;

		try
		{
			rememberScriptToRestart();

			// 重载会停掉所有 side-load 脚本，再重新扫描目录加载一遍
			microbotPluginManager.reloadSideLoadPlugins();

			// 重载后拿到的是全新的实例，得重新找一遍
			target = findRestartTarget();
		}
		catch (Exception e)
		{
			log.warn("ToolbarButtons: 重启脚本失败", e);
		}
		finally
		{
			final Plugin restartTarget = target;
			SwingUtilities.invokeLater(() ->
			{
				setButtonsEnabled(true);
				restarting = false;

				if (restartTarget == null)
				{
					// 找不到同名脚本了 —— 可能是改了类名，或者 jar 被换掉了。
					// 让用户自己挑一个。
					log.info("ToolbarButtons: 找不到要重启的脚本，打开弹窗");
					showScriptLauncher();
				}
				else
				{
					startScript(restartTarget);
				}

				refreshButtonsFromState();
			});
		}
	}

	/**
	 * 记下要重启的是哪个脚本。
	 *
	 * <p>已经记过就不覆盖 —— 用户可能启动了 A 又启动了 B，
	 * 这时要重启的是 B。
	 *
	 * <p>没记过但有脚本在跑的话，就把当前跑的那个记下来。
	 * （客户端启动时自动拉起的脚本就属于这种。）
	 */
	void rememberScriptToRestart()
	{
		if (lastScriptClassName != null)
		{
			return;
		}

		for (Plugin script : pluginManager.getActivePlugins())
		{
			if (isExternalScript(script))
			{
				lastScriptClassName = script.getClass().getName();
				lastScriptJarName = microbotPluginManager.findSideLoadJarName(script);
				log.info("ToolbarButtons: 用当前跑着的脚本作为重启目标 {}", lastScriptClassName);
				return;
			}
		}
	}

	/**
	 * 在重载后的插件里找到要重启的那一个。
	 *
	 * <p>类名要一样，jar 也要一样 —— 光看类名的话，同名同包的类装在
	 * 不同 jar 里会有两个候选，选错了就启动了另一个。
	 *
	 * @return 找到的插件，没找到返回 null
	 */
	Plugin findRestartTarget()
	{
		if (lastScriptClassName == null)
		{
			return null;
		}

		for (Plugin plugin : pluginManager.getPlugins())
		{
			if (!plugin.getClass().getName().equals(lastScriptClassName))
			{
				continue;
			}

			String jarName = microbotPluginManager.findSideLoadJarName(plugin);
			if (lastScriptJarName == null || lastScriptJarName.equals(jarName))
			{
				return plugin;
			}
		}

		return null;
	}

	/**
	 * 启动一个脚本。
	 *
	 * <p>走的是和 {@code Microbot.startPlugin} 一样的路子：
	 * 先打开配置开关，再启动插件，最后拉起其它已启用的插件
	 * （脚本常常依赖内置插件）。
	 *
	 * <p>必须在 EDT 上调用 —— {@code PluginManager.startPlugin} 断言这一点。
	 *
	 * @return 启动成功返回 true
	 */
	private boolean startScript(Plugin script)
	{
		try
		{
			pluginManager.setPluginEnabled(script, true);
			boolean started = pluginManager.startPlugin(script);
			// 拉起其它已启用的插件，脚本的依赖要跟着起来
			pluginManager.startPlugins();

			if (started)
			{
				log.info("ToolbarButtons: 已启动 {}", script.getClass().getName());
			}
			else
			{
				log.warn("ToolbarButtons: 启动失败 {}（可能已经在运行）", script.getClass().getName());
			}

			return started;
		}
		catch (PluginInstantiationException e)
		{
			log.warn("ToolbarButtons: 启动脚本失败 " + script.getClass().getName(), e);
			return false;
		}
	}

	/**
	 * 把四个按钮一起启用或禁用。
	 */
	private void setButtonsEnabled(boolean enabled)
	{
		if (startButton != null)
		{
			startButton.setEnabled(enabled);
		}
		if (pauseButton != null)
		{
			pauseButton.setEnabled(enabled);
		}
		if (stopButton != null)
		{
			stopButton.setEnabled(enabled);
		}
		if (restartButton != null)
		{
			restartButton.setEnabled(enabled);
		}
	}

	/**
	 * 造一个图标按钮。
	 *
	 * @param tooltip 提示文字
	 * @param glyph   图标形状
	 * @param action  点击时要做的事，为 null 表示点了没反应
	 */
	private JButton createButton(String tooltip, Glyph glyph, Runnable action)
	{
		JButton button = new JButton(new ImageIcon(createIcon(glyph)));
		// 和工具栏里其它按钮一样，去掉边框只留图标
		SwingUtil.removeButtonDecorations(button);
		button.setToolTipText(tooltip);
		button.setFocusable(false);
		button.setPreferredSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
		button.setAlignmentX(.5f);
		button.setAlignmentY(.5f);
		if (action != null)
		{
			button.addActionListener(e -> action.run());
		}
		return button;
	}

	/**
	 * 画图标。
	 *
	 * <p>用代码画而不是找图片资源，三个图标的风格才能一致，
	 * 也不用往仓库里加二进制文件。
	 */
	static BufferedImage createIcon(Glyph glyph)
	{
		BufferedImage image = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(Color.WHITE);

			switch (glyph)
			{
				case TRIANGLE:
					// 播放：三角形
					g.fillPolygon(new int[]{5, 5, 12}, new int[]{3, 13, 8}, 3);
					break;
				case PAUSE:
					// 暂停：两条竖线
					g.fillRect(4, 3, 3, 10);
					g.fillRect(9, 3, 3, 10);
					break;
				case SQUARE:
					// 停止：方块
					g.fillRect(4, 4, 8, 8);
					break;
				case RESTART:
					// 重启：断开的圆环，缺口在右上方
					g.setStroke(new BasicStroke(2f));
					g.drawArc(2, 2, 12, 12, 40, 290);
					// 缺口处的箭头，指向左上（圆弧继续前进的方向）
					g.fillPolygon(new int[]{14, 10, 13}, new int[]{4, 5, 8}, 3);
					break;
				case RESUME:
					// 继续：两个并排的三角形，中间留个缝，不然看着像一个粗三角形
					g.fillPolygon(new int[]{2, 2, 7}, new int[]{3, 13, 8}, 3);
					g.fillPolygon(new int[]{9, 9, 14}, new int[]{3, 13, 8}, 3);
					break;
				default:
					break;
			}
		}
		finally
		{
			g.dispose();
		}
		return image;
	}

	/**
	 * 图标的形状。
	 */
	enum Glyph
	{
		/**
		 * 三角形，启动。
		 */
		TRIANGLE,
		/**
		 * 双竖线，暂停。
		 */
		PAUSE,
		/**
		 * 方块，停止。
		 */
		SQUARE,
		/**
		 * 断开的圆环加箭头，重启。
		 */
		RESTART,
		/**
		 * 两个并排的三角形，继续。
		 */
		RESUME
	}

	/**
	 * 找出容器里的按钮，方便测试。
	 *
	 * @param container 容器
	 * @return 容器里的按钮
	 */
	static Component[] buttonsOf(Container container)
	{
		return container.getComponents();
	}
}
