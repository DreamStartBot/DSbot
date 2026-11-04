package net.runelite.client.plugins.microbot.util.mouse.speed;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

/**
 * 鼠标速度插件的配置。
 */
@ConfigGroup(MouseSpeedConfig.CONFIG_GROUP)
public interface MouseSpeedConfig extends Config
{
	String CONFIG_GROUP = "mouseSpeed";

	/**
	 * 速度的键名。{@link MouseSpeed} 用它写回配置。
	 */
	String SPEED_KEY = "speed";

	/**
	 * 跟随开关的键名。
	 */
	String FOLLOW_KEY = "followAntibanIntensity";

	/**
	 * 滑条的最小值。
	 */
	int MIN_SPEED = 1;

	/**
	 * 滑条的最大值。
	 */
	int MAX_SPEED = 100;

	/**
	 * 滑条的默认值。这个值映射到现有默认档的耗时，行为跟插件没装一样。
	 */
	int DEFAULT_SPEED = 50;

	@ConfigItem(
		keyName = SPEED_KEY,
		name = "Mouse speed",
		description = "How fast the mouse travels to its target. 1 is the slowest, 100 is the fastest. "
			+ "The value at 50 matches the default mouse speed.",
		position = 0
	)
	@Range(min = MIN_SPEED, max = MAX_SPEED)
	default int speed()
	{
		return DEFAULT_SPEED;
	}

	@ConfigItem(
		keyName = FOLLOW_KEY,
		name = "Follow antiban intensity",
		description = "When on, the mouse speed follows the antiban intensity level instead of the slider above. "
			+ "Turning this off lets the slider control the speed on its own, without touching the antiban strength.",
		position = 1
	)
	default boolean followAntibanIntensity()
	{
		return true;
	}
}
