package net.runelite.client.plugins.microbot.util.mouse.speed;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.NaturalMouse;

/**
 * 鼠标移动速度的脚本接口。
 *
 * <p>脚本可以直接调用这里的方法来读或者改鼠标速度，不用去碰插件面板：
 *
 * <pre>{@code
 * MouseSpeed.setSpeed(90);              // 临时调快，立刻生效
 * MouseSpeed.setFollowAntibanIntensity(false);  // 关掉跟随，交给滑条
 * long ms = MouseSpeed.getBaseTimeMs(); // 读当前生效的基准耗时
 * MouseSpeed.reset();                    // 恢复默认
 * }</pre>
 *
 * <p>改速度会同时写回配置，所以插件面板上的滑条和这里设的值永远一致，
 * 脚本改完不需要再点一次面板。
 *
 * <p>速度指的是鼠标从当前位置走到目标点要花多久，不是点击的快慢。
 */
@Slf4j
public final class MouseSpeed
{
	/**
	 * 滑条最慢端的移动耗时（毫秒）。跟 antiban 的 average computer user 档一样。
	 */
	public static final long SLOWEST_BASE_TIME_MS = 1000;

	/**
	 * 滑条 50 对应的移动耗时（毫秒）。等于现有默认档（super fast gamer）的耗时，
	 * 所以滑条在默认值时行为跟插件没装一样。
	 */
	public static final long ANCHOR_BASE_TIME_MS = 90;

	/**
	 * 滑条最快端的移动耗时（毫秒）。比现有最快档还快一倍。
	 */
	public static final long FASTEST_BASE_TIME_MS = 50;

	/**
	 * 当前生效的速度（滑条值）。
	 */
	private static volatile int currentSpeed = MouseSpeedConfig.DEFAULT_SPEED;

	/**
	 * 当前是否跟随 antiban 档位。
	 */
	private static volatile boolean currentFollowAntiban = true;

	/**
	 * 配置管理器。插件没启用时为 null，这时设置只改内存值，不会被持久化。
	 */
	private static volatile ConfigManager configManager;

	private MouseSpeed()
	{
	}

	// ======================== 脚本入口 ========================

	/**
	 * 设置鼠标移动速度，立刻生效。
	 *
	 * <p>范围是 1 到 100，越大越快。超出范围的值会被夹回范围里。
	 *
	 * <p>这一调用表示「我明确要这个速度」，所以会顺带关掉「跟随 antiban 档位」。
	 * 否则跟随模式下速度由 antiban 档位说了算，滑条设了也没用。
	 * 想回到跟随状态调 {@link #setFollowAntibanIntensity(boolean)}。
	 *
	 * @param slider 速度，1（最慢）到 100（最快）
	 */
	public static void setSpeed(int slider)
	{
		int clamped = clamp(slider);
		currentFollowAntiban = false;
		persist(MouseSpeedConfig.FOLLOW_KEY, String.valueOf(false));
		currentSpeed = clamped;
		persist(MouseSpeedConfig.SPEED_KEY, String.valueOf(clamped));
		apply();
	}

	/**
	 * 当前生效的速度。
	 *
	 * @return 滑条值，1（最慢）到 100（最快）
	 */
	public static int getSpeed()
	{
		return currentSpeed;
	}

	/**
	 * 设置是否跟随 antiban 档位。
	 *
	 * <p>跟随时速度由 antiban 的强度档位决定，滑条不起作用。
	 * 不跟随时速度由滑条决定，antiban 的强度一点不动。
	 *
	 * @param follow true 跟随 antiban 档位，false 交给滑条
	 */
	public static void setFollowAntibanIntensity(boolean follow)
	{
		currentFollowAntiban = follow;
		persist(MouseSpeedConfig.FOLLOW_KEY, String.valueOf(follow));
		apply();
	}

	/**
	 * 当前是否跟随 antiban 档位。
	 *
	 * @return true 表示跟随
	 */
	public static boolean isFollowAntibanIntensity()
	{
		return currentFollowAntiban;
	}

	/**
	 * 当前生效的基准移动耗时。
	 *
	 * <p>这是基准值，不是实际耗时：实际耗时是这个值到两倍之间的随机数
	 * （见 {@code DefaultSpeedManager.getFlowWithTime}）。
	 *
	 * <p>跟随 antiban 时返回的是那个档位自己的耗时。
	 *
	 * @return 基准耗时（毫秒）
	 */
	public static long getBaseTimeMs()
	{
		if (currentFollowAntiban)
		{
			return antibanBaseTimeMs();
		}

		return toBaseTimeMs(currentSpeed);
	}

