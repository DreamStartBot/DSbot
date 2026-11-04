package net.runelite.client.plugins.microbot.util.mouse.speed;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.NaturalMouse;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.api.SpeedManager;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.support.DefaultSpeedManager;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.support.Flow;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.util.FlowTemplates;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.util.Pair;

import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

/**
 * 鼠标速度的映射和脚本接口测试。
 */
public class MouseSpeedPluginTest
{
	@After
	public void tearDown()
	{
		// 静态状态是跨测试共享的，每个测试结束都恢复默认，免得互相影响。
		MouseSpeed.reset();
	}

	/**
	 * 滑条两端对应固定的锚点。
	 */
	@Test
	public void theEndsMapToTheAnchors()
	{
		assertEquals(1000L, MouseSpeed.toBaseTimeMs(1));
		assertEquals(50L, MouseSpeed.toBaseTimeMs(100));
	}

	/**
	 * 默认值 50 对应 90 毫秒 —— 也就是现有默认档的耗时，
	 * 所以插件装上但不动滑条时，行为跟没装一样。
	 */
	@Test
	public void theDefaultMatchesTheExistingDefault()
	{
		assertEquals(50, MouseSpeedConfig.DEFAULT_SPEED);
		assertEquals(90L, MouseSpeed.toBaseTimeMs(MouseSpeedConfig.DEFAULT_SPEED));
	}

	/**
	 * 越大越快。
	 */
	@Test
	public void theSpeedIncreasesWithTheSlider()
	{
		long previous = Long.MAX_VALUE;
		for (int slider = MouseSpeedConfig.MIN_SPEED; slider <= MouseSpeedConfig.MAX_SPEED; slider++)
		{
			long current = MouseSpeed.toBaseTimeMs(slider);
			assertTrue("滑条 " + slider + " 的耗时 " + current + " 应该比上一档小",
				current <= previous);
			previous = current;
		}
	}

	/**
	 * 超出范围的滑条值被夹回范围内，不会算出乱七八糟的耗时。
	 */
	@Test
	public void outOfRangeValuesAreClamped()
	{
		assertEquals(1000L, MouseSpeed.toBaseTimeMs(0));
		assertEquals(1000L, MouseSpeed.toBaseTimeMs(-5));
		assertEquals(50L, MouseSpeed.toBaseTimeMs(101));
		assertEquals(50L, MouseSpeed.toBaseTimeMs(Integer.MAX_VALUE));
	}

	/**
	 * 插值是在对数空间做的，所以 log 值在滑条上等距 ——
	 * 直线插值的话中段几乎看不出差别。
	 */
	@Test
	public void theInterpolationIsLogarithmic()
	{
		double logAt1 = Math.log(MouseSpeed.toBaseTimeMs(1));
		double logAt25 = Math.log(MouseSpeed.toBaseTimeMs(25));
		double logAt50 = Math.log(MouseSpeed.toBaseTimeMs(50));

		// 滑条 25 在 1 和 50 之间占的位置，和它在对数空间里占的位置必须一致
		double sliderRatio = (double) (25 - 1) / (50 - 1);
		double logRatio = (logAt25 - logAt1) / (logAt50 - logAt1);

		assertEquals(sliderRatio, logRatio, 0.01);
	}

	/**
	 * 覆盖值真的能落到 SpeedManager 上。
	 */
	@Test
	public void theOverrideReachesTheSpeedManager()
	{
		DefaultSpeedManager manager = new DefaultSpeedManager(Collections.singletonList(
			new Flow(FlowTemplates.constantSpeed())));

		manager.setMouseMovementBaseTimeMs(250L);

		Pair<Flow, Long> flow = manager.getFlowWithTime(100);
		// getFlowWithTime 给的是 [T, 2T] 之间的随机值
		assertTrue("耗时 " + flow.y + " 应该在 250 到 500 之间",
			flow.y >= 250L && flow.y <= 500L);
	}

	/**
	 * 耗时不会因为距离而变 —— 远近一样快。
	 */
	@Test
	public void theTimeDoesNotDependOnTheDistance()
	{
		DefaultSpeedManager manager = new DefaultSpeedManager(Collections.singletonList(
			new Flow(FlowTemplates.constantSpeed())));
		manager.setMouseMovementBaseTimeMs(90L);

		long near = manager.getFlowWithTime(10).y;
		long far = manager.getFlowWithTime(1000).y;

		assertTrue("近距离耗时 " + near + " 应该在 90 到 180 之间", near >= 90L && near <= 180L);
		assertTrue("远距离耗时 " + far + " 应该在 90 到 180 之间", far >= 90L && far <= 180L);
	}

