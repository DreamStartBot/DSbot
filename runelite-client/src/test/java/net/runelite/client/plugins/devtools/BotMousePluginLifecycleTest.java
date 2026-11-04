package net.runelite.client.plugins.devtools;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.ui.overlay.OverlayManager;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * Bot Mouse 插件的启停生命周期。
 *
 * <p>回归测试：插件的执行器曾经写成 final 字段，只在构造时创建一次，
 * 而 shutDown 会把它终止掉。插件实例在禁用之后会被复用，
 * 再次启动时往一个已终止的执行器提交任务会被拒绝，
 * 报 {@code RejectedExecutionException}，插件再也起不来。
 */
public class BotMousePluginLifecycleTest
{
	private BotMousePlugin newPlugin()
	{
		Injector injector = Guice.createInjector(new AbstractModule()
		{
			@Override
			protected void configure()
			{
				bind(OverlayManager.class).toInstance(mock(OverlayManager.class));
				bind(BotMouseOverlay.class).toInstance(mock(BotMouseOverlay.class));
				bind(EventBus.class).toInstance(new EventBus());
			}
		});

		BotMousePlugin plugin = injector.getInstance(BotMousePlugin.class);
		assertNotNull(plugin);
		return plugin;
	}

	@Test
	public void canBeStartedAfterBeingStopped()
	{
		BotMousePlugin plugin = newPlugin();

		plugin.startUp();
		plugin.shutDown();

		// 同一个实例再启动一次，之前这里会抛 RejectedExecutionException
		plugin.startUp();
		plugin.shutDown();
	}

	@Test
	public void canBeStartedRepeatedly()
	{
		BotMousePlugin plugin = newPlugin();

		for (int i = 0; i < 3; i++)
		{
			plugin.startUp();
			plugin.shutDown();
		}
	}
}
