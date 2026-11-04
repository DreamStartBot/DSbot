package net.runelite.client.plugins.microbot.toolbar;

import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.microbot.InputSelector;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.externalplugins.MicrobotPluginManager;
import net.runelite.client.plugins.microbot.util.events.PluginPauseEvent;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.NavigationButton;
import org.junit.Before;
import org.junit.Test;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;

import org.junit.After;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具栏按钮插件。
 *
 * <p>重点是两件事：按钮容器真的装进了工具栏，
 * 以及 {@link MinimalToolbarPlugin} 不会把它当成多余按钮摘掉。
 */
public class ToolbarButtonsPluginTest
{
	private static final String PANEL_CLASS_NAME = "net.runelite.client.ui.ClientToolbarPanel";

	private Class<?> panelClass;
	private Container panel;
	private Method addMethod;
	private ToolbarButtonsPlugin plugin;

	/**
	 * 一个从 jar 加载的脚本。
	 */
	@PluginDescriptor(name = "A Script", isExternal = true)
	private static class ExternalScript extends Plugin
	{
	}

	/**
	 * 另一个脚本类。
	 */
	@PluginDescriptor(name = "Other Script", isExternal = true)
	private static class OtherScript extends Plugin
	{
	}

	/**
	 * 一个客户端内置插件。它在不在跑都不影响按钮显示。
	 */
	@PluginDescriptor(name = "Built In")
	private static class BuiltInPlugin extends Plugin
	{
	}

	@Before
	public void setUp() throws Exception
	{
		panelClass = Class.forName(PANEL_CLASS_NAME);
		assertNotNull("反射拿不到工具栏面板的类", panelClass);

		Constructor<?> ctor = panelClass.getDeclaredConstructor(boolean.class);
		ctor.setAccessible(true);
		panel = (Container) ctor.newInstance(false);

		addMethod = panelClass.getDeclaredMethod("add", NavigationButton.class, boolean.class);
		addMethod.setAccessible(true);

		plugin = new ToolbarButtonsPlugin();
	}

	/**
	 * 把插件管理器塞进去。按钮显隐要看有没有脚本在跑。
	 */
	private void injectPluginManager(PluginManager pluginManager) throws Exception
	{
		Field field = ToolbarButtonsPlugin.class.getDeclaredField("pluginManager");
		field.setAccessible(true);
		field.set(plugin, pluginManager);
	}

	/**
	 * 把脚本加载器塞进去。重启靠它重载 jar。
	 */
	private MicrobotPluginManager injectMicrobotPluginManager(MicrobotPluginManager microbotPluginManager) throws Exception
	{
		Field field = ToolbarButtonsPlugin.class.getDeclaredField("microbotPluginManager");
		field.setAccessible(true);
		field.set(plugin, microbotPluginManager);
		return microbotPluginManager;
	}

	/**
	 * 把输入开关塞进去。
	 */
	private InputSelector injectInputSelector(InputSelector inputSelector) throws Exception
	{
		Field field = ToolbarButtonsPlugin.class.getDeclaredField("inputSelector");
		field.setAccessible(true);
		field.set(plugin, inputSelector);
		return inputSelector;
	}

	/**
	 * 等 EDT 上排队的任务跑完。
	 */
	private static void drainEventQueue() throws Exception
	{
		SwingUtilities.invokeAndWait(() -> {
		});
	}

	/**
	 * 造一个只会报告给定 active 插件的插件管理器。
	 */
	private PluginManager useActivePlugins(Plugin... active) throws Exception
	{
		PluginManager pluginManager = mock(PluginManager.class);
		when(pluginManager.getActivePlugins()).thenReturn(Arrays.asList(active));
		injectPluginManager(pluginManager);
		return pluginManager;
	}

	private JButton buttonAt(Container container, int index)
	{
		return (JButton) container.getComponent(index);
	}

	/**
	 * 暂停标志是静态的，测完要清掉，不然会串到下一个测试。
	 */
	@After
	public void tearDown()
	{
		PluginPauseEvent.setPausedAll(false);
	}