	/**
	 * 覆盖值会真的落到 factory 的 SpeedManager 上。
	 */
	@Test
	public void theOverrideReachesTheFactorySpeedManager()
	{
		NaturalMouse naturalMouse = new NaturalMouse();

		naturalMouse.setMovementTimeOverrideMs(200L);

		SpeedManager speedManager = naturalMouse.getFactory().getSpeedManager();
		assertTrue(speedManager instanceof DefaultSpeedManager);

		long time = ((DefaultSpeedManager) speedManager).getFlowWithTime(100).y;
		assertTrue("耗时 " + time + " 应该在 200 到 400 之间", time >= 200L && time <= 400L);
	}

	/**
	 * 取消覆盖之后，回到档位自己的耗时。
	 */
	@Test
	public void clearingTheOverrideRestoresTheDefaultTime()
	{
		NaturalMouse naturalMouse = new NaturalMouse();

		naturalMouse.setMovementTimeOverrideMs(1000L);
		naturalMouse.getFactory();

		naturalMouse.setMovementTimeOverrideMs(null);
		SpeedManager speedManager = naturalMouse.getFactory().getSpeedManager();

		// 默认档（super fast gamer）的耗时是 90 毫秒
		long time = ((DefaultSpeedManager) speedManager).getFlowWithTime(100).y;
		assertTrue("耗时 " + time + " 应该在 90 到 180 之间", time >= 90L && time <= 180L);
	}

	// ==================== 脚本接口 ====================

	/**
	 * 设置速度之后能读回来。
	 */
	@Test
	public void theSpeedCanBeSetAndRead()
	{
		MouseSpeed.setSpeed(80);

		assertEquals(80, MouseSpeed.getSpeed());
	}

	/**
	 * 设置速度时超出范围的值会被夹回去。
	 */
	@Test
	public void settingOutOfRangeSpeedIsClamped()
	{
		MouseSpeed.setSpeed(500);
		assertEquals(100, MouseSpeed.getSpeed());

		MouseSpeed.setSpeed(-10);
		assertEquals(1, MouseSpeed.getSpeed());
	}

	/**
	 * 关掉跟随后，读到的基准耗时是滑条换算出来的。
	 */
	@Test
	public void theBaseTimeFollowsTheSliderWhenNotFollowingAntiban()
	{
		MouseSpeed.setFollowAntibanIntensity(false);
		MouseSpeed.setSpeed(25);

		assertEquals(MouseSpeed.toBaseTimeMs(25), MouseSpeed.getBaseTimeMs());
	}

	/**
	 * 跟随 antiban 时，基准耗时是那个档位自己的值，不是滑条换算出来的。
	 */
	@Test
	public void theBaseTimeFollowsAntibanWhenFollowing()
	{
		// 先把滑条设成一个跟档位明显不同的值，再打开跟随
		MouseSpeed.setSpeed(1);
		MouseSpeed.setFollowAntibanIntensity(true);

		// 默认档（super fast gamer）的耗时是 90 毫秒，不是滑条 1 对应的 1000 毫秒
		assertEquals(90L, MouseSpeed.getBaseTimeMs());
	}

	/**
	 * 恢复默认之后，回到默认速度和跟随状态。
	 */
	@Test
	public void theResetRestoresTheDefaults()
	{
		MouseSpeed.setFollowAntibanIntensity(false);
		MouseSpeed.setSpeed(10);

		MouseSpeed.reset();

		assertEquals(MouseSpeedConfig.DEFAULT_SPEED, MouseSpeed.getSpeed());
		assertTrue(MouseSpeed.isFollowAntibanIntensity());
	}

	/**
	 * 设置速度会顺带关掉跟随 —— 否则跟随模式下设了也没用。
	 */
	@Test
	public void settingTheSpeedTakesOverFromAntiban()
	{
		MouseSpeed.setFollowAntibanIntensity(true);
		assertTrue(MouseSpeed.isFollowAntibanIntensity());

		MouseSpeed.setSpeed(80);

		assertFalse(MouseSpeed.isFollowAntibanIntensity());
	}

	/**
	 * 脚本调用的东西真的送到了 NaturalMouse 那里。
	 */
	@Test
	public void theScriptApiReachesTheNaturalMouse()
	{
		try (MockedStatic<Microbot> microbot = mockStatic(Microbot.class))
		{
			NaturalMouse naturalMouse = mock(NaturalMouse.class);
			microbot.when(Microbot::getNaturalMouse).thenReturn(naturalMouse);

			MouseSpeed.setSpeed(80);

			verify(naturalMouse).setMovementTimeOverrideMs(MouseSpeed.toBaseTimeMs(80));
		}
	}

	/**
	 * 跟随 antiban 时，NaturalMouse 那边拿到的是 null，也就是「没有覆盖」。
	 */
	@Test
	public void followingAntibanClearsTheOverride()
	{
		try (MockedStatic<Microbot> microbot = mockStatic(Microbot.class))
		{
			NaturalMouse naturalMouse = mock(NaturalMouse.class);
			microbot.when(Microbot::getNaturalMouse).thenReturn(naturalMouse);

			MouseSpeed.setFollowAntibanIntensity(true);

			verify(naturalMouse).setMovementTimeOverrideMs(null);
		}
	}
}
