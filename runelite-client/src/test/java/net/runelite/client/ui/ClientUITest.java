package net.runelite.client.ui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.joran.spi.ConsoleTarget;
import com.google.common.collect.Iterables;
import java.awt.BorderLayout;
import java.awt.LayoutManager;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Provider;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.Document;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.input.MouseManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class ClientUITest
{
	private static final String CONFIG_GROUP = "runelite";
	private static final String SIDEBAR_TAB = "clientSidebarTab";
	private static final String SIDEBAR_CLOSED = "clientSidebarClosed";

	private ConfigManager configManager;
	private EventBus eventBus;
	private ClientUI clientUI;
	private PrintStream originalOut;
	private ConsoleAppender<ILoggingEvent> stdoutAppender;

	@Before
	public void setUp() throws Exception
	{
		originalOut = System.out;
		configManager = mock(ConfigManager.class);
		eventBus = new EventBus();
		// 最后一个参数是 --index 自定义窗口标题的覆盖值（本地改动加的），传 null 即可。
		Constructor<ClientUI> constructor = ClientUI.class.getDeclaredConstructor(RuneLiteConfig.class, MouseManager.class,
			Client.class, ConfigManager.class, Provider.class, EventBus.class, boolean.class, String.class, String.class);
		constructor.setAccessible(true);
		clientUI = constructor.newInstance(mock(RuneLiteConfig.class), null, null, configManager,
			(Provider<ClientThread>) () -> null, eventBus, false, "Microbot", null);
		eventBus.register(clientUI);
	}

	@After
	public void tearDown() throws Exception
	{
		System.setOut(originalOut);
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
		ConsoleLogAppender consoleLogAppender = (ConsoleLogAppender) get("consoleLogAppender");
		if (consoleLogAppender != null)
		{
			consoleLogAppender.uninstall();
		}
		if (stdoutAppender != null)
		{
			root.detachAppender(stdoutAppender);
			stdoutAppender.stop();
			ConsoleLogAppender.bindStdoutAppenders(context, ConsoleTarget.SystemOut.getStream());
		}
	}

	@Test
	public void removingTheOpenTabStoresTheTabShownInstead() throws Exception
	{
		NavigationButton first = button("First", 1);
		NavigationButton second = button("Second", 2);
		onEdt(() ->
		{
			installSidebar();
			clientUI.addNavigation(first);
			clientUI.addNavigation(second);
			clientUI.openPanel(first, true);
			clientUI.openPanel(second, true);
			clientUI.removeNavigation(second);
		});

		verify(configManager).setConfiguration(CONFIG_GROUP, SIDEBAR_TAB, "First");
	}

	@Test
	public void removingTheOpenTabWhileClosingKeepsTheSavedTab() throws Exception
	{
		NavigationButton first = button("First", 1);
		NavigationButton second = button("Second", 2);
		onEdt(() ->
		{
			installSidebar();
			clientUI.addNavigation(first);
			clientUI.addNavigation(second);
			clientUI.openPanel(first, true);
			clientUI.openPanel(second, true);
		});

		eventBus.post(new ClientShutdown());
		onEdt(() -> clientUI.removeNavigation(second));

		verify(configManager, never()).setConfiguration(eq(CONFIG_GROUP), eq(SIDEBAR_TAB), any(String.class));
		verify(configManager, never()).unsetConfiguration(CONFIG_GROUP, SIDEBAR_TAB);
	}

	@Test
	public void removingTheOpenTabWhileClosingDoesNotReopenAClosedSidebar() throws Exception
	{
		NavigationButton first = button("First", 1);
		NavigationButton second = button("Second", 2);
		AtomicReference<JTabbedPane> sidebar = new AtomicReference<>();
		onEdt(() ->
		{
			installSidebar();
			sidebar.set((JTabbedPane) get("sidebar"));
			clientUI.addNavigation(first);
			clientUI.addNavigation(second);
			clientUI.openPanel(first, true);
			clientUI.openPanel(second, true);
			sidebar.get().setVisible(false);
			pushHistory();
		});

		eventBus.post(new ClientShutdown());
		onEdt(() -> clientUI.removeNavigation(second));

		verify(configManager, never()).setConfiguration(eq(CONFIG_GROUP), eq(SIDEBAR_CLOSED), any(Boolean.class));
		verify(configManager, never()).unsetConfiguration(CONFIG_GROUP, SIDEBAR_CLOSED);
		assertFalse(sidebar.get().isVisible());
	}

	@Test
	public void consoleInitializationKeepsStdoutLogLinesOutOfThePanel() throws Exception
	{
		ByteArrayOutputStream terminal = new ByteArrayOutputStream();
		System.setOut(new PrintStream(terminal, true, StandardCharsets.UTF_8));

		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		PatternLayoutEncoder encoder = new PatternLayoutEncoder();
		encoder.setContext(context);
		encoder.setPattern("%msg%n");
		encoder.start();
		stdoutAppender = new ConsoleAppender<>();
		stdoutAppender.setContext(context);
		stdoutAppender.setEncoder(encoder);
		stdoutAppender.start();
		Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
		root.addAppender(stdoutAppender);

		AtomicReference<LogConsolePanel> panel = new AtomicReference<>();
		onEdt(() -> panel.set(new LogConsolePanel()));
		set("consolePanel", panel.get());
		Method initialize = ClientUI.class.getDeclaredMethod("initializeConsoleLogging");
		initialize.setAccessible(true);
		initialize.invoke(clientUI);

		String line = "console init probe " + System.nanoTime();
		root.warn(line);
		onEdt(() -> { });
		onEdt(() -> { });

		JTextPane textPane = (JTextPane) ((JScrollPane) panel.get().getComponent(0)).getViewport().getView();
		AtomicReference<String> text = new AtomicReference<>();
		onEdt(() ->
		{
			Document document = textPane.getDocument();
			text.set(document.getText(0, document.getLength()));
		});

		assertEquals(1, occurrences(text.get(), line));
		assertTrue(terminal.toString(StandardCharsets.UTF_8).contains(line));
	}

	/**
	 * 走完整链路：logback 记一条 WARN，日志框里这一行应该是 WARN 的颜色。
	 */
	@Test
	public void logLinesAreColoredByTheirLevel() throws Exception
	{
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);

		AtomicReference<LogConsolePanel> panel = new AtomicReference<>();
		onEdt(() -> panel.set(new LogConsolePanel()));
		set("consolePanel", panel.get());
		Method initialize = ClientUI.class.getDeclaredMethod("initializeConsoleLogging");
		initialize.setAccessible(true);
		initialize.invoke(clientUI);

		root.warn("warn probe");
		root.info("info probe");
		onEdt(() -> { });
		onEdt(() -> { });

		AtomicReference<java.awt.Color[]> colors = new AtomicReference<>();
		onEdt(() ->
		{
			JTextPane textPane = (JTextPane) ((JScrollPane) panel.get().getComponent(0)).getViewport().getView();
			StyledDocument document = (StyledDocument) textPane.getDocument();
			String text = document.getText(0, document.getLength());
			int warn = text.indexOf("warn probe");
			int info = text.indexOf("info probe");
			colors.set(new java.awt.Color[]{
				StyleConstants.getForeground(document.getCharacterElement(warn).getAttributes()),
				StyleConstants.getForeground(document.getCharacterElement(info).getAttributes())
			});
		});

		assertEquals(LogConsolePanel.colorFor(Level.WARN), colors.get()[0]);
		assertEquals(LogConsolePanel.colorFor(Level.INFO), colors.get()[1]);
	}

	@Test
	public void hidingTheConsoleShrinksTheWindowBackDown() throws Exception
	{
		installFrameAndClient();
		ContainableFrame frame = (ContainableFrame) get("frame");
		try
		{
			onEdt(() ->
			{
				frame.setSize(800, 600);
				frame.validate();
				setConsoleVisible(true);
				frame.validate();
			});

			int tallHeight = frame.getHeight();

			onEdt(() ->
			{
				setConsoleVisible(false);
				frame.validate();
			});

			int shortHeight = frame.getHeight();

			assertTrue("显示 console 之后窗口应该变高，实际是 " + tallHeight + " -> " + shortHeight,
				tallHeight > shortHeight);
			assertTrue("隐藏 console 之后窗口应该缩回去，实际是 " + shortHeight,
				shortHeight < tallHeight);
		}
		finally
		{
			onEdt(frame::dispose);
		}
	}

	/**
	 * 隐藏 console 之后窗口要回到显示之前的高度，而不是缩到最小。
	 */
	@Test
	public void hidingTheConsoleRestoresTheHeightItHadBefore() throws Exception
	{
		installFrameAndClient();
		ContainableFrame frame = (ContainableFrame) get("frame");
		try
		{
			onEdt(() ->
			{
				frame.setSize(800, 600);
				frame.validate();
			});
			int before = frame.getHeight();

			onEdt(() ->
			{
				setConsoleVisible(true);
				frame.validate();
			});

			onEdt(() ->
			{
				setConsoleVisible(false);
				frame.validate();
			});

			assertEquals("隐藏 console 之后应该回到原来的高度", before, frame.getHeight());
		}
		finally
		{
			onEdt(frame::dispose);
		}
	}

	/**
	 * 隐藏 console 之后，画布拿回整块高度，不会被多出来的 160px 拉变形。
	 */
	@Test
	public void hidingTheConsoleGivesTheCanvasItsHeightBack() throws Exception
	{
		installFrameAndClient();
		ClientPanel clientPanel = (ClientPanel) get("clientPanel");
		ContainableFrame frame = (ContainableFrame) get("frame");
		try
		{
			onEdt(() -> setConsoleVisible(true));
			int withConsole = clientPanel.getMinimumSize().height;

			onEdt(() -> setConsoleVisible(false));
			int withoutConsole = clientPanel.getMinimumSize().height;

			assertTrue("隐藏之后最小高度应该变回画布本身的高度", withoutConsole < withConsole);
		}
		finally
		{
			onEdt(frame::dispose);
		}
	}

	/**
	 * 搭出一个带 frame、content 和 clientPanel 的环境。
	 *
	 * <p>不调 init() —— 它会去做一整套启动流程，而且 api.Client 接口不是 Component，
	 * mock 不出来。这里只需要窗口布局这一块。
	 */
	private void installFrameAndClient() throws Exception
	{
		onEdt(() ->
		{
			ContainableFrame frame = new ContainableFrame();
			JPanel content = new JPanel();
			content.setLayout(createClientUiLayout());

			ClientPanel clientPanel = new ClientPanel(null);
			// 必须装上日志框，否则 clientPanel 的最小高度不会因为日志框而变大，
			// 布局也就不会把窗口撑高。
			clientPanel.setConsole(new LogConsolePanel());
			clientPanel.setConsoleVisible(false);
			content.add(clientPanel, BorderLayout.CENTER);
			// ClientUI.Layout 会去看 content 的第二个组件 当作侧边栏
			content.add(new JPanel(), BorderLayout.EAST);

			frame.setContentPane(content);
			frame.setSize(800, 600);

			set("frame", frame);
			set("content", content);
			set("clientPanel", clientPanel);
			// setConsoleVisible 一开始就会检查 consolePanel 为不为 null
			set("consolePanel", new LogConsolePanel());
		});
	}

	/**
	 * ClientUI.Layout 是私有内部类，反射造一个出来 —— 窗口高度就是它算的。
	 */
	private LayoutManager createClientUiLayout() throws Exception
	{
		Class<?> layoutClass = Class.forName("net.runelite.client.ui.ClientUI$Layout");
		Constructor<?> constructor = layoutClass.getDeclaredConstructor(ClientUI.class);
		constructor.setAccessible(true);
		return (LayoutManager) constructor.newInstance(clientUI);
	}

	private void setConsoleVisible(boolean visible) throws Exception
	{
		Method method = ClientUI.class.getDeclaredMethod("setConsoleVisible", boolean.class);
		method.setAccessible(true);
		method.invoke(clientUI, visible);
	}

	private void installSidebar() throws Exception
	{
		JTabbedPane sidebar = new JTabbedPane(JTabbedPane.RIGHT);
		sidebar.setSelectedIndex(-1);
		Collection<NavigationButton> entries = (Collection<NavigationButton>) get("sidebarEntries");
		sidebar.addChangeListener(ev ->
		{
			int index = sidebar.getSelectedIndex();
			try
			{
				NavigationButton selected = index < 0 ? null : Iterables.get(entries, index);
				if (selected == get("selectedTab"))
				{
					return;
				}
				set("selectedTab", selected);
				if (sidebar.isVisible())
				{
					pushHistory();
				}
			}
			catch (ReflectiveOperationException ex)
			{
				throw new IllegalStateException(ex);
			}
		});
		set("sidebar", sidebar);
		set("content", new JPanel());
	}

	private void pushHistory() throws ReflectiveOperationException
	{
		Method pushHistory = ClientUI.class.getDeclaredMethod("pushHistory");
		pushHistory.setAccessible(true);
		pushHistory.invoke(clientUI);
	}

	private static NavigationButton button(String tooltip, int priority)
	{
		return NavigationButton.builder()
			.tooltip(tooltip)
			.priority(priority)
			.icon(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB))
			.panel(new PluginPanel()
			{
			})
			.build();
	}

	private Object get(String name) throws ReflectiveOperationException
	{
		Field field = ClientUI.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(clientUI);
	}

	private void set(String name, Object value) throws ReflectiveOperationException
	{
		Field field = ClientUI.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(clientUI, value);
	}

	private static int occurrences(String text, String needle)
	{
		int count = 0;
		for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1))
		{
			count++;
		}
		return count;
	}

	private static void onEdt(ThrowingRunnable runnable) throws Exception
	{
		AtomicReference<Exception> failure = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				runnable.run();
			}
			catch (Exception ex)
			{
				failure.set(ex);
			}
		});
		if (failure.get() != null)
		{
			throw failure.get();
		}
	}

	private interface ThrowingRunnable
	{
		void run() throws Exception;
	}
}