	/**
	 * 把滑条值换算成基准移动耗时。
	 *
	 * <p>在毫秒的对数空间做分段插值 —— 直线插值的话中段几乎看不出差别，
	 * 而人对快慢的感知本来就是对数的。
	 *
	 * <p>三个锚点：滑条 1 是 {@value #SLOWEST_BASE_TIME_MS} 毫秒，
	 * 滑条 {@value MouseSpeedConfig#DEFAULT_SPEED} 是 {@value #ANCHOR_BASE_TIME_MS} 毫秒，
	 * 滑条 100 是 {@value #FASTEST_BASE_TIME_MS} 毫秒。
	 *
	 * @param slider 滑条值
	 * @return 基准耗时（毫秒）
	 */
	public static long toBaseTimeMs(int slider)
	{
		int clamped = clamp(slider);

		if (clamped <= MouseSpeedConfig.DEFAULT_SPEED)
		{
			return interpolateLog(SLOWEST_BASE_TIME_MS, ANCHOR_BASE_TIME_MS,
				clamped, MouseSpeedConfig.MIN_SPEED, MouseSpeedConfig.DEFAULT_SPEED);
		}

		return interpolateLog(ANCHOR_BASE_TIME_MS, FASTEST_BASE_TIME_MS,
			clamped, MouseSpeedConfig.DEFAULT_SPEED, MouseSpeedConfig.MAX_SPEED);
	}

	/**
	 * 恢复默认：速度回到 50，重新跟随 antiban 档位。
	 */
	public static void reset()
	{
		currentFollowAntiban = true;
		persist(MouseSpeedConfig.FOLLOW_KEY, String.valueOf(true));
		currentSpeed = MouseSpeedConfig.DEFAULT_SPEED;
		persist(MouseSpeedConfig.SPEED_KEY, String.valueOf(currentSpeed));
		apply();
	}

	// ======================== 内部 ========================

	/**
	 * 把配置管理器和配置绑上。插件启动时调用。
	 *
	 * @param manager 配置管理器
	 */
	static void bind(ConfigManager manager)
	{
		configManager = manager;
	}

	/**
	 * 把配置里的值同步到运行时状态并生效。插件启动和配置变化时调用。
	 *
	 * @param config 配置
	 */
	static void syncFromConfig(MouseSpeedConfig config)
	{
		currentSpeed = clamp(config.speed());
		currentFollowAntiban = config.followAntibanIntensity();
		apply();
	}

	/**
	 * 把当前状态应用到鼠标上。
	 */
	static void apply()
	{
		NaturalMouse naturalMouse = Microbot.getNaturalMouse();
		if (naturalMouse == null)
		{
			return;
		}

		if (currentFollowAntiban)
		{
			naturalMouse.setMovementTimeOverrideMs(null);
			return;
		}

		long baseTimeMs = toBaseTimeMs(currentSpeed);
		naturalMouse.setMovementTimeOverrideMs(baseTimeMs);
		log.debug("Mouse speed set to {} ms per move", baseTimeMs);
	}

	/**
	 * 写回配置。配置管理器还没绑上的时候什么都不做 —— 那说明插件没启用，
	 * 这时候的速度只在内存里生效。
	 */
	private static void persist(String key, String value)
	{
		ConfigManager manager = configManager;
		if (manager == null)
		{
			return;
		}

		manager.setConfiguration(MouseSpeedConfig.CONFIG_GROUP, key, value);
	}

	/**
	 * antiban 当前档位自己的耗时。
	 *
	 * <p>档位带随机和疲劳浮动，这里返回的是那个档位的代表值。
	 */
	private static long antibanBaseTimeMs()
	{
		ActivityIntensity intensity = Rs2Antiban.getActivityIntensity();
		if (intensity == null)
		{
			// 没设过档位时走的是 super fast gamer 分支
			return ANCHOR_BASE_TIME_MS;
		}

		switch (intensity)
		{
			case VERY_LOW:
				return 400;
			case LOW:
				return 150;
			case MODERATE:
			case HIGH:
				return 120;
			case EXTREME:
			default:
				return ANCHOR_BASE_TIME_MS;
		}
	}

	private static int clamp(int slider)
	{
		return Math.max(MouseSpeedConfig.MIN_SPEED, Math.min(MouseSpeedConfig.MAX_SPEED, slider));
	}

	/**
	 * 在毫秒的对数空间里插值。
	 *
	 * @param fromMs 起点耗时
	 * @param toMs 终点耗时
	 * @param slider 当前滑条值
	 * @param fromSlider 起点对应的滑条值
	 * @param toSlider 终点对应的滑条值
	 * @return 插值出来的耗时（毫秒）
	 */
	private static long interpolateLog(long fromMs, long toMs, int slider, int fromSlider, int toSlider)
	{
		double t = (double) (slider - fromSlider) / (toSlider - fromSlider);
		double logFrom = Math.log(fromMs);
		double logTo = Math.log(toMs);
		return Math.round(Math.exp(logFrom + t * (logTo - logFrom)));
	}
}