	private NavigationButton addButton(String tooltip, int priority) throws Exception
	{
		BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		NavigationButton button = NavigationButton.builder()
			.icon(icon)
			.tooltip(tooltip)
			.priority(priority)
			.build();
		addMethod.invoke(panel, button, true);
		return button;
	}

	@Test
	public void installsFourButtonsInOneContainer() throws Exception
	{
		plugin.installInto(panel);

		assertEquals("工具栏里应该只多了一个容器", 1, panel.getComponentCount());

		Container container = (Container) panel.getComponent(0);
		assertTrue("加进去的应该是个容器", container instanceof JPanel);
		assertEquals("容器里应该有四个按钮", 4, container.getComponentCount());

		JButton start = (JButton) container.getComponent(0);
		JButton pause = (JButton) container.getComponent(1);
		JButton stop = (JButton) container.getComponent(2);
		JButton restart = (JButton) container.getComponent(3);

		assertEquals("Start", start.getToolTipText());
		assertEquals("Pause", pause.getToolTipText());
		assertEquals("Stop", stop.getToolTipText());
		assertEquals("Restart", restart.getToolTipText());
	}

	@Test
	public void buttonsHaveDistinctIcons() throws Exception
	{
		plugin.installInto(panel);
		Container container = (Container) panel.getComponent(0);

		JButton start = (JButton) container.getComponent(0);
		JButton pause = (JButton) container.getComponent(1);
		JButton stop = (JButton) container.getComponent(2);
		JButton restart = (JButton) container.getComponent(3);

		assertNotNull(start.getIcon());
		assertNotNull(pause.getIcon());
		assertNotNull(stop.getIcon());
		assertNotNull(restart.getIcon());

		// 四个图标画得不一样，不能是同一张图
		assertFalse("启动和暂停的图标不能相同",
			sameImage(start, pause));
		assertFalse("暂停和停止的图标不能相同",
			sameImage(pause, stop));
		assertFalse("停止和重启的图标不能相同",
			sameImage(stop, restart));
		assertFalse("重启和启动的图标不能相同",
			sameImage(restart, start));
	}

	/**
	 * 停止按钮会停掉正在跑的脚本。
	 */
	@Test
	public void stopButtonStopsTheRunningScript() throws Exception
	{
		ExternalScript script = new ExternalScript();
		PluginManager pluginManager = useActivePlugins(script);

		plugin.installInto(panel);
		Container container = (Container) panel.getComponent(0);

		buttonAt(container, 2).doClick();

		verify(pluginManager).stopPlugin(script);
		// 停之前要关掉配置开关，不然重启客户端它又被自动拉起来
		verify(pluginManager).setPluginEnabled(script, false);
	}

	/**
	 * 停止按钮不动内置插件。
	 */
	@Test
	public void stopButtonLeavesBuiltInPluginsAlone() throws Exception
	{
		BuiltInPlugin builtIn = new BuiltInPlugin();
		PluginManager pluginManager = useActivePlugins(builtIn);

		plugin.installInto(panel);
		Container container = (Container) panel.getComponent(0);

		buttonAt(container, 2).doClick();

		verify(pluginManager, never()).stopPlugin(any(Plugin.class));
	}

	/**
	 * 没有脚本在跑时点停止不会出事。
	 */
	@Test
	public void stoppingWithNothingRunningIsHarmless() throws Exception
	{
		PluginManager pluginManager = useActivePlugins();

		plugin.installInto(panel);
		Container container = (Container) panel.getComponent(0);

		buttonAt(container, 2).doClick();

		verify(pluginManager, never()).stopPlugin(any(Plugin.class));
	}

	/**
	 * 停掉脚本之后，按钮回到「开始 + 重启」。
	 */
	@Test
	public void stoppingBringsBackStartAndRestart() throws Exception
	{
		ExternalScript script = new ExternalScript();
		PluginManager pluginManager = useActivePlugins(script);

		plugin.installInto(panel);
		Container container = (Container) panel.getComponent(0);
		assertFalse("脚本在跑时开始按钮是收起来的", buttonAt(container, 0).isVisible());

		// 脚本停了
		when(pluginManager.getActivePlugins()).thenReturn(Collections.emptyList());
		buttonAt(container, 2).doClick();

		assertTrue("停了之后开始按钮该回来", buttonAt(container, 0).isVisible());
		assertFalse("停了之后不该显示暂停", buttonAt(container, 1).isVisible());
		assertFalse("停了之后不该显示停止", buttonAt(container, 2).isVisible());
		assertTrue("跑过之后重启按钮该留着", buttonAt(container, 3).isVisible());
	}

