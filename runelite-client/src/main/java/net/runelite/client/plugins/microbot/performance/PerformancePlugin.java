package net.runelite.client.plugins.microbot.performance;

import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.AmbientSoundEffectCreated;
import net.runelite.api.events.AreaSoundEffectPlayed;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.SoundEffectPlayed;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.fps.FpsPlugin;
import net.runelite.client.plugins.lowmemory.LowMemoryPlugin;
import net.runelite.client.plugins.microbot.Microbot;

import javax.swing.SwingUtilities;

/**
 * 调整客户端的性能与声音设置。
 *
 * <p>做三件事：
 * <ol>
 *     <li>启用上游的 FPS Control 插件（限速开关和目标帧率仍由用户在它自己的面板里配置）；</li>
 *     <li>启用上游的 Low Detail 插件（隐藏地面装饰与部分贴图，降低内存占用）；</li>
 *     <li>关闭游戏声音。</li>
 * </ol>
 *
 * <p>前两件事只在插件启动时做一次。
 *
 * <p>静音不通过操作游戏内 Settings 界面实现 —— 那种做法依赖硬编码的 widget 坐标，
 * 会随游戏更新失效。这里改成直接用客户端接口和声音事件：
 * 音乐音量置零，音效与区域音在事件层面吞掉，环境音直接清空。
 * 效果一样，但不依赖界面，也不需要等游戏登录。
 */
@Slf4j
@PluginDescriptor(
		name = PluginDescriptor.Mocrosoft + "Performance",
		description = "Enables FPS Control and Low Detail, and mutes the game sounds.",
		tags = {"microbot", "performance", "fps", "sound"},
		enabledByDefault = true
)
public class PerformancePlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	/**
	 * 插件启动前的音乐音量，停用插件时还原。
	 */
	private int originalMusicVolume;

	@Override
	protected void startUp()
	{
		// PluginManager.startPlugin 里断言必须在 EDT 上调用，所以这里切到 EDT 再启用上游插件。
		SwingUtilities.invokeLater(this::enableUpstreamPlugins);

		clientThread.invoke(() ->
		{
			originalMusicVolume = client.getMusicVolume();
			client.setMusicVolume(0);
		});
	}

	@Override
	protected void shutDown()
	{
		// 还原停用前的音乐音量。音效与区域音是拦截事件实现的，插件一停就自动恢复。
		clientThread.invoke(() -> client.setMusicVolume(originalMusicVolume));
	}

	/**
	 * 吞掉所有音效，包括界面点击、攻击、采集等。
	 */
	@Subscribe
	public void onSoundEffectPlayed(SoundEffectPlayed event)
	{
		event.consume();
	}

	/**
	 * 吞掉所有区域音。
	 */
	@Subscribe
	public void onAreaSoundEffectPlayed(AreaSoundEffectPlayed event)
	{
		event.consume();
	}

	/**
	 * 清掉环境音。
	 *
	 * <p>环境音没有单独的取消事件，只能在创建时把客户端的列表清空。
	 */
	@Subscribe
	public void onAmbientSoundEffectCreated(AmbientSoundEffectCreated event)
	{
		client.getAmbientSoundEffects().clear();
	}

	/**
	 * 登录后补一次音乐音量，并清掉环境音。
	 *
	 * <p>登录流程会用本地设置覆盖音乐音量，所以这里再设一次。
	 * 另外地图加载时创建的环境音不会触发创建事件，只能在这里补清一次。
	 */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		clientThread.invoke(() ->
		{
			client.setMusicVolume(0);
			client.getAmbientSoundEffects().clear();
		});
	}

	/**
	 * 启用 FPS Control 与 Low Detail。它们的启用状态是持久化的，重启后依然生效。
	 */
	private void enableUpstreamPlugins()
	{
		enable(FpsPlugin.class);
		enable(LowMemoryPlugin.class);
	}

	private void enable(Class<? extends Plugin> pluginClass)
	{
		if (Microbot.isPluginEnabled(pluginClass))
		{
			log.debug("Performance: {} 已经处于启用状态", pluginClass.getSimpleName());
			return;
		}

		try
		{
			boolean started = Microbot.startPlugin(pluginClass);
			log.info("Performance: {} {}", pluginClass.getSimpleName(), started ? "已启用" : "启用失败");
		}
		catch (Exception e)
		{
			log.warn("Performance: 启用 {} 失败", pluginClass.getSimpleName(), e);
		}
	}
}
