package net.runelite.client.ui.overlay;

import java.awt.Dimension;
import java.awt.Graphics2D;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * 覆盖 {@link OverlayRenderer#shouldRender} 的放行判定。
 *
 * <p>要守住的不变量是「默认行为与上游一致」：没有显式打开
 * {@link Overlay#setAlwaysVisible(boolean)} 的 overlay，仍然只在登录后才渲染。
 */
public class OverlayRendererTest
{
	private static class TestOverlay extends Overlay
	{
		@Override
		public Dimension render(Graphics2D graphics)
		{
			return null;
		}
	}

	@Test
	public void testAlwaysVisibleDefaultsToOff()
	{
		assertFalse(new TestOverlay().isAlwaysVisible());
	}

	@Test
	public void testAlwaysVisibleCanBeToggled()
	{
		Overlay overlay = new TestOverlay();
		overlay.setAlwaysVisible(true);
		assertTrue(overlay.isAlwaysVisible());
		overlay.setAlwaysVisible(false);
		assertFalse(overlay.isAlwaysVisible());
	}

	@Test
	public void testLoggedInRendersEverything()
	{
		Overlay plain = new TestOverlay();
		Overlay always = new TestOverlay();
		always.setAlwaysVisible(true);

		assertTrue(OverlayRenderer.shouldRender(plain, true));
		assertTrue(OverlayRenderer.shouldRender(always, true));
	}

	@Test
	public void testNotLoggedInSkipsPlainOverlay()
	{
		assertFalse(OverlayRenderer.shouldRender(new TestOverlay(), false));
	}

	@Test
	public void testNotLoggedInRendersAlwaysVisibleOverlay()
	{
		Overlay overlay = new TestOverlay();
		overlay.setAlwaysVisible(true);
		assertTrue(OverlayRenderer.shouldRender(overlay, false));
	}
}