	/**
	 * 点暂停，按钮换成「继续」的图标和提示。
	 */
	@Test
	public void pausingSwitchesTheButtonToResume() throws Exception
	{
		useActivePlugins(new ExternalScript());
		plugin.installInto(panel);
		Container container = (Container) panel.getComponent(0);

		JButton pause = buttonAt(container, 1);
		assertEquals("没暂停时提示应该是 Pause", "Pause", pause.getToolTipText());
		ImageIcon before = (ImageIcon) pause.getIcon();

		pause.doClick();

		assertEquals("暂停后提示应该变成 Resume", "Resume", pause.getToolTipText());
		ImageIcon after = (ImageIcon) pause.getIcon();
		assertFalse("暂停后图标应该换成继续的", sameIcon(before, after));
	}

	/**
	 * 暂停要真的把全局标志设上，脚本才会停。
	 */
	@Test
	public void pausingSetsTheGlobalPauseFlag() throws Exception
	{
		useActivePlugins(new ExternalScript());
		plugin.installInto(panel);
		Container container = (Container) panel.getComponent(0);

		buttonAt(container, 1).doClick();

		assertTrue("暂停后全局标志要设上", PluginPauseEvent.isPaused());
		assertTrue("暂停后脚本循环的守卫也要生效", Microbot.pauseAllScripts.get());
	}

	/**
	 * 再点一下就恢复。
	 */
	@Test
	public void resumingClearsTheGlobalPauseFlag() throws Exception
	{
		useActivePlugins(new ExternalScript());
		plugin.installInto(panel);
		Container container = (Container) panel.getComponent(0);

		JButton pause = buttonAt(container, 1);
		pause.doClick();
		pause.doClick();

		assertFalse("恢复后全局标志要清掉", PluginPauseEvent.isPaused());
		assertFalse("恢复后脚本循环的守卫也要放开", Microbot.pauseAllScripts.get());
		assertEquals("恢复后提示该变回 Pause", "Pause", pause.getToolTipText());
	}

	/**
	 * 暂停的时候按钮组不变，还是三个，只是暂停那个变成了「继续」。
	 */
	@Test
	public void thePausedStateStillShowsThreeButtons() throws Exception
	{
		useActivePlugins(new ExternalScript());
		plugin.installInto(panel);
		Container container = (Container) panel.getComponent(0);

		buttonAt(container, 1).doClick();

		assertFalse("暂停时脚本还在跑，开始按钮该收着", buttonAt(container, 0).isVisible());
		assertTrue("暂停时继续按钮要显示", buttonAt(container, 1).isVisible());
		assertTrue("暂停时停止按钮要显示", buttonAt(container, 2).isVisible());
		assertTrue("暂停时重启按钮要显示", buttonAt(container, 3).isVisible());
	}

	/**
	 * 脚本停了之后，暂停标志要跟着清掉。
	 *
	 * <p>不然下次启动脚本会立刻被暂停住。
	 */
	@Test
	public void stoppingClearsThePauseFlag() throws Exception
	{
		ExternalScript script = new ExternalScript();
		PluginManager pluginManager = useActivePlugins(script);

		plugin.installInto(panel);
		Container container = (Container) panel.getComponent(0);

		buttonAt(container, 1).doClick();
		assertTrue("先暂停", PluginPauseEvent.isPaused());

		// 脚本停了
		when(pluginManager.getActivePlugins()).thenReturn(Collections.emptyList());
		buttonAt(container, 2).doClick();

		assertFalse("脚本停了暂停标志要清掉", PluginPauseEvent.isPaused());
		assertEquals("脚本停了之后提示该变回 Pause", "Pause",
			buttonAt(container, 1).getToolTipText());
	}

