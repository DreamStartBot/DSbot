package net.runelite.client.plugins.devtools;

import com.google.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.ui.overlay.OverlayManager;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Bot 准星插件。
 *
 * <p>在脚本没跑的时候，也让准星跟着真实鼠标移动。
 *
 * <p>原本的 bot 准星（{@link MicrobotMouseOverlay}）位置取自
 * {@code PointerState.lastBotPoint()}，而那个值只有脚本移动过鼠标之后才会存在，
 * 所以客户端启动后、脚本没跑的时候看不到准星。
 *
 * <p>本插件用另一个 overlay（{@link BotMouseOverlay}）来画准星：
 * 脚本在跑时跟 bot 位置，空闲时跟真实鼠标位置。
 *
 * <p>本插件放在 devtools 包下，是为了能访问 {@link DevToolsButton#setActive(boolean)}，
 * 那个方法是包私有的。需要用它把 DevTools 面板里的 "Bot Mouse" 按钮关掉，
 * 否则两个准星会画在同一处。
 */
@Slf4j
@PluginDescriptor(
		name = PluginDescriptor.Mocrosoft + "Bot Mouse",
		description = "Shows the bot cursor, and follows the real mouse when no script is running.",
		tags = {"microbot", "mouse", "cursor"},
		enabledByDefault = true
)
public class BotMousePlugin extends Plugin
{
	/**
	 * 脚本运行状态的检查周期（毫秒）。
	 */
	private static final int SCRIPT_STATE_POLL_MS = 500;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private BotMouseOverlay overlay;

	/**
	 * 检查脚本状态的定时任务。
	 *
	 * <p>刻意不写成 final 字段：插件的实例在禁用之后会被复用，
	 * 而 shutDown 会终止这个执行器。如果复用同一个实例，
	 * 再次启动时往一个已终止的执行器提交任务会被拒绝，
	 * 报 RejectedExecutionException，插件就再也起不来了。
	 */
	private ScheduledExecutorService executor;

	/**
	 * 当前是否有脚本在运行。
	 *
	 * <p>准星每帧都要读这个值，而判断本身要反射扫描所有 microbot 插件的字段，
	 * 不能每帧都做。所以由定时任务更新这里，overlay 直接读缓存。
	 */
	@Getter
	private volatile boolean scriptRunning;

	@Override
	protected void startUp()
	{
		overlay.setPlugin(this);
		overlayManager.add(overlay);

		// DevTools 可能还没启动，这时它的按钮还不存在。
		// 之后 DevTools 一启动，onPluginChanged 会再关一次。
		disableDevToolsBotMouse();

		// 每次启动都新建一个执行器。插件实例会被复用，
		// 上一次 shutDown 已经把旧的终止掉了，不能再往里面提交任务。
		executor = Executors.newSingleThreadScheduledExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "BotMousePlugin");
			thread.setDaemon(true);
			return thread;
		});
		executor.scheduleWithFixedDelay(
				this::refreshScriptState, 0, SCRIPT_STATE_POLL_MS, TimeUnit.MILLISECONDS);
	}

	@Override
	protected void shutDown()
	{
		if (executor != null)
		{
			executor.shutdownNow();
			executor = null;
		}
		overlayManager.remove(overlay);

		// 把 DevTools 的按钮恢复原状，否则用户会发现它莫名其妙地是关的。
		restoreDevToolsBotMouse();
	}

	/**
	 * DevTools 插件启动后，它的 Bot Mouse 按钮会被重置为开启状态，
	 * 这里再关一次，保证不会出现两个准星。
	 */
	@Subscribe
	public void onPluginChanged(PluginChanged event)
	{
		if (event.isLoaded() && event.getPlugin() instanceof DevToolsPlugin)
		{
			disableDevToolsBotMouse();
		}
	}

	/**
	 * 刷新「是否有脚本在运行」。
	 */
	private void refreshScriptState()
	{
		try
		{
			scriptRunning = Microbot.getActiveScripts().stream().anyMatch(Script::isRunning);
		}
		catch (Exception e)
		{
			// 反射扫描在插件初始化期间可能拿到不完整的状态，忽略即可，
			// 下一个周期会再试一次。
			log.debug("BotMouse: 读取脚本运行状态失败", e);
		}
	}

	/**
	 * 关掉 DevTools 面板里的 "Bot Mouse" 按钮，避免两个准星重叠。
	 *
	 * <p>DevTools 是 developerPlugin，safe mode 下不会加载，此时按钮不存在，跳过即可。
	 * 本插件不依赖 DevTools，准星由自己的 overlay 画。
	 */
	private void disableDevToolsBotMouse()
	{
		DevToolsButton button = findDevToolsBotMouseButton();
		if (button == null)
		{
			return;
		}

		button.setActive(false);
	}

	/**
	 * 把 DevTools 的 "Bot Mouse" 按钮恢复为开启状态。
	 */
	private void restoreDevToolsBotMouse()
	{
		DevToolsButton button = findDevToolsBotMouseButton();
		if (button == null)
		{
			return;
		}

		button.setActive(true);
	}

	/**
	 * 找 DevTools 面板里的 "Bot Mouse" 按钮。
	 *
	 * @return 找不到时返回 null
	 */
	private DevToolsButton findDevToolsBotMouseButton()
	{
		try
		{
			DevToolsPlugin devTools = Microbot.getPlugin(DevToolsPlugin.class);
			if (devTools == null)
			{
				return null;
			}

			return devTools.getMouseMovement();
		}
		catch (Exception e)
		{
			// Microbot.getPlugin 依赖静态注入的 PluginManager，
			// 在插件还没装配好的时候可能是 null。找不到就跳过，
			// 本插件不依赖 DevTools，准星由自己的 overlay 画。
			log.debug("BotMouse: 找不到 DevTools 的 Bot Mouse 按钮", e);
			return null;
		}
	}
}
