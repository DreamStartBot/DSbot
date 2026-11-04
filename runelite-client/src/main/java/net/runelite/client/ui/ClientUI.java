/*
 * Copyright (c) 2016-2017, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.ui.FlatNativeWindowBorder;
import com.formdev.flatlaf.util.SystemInfo;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.inject.Inject;
import ch.qos.logback.classic.LoggerContext;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.ExpandResizeType;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.config.WarningOnExit;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.questhelper.QuestHelperConfig;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.ui.laf.RuneLiteLAF;
import net.runelite.client.ui.laf.RuneLiteRootPaneUI;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.OSType;
import net.runelite.client.util.SwingUtil;
import net.runelite.client.util.WinUtil;
import net.runelite.client.util.*;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.swing.Timer;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.awt.desktop.QuitStrategy;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.*;
import java.util.function.Function;

import static javax.swing.JOptionPane.INFORMATION_MESSAGE;

@Slf4j
@Singleton
public class ClientUI
{
	// ===================== [MICROBOT 本地改动] 配置组对外可见 =====================
	// LogConsolePanel 要把「显示等级 / 显示类名」的开关状态存进同一个配置组，
	// 所以这里从 private 改成包内可见，避免 group 名字写两遍。
	// 冲突时如何覆盖：改回 private，并把 LogConsolePanel 里的引用换成本类的常量。
	// ===================== [MICROBOT 本地改动 END] =====================
	static final String CONFIG_GROUP = "runelite";
	private static final String CONFIG_CLIENT_BOUNDS = "clientBounds";
	private static final String CONFIG_CLIENT_MAXIMIZED = "clientMaximized";
	private static final String CONFIG_CLIENT_SIDEBAR_CLOSED = "clientSidebarClosed";
	private static final String CONFIG_CLIENT_SIDEBAR_TAB = "clientSidebarTab";
	private static final String CONFIG_CLIENT_CONSOLE_OPEN = "clientConsoleOpen";
	// ===================== [MICROBOT 本地改动] 日志显示字段开关 =====================
	// 日志框里要不要显示日志等级和类名，默认都不显示。
	// 冲突时如何覆盖：保留这两个键。
	// ===================== [MICROBOT 本地改动 END] =====================
	static final String CONFIG_CLIENT_CONSOLE_SHOW_LEVEL = "clientConsoleShowLevel";
	static final String CONFIG_CLIENT_CONSOLE_SHOW_LOGGER = "clientConsoleShowLogger";
	public static final BufferedImage ICON_128 = ImageUtil.loadImageResource(Microbot.class, "microbot_logo.png");
	public static final BufferedImage ICON_16 = ImageUtil.loadImageResource(Microbot.class, "microbot_logo.png");
	public static String proxyMessage = "";
	@Getter
	private TrayIcon trayIcon;

	private final RuneLiteConfig config;
	private final MouseManager mouseManager;
	@Getter
    private static Component client;
	private final ConfigManager configManager;
	private final Provider<ClientThread> clientThreadProvider;
	private final EventBus eventBus;
	private final boolean safeMode;
	private final String title;
	// ===================== [MICROBOT 本地改动] --index 自定义窗口标题 =====================
	// 本地新增：--index 启动参数传入的窗口标题覆盖值。
	// windowTitleOverride —— 原始字符串，未指定时为空串。
	// windowTitleOverridden —— 是否传入了 --index。用于关闭默认的「标题 + 玩家名」逻辑。
	// windowTitleOverrideApplied —— 标题是否已经真正被设置过。用于让 updateFrameConfig 每次都把标题盖回自定义值。
	//   它在 EDT 上写、在 client 线程上读，所以声明为 volatile 保证可见性。
	// 详见 RuneLite.java 中标注 MICROBOT 本地改动 的位置。
	// ===================== [MICROBOT 本地改动 END] =====================
	private final String windowTitleOverride;
	private final boolean windowTitleOverridden;
	private volatile boolean windowTitleOverrideApplied;

	private final Rectangle sidebarButtonPosition = new Rectangle();
	private BufferedImage sidebarOpenIcon;
	private BufferedImage sidebarCloseIcon;

	private JTabbedPane sidebar;
	private final TreeSet<NavigationButton> sidebarEntries = new TreeSet<>(NavigationButton.COMPARATOR);
	private final Deque<HistoryEntry> selectedTabHistory = new ArrayDeque<>();
	private NavigationButton selectedTab;

	private ClientToolbarPanel toolbarPanel;
	private boolean withTitleBar;

	@Getter
	private static ContainableFrame frame;
	private JPanel content;
	private ClientPanel clientPanel;
	private JButton sidebarNavBtn;
	private Dimension lastClientSize;
	private Cursor defaultCursor;

	private JButton questHelperNavBtn;
	private BufferedImage questIconOn;
	private BufferedImage questIconOff;
	private String lastNormalBounds;
	private final Timer normalBoundsTimer;

	@Inject(optional = true)
	@Named("minMemoryLimit")
	@SuppressWarnings("PMD.ImmutableField")
	private int minMemoryLimit = 400;

	@Inject(optional = true)
	@Named("recommendedMemoryLimit")
	@SuppressWarnings("PMD.ImmutableField")
	private int recommendedMemoryLimit = 512;

	private List<KeyListener> keyListeners;

	private LogConsolePanel consolePanel;
	private ConsoleLogAppender consoleLogAppender;
	private PrintStream consolePrintStream;
	private JButton consoleToggleButton;
	private BufferedImage consoleIconOpen;
	private BufferedImage consoleIconClosed;
	private boolean consoleVisible;
	private boolean sidebarTabRestored;
	private boolean updatingNavigation;
	private boolean closing;

	@RequiredArgsConstructor
	private static class HistoryEntry
	{
		private final boolean sidebarOpen;
		private final NavigationButton navBtn;
	}

	@Inject
	private ClientUI(
		RuneLiteConfig config,
		MouseManager mouseManager,
		Client client,
		ConfigManager configManager,
		Provider<ClientThread> clientThreadProvider,
		EventBus eventBus,
		@Named("safeMode") boolean safeMode,
		@Named("runelite.title") String title,
		// ===================== [MICROBOT 本地改动] --index 自定义窗口标题 =====================
		// 本地新增的注入参数：--index 传入的窗口标题覆盖值。
		// 放在构造器最后一个参数，避免影响其它注入。
		// 上游更新后若此处冲突，保留这个参数即可。
		// ===================== [MICROBOT 本地改动 END] =====================
		@Named("windowTitleOverride") String windowTitleOverride
	)
	{
		this.config = config;
		this.mouseManager = mouseManager;
		this.client = (Component) client;
		this.configManager = configManager;
		this.clientThreadProvider = clientThreadProvider;
		this.eventBus = eventBus;
		this.safeMode = safeMode;
		this.title = title + (safeMode ? " (safe mode)" : "");
		// ===================== [MICROBOT 本地改动] --index 自定义窗口标题 =====================
		// 空串表示未指定 --index，此时行为与上游完全一致。
		// ===================== [MICROBOT 本地改动 END] =====================
		this.windowTitleOverride = windowTitleOverride;
		this.windowTitleOverridden = !Strings.isNullOrEmpty(windowTitleOverride);

		normalBoundsTimer = new Timer(250, _ev -> setLastNormalBounds());
		normalBoundsTimer.setRepeats(false);
	}

	@Subscribe
	private void onConfigChanged(ConfigChanged event)
	{
		if (!event.getGroup().equals(CONFIG_GROUP) ||
			event.getKey().equals(CONFIG_CLIENT_MAXIMIZED) ||
			event.getKey().equals(CONFIG_CLIENT_BOUNDS) ||
			event.getKey().equals(CONFIG_CLIENT_SIDEBAR_TAB) ||
			event.getKey().equals(CONFIG_CLIENT_CONSOLE_OPEN))
		{
			return;
		}

		SwingUtilities.invokeLater(() -> updateFrameConfig(event.getKey().equals("lockWindowSize")));
	}

	void addNavigation(NavigationButton navBtn)
	{
		if (navBtn.getPanel() == null)
		{
			toolbarPanel.add(navBtn, true);
			return;
		}

		if (!sidebarEntries.add(navBtn))
		{
			return;
		}

		final int TAB_SIZE = 16;
		Icon icon = new ImageIcon(ImageUtil.resizeImage(navBtn.getIcon(), TAB_SIZE, TAB_SIZE));

		updatingNavigation = true;
		try
		{
			sidebar.insertTab(null, icon, navBtn.getPanel().getWrappedPanel(), navBtn.getTooltip(),
				sidebarEntries.headSet(navBtn).size());
			// insertTab changes the selected index when the first tab is inserted, avoid this
			if (sidebar.getTabCount() == 1)
			{
				sidebar.setSelectedIndex(-1);
			}

			if (!sidebarTabRestored
				&& sidebar.getSelectedIndex() < 0
				&& navBtn.getTooltip().equals(configManager.getConfiguration(CONFIG_GROUP, CONFIG_CLIENT_SIDEBAR_TAB)))
			{
				sidebarTabRestored = true;
				sidebar.setSelectedIndex(sidebarEntries.headSet(navBtn).size());
			}
		}
		finally
		{
			updatingNavigation = false;
		}
	}

	void removeNavigation(NavigationButton navBtn)
	{
		if (navBtn.getPanel() == null)
		{
			toolbarPanel.remove(navBtn);
		}
		else
		{
			boolean closingOpenTab = !selectedTabHistory.isEmpty() && selectedTabHistory.getLast().navBtn == navBtn;
			selectedTabHistory.removeIf(it -> it.navBtn == navBtn);
			updatingNavigation = true;
			try
			{
				sidebar.remove(navBtn.getPanel().getWrappedPanel());
				if (closingOpenTab)
				{
					HistoryEntry entry = selectedTabHistory.isEmpty()
						? new HistoryEntry(true, null)
						: selectedTabHistory.removeLast();

					openPanel(entry.navBtn, closing ? sidebar.isVisible() : entry.sidebarOpen);
					if (!closing)
					{
						saveSidebarTab();
					}
				}
			}
			finally
			{
				updatingNavigation = false;
			}
		}

		sidebarEntries.remove(navBtn);
	}

	@Subscribe(priority = 100)
	private void onClientShutdown(ClientShutdown event)
	{
		closing = true;
	}

	@Subscribe
	private void onGameStateChanged(final GameStateChanged event)
	{
		// ===================== [MICROBOT 本地改动] --index 自定义窗口标题 =====================
		// 传入了 --index 时跳过，不追加玩家名：自定义标题是最终字符串，不再拼接。
		// 上游更新后若此处冲突，保留 windowTitleOverridden 这个短路条件即可。
		// ===================== [MICROBOT 本地改动 END] =====================
		if (event.getGameState() != GameState.LOGGED_IN || windowTitleOverridden || !config.usernameInTitle())
		{
			return;
		}

		final Client client = (Client) this.client;
		final ClientThread clientThread = clientThreadProvider.get();

		// Keep scheduling event until we get our name
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return true;
			}

			final Player player = client.getLocalPlayer();

			if (player == null)
			{
				return false;
			}

			final String name = player.getName();

			if (Strings.isNullOrEmpty(name))
			{
				return false;
			}

			frame.setTitle(title + " - " + name);
			return true;
		});
	}

	/**
	 * Initialize UI.
	 */
	public void init() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			// Set some sensible swing defaults
			setupDefaults();

			RuneLiteLAF.setup();

			// Create main window
			frame = new ContainableFrame();

			if (OSType.getOSType() == OSType.MacOS)
			{
				try
				{
					OSXFullScreenAdapter.install(frame);
				}
				catch (IllegalAccessError e)
				{
					// Only happens when launched without the eawt exports (e.g. an IDE run config).
					// The adapter just syncs the extended state on fullscreen toggle.
					log.warn("macOS fullscreen listener unavailable, add to the VM options: " +
						"--add-exports=java.desktop/com.apple.eawt=ALL-UNNAMED " +
						"--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED");
				}
			}

			final Client apiClient = (Client) this.client;
			String frameTitle = title;
			if (apiClient.getLauncherDisplayName() != null && config.usernameInTitle())
			{
				frameTitle += " - " + apiClient.getLauncherDisplayName();
			}

			frame.setTitle(frameTitle);
			frame.setIconImages(Arrays.asList(ICON_128, ICON_16));
			frame.setLocationRelativeTo(frame.getOwner());
			frame.setResizable(true);

			frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
			if (OSType.getOSType() == OSType.MacOS)
			{
				// Change the default quit strategy to CLOSE_ALL_WINDOWS so that ctrl+q
				// triggers the listener below instead of exiting.
				Desktop.getDesktop()
					.setQuitStrategy(QuitStrategy.CLOSE_ALL_WINDOWS);
			}
			frame.addWindowListener(new WindowAdapter()
			{
				@Override
				public void windowClosing(WindowEvent event)
				{
					int result = JOptionPane.OK_OPTION;

					if (showWarningOnExit())
					{
						try
						{
							result = JOptionPane.showConfirmDialog(
								frame,
								"Are you sure you want to exit?", "Exit",
								JOptionPane.OK_CANCEL_OPTION,
								JOptionPane.QUESTION_MESSAGE);
						}
						catch (Exception e)
						{
							log.warn("Unexpected exception occurred while check for confirm required", e);
						}
					}

					if (result == JOptionPane.OK_OPTION)
					{
						shutdownClient();
					}
				}
			});
			frame.addComponentListener(new ComponentAdapter()
			{
				@Override
				public void componentResized(ComponentEvent e)
				{
					windowBoundsChanged();
				}

				@Override
				public void componentMoved(ComponentEvent e)
				{
					windowBoundsChanged();
				}
			});

			content = new JPanel();
			content.setLayout(new Layout());

			clientPanel = new ClientPanel(client);
			// ===================== [MICROBOT 本地改动] 日志框拿到 ConfigManager =====================
			// 右键菜单里的显示开关要能存进配置。
			// ===================== [MICROBOT 本地改动 END] =====================
			consolePanel = new LogConsolePanel(configManager);
			clientPanel.setConsole(consolePanel);
			clientPanel.setConsoleVisible(false);
			consoleVisible = false;
			initializeConsoleLogging();
			content.add(clientPanel);

			sidebar = new JTabbedPane(JTabbedPane.RIGHT);
			sidebar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			sidebar.setOpaque(true);
			sidebar.putClientProperty(FlatClientProperties.STYLE, "tabInsets: 2,5,2,5; variableSize: true; deselectable: true; tabHeight: 26");
			sidebar.setSelectedIndex(-1);
			sidebar.addChangeListener(ev ->
			{
				NavigationButton oldSelectedTab = selectedTab;
				NavigationButton newSelectedTab;

				int index = sidebar.getSelectedIndex();
				if (index < 0)
				{
					newSelectedTab = null;
				}
				else
				{
					// maybe just include a map component -> navbtn?
					newSelectedTab = Iterables.get(sidebarEntries, index);
				}

				if (oldSelectedTab == newSelectedTab)
				{
					return;
				}

				selectedTab = newSelectedTab;

				if (!updatingNavigation)
				{
					saveSidebarTab();
				}

				if (sidebar.isVisible())
				{
					pushHistory();

					if (oldSelectedTab != null)
					{
						SwingUtil.deactivate(oldSelectedTab.getPanel());
					}
					if (newSelectedTab != null)
					{
						SwingUtil.activate(newSelectedTab.getPanel());
					}

					if (newSelectedTab == null)
					{
						giveClientFocus();
					}
				}
			});
			sidebar.addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					if (e.getButton() == MouseEvent.BUTTON3)
					{
						int index = 0;
						for (var navBtn : sidebarEntries)
						{
							Rectangle bounds = sidebar.getBoundsAt(index++);
							if (bounds != null && bounds.contains(e.getX(), e.getY()))
							{
								if (navBtn.getPopup() != null)
								{
									var menu = new JPopupMenu();
									navBtn.getPopup().forEach((name, cb) ->
									{
										var menuItem = new JMenuItem(name);
										menuItem.addActionListener(ev -> cb.run());
										menu.add(menuItem);
									});
									menu.show(sidebar, e.getX(), e.getY());
								}
								return;
							}
						}
					}
				}
			});

			content.add(sidebar);

			frame.setContentPane(content);

			// Add key listener
			keyListeners = List.of(
				new HotkeyListener(config::sidebarToggleKey)
				{
					@Override
					public void hotkeyPressed()
					{
						toggleSidebar();
					}
				},
				new HotkeyListener(config::panelToggleKey)
				{
					@Override
					public void hotkeyPressed()
					{
						togglePluginPanel();
					}
				});
			KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this::dispatchWindowKeyEvent);

			frame.addWindowFocusListener(new WindowFocusListener()
			{
				@Override
				public void windowGainedFocus(WindowEvent e)
				{
				}

				@Override
				public void windowLostFocus(WindowEvent e)
				{
					for (KeyListener keyListener : keyListeners)
					{
						keyListener.focusLost();
					}
				}
			});

			// Add mouse listener
			final MouseListener mouseListener = new MouseAdapter()
			{
				@Override
				public MouseEvent mousePressed(MouseEvent mouseEvent)
				{
					if (SwingUtilities.isLeftMouseButton(mouseEvent) && sidebarButtonPosition.contains(mouseEvent.getPoint()))
					{
						SwingUtilities.invokeLater(ClientUI.this::toggleSidebar);
						mouseEvent.consume();
					}

					return mouseEvent;
				}
			};
			mouseManager.registerMouseListener(mouseListener);

			// Decorate window with custom chrome and titlebar if needed
			withTitleBar = config.enableCustomChrome();
			toolbarPanel = new ClientToolbarPanel(!withTitleBar);
			consoleIconClosed = createConsoleIcon(false);
			consoleIconOpen = createConsoleIcon(true);
			consoleToggleButton = toolbarPanel.add(
				NavigationButton.builder()
					.priority(95)
					.icon(consoleIconClosed)
					.tooltip("Show console")
					.onClick(this::toggleConsole)
					.popup(Map.of("Clear", () -> consolePanel.clear()))
					.build(), false);
			updateConsoleToggleButton();

			sidebarOpenIcon = ImageUtil.loadImageResource(ClientUI.class, withTitleBar ? "open.png" : "open_rs.png");
			sidebarCloseIcon = ImageUtil.flipImage(sidebarOpenIcon, true, false);

			if (withTitleBar)
			{
				JMenuBar menuBar = new JMenuBar();
				menuBar.add(Box.createGlue());
				menuBar.add(toolbarPanel);
				frame.setJMenuBar(menuBar);

				JRootPane rp = frame.getRootPane();
				if (FlatNativeWindowBorder.isSupported())
				{
					rp.putClientProperty(FlatClientProperties.USE_WINDOW_DECORATIONS, true);
				}
				else if (OSType.getOSType() == OSType.MacOS && SystemInfo.isMacFullWindowContentSupported)
				{
					rp.putClientProperty("apple.awt.fullWindowContent", true);
					rp.putClientProperty("apple.awt.transparentTitleBar", true);

					// use our own title if supported.
					// the native title color is determined by the application appearance,
					// which will lead to grey text on a black background if the appearance
					// is light mode.
					if (Runtime.version().feature() >= 17)
					{
						rp.putClientProperty("apple.awt.windowTitleVisible", false);
						rp.putClientProperty(RuneLiteRootPaneUI.PROP_RUNELITE_TITLEBAR, true); // enable titlebar before adjusting visibility props
						rp.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_CLOSE, false);
						rp.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_MAXIMIZE, false);
						rp.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICONIFFY, false);
						rp.putClientProperty(FlatClientProperties.TITLE_BAR_SHOW_ICON, false);
					}

					menuBar.setBorder(new EmptyBorder(3, 70, 3, 10));
				}
				else
				{
					if (OSType.getOSType() == OSType.Linux)
					{
						// FlatLaf explicitly checks this property when checking for custom window decorations on Linux
						JDialog.setDefaultLookAndFeelDecorated(true);
						JFrame.setDefaultLookAndFeelDecorated(true);
					}

					frame.setUndecorated(true);
					rp.setWindowDecorationStyle(JRootPane.FRAME);
				}

				frame.addWindowStateListener(_ev -> applyCustomChromeBorder());
				applyCustomChromeBorder();

				sidebarNavBtn = toolbarPanel.add(NavigationButton
					.builder()
					.priority(100)
					.icon(sidebarCloseIcon)
					.tooltip("Close sidebar")
					.onClick(this::toggleSidebar)
					.build(), false);
			}
			else
			{
				sidebar.putClientProperty(
					FlatClientProperties.TABBED_PANE_TRAILING_COMPONENT,
					toolbarPanel.createSidebarPanel());
			}

			questIconOn = net.runelite.client.plugins.microbot.questhelper.tools.Icon.QUEST_ICON_ON.getImage();
			questIconOff = net.runelite.client.plugins.microbot.questhelper.tools.Icon.QUEST_ICON_OFF.getImage();
			questHelperNavBtn = toolbarPanel.add(
				NavigationButton.builder()
					.icon(isQuestHelperEnabled() ? questIconOn : questIconOff)
					.tooltip(isQuestHelperEnabled() ? "Disable 'Semi-Auto' Questing" : "Enable 'Semi-Auto' Questing")
					.onClick(() ->
					{
						boolean isEnabled = isQuestHelperEnabled();
						configManager.setConfiguration(QuestHelperConfig.QUEST_HELPER_GROUP, "TurnOn", !isEnabled);
						questHelperNavBtn.setIcon(new ImageIcon(!isEnabled ? questIconOn : questIconOff ));
						questHelperNavBtn.setToolTipText(!isEnabled ? "Disable 'Semi-Auto' Questing" : "Enable 'Semi-Auto' Questing");
						if (isEnabled) Rs2Walker.clearWalkingRoute("client-ui:quest-helper-toggle-disable");
					})
					.build(), false
			);

			// Update config
			updateFrameConfig(false);

			// Reopen the console if it was open when the client was last closed
			if (configManager.getConfiguration(CONFIG_GROUP, CONFIG_CLIENT_CONSOLE_OPEN, Boolean.class) == Boolean.TRUE)
			{
				setConsoleVisible(true);
			}

			// Close sidebar if the config closed state is set
			if (configManager.getConfiguration(CONFIG_GROUP, CONFIG_CLIENT_SIDEBAR_CLOSED, Boolean.class) == Boolean.TRUE)
			{
				toggleSidebar(false, true);
			}
		});
	}

	private void applyCustomChromeBorder()
	{
		content.setBorder((frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH
			? null
			: new MatteBorder(4, 4, 4, 4, ColorScheme.DARKER_GRAY_COLOR));
	}

	public void show()
	{
		logGraphicsEnvironment();

		SwingUtilities.invokeLater(() ->
		{
			// Layout frame
			frame.pack();

			// Create tray icon (needs to be created after frame is packed)
			if (config.enableTrayIcon())
			{
				trayIcon = createTrayIcon(ICON_16, title, frame);
			}

			// Move frame around (needs to be done after frame is packed)
			boolean appliedSize = false;
			if (config.rememberScreenBounds() && !safeMode)
			{
				appliedSize = restoreClientBoundsConfig();
				if (appliedSize)
				{
					// Adjust for insets before performing display test
					Insets insets = frame.getInsets();
					Rectangle clientBounds = frame.getBounds();

					clientBounds = new Rectangle(
						clientBounds.x + insets.left,
						clientBounds.y + insets.top,
						clientBounds.width - (insets.left + insets.right),
						clientBounds.height - (insets.top + insets.bottom)
					);

					// Check that the bounds are contained inside a valid display
					GraphicsConfiguration gc = findDisplayFromBounds(clientBounds);
					if (gc == null)
					{
						log.info("Reset client position. Client bounds: {}x{}x{}x{}",
							clientBounds.x, clientBounds.y, clientBounds.width, clientBounds.height);
						// Reset the position, but not the size
						frame.setLocationRelativeTo(frame.getOwner());
					}
				}

				if (configManager.getConfiguration(CONFIG_GROUP, CONFIG_CLIENT_MAXIMIZED) != null)
				{
					frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
					// According to the documentation of JFrame#setExtendedState, if the frame isn't visible, a window
					// state change event isn't guaranteed to be fired. Since RuneLite's custom chrome borders rely on a
					// state change listener, borders need to be applied manually when maximizing prior to setVisible
					applyCustomChromeBorder();
				}
			}

			if (!appliedSize)
			{
				applyGameSize(true);
				frame.setLocationRelativeTo(frame.getOwner());
			}

			// Show frame
			frame.setVisible(true);
			// ===================== [MICROBOT 本地改动] --index 自定义窗口标题 =====================
			// 自定义标题必须在窗口显示之后才轮询设置，不能放在显示之前：
			// 游戏可能尚未加载成功甚至加载失败，那样标题会先一步显示出来骗人。
			// 上游更新后若此处冲突，保留这三行即可。
			// ===================== [MICROBOT 本地改动 END] =====================
			if (windowTitleOverridden)
			{
				scheduleWindowTitleOnLoggedIn();
			}
			// On macos setResizable needs to be called after setVisible
			frame.setResizable(!config.lockWindowSize());
			frame.toFront();
			requestFocus();
			log.debug("Showing frame {}", frame);
			frame.revalidateMinimumSize();
			// this must run after the native window border is installed on the window
			frame.updateContainsInScreen();
			sidebarTabRestored = true;
		});

		// Show out of date dialog if needed
		if (client != null && !(client instanceof Client))
		{
			SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame,
				"RuneLite has not yet been updated to work with the latest\n"
					+ "game update, it will work with reduced functionality until then.",
				"RuneLite is outdated", INFORMATION_MESSAGE));
		}

		final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024L / 1024L);
		if (maxMemory < minMemoryLimit)
		{
			SwingUtilities.invokeLater(() ->
			{
				JEditorPane ep = new JEditorPane("text/html",
					"Your Java memory limit is " + maxMemory + "mb, which is lower than the recommended " + recommendedMemoryLimit + "mb.<br>" +
						"This can cause instability, and it is recommended you remove or increase this limit.<br>" +
						"Join <a href=\"" + RuneLiteProperties.getDiscordInvite() + "\">Discord</a> for assistance."
				);
				ep.addHyperlinkListener(e ->
				{
					if (e.getEventType().equals(HyperlinkEvent.EventType.ACTIVATED))
					{
						LinkBrowser.browse(e.getURL().toString());
					}
				});
				ep.setEditable(false);
				ep.setOpaque(false);
				JOptionPane.showMessageDialog(frame,
					ep, "Max memory limit low", JOptionPane.WARNING_MESSAGE);
			});
		}
	}

	// ===================== [MICROBOT 本地改动] --index 自定义窗口标题 =====================
	/**
	 * 等游戏引擎启动后再应用 --index 自定义窗口标题。
	 *
	 * <p>若超时（默认 60 秒）仍未启动，则放弃并保持默认标题。
	 * 该方法必须在 show() 中 frame.setVisible(true) 之后调用一次。
	 *
	 * <p>用 ClientThread.invokeLater(BooleanSupplier) 轮询游戏引擎状态：
	 * 返回 false 会在下一个 client tick 重新执行，返回 true 表示结束。
	 */
	private void scheduleWindowTitleOnLoggedIn()
	{
		final long startTime = System.currentTimeMillis();
		final long TIMEOUT_MS = 60_000L;

		clientThreadProvider.get().invokeLater(() ->
		{
			// 已经应用过则停止轮询
			if (windowTitleOverrideApplied)
			{
				return true;
			}
			// 超时则放弃，保持默认标题
			if (System.currentTimeMillis() - startTime > TIMEOUT_MS)
			{
				log.debug("Timed out waiting for game engine to start; keeping default window title");
				return true;
			}
			// client 字段类型为 Component，需强转为 Client 才能调用 getGameState()
			// 引擎活着就算加载成功：UNKNOWN/STARTING 表示引擎尚未启动，继续等待；
			// 其他状态（LOGIN_SCREEN 及之后）都认为加载成功，可以应用自定义标题。
			final Client gameClient = (Client) client;
			if (gameClient == null || gameClient.getGameState() == GameState.UNKNOWN || gameClient.getGameState() == GameState.STARTING)
			{
				return false; // 引擎还没启动，下一 tick 再试
			}
			// 切回 EDT 线程设置窗口标题（setTitle 是 Swing 调用）
			SwingUtilities.invokeLater(() ->
			{
				if (!windowTitleOverrideApplied)
				{
					windowTitleOverrideApplied = true;
					frame.setTitle(windowTitleOverride);
					log.debug("Applied window title override after game engine loaded: {}", windowTitleOverride);
				}
			});
			return true;
		});
	}
	// ===================== [MICROBOT 本地改动 END] =====================

	private boolean dispatchWindowKeyEvent(KeyEvent ev)
	{
		if (!frame.isFocused())
		{
			return false;
		}

		for (var listener : keyListeners)
		{
			switch (ev.getID())
			{
				case KeyEvent.KEY_TYPED:
					listener.keyTyped(ev);
					break;
				case KeyEvent.KEY_PRESSED:
					listener.keyPressed(ev);
					break;
				case KeyEvent.KEY_RELEASED:
					listener.keyReleased(ev);
					break;
			}

			if (ev.isConsumed())
			{
				return true;
			}
		}

		return false;
	}

	private void logGraphicsEnvironment()
	{
		GraphicsEnvironment graphicsEnvironment = GraphicsEnvironment.getLocalGraphicsEnvironment();
		for (GraphicsDevice graphicsDevice : graphicsEnvironment.getScreenDevices())
		{
			GraphicsConfiguration configuration = graphicsDevice.getDefaultConfiguration();
			log.debug("Graphics device {}: bounds {} transform: {}", graphicsDevice, configuration.getBounds(), configuration.getDefaultTransform());
		}
	}

	private GraphicsConfiguration findDisplayFromBounds(final Rectangle bounds)
	{
		GraphicsDevice[] gds = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();

		for (GraphicsDevice gd : gds)
		{
			GraphicsConfiguration gc = gd.getDefaultConfiguration();

			final Rectangle displayBounds = gc.getBounds();
			if (displayBounds.contains(bounds))
			{
				return gc;
			}
		}

		return null;
	}

	private boolean showWarningOnExit()
	{
		if (config.warningOnExit() == WarningOnExit.ALWAYS)
		{
			return true;
		}

		if (config.warningOnExit() == WarningOnExit.LOGGED_IN)
		{
			return ((Client) client).getGameState() != GameState.LOGIN_SCREEN;
		}

		return false;
	}

	private void shutdownClient()
	{
		saveClientBoundsConfig();
		ClientShutdown csev = new ClientShutdown();
		eventBus.post(csev);
		new Thread(() ->
		{
			csev.waitForAllConsumers(Duration.ofSeconds(10));

			// The client can call System.exit when it's done shutting down
			// if it doesn't though, we want to exit anyway, so race it
			((Client) client).stopNow();

			try
			{
				Thread.sleep(1000);
			}
			catch (InterruptedException ignored)
			{
			}
			System.exit(0);
		}, "RuneLite Shutdown").start();
	}

	/**
	 * Paint this component to target graphics
	 *
	 * @param graphics the graphics
	 */
	public void paint(final Graphics graphics)
	{
		assert SwingUtilities.isEventDispatchThread() : "paint must be called on EDT";
		frame.paint(graphics);
	}

	/**
	 * Gets component width.
	 *
	 * @return the width
	 */
	public int getWidth()
	{
		return frame.getWidth();
	}

	/**
	 * Gets component height.
	 *
	 * @return the height
	 */
	public int getHeight()
	{
		return frame.getHeight();
	}

	/**
	 * Returns true if this component has focus.
	 *
	 * @return true if component has focus
	 */
	public boolean isFocused()
	{
		return frame.isFocused();
	}

	/**
	 * Request focus on this component and then on client component
	 */
	public void requestFocus()
	{
		switch (OSType.getOSType())
		{
			case MacOS:
				// On macOS Component::requestFocus doesn't cause the taskbar icon to bounce, so use
				// Taskbar.requestUserAttention
				Taskbar.getTaskbar().requestUserAttention(true, true);
				break;
			default:
				frame.requestFocus();
		}

		giveClientFocus();
	}

	/**
	 * Attempt to forcibly bring the client frame to front
	 */
	public void forceFocus()
	{
		switch (OSType.getOSType())
		{
			case MacOS:
				Desktop.getDesktop().requestForeground(true);
				frame.setState(Frame.NORMAL);
				break;
			case Windows:
				WinUtil.requestForeground(frame);
				break;
			default:
				frame.requestFocus();
				break;
		}

		giveClientFocus();
	}

	/**
	 * Request user attention to the window (flash the taskbar)
	 */
	public void flashTaskbar()
	{
		if (!Taskbar.isTaskbarSupported())
		{
			log.debug("Taskbar is not supported on this platform");
			return;
		}

		Taskbar taskbar = Taskbar.getTaskbar();
		if (taskbar.isSupported(Taskbar.Feature.USER_ATTENTION_WINDOW))
		{
			taskbar.requestWindowUserAttention(frame);
		}
		else
		{
			log.debug("USER_ATTENTION_WINDOW is not supported");
		}
	}

	/**
	 * Returns current cursor set on game container
	 * @return awt cursor
	 */
	public Cursor getCurrentCursor()
	{
		return content.getCursor();
	}

	/**
	 * Returns current custom cursor or default system cursor if cursor is not set
	 * @return awt cursor
	 */
	public Cursor getDefaultCursor()
	{
		return defaultCursor != null ? defaultCursor : Cursor.getDefaultCursor();
	}

	/**
	 * Changes cursor for client window. Requires ${@link ClientUI#init()} to be called first.
	 * FIXME: This is working properly only on Windows, Linux and Mac are displaying cursor incorrectly
	 * @param image cursor image
	 * @param name  cursor name
	 */
	public void setCursor(final BufferedImage image, final String name)
	{
		if (content == null)
		{
			return;
		}

		final Point hotspot = new Point(0, 0);
		final Cursor cursorAwt = Toolkit.getDefaultToolkit().createCustomCursor(image, hotspot, name);
		defaultCursor = cursorAwt;
		setCursor(cursorAwt);
	}

	/**
	 * Changes cursor for client window. Requires ${@link ClientUI#init()} to be called first.
	 * @param cursor awt cursor
	 */
	public void setCursor(final Cursor cursor)
	{
		content.setCursor(cursor);
	}

	/**
	 * Resets client window cursor to default one.
	 * @see ClientUI#setCursor(BufferedImage, String)
	 */
	public void resetCursor()
	{
		if (content == null)
		{
			return;
		}

		defaultCursor = null;
		content.setCursor(Cursor.getDefaultCursor());
	}

	/**
	 * Get offset of game canvas in game window
	 *
	 * @return game canvas offset
	 */
	public Point getCanvasOffset()
	{
		final Canvas canvas = ((Client) client).getCanvas();
		if (canvas != null)
		{
			return SwingUtilities.convertPoint(canvas, 0, 0, frame);
		}

		return new Point(0, 0);
	}

	public Insets getInsets()
	{
		return frame.getInsets();
	}

	/**
	 * Paint UI related overlays to target graphics
	 * @param graphics target graphics
	 */
	public void paintOverlays(final Graphics2D graphics)
	{
		if (withTitleBar)
		{
			return;
		}

		final Client client = (Client) this.client;
		final int x = client.getRealDimensions().width - sidebarOpenIcon.getWidth() - 5;

		// Offset sidebar button if resizable mode logout is visible
		final Widget logoutButton = client.getWidget(InterfaceID.ToplevelPreEoc.STONE10);
		final int y = logoutButton != null && !logoutButton.isHidden() && logoutButton.getParent() != null
			? logoutButton.getHeight() + logoutButton.getRelativeY()
			: 5;

		final BufferedImage image = sidebar.isVisible() ? sidebarCloseIcon : sidebarOpenIcon;

		final Rectangle sidebarButtonRange = new Rectangle(x - 15, 0, image.getWidth() + 25, client.getRealDimensions().height);
		final Point mousePosition = new Point(
			client.getMouseCanvasPosition().getX() + client.getViewportXOffset(),
			client.getMouseCanvasPosition().getY() + client.getViewportYOffset());

		if (sidebarButtonRange.contains(mousePosition.getX(), mousePosition.getY()))
		{
			graphics.drawImage(image, x, y, null);
		}

		// Update button dimensions
		sidebarButtonPosition.setBounds(x, y, image.getWidth(), image.getHeight());
	}

	public GraphicsConfiguration getGraphicsConfiguration()
	{
		return frame.getGraphicsConfiguration();
	}

	private void saveSidebarTab()
	{
		sidebarTabRestored = true;
		if (selectedTab == null)
		{
			configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_CLIENT_SIDEBAR_TAB);
		}
		else
		{
			configManager.setConfiguration(CONFIG_GROUP, CONFIG_CLIENT_SIDEBAR_TAB, selectedTab.getTooltip());
		}
	}

	void openPanel(NavigationButton navBtn, boolean showSidebar)
	{
		if (navBtn != null && !sidebarEntries.contains(navBtn))
		{
			return;
		}

		int index = navBtn == null ? -1 : sidebarEntries.headSet(navBtn).size();
		sidebar.setSelectedIndex(index);

		toggleSidebar(showSidebar, false);

		pushHistory();
	}

	NavigationButton findSidebarNavigation(String tooltip)
	{
		return sidebarEntries.stream()
			.filter(navBtn -> tooltip.equals(navBtn.getTooltip()))
			.findFirst()
			.orElse(null);
	}

	Rectangle getNavigationBounds(NavigationButton navBtn)
	{
		if (navBtn == null)
		{
			return null;
		}
		if (navBtn.getPanel() == null)
		{
			return toolbarPanel == null ? null : screenBounds(toolbarPanel.get(navBtn));
		}
		if (sidebar == null || !sidebar.isShowing() || !sidebarEntries.contains(navBtn))
		{
			return null;
		}
		int index = sidebarEntries.headSet(navBtn).size();
		if (index >= sidebar.getTabCount())
		{
			return null;
		}
		Rectangle bounds = sidebar.getBoundsAt(index);
		if (bounds == null || bounds.isEmpty())
		{
			return null;
		}
		Point origin = sidebar.getLocationOnScreen();
		Rectangle result = new Rectangle(bounds);
		result.translate(origin.x, origin.y);
		return result;
	}

	Rectangle getSidebarBounds()
	{
		return sidebar == null ? null : screenBounds(sidebar);
	}

	Rectangle getSidebarTabsBounds()
	{
		if (sidebar == null || !sidebar.isShowing())
		{
			return null;
		}
		Rectangle union = null;
		for (int i = 0; i < sidebar.getTabCount(); i++)
		{
			Rectangle bounds = sidebar.getBoundsAt(i);
			if (bounds != null && !bounds.isEmpty())
			{
				union = union == null ? new Rectangle(bounds) : union.union(bounds);
			}
		}
		if (union == null)
		{
			return null;
		}
		union = union.intersection(new Rectangle(0, 0, sidebar.getWidth(), sidebar.getHeight()));
		if (union.isEmpty())
		{
			return null;
		}
		Point origin = sidebar.getLocationOnScreen();
		union.translate(origin.x, origin.y);
		return union;
	}

	boolean isSidebarOpen()
	{
		return sidebar != null && sidebar.isVisible();
	}

	boolean isPanelOpen(NavigationButton navBtn)
	{
		return isSidebarOpen() && navBtn != null && selectedTab == navBtn;
	}

	private static Rectangle screenBounds(Component component)
	{
		if (component == null || !component.isShowing())
		{
			return null;
		}
		return new Rectangle(component.getLocationOnScreen(), component.getSize());
	}

	private void toggleSidebar()
	{
		toggleSidebar(!sidebar.isVisible(), true);
	}

	private void toggleSidebar(boolean open, boolean pushHistory)
	{
		if (sidebar.isVisible() == open)
		{
			return;
		}

		if (open)
		{
			configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_CLIENT_SIDEBAR_CLOSED);
		}
		else
		{
			configManager.setConfiguration(CONFIG_GROUP, CONFIG_CLIENT_SIDEBAR_CLOSED, true);
		}

		sidebar.setVisible(open);
		content.revalidate();

		if (pushHistory)
		{
			pushHistory();
		}

		if (selectedTab != null)
		{
			if (open)
			{
				SwingUtil.activate(selectedTab.getPanel());
			}
			else
			{
				SwingUtil.deactivate(selectedTab.getPanel());
			}
		}

		if (!open)
		{
			giveClientFocus();
		}

		if (sidebarNavBtn != null)
		{
			sidebarNavBtn.setIcon(new ImageIcon(open ? sidebarCloseIcon : sidebarOpenIcon));
			sidebarNavBtn.setToolTipText(open ? "Close sidebar" : "Open sidebar");
		}
	}

	private void togglePluginPanel()
	{
		if (!sidebar.isVisible() || sidebar.getSelectedIndex() < 0)
		{
			toggleSidebar(true, false);

			NavigationButton open = null;
			while (!selectedTabHistory.isEmpty())
			{
				HistoryEntry historyEntry = selectedTabHistory.removeLast();
				if (historyEntry.navBtn != null)
				{
					open = historyEntry.navBtn;
					break;
				}
			}

			if (open == null)
			{
				open = sidebarEntries.first();
			}

			openPanel(open, true);
		}
		else
		{
			sidebar.setSelectedIndex(-1);
		}
	}

	private void toggleConsole()
	{
		setConsoleVisible(!consoleVisible);
	}

	private void setConsoleVisible(boolean visible)
	{
		if (consolePanel == null || clientPanel == null || consoleVisible == visible)
		{
			return;
		}

		consoleVisible = visible;
		if (visible)
		{
			// ===================== [MICROBOT 本地改动] 记下显示前的窗口高度 =====================
			// 隐藏的时候要恢复到这个高度。
			// ===================== [MICROBOT 本地改动 END] =====================
			if (frame != null)
			{
				consolePreShowHeight = frame.getHeight();
			}
			configManager.setConfiguration(CONFIG_GROUP, CONFIG_CLIENT_CONSOLE_OPEN, true);
		}
		else
		{
			configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_CLIENT_CONSOLE_OPEN);
		}
		clientPanel.setConsoleVisible(visible);
		updateConsoleToggleButton();

		// ===================== [MICROBOT 本地改动] 隐藏 console 时把窗口缩回去 =====================
		// 隐藏的时候要把 content 的高度先压回去。
		//
		// 上游这里只做了 revalidate，没有恢复尺寸。而 Layout.layout() 里的
		//   innerHeight = Math.max(content.getHeight() - insetHeight, client.getMinimumSize().height)
		// 是只增不减的 —— content 的高度不先降下来，下一次布局会把它又撑回
		// 撑高后的值，窗口就永远缩不回去，画布还会被拉高变形。
		//
		// 隐藏时目标高度取「刚好装下游戏画布」的最小高度，
		// 也就是 console 占掉的那 160px 原样还回去。
		// ===================== [MICROBOT 本地改动 END] =====================
		if (!visible)
		{
			shrinkToClientHeight();

			// 布局是异步的：content.revalidate() 触发的布局可能在我们缩回去之后才跑，
			// 那次布局会用撑高后的 content 高度把窗口又顶回去。
			// 所以布局跑完之后再收一次。
			SwingUtilities.invokeLater(() ->
			{
				if (!consoleVisible)
				{
					shrinkToClientHeight();
				}
			});
		}

		if (content != null)
		{
			content.revalidate();
			content.repaint();
		}

		if (frame != null)
		{
			frame.revalidateMinimumSize();
		}
	}

	// ===================== [MICROBOT 本地改动] 隐藏 console 时把窗口缩回去 =====================
	// 隐藏的时候要把 content 的高度先压回去。
	//
	// 上游这里只做了 revalidate，没有恢复尺寸。而 Layout.layout() 里的
	//   innerHeight = Math.max(content.getHeight() - insetHeight, client.getMinimumSize().height)
	// 是只增不减的 —— content 的高度不先降下来，下一次布局会把它又撑回
	// 撑高后的值，窗口就永远缩不回去，画布还会被拉高变形。
	//
	// 隐藏时优先把窗口恢复到「显示 console 之前的高度」；
	// 没有记录时退回到「刚好装下游戏画布」的最小高度。
	// ===================== [MICROBOT 本地改动 END] =====================
	private Integer consolePreShowHeight;

	// ===================== [MICROBOT 本地改动] 隐藏 console 时把窗口缩回去 =====================
	/**
	 * 把窗口高度恢复到显示 console 之前的状态。
	 *
	 * <p>两件事都要做：
	 * <ol>
	 *   <li>把 content 的高度压回最小，否则 {@code Layout.layout()} 里的
	 *       {@code Math.max} 会把撑高后的高度保下来；</li>
	 *   <li>把窗口本身缩回去。</li>
	 * </ol>
	 */
	private void shrinkToClientHeight()
	{
		if (content == null || frame == null || clientPanel == null)
		{
			return;
		}

		Insets insets = content.getInsets();
		Dimension clientMin = clientPanel.getMinimumSize();
		int targetContentHeight = clientMin.height + insets.top + insets.bottom;

		// content 的高度必须先压回去
		content.setSize(content.getWidth(), targetContentHeight);
		content.setPreferredSize(content.getSize());

		frame.revalidateMinimumSize();

		// 最大化的时候不要去改窗口尺寸，但 content 的高度已经压回去了，
		// 画布不会变形。
		if ((frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH)
		{
			return;
		}

		Rectangle oldBounds = frame.getBounds();
		int targetHeight;

		if (consolePreShowHeight != null && consolePreShowHeight > 0)
		{
			// 恢复显示 console 之前的高度
			targetHeight = consolePreShowHeight;
		}
		else
		{
			// 没有记录就用「刚好装下画布」的最小高度
			Insets frameInsets = frame.getInsets();
			targetHeight = targetContentHeight + frameInsets.top + frameInsets.bottom;
		}

		frame.containedSetSize(new Dimension(oldBounds.width, targetHeight), oldBounds);

		// ===================== [MICROBOT 本地改动] 诊断日志 =====================
		// 临时用 log.info，方便在日志框里直接看到尺寸变化。
		log.info("[console] 隐藏日志框: clientMin={} content高 {} -> {}，窗口高 {} -> {}（目标 {}），最大化={}",
			clientMin, oldBounds.height, targetHeight, frame.getHeight(), frame.getHeight(), targetHeight,
			(frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH);
		// ===================== [MICROBOT 本地改动 END] =====================
	}
	// ===================== [MICROBOT 本地改动 END] =====================

	private void updateConsoleToggleButton()
	{
		if (consoleToggleButton == null)
		{
			return;
		}

		consoleToggleButton.setIcon(new ImageIcon(consoleVisible ? consoleIconOpen : consoleIconClosed));
		consoleToggleButton.setToolTipText(consoleVisible ? "Hide console" : "Show console");
	}


	private void initializeConsoleLogging()
	{
		if (consolePanel == null || consoleLogAppender != null)
		{
			return;
		}

		OutputStream consoleStream = consolePanel.createOutputStream();
		PrintStream originalOut = System.out;
		TeeOutputStream teeStream = new TeeOutputStream(consoleStream, originalOut);

		try
		{
			consolePrintStream = new PrintStream(teeStream, true, StandardCharsets.UTF_8);
		}
		catch (Exception ex)
		{
			consolePrintStream = new PrintStream(teeStream, true);
		}

		System.setOut(consolePrintStream);

		LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
		consoleLogAppender = ConsoleLogAppender.install(loggerContext, originalOut, consolePanel::append);

		// ===================== [MICROBOT 本地改动] 读回日志显示开关 =====================
		// 日志等级和类名默认都不显示，勾选状态存在配置里。
		// ===================== [MICROBOT 本地改动 END] =====================
		ConsoleLogAppender.setShowLevel(configManager.getConfiguration(CONFIG_GROUP,
			CONFIG_CLIENT_CONSOLE_SHOW_LEVEL, Boolean.class) == Boolean.TRUE);
		ConsoleLogAppender.setShowLogger(configManager.getConfiguration(CONFIG_GROUP,
			CONFIG_CLIENT_CONSOLE_SHOW_LOGGER, Boolean.class) == Boolean.TRUE);
	}

	private BufferedImage createConsoleIcon(boolean active)
	{
		BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = icon.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setColor(ColorScheme.DARK_GRAY_COLOR);
		graphics.fillRoundRect(1, 3, 14, 10, 3, 3);
		graphics.setColor(active ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		graphics.drawRoundRect(1, 3, 14, 10, 3, 3);
		graphics.setColor(ColorScheme.PROGRESS_COMPLETE_COLOR);
		graphics.drawLine(3, 7, 12, 7);
		graphics.drawLine(3, 10, 9, 10);
		graphics.dispose();
		return icon;
	}

	private void pushHistory()
	{
		selectedTabHistory.addLast(new HistoryEntry(sidebar.isVisible(), selectedTab));

		// we keep multiple history entries so you can open a panel, close it, open another, *remove* it, then resume the first open panel
		if (selectedTabHistory.size() > 4)
		{
			HistoryEntry ent = selectedTabHistory.removeFirst();
			// Try to always keep a panel in the history
			if (ent.navBtn != null && selectedTabHistory.stream().noneMatch(it -> it.navBtn != null))
			{
				selectedTabHistory.removeFirst();
				selectedTabHistory.addFirst(ent);
			}
		}
	}

	private void giveClientFocus()
	{
		final Canvas c = ((Client) client).getCanvas();
		if (c != null)
		{
			c.requestFocusInWindow();
		}
		else
		{
			client.requestFocusInWindow();
		}
	}

	private boolean isQuestHelperEnabled()
	{
		// Unset until the QuestHelper config is first registered (fresh profile); its declared default is true
		return !Boolean.FALSE.equals(configManager.getConfiguration(QuestHelperConfig.QUEST_HELPER_GROUP, "TurnOn", Boolean.class));
	}

	private void updateFrameConfig(boolean updateResizable)
	{
		if (frame == null)
		{
			return;
		}

		if (frame.getGraphicsConfiguration().getDevice().getFullScreenWindow() == null
			&& !safeMode)
		{
			frame.setOpacity(config.windowOpacity() / 100.0f);
		}

		// ===================== [MICROBOT 本地改动] --index 自定义窗口标题 =====================
		// 标题优先级：已应用的 override 标题 > 「标题 + 玩家名」 > 默认标题。
		// 上游原本只有后两种（if usernameInTitle / else）。
		// 这一段不能省：否则用户在设置面板改任何一项配置，标题都会被重置回默认。
		// 上游更新后若此处冲突，保留整个三分支结构即可。
		// ===================== [MICROBOT 本地改动 END] =====================
		if (windowTitleOverrideApplied)
		{
			frame.setTitle(windowTitleOverride);
		}
		else if (!windowTitleOverridden && config.usernameInTitle())
		{
			final Client client = (Client) this.client;
			final Player player = client.getLocalPlayer();

			String playerName = null;
			if (player != null && !Strings.isNullOrEmpty(player.getName()))
			{
				playerName = player.getName();
			}
			else if (client.getLauncherDisplayName() != null)
			{
				playerName = client.getLauncherDisplayName();
			}

			if (playerName != null)
			{
				frame.setTitle(title + " - " + playerName);
			}
		}
		else
		{
			frame.setTitle(title);
		}

		if (frame.isAlwaysOnTopSupported())
		{
			frame.setAlwaysOnTop(config.gameAlwaysOnTop());
		}

		if (updateResizable)
		{
			frame.setResizable(!config.lockWindowSize());
		}

		frame.setContainedInScreen(config.containInScreen());
		frame.updateContainsInScreen();

		if (!config.rememberScreenBounds())
		{
			configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_CLIENT_MAXIMIZED);
			configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_CLIENT_BOUNDS);
		}

		applyGameSize(false);
	}

	private void applyGameSize(boolean force)
	{
		if (client == null)
		{
			return;
		}

		// The upper bounds are defined by the applet's max size
		// The lower bounds are defined by the client's fixed size
		int width = Math.max(Math.min(config.gameSize().width, 7680), Constants.GAME_FIXED_WIDTH);
		int height = Math.max(Math.min(config.gameSize().height, 2160), Constants.GAME_FIXED_HEIGHT);
		final Dimension size = new Dimension(width, height);

		if (force || !size.equals(lastClientSize))
		{
			lastClientSize = size;
			((Layout) content.getLayout()).forceClientSize(width, height);
		}
	}

	private void windowBoundsChanged()
	{
		// Sometimes when maximizing windowMoved can be delivered before extendedState is updated, so defer
		// actually saving for some ms to reduce the likelyhood of this
		normalBoundsTimer.stop();
		if ((frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) == 0)
		{
			normalBoundsTimer.start();
		}
	}

	private void setLastNormalBounds()
	{
		if ((frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) == 0)
		{
			Insets insets = frame.getInsets();
			char mode;
			Dimension size;
			if (config.automaticResizeType() == ExpandResizeType.KEEP_GAME_SIZE)
			{
				mode = 'g';
				size = clientPanel.getSize();
			}
			else
			{
				mode = 'c';
				size = frame.getSize();
				size.width -= insets.left + insets.right;
				size.height -= insets.top + insets.bottom;
			}
			Point point = frame.getLocation();
			point.x += insets.left;
			point.y += insets.top;
			lastNormalBounds = point.x + ":" + point.y + ":" + size.width + ":" + size.height + ":" + mode;
		}
	}

	private void saveClientBoundsConfig()
	{
		if (lastNormalBounds != null)
		{
			configManager.setConfiguration(CONFIG_GROUP, CONFIG_CLIENT_BOUNDS, lastNormalBounds);
		}

		if ((frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) != 0)
		{
			// leave the previous bounds there, so when the client starts maximized it
			// can restore to the restored size from the previous run
			configManager.setConfiguration(CONFIG_GROUP, CONFIG_CLIENT_MAXIMIZED, true);
		}
		else
		{
			configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_CLIENT_MAXIMIZED);
		}
	}

	private boolean restoreClientBoundsConfig()
	{
		String str = configManager.getConfiguration(CONFIG_GROUP, CONFIG_CLIENT_BOUNDS);
		if (str == null)
		{
			return false;
		}

		try
		{
			String[] splitStr = str.split(":");
			int x = Integer.parseInt(splitStr[0]);
			int y = Integer.parseInt(splitStr[1]);
			int width = Integer.parseInt(splitStr[2]);
			int height = Integer.parseInt(splitStr[3]);
			String mode = null;
			if (splitStr.length > 4)
			{
				mode = splitStr[4];
			}

			Insets insets = frame.getInsets();

			if (mode != null)
			{
				// null mode means legacy exact frame bounds
				x -= insets.left;
				y -= insets.top;
			}

			frame.setLocation(x, y);

			if ("g".equals(mode))
			{
				((Layout) content.getLayout()).forceClientSize(width, height);
			}
			else
			{
				frame.setSize(width + insets.left + insets.right, height + insets.top + insets.bottom);
			}

			return true;
		}
		catch (RuntimeException ignored)
		{
			return false;
		}
	}

	private static void setupDefaults()
	{
		// Force heavy-weight popups/tooltips.
		// Prevents them from being obscured by the game applet.
		var tooltipManager = ToolTipManager.sharedInstance();
		tooltipManager.setLightWeightPopupEnabled(false);
		tooltipManager.setInitialDelay(300);
		tooltipManager.setDismissDelay(10_000);
		JPopupMenu.setDefaultLightWeightPopupEnabled(false);

		// Do not fill in background on repaint. Reduces flickering when
		// the applet is resized.
		System.setProperty("sun.awt.noerasebackground", "true");
	}

	@Nullable
	private static TrayIcon createTrayIcon(@Nonnull final Image icon, @Nonnull final String title, @Nonnull final Frame frame)
	{
		if (!SystemTray.isSupported())
		{
			return null;
		}

		final SystemTray systemTray = SystemTray.getSystemTray();
		final TrayIcon trayIcon = new TrayIcon(icon, title);
		trayIcon.setImageAutoSize(true);

		try
		{
			systemTray.add(trayIcon);
		}
		catch (AWTException ex)
		{
			log.debug("Unable to add system tray icon", ex);
			return trayIcon;
		}

		// Bring to front when tray icon is clicked
		trayIcon.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (OSType.getOSType() == OSType.MacOS && !frame.isFocused())
				{
					// On macOS, frame.setVisible(true) only restores focus when the visibility was previously false.
					// The frame's visibility is not set to false when the window loses focus, so we set it manually.
					// Additionally, in order to bring the window to the foreground,
					// frame.setVisible(true) calls CPlatformWindow::nativePushNSWindowToFront.
					// However, this native method is not called with activateIgnoringOtherApps:YES,
					// so any other active window will prevent our window from being brought to the front.
					// To work around this, use eawt requestForeground() via java.desktop.
					frame.setVisible(false);
					Desktop.getDesktop().requestForeground(true);
				}
				frame.setVisible(true);
				frame.setState(Frame.NORMAL); // Restore
			}
		});

		return trayIcon;
	}


	private static final class TeeOutputStream extends OutputStream
	{
		private final OutputStream primary;
		private final OutputStream secondary;

		private TeeOutputStream(OutputStream primary, OutputStream secondary)
		{
			this.primary = primary;
			this.secondary = secondary;
		}

		@Override
		public void write(int b) throws IOException
		{
			if (primary != null)
			{
				primary.write(b);
			}
			if (secondary != null)
			{
				secondary.write(b);
			}
		}

		@Override
		public void flush() throws IOException
		{
			if (primary != null)
			{
				primary.flush();
			}
			if (secondary != null)
			{
				secondary.flush();
			}
		}

		@Override
		public void close() throws IOException
		{
			flush();
		}
	}

	private class Layout implements LayoutManager2
	{
		private int prevState;
		private int previousContentWidth;
		private boolean doingLayout;

		@Override
		public void addLayoutComponent(String name, Component comp)
		{
		}

		@Override
		public void removeLayoutComponent(Component comp)
		{
		}

		@Override
		public void addLayoutComponent(Component comp, Object constraints)
		{
		}

		@Override
		public Dimension preferredLayoutSize(Container content)
		{
			synchronized (content.getTreeLock())
			{
				return size(content, Component::getPreferredSize);
			}
		}

		@Override
		public Dimension minimumLayoutSize(Container content)
		{
			synchronized (content.getTreeLock())
			{
				return size(content, c ->
				{
					if (c == content.getComponent(1))
					{
						// the sidebar always is given it's pref width exactly
						return new Dimension(c.getPreferredSize().width, c.getMinimumSize().height);
					}

					return c.getMinimumSize();
				});
			}
		}

		void forceClientSize(int width, int height)
		{
			Component client = content.getComponent(0);
			client.setSize(width, height);
			// must adjust content height since the client height is derived from the content height
			Insets insets = content.getInsets();
			content.setSize(content.getWidth(), height + insets.top + insets.bottom);
			layout(content, true);
		}

		@Override
		public void layoutContainer(Container content)
		{
			layout(content, false);
		}

		private void layout(Container content, boolean forceSizingClient)
		{
			int changed = prevState ^ frame.getExtendedState();
			prevState = frame.getExtendedState();

			Insets insets = content.getInsets();
			int insetWidth = insets.left + insets.right;
			int insetHeight = insets.top + insets.bottom;

			Component client = content.getComponent(0);
			Component sidebar = content.getComponent(1);

			log.trace("starting layout  - content={} client={} sidebar={} frame={} prevContent={}", content.getWidth(), client.getWidth(), sidebar.getWidth(), frame.getWidth(), previousContentWidth);

			// adjust sidebar height first, as changing it's height can make it's min width change too
			int innerHeight = Math.max(content.getHeight() - insetHeight, Math.max(
				client.getMinimumSize().height,
				sidebar.getMinimumSize().height));
			{
				sidebar.setSize(sidebar.getWidth(), innerHeight);
			}

			Dimension minimumSize = minimumLayoutSize(content);

			int contentWidth = Math.max(minimumSize.width, content.getWidth()) - insetWidth;
			if (previousContentWidth <= 0)
			{
				previousContentWidth = contentWidth;
			}

			final int clientMinWidth = client.getMinimumSize().width;
			int clientWidth = Math.max(client.getWidth(), clientMinWidth);
			int sidebarWidth = sidebar.isVisible()
				? sidebar.getPreferredSize().width
				: 0;

			boolean keepGameSize = (frame.getExtendedState() & Frame.MAXIMIZED_HORIZ) == 0
				&& (config.automaticResizeType() == ExpandResizeType.KEEP_GAME_SIZE || forceSizingClient);

			if (keepGameSize)
			{
				// adjust client for window resizes
				clientWidth = Math.max(clientMinWidth, clientWidth + content.getWidth() - insetWidth - previousContentWidth);
			}
			else
			{
				// fit client to window
				clientWidth = Math.max(clientMinWidth, contentWidth - sidebarWidth);
			}

			// fit window to client
			int width = clientWidth + sidebarWidth;

			content.setSize(width + insetWidth, innerHeight + insetHeight);
			content.setPreferredSize(content.getSize());
			previousContentWidth = width;

			client.setBounds(insets.left, insets.top, clientWidth, innerHeight);
			sidebar.setBounds(insets.left + clientWidth, insets.top, sidebarWidth, innerHeight);

			Rectangle oldBounds = frame.getBounds();
			frame.revalidateMinimumSize();
			if ((OSType.getOSType() != OSType.Windows || (changed & Frame.MAXIMIZED_BOTH) == 0)
				&& !frame.getPreferredSize().equals(oldBounds.getSize()))
			{
				frame.containedSetSize(frame.getPreferredSize(), oldBounds);
				if (!doingLayout)
				{
					try
					{
						// synchronously layout the frame and it's root pane so we don't get re-layouted
						// with the root pane's old size before it gets layouted automatically. This can
						// call us recursively if we calculate size wrong, so don't do that.
						doingLayout = true;
						frame.validate();
					}
					finally
					{
						doingLayout = false;
					}
				}
			}

			log.trace("finishing layout - content={} client={} sidebar={} frame={}", content.getWidth(), client.getWidth(), sidebar.getWidth(), frame.getWidth());
		}

		private Dimension size(Container content, Function<Component, Dimension> sizer)
		{
			Dimension out = new Dimension(0, 0);
			for (int i = 0; i < content.getComponentCount(); i++)
			{
				Component child = content.getComponent(i);
				if (child.isVisible())
				{
					Dimension dim = sizer.apply(child);
					out.width += dim.width;
					out.height = Math.max(out.height, dim.height);
				}
			}

			Insets is = content.getInsets();
			out.width += is.left + is.right;
			out.height += is.top + is.bottom;

			return out;
		}

		@Override
		public Dimension maximumLayoutSize(Container content)
		{
			return size(content, Component::getMaximumSize);
		}

		@Override
		public float getLayoutAlignmentX(Container target)
		{
			return 0;
		}

		@Override
		public float getLayoutAlignmentY(Container target)
		{
			return 0;
		}

		@Override
		public void invalidateLayout(Container target)
		{
		}
	}
}