	/**
	 * 暂停被别的组件清掉了，定时器要补回来。
	 *
	 * <p>antiban、breakhandler 这些结束时会把全局标志恢复掉，
	 * 顺带把用户的暂停也清了。不补回来的话脚本会莫名其妙继续跑。
	 */
	@Test
	public void thePauseIsPutBackWhenSomethingElseClearsIt() throws Exception
	{
		useActivePlugins(new ExternalScript());
		plugin.installInto(panel);
		Container container = (Container) panel.getComponent(0);

		buttonAt(container, 1).doClick();

		// 模拟别的组件把全局标志清了
		PluginPauseEvent.setPausedAll(false);
		assertFalse("先确认全局标志被清了", PluginPauseEvent.isPaused());

		plugin.restorePauseIfLost();

		assertTrue("定时器要把用户的暂停补回来", PluginPauseEvent.isPaused());
	}

	/**
	 * 没有用户暂停的时候，定时器不能乱设标志。
	 */
	@Test
	public void thePauseIsNotPutBackWithoutAUserPause() throws Exception
	{
		useActivePlugins(new ExternalScript());
		plugin.installInto(panel);

		plugin.restorePauseIfLost();

		assertFalse("没暂停过就不该设标志", PluginPauseEvent.isPaused());
	}
	/**
	 * 脚本跑起来就把用户输入锁住。
	 */
	@Test
	public void aRunningScriptLocksTheUserInput() throws Exception
	{
		useActivePlugins(new ExternalScript());
		InputSelector inputSelector = injectInputSelector(mock(InputSelector.class));

		plugin.installInto(panel);
		plugin.refreshButtonsFromState();

		verify(inputSelector).setInputDisabled(true);
	}

	/**
	 * 脚本停了就把用户输入放回去。
	 */
	@Test
	public void aStoppedScriptUnlocksTheUserInput() throws Exception
	{
		PluginManager pluginManager = useActivePlugins(new ExternalScript());
		InputSelector inputSelector = injectInputSelector(mock(InputSelector.class));

		plugin.installInto(panel);
		plugin.refreshButtonsFromState();
		verify(inputSelector).setInputDisabled(true);

		// 脚本停了
		when(pluginManager.getActivePlugins()).thenReturn(Collections.emptyList());
		plugin.refreshButtonsFromState();

		verify(inputSelector).setInputDisabled(false);
	}

	/**
	 * 暂停的时候要把用户输入放回去 —— 用户得能操作游戏。
	 */
	@Test
	public void pausingUnlocksTheUserInput() throws Exception
	{
		useActivePlugins(new ExternalScript());
		InputSelector inputSelector = injectInputSelector(mock(InputSelector.class));

		plugin.installInto(panel);
		Container container = (Container) panel.getComponent(0);
		plugin.refreshButtonsFromState();
		verify(inputSelector).setInputDisabled(true);

		// 点暂停
		buttonAt(container, 1).doClick();

		verify(inputSelector).setInputDisabled(false);
	}

	/**
	 * 从暂停点继续，要重新锁上。
	 */
	@Test
	public void resumingRelocksTheUserInput() throws Exception
	{
		useActivePlugins(new ExternalScript());
		InputSelector inputSelector = injectInputSelector(mock(InputSelector.class));

		plugin.installInto(panel);
		Container container = (Container) panel.getComponent(0);
		plugin.refreshButtonsFromState();

		// 暂停，然后继续
		buttonAt(container, 1).doClick();
		buttonAt(container, 1).doClick();

		verify(inputSelector, times(2)).setInputDisabled(true);
		verify(inputSelector, times(1)).setInputDisabled(false);
		// 锁、解锁、再锁，一共三次
		verify(inputSelector, times(3)).setInputDisabled(anyBoolean());
	}

