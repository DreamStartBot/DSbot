package net.runelite.client.plugins.microbot.util.mouse.speed;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.NaturalMouse;

import javax.inject.Inject;
import java.util.Optional;

/**
 * 设置鼠标移动的速度。
 *
 * <p>速度指的是鼠标从当前位置走到目标点要花多久，不是点击的快慢。
 *
 * <p>上游本来把「移动快慢」和「轨迹形状」绑在同一个 antiban 档位上，
 * 而那个档位还管着 antiban 的行为强度。这个插件只覆盖移动耗时，
 * 轨迹形状和 antiban 强度都不动。
 *
 * <p>脚本要读写速度走 {@link MouseSpeed}，不用经过这个插件。
 */
@Slf4j
@PluginDescriptor(
	name = PluginDescriptor.Mocrosoft + "Mouse Speed",
	description = "Sets how fast the mouse travels to its target",
	tags = {"microbot", "mouse", "speed"},
	enabledByDefault = false
)
public class MouseSpeedPlugin extends Plugin
{
	@Inject
	private MouseSpeedConfig config;

	@Inject
	private ConfigManager configManager;

	@Provides
	MouseSpeedConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(MouseSpeedConfig.class);
	}

	@Override
	protected void startUp()
	{
		MouseSpeed.bind(configManager);
		MouseSpeed.syncFromConfig(config);
	}

	@Override
	protected void shutDown()
	{
		// 插件停用时把覆盖撤掉，回到跟随 antiban 档位的样子。
		naturalMouse().ifPresent(naturalMouse -> naturalMouse.setMovementTimeOverrideMs(null));
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!MouseSpeedConfig.CONFIG_GROUP.equals(event.getGroup()))
		{
			return;
		}

		MouseSpeed.syncFromConfig(config);
	}

	/**
	 * 拿到脚本真正在用的那个 NaturalMouse。
	 *
	 * <p>不能直接注入：NaturalMouse 没有 @Singleton，注入会拿到另一个实例，
	 * 而脚本移动鼠标走的是 {@code Microbot.naturalMouse} 这个 static 字段。
	 */
	private Optional<NaturalMouse> naturalMouse()
	{
		return Optional.ofNullable(Microbot.getNaturalMouse());
	}
}
