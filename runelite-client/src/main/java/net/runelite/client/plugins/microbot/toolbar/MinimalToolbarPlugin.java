package net.runelite.client.plugins.microbot.toolbar;

import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 精简右上角工具栏。
 *
 * <p>把窗口右上角工具栏里的按钮清掉，只留下三个：
 *
 * <ul>
 *     <li>禁用/启用输入（Enable Input / Disable Input）</li>
 *     <li>显示/隐藏控制台日志（Show console / Hide console）</li>
 *     <li>折叠/展开侧边栏（Close sidebar）</li>
 * </ul>
 *
 * <p>左侧那一列插件图标不受影响。
 *
 * <h2>实现说明</h2>
 *
 * <p>右上角的按钮都放在 {@code net.runelite.client.ui.ClientToolbarPanel} 里。
 * 这个类是包私有的，而且它自己的 {@code add} / {@code remove} / {@code get}
 * 也是包私有的，插件所在的包访问不到，所以这里用反射操作。
 *
 * <p>为什么不用 {@code setVisible(false)}：它用的 {@code DynamicGridLayout}
 * 计算尺寸时<b>不区分组件是否可见</b>，隐藏的按钮照样占位置，
 * 工具栏里会留下一段空白。所以这里是把按钮真的从容器里摘掉。
 *
 * <p>恢复的时候走公开的 {@link ClientToolbar#addNavigation(NavigationButton)}：
 * 摘按钮的时候已经把内部表里的记录一并清掉了，所以重新添加时会生成新的按钮实例，
 * 位置按 priority 排回去，功能也完全正常。
 */
@Slf4j
@PluginDescriptor(
		name = PluginDescriptor.Mocrosoft + "Minimal Toolbar",
		description = "Hides the toolbar icons except the input toggle, the console toggle and the sidebar toggle.",
		tags = {"microbot", "toolbar"},
		enabledByDefault = true
)
public class MinimalToolbarPlugin extends Plugin
{
	/**
	 * 保留的按钮：tooltip 里含有这些关键字（小写匹配）就留下。
	 *
	 * <p>用关键字而不是全等匹配，是因为 console 那个按钮的 tooltip
	 * 会在 "Show console" 和 "Hide console" 之间来回变。
	 */
	private static final List<String> KEEP_KEYWORDS = List.of("input", "console", "sidebar");

	/**
	 * 扫描间隔（毫秒）。
	 *
	 * <p>按钮不是一次加完的：插件的加载顺序不确定，而且「禁用/启用输入」
	 * 那个按钮在切换状态时是移除旧的、加入一个新的。所以要持续扫描。
	 */
	private static final long SCAN_INTERVAL_MS = 500;

	@Inject
	private ClientToolbar clientToolbar;

	/**
	 * 扫描定时器。
	 *
	 * <p>刻意不写成 final 字段：插件实例在禁用之后会被复用，
	 * 而 shutDown 会终止这个执行器。复用同一个实例的话，
	 * 再次启动时往一个已终止的执行器提交任务会被拒绝。
	 */
	private ScheduledExecutorService executor;

	/**
	 * 被本插件摘掉的按钮。禁用插件时按这个列表放回去。
	 */
	private final Set<NavigationButton> hidden = new LinkedHashSet<>();

	@Override
	protected void startUp()
	{
		executor = Executors.newSingleThreadScheduledExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "MinimalToolbarPlugin");
			thread.setDaemon(true);
			return thread;
		});
		executor.scheduleWithFixedDelay(
				this::scheduleApply, 0, SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);
	}

	@Override
	protected void shutDown()
	{
		if (executor != null)
		{
			executor.shutdownNow();
			executor = null;
		}

		// 禁用插件后把按钮放回去
		SwingUtilities.invokeLater(this::restore);
	}

	/**
	 * 插件启停会增删工具栏按钮，立刻再扫一遍，不用等下一个周期。
	 */
	@Subscribe
	public void onPluginChanged(PluginChanged event)
	{
		scheduleApply();
	}

	/**
	 * 把扫描安排到 EDT 上执行。
	 *
	 * <p>反射拿到的都是 Swing 组件，必须在 EDT 上操作。
	 */
	private void scheduleApply()
	{
		try
		{
			SwingUtilities.invokeLater(this::apply);
		}
		catch (Exception e)
		{
			log.debug("MinimalToolbar: 安排扫描失败", e);
		}
	}

	/**
	 * 扫描工具栏，把不该出现的按钮摘掉。
	 */
	private void apply()
	{
		try
		{
			Container panel = ToolbarAccess.findPanel();
			if (panel == null)
			{
				return;
			}
			applyTo(panel);
		}
		catch (Exception e)
		{
			// 这里失败的话插件就完全不起作用，所以要留个明显的日志。
			log.warn("MinimalToolbar: 扫描工具栏失败", e);
		}
	}

	/**
	 * 对一个工具栏面板执行隐藏。
	 *
	 * <p>拆出来是为了能单独测，不用真的建一个客户端窗口。
	 *
	 * @param panel 工具栏面板
	 */
	void applyTo(Container panel)
	{
		try
		{
			Map<NavigationButton, Component> entries = ToolbarAccess.entries(panel);
			if (entries == null)
			{
				return;
			}

			// 先收集再删，边遍历边改会抛 ConcurrentModificationException
			List<NavigationButton> toHide = new ArrayList<>();
			for (NavigationButton button : new ArrayList<>(entries.keySet()))
			{
				if (button == null)
				{
					continue;
				}
				if (!shouldKeep(button.getTooltip()))
				{
					toHide.add(button);
				}
			}

			if (toHide.isEmpty())
			{
				return;
			}

			for (NavigationButton button : toHide)
			{
				Component component = entries.remove(button);
				if (component != null)
				{
					Container parent = component.getParent();
					if (parent != null)
					{
						parent.remove(component);
					}
				}
				hidden.add(button);
			}

			// 摘掉按钮之后要重算一次尺寸，否则工具栏会留着原来的宽度
			panel.setMaximumSize(panel.getPreferredSize());
			panel.revalidate();
			panel.repaint();
		}
		catch (Exception e)
		{
			log.warn("MinimalToolbar: 处理工具栏失败", e);
		}
	}

	/**
	 * 把被摘掉的按钮放回去。
	 */
	void restore()
	{
		if (hidden.isEmpty())
		{
			return;
		}

		for (NavigationButton button : hidden)
		{
			try
			{
				// 内部表里对应的记录已经被清掉了，这里会生成一个新的按钮实例，
				// 位置按 priority 排回去。
				clientToolbar.addNavigation(button);
			}
			catch (Exception e)
			{
				log.warn("MinimalToolbar: 恢复按钮失败", e);
			}
		}
		hidden.clear();
	}

	/**
	 * 判断一个按钮要不要保留。
	 *
	 * @param tooltip 按钮的 tooltip
	 * @return true 表示保留
	 */
	static boolean shouldKeep(String tooltip)
	{
		if (tooltip == null)
		{
			return false;
		}

		String text = tooltip.toLowerCase(Locale.ROOT);
		for (String keyword : KEEP_KEYWORDS)
		{
			if (text.contains(keyword))
			{
				return true;
			}
		}
		return false;
	}
}