	/**
	 * 用户手动点过 Enable Input 解锁之后，状态刷新不能把他的操作盖回去。
	 *
	 * <p>这是「除非手动关闭」的关键：脚本还在跑，但我方的意图没变，
	 * 所以不该再去动那个开关。
	 */
	@Test
	public void aManualUnlockIsNotOverwritten() throws Exception
	{
		useActivePlugins(new ExternalScript());
		InputSelector inputSelector = injectInputSelector(mock(InputSelector.class));

		plugin.installInto(panel);
		plugin.refreshButtonsFromState();
		verify(inputSelector).setInputDisabled(true);

		// 用户手动解锁了 —— 模拟这个情况就是不再调用 setInputDisabled
		plugin.refreshButtonsFromState();
		plugin.refreshButtonsFromState();

		// 只该锁过一次，不该再锁
		verify(inputSelector, times(1)).setInputDisabled(anyBoolean());
	}

	/**
	 * 没脚本在跑的时候不该去动输入开关。
	 */
	@Test
	public void noScriptMeansNoInputLock() throws Exception
	{
		useActivePlugins();
		InputSelector inputSelector = injectInputSelector(mock(InputSelector.class));

		plugin.installInto(panel);
		plugin.refreshButtonsFromState();

		verify(inputSelector, never()).setInputDisabled(anyBoolean());
	}

	/**
	 * 重启按钮现在接上功能了，点了应该有反应。
	 */
	@Test
	public void restartButtonHasAnAction() throws Exception
	{
		plugin.installInto(panel);
		Container container = (Container) panel.getComponent(0);

		JButton restart = (JButton) container.getComponent(3);
		assertEquals("重启按钮该有动作监听器",
			1, restart.getActionListeners().length);
	}

	/**
	 * 重启会重载 jar 包，然后启动同一个脚本。
	 */
	@Test
	public void restartingReloadsAndStartsTheSameScript() throws Exception
	{
		ExternalScript script = new ExternalScript();
		PluginManager pluginManager = mock(PluginManager.class);
		when(pluginManager.getActivePlugins()).thenReturn(Arrays.asList(script));
		when(pluginManager.getPlugins()).thenReturn(Arrays.asList(script));
		when(pluginManager.startPlugin(any())).thenReturn(true);
		injectPluginManager(pluginManager);

		MicrobotPluginManager microbotPluginManager = injectMicrobotPluginManager(mock(MicrobotPluginManager.class));

		plugin.installInto(panel);
		plugin.onScriptStarted(script);

		plugin.doRestart();
		drainEventQueue();

		verify(microbotPluginManager).reloadSideLoadPlugins();
		verify(pluginManager).startPlugin(script);
	}

	/**
	 * 重载会换掉插件实例，重启要按类名找到新的那一个。
	 */
	@Test
	public void restartingFindsTheNewInstanceAfterTheReload() throws Exception
	{
		ExternalScript original = new ExternalScript();
		// 重载后的新实例：类名一样，对象不同
		ExternalScript reloaded = new ExternalScript();

		PluginManager pluginManager = mock(PluginManager.class);
		when(pluginManager.getActivePlugins()).thenReturn(Arrays.asList(original));
		// 重载之后插件列表里换成了新实例
		when(pluginManager.getPlugins()).thenReturn(Arrays.asList(reloaded));
		when(pluginManager.startPlugin(any())).thenReturn(true);
		injectPluginManager(pluginManager);

		injectMicrobotPluginManager(mock(MicrobotPluginManager.class));

		plugin.installInto(panel);
		plugin.onScriptStarted(original);

		plugin.doRestart();
		drainEventQueue();

		verify(pluginManager).startPlugin(reloaded);
		verify(pluginManager, never()).startPlugin(original);
	}

	/**
	 * 找不到同名脚本了，就不启动，让用户自己去弹窗里选。
	 */
	@Test
	public void restartingStartsNothingWhenTheScriptIsGone() throws Exception
	{
		ExternalScript script = new ExternalScript();
		PluginManager pluginManager = mock(PluginManager.class);
		when(pluginManager.getActivePlugins()).thenReturn(Arrays.asList(script));
		when(pluginManager.getPlugins()).thenReturn(Collections.emptyList());
		injectPluginManager(pluginManager);

		injectMicrobotPluginManager(mock(MicrobotPluginManager.class));

		plugin.installInto(panel);
		plugin.onScriptStarted(script);

		plugin.doRestart();
		drainEventQueue();

		verify(pluginManager, never()).startPlugin(any());
	}

	/**
	 * 同名同包的类装在两个 jar 里，要靠 jar 区分，不能启动错。
	 */
	@Test
	public void theRestartTargetIsPickedByClassNameAndJar() throws Exception
	{
		ExternalScript fromJarA = new ExternalScript();
		ExternalScript fromJarB = new ExternalScript();

		PluginManager pluginManager = mock(PluginManager.class);
		when(pluginManager.getPlugins()).thenReturn(Arrays.asList(fromJarA, fromJarB));
		injectPluginManager(pluginManager);

		MicrobotPluginManager microbotPluginManager = mock(MicrobotPluginManager.class);
		when(microbotPluginManager.findSideLoadJarName(fromJarA)).thenReturn("scriptA");
		when(microbotPluginManager.findSideLoadJarName(fromJarB)).thenReturn("scriptB");
		injectMicrobotPluginManager(microbotPluginManager);

		plugin.installInto(panel);
		plugin.onScriptStarted(fromJarB);

		assertSame("要重启 jarB 里那一个", fromJarB, plugin.findRestartTarget());
	}

	/**
	 * 什么都没记住、但有脚本在跑的话，就用当前跑的那个当重启目标。
	 */
	@Test
	public void theRunningScriptBecomesTheRestartTargetWhenNothingWasRemembered() throws Exception
	{
		ExternalScript script = new ExternalScript();
		PluginManager pluginManager = mock(PluginManager.class);
		when(pluginManager.getActivePlugins()).thenReturn(Arrays.asList(script));
		when(pluginManager.getPlugins()).thenReturn(Arrays.asList(script));
		injectPluginManager(pluginManager);

		MicrobotPluginManager microbotPluginManager = mock(MicrobotPluginManager.class);
		when(microbotPluginManager.findSideLoadJarName(script)).thenReturn("scriptA");
		injectMicrobotPluginManager(microbotPluginManager);

		plugin.installInto(panel);

		plugin.rememberScriptToRestart();

		assertSame("当前跑着的脚本该成为重启目标", script, plugin.findRestartTarget());
	}

	/**
	 * 记过之后再启动别的，重启目标要跟着更新。
	 */
	@Test
	public void theRestartTargetFollowsTheLastStartedScript() throws Exception
	{
		ExternalScript first = new ExternalScript();
		OtherScript second = new OtherScript();

		PluginManager pluginManager = mock(PluginManager.class);
		when(pluginManager.getPlugins()).thenReturn(Arrays.asList(first, second));
		injectPluginManager(pluginManager);

		injectMicrobotPluginManager(mock(MicrobotPluginManager.class));

		plugin.installInto(panel);
		plugin.onScriptStarted(first);
		assertSame("先记住第一个", first, plugin.findRestartTarget());

		plugin.onScriptStarted(second);
		assertSame("启动第二个之后要记第二个", second, plugin.findRestartTarget());
	}

	/**
	 * 刚打开、没跑过脚本时只显示开始按钮。
	 */
	@Test
	public void onlyStartIsVisibleBeforeAnythingRan() throws Exception
	{
		useActivePlugins();
		plugin.installInto(panel);
		Container container = (Container) panel.getComponent(0);

		assertTrue("没跑过脚本时应该显示开始", buttonAt(container, 0).isVisible());
		assertFalse("没跑过脚本时不该显示暂停", buttonAt(container, 1).isVisible());
		assertFalse("没跑过脚本时不该显示停止", buttonAt(container, 2).isVisible());
		assertFalse("没跑过脚本时不该显示重启", buttonAt(container, 3).isVisible());
	}

	/**
	 * 脚本在跑的时候，开始按钮收起来，显示暂停、停止、重启。
	 */
	@Test
	public void runningScriptsShowPauseStopAndRestart() throws Exception
	{
		useActivePlugins(new ExternalScript());
		plugin.installInto(panel);
		Container container = (Container) panel.getComponent(0);

		assertFalse("脚本在跑时不该显示开始", buttonAt(container, 0).isVisible());
		assertTrue("脚本在跑时应该显示暂停", buttonAt(container, 1).isVisible());
		assertTrue("脚本在跑时应该显示停止", buttonAt(container, 2).isVisible());
		assertTrue("脚本在跑时应该显示重启", buttonAt(container, 3).isVisible());
	}

	/**
	 * 内置插件在跑不算数，按钮显示不变。
	 */
	@Test
	public void builtInPluginsDoNotCountAsScripts() throws Exception
	{
		useActivePlugins(new BuiltInPlugin());
		plugin.installInto(panel);
		Container container = (Container) panel.getComponent(0);

		assertTrue("内置插件在跑时仍然显示开始", buttonAt(container, 0).isVisible());
		assertFalse("内置插件在跑时不该显示暂停", buttonAt(container, 1).isVisible());
	}

	/**
	 * 脚本跑过又停下之后，开始和重启都在，暂停和停止收起来。
	 */
	@Test
	public void afterStoppingTheRestartButtonStays() throws Exception
	{
		ExternalScript script = new ExternalScript();
		useActivePlugins(script);
		plugin.installInto(panel);
		Container container = (Container) panel.getComponent(0);

		// 跑起来了
		assertTrue(buttonAt(container, 3).isVisible());

		// 停了
		useActivePlugins();
		plugin.onPluginChanged(new PluginChanged(script, false));

		assertTrue("跑过之后应该还能看到开始", buttonAt(container, 0).isVisible());
		assertFalse("停了之后不该显示暂停", buttonAt(container, 1).isVisible());
		assertFalse("停了之后不该显示停止", buttonAt(container, 2).isVisible());
		assertTrue("跑过之后重启按钮应该留着", buttonAt(container, 3).isVisible());
	}

	/**
	 * 脚本启动的事件也会刷新按钮。
	 */
	@Test
	public void startingAScriptRefreshesTheButtons() throws Exception
	{
		useActivePlugins();
		plugin.installInto(panel);
		Container container = (Container) panel.getComponent(0);

		ExternalScript script = new ExternalScript();
		useActivePlugins(script);
		plugin.onPluginChanged(new PluginChanged(script, true));

		assertFalse("脚本启动后开始按钮该收起来", buttonAt(container, 0).isVisible());
		assertTrue("脚本启动后该显示停止", buttonAt(container, 2).isVisible());
	}

	/**
	 * 用真的 EventBus 走一遍完整链路：注册、收事件、按钮跟着变。
	 *
	 * <p>{@code PluginManager} 启停插件时会发 {@code PluginChanged}，
	 * 插件靠它刷新按钮。这条链路断了按钮就永远不更新。
	 *
	 * <p>这里必须用 RuneLite 自己的 {@code EventBus} ——
	 * 它只认 {@code net.runelite.client.eventbus.Subscribe} 注解，
	 * 而且要求方法名是 {@code on} + 事件类名。
	 * 用 Guava 的 EventBus 测是测不出来的。
	 */
	@Test
	public void reactsToARealPluginChangedEvent() throws Exception
	{
		EventBus eventBus = new EventBus();
		// PluginManager 启动插件时会把它注册到 eventBus 上
		eventBus.register(plugin);

		useActivePlugins();
		plugin.installInto(panel);
		Container container = (Container) panel.getComponent(0);
		assertTrue("一开始只有开始按钮可见", buttonAt(container, 0).isVisible());

		ExternalScript script = new ExternalScript();
		useActivePlugins(script);
		eventBus.post(new PluginChanged(script, true));

		assertFalse("事件到了之后开始按钮该收起来", buttonAt(container, 0).isVisible());
		assertTrue("事件到了之后该显示暂停", buttonAt(container, 1).isVisible());
		assertTrue("事件到了之后该显示停止", buttonAt(container, 2).isVisible());
		assertTrue("事件到了之后该显示重启", buttonAt(container, 3).isVisible());
	}

	/**
	 * 订阅方法必须符合 RuneLite EventBus 的要求，否则根本不会被调用。
	 *
	 * <p>要求：带 {@code net.runelite.client.eventbus.Subscribe} 注解，
	 * 方法名是 {@code on} + 事件类名。
	 */
	@Test
	public void theSubscriberMethodMeetsTheEventBusRequirements() throws Exception
	{
		Method method = ToolbarButtonsPlugin.class.getMethod("onPluginChanged", PluginChanged.class);

		assertNotNull("订阅方法必须带 RuneLite 的 Subscribe 注解，不是 Guava 的",
			method.getAnnotation(net.runelite.client.eventbus.Subscribe.class));
		assertEquals("订阅方法名必须是 on + 事件类名",
			"on" + PluginChanged.class.getSimpleName(), method.getName());
	}

	/**
	 * 事件没到的时候，定时兜底也得能把按钮刷对。
	 *
	 * <p>事件只是锦上添花，UI 状态不能只靠它 —— 万一事件没送到，
	 * 按钮就永远停在旧状态了。
	 */
	@Test
	public void thePollingFallbackAlsoRefreshesTheButtons() throws Exception
	{
		useActivePlugins();
		plugin.installInto(panel);
		Container container = (Container) panel.getComponent(0);

		ExternalScript script = new ExternalScript();
		useActivePlugins(script);
		plugin.refreshButtonsFromState();

		assertFalse("兜底刷新后开始按钮该收起来", buttonAt(container, 0).isVisible());
		assertTrue("兜底刷新后该显示停止", buttonAt(container, 2).isVisible());
	}

	@Test
	public void iconsAreDrawnWithVisiblePixels() throws Exception
	{
		for (ToolbarButtonsPlugin.Glyph glyph : ToolbarButtonsPlugin.Glyph.values())
		{
			BufferedImage icon = ToolbarButtonsPlugin.createIcon(glyph);
			assertEquals(16, icon.getWidth());
			assertEquals(16, icon.getHeight());

			boolean anyPixel = false;
			for (int x = 0; x < icon.getWidth() && !anyPixel; x++)
			{
				for (int y = 0; y < icon.getHeight(); y++)
				{
					if ((icon.getRGB(x, y) >>> 24) > 0)
					{
						anyPixel = true;
						break;
					}
				}
			}
			assertTrue(glyph + " 的图标应该是画了东西的", anyPixel);
		}
	}

	@Test
	public void minimalToolbarDoesNotRemoveTheContainer() throws Exception
	{
		// 工具栏里先放几个会被隐藏的按钮
		addButton("Enable 'Semi-Auto' Questing", 2);
		addButton("Report a problem", 90);
		addButton("Show console", 95);
		addButton("Close sidebar", 100);

		plugin.installInto(panel);
		assertEquals(5, panel.getComponentCount());

		MinimalToolbarPlugin minimalToolbar = new MinimalToolbarPlugin();
		minimalToolbar.applyTo(panel);

		// 三个会被隐藏的按钮被摘掉了，但容器还在
		assertEquals("容器和两个白名单按钮应该还在", 3, panel.getComponentCount());
		assertTrue("按钮容器不能被 MinimalToolbar 摘掉",
			panel.getComponent(0) instanceof JPanel);
	}

	@Test
	public void uninstallRemovesTheContainer() throws Exception
	{
		plugin.installInto(panel);
		assertEquals(1, panel.getComponentCount());

		plugin.uninstall();
		assertEquals("容器应该被摘掉", 0, panel.getComponentCount());
	}

	private static boolean sameImage(JButton a, JButton b) throws Exception
	{
		return sameIcon((ImageIcon) a.getIcon(), (ImageIcon) b.getIcon());
	}

	/**
	 * 比较两张图标是不是同一张图。
	 */
	private static boolean sameIcon(ImageIcon a, ImageIcon b) throws Exception
	{
		BufferedImage ia = (BufferedImage) a.getImage();
		BufferedImage ib = (BufferedImage) b.getImage();
		if (ia.getWidth() != ib.getWidth() || ia.getHeight() != ib.getHeight())
		{
			return false;
		}
		for (int x = 0; x < ia.getWidth(); x++)
		{
			for (int y = 0; y < ia.getHeight(); y++)
			{
				if (ia.getRGB(x, y) != ib.getRGB(x, y))
				{
					return false;
				}
			}
		}
		return true;
	}
}
