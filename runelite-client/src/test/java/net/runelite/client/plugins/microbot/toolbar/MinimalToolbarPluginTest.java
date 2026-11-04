package net.runelite.client.plugins.microbot.toolbar;

import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import org.junit.Before;
import org.junit.Test;

import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 精简工具栏插件的隐藏逻辑。
 *
 * <p>重点验证反射那条路真的走得通 —— {@code ClientToolbarPanel} 是包私有的，
 * 插件靠反射操作它。如果这条路不通，插件会静默地什么都不做。
 */
public class MinimalToolbarPluginTest
{
	private static final String PANEL_CLASS_NAME = "net.runelite.client.ui.ClientToolbarPanel";

	private Class<?> panelClass;
	private Container panel;
	private Method addMethod;
	private MinimalToolbarPlugin plugin;

	@Before
	public void setUp() throws Exception
	{
		panelClass = Class.forName(PANEL_CLASS_NAME);
		assertNotNull("反射拿不到工具栏面板的类", panelClass);

		Constructor<?> ctor = panelClass.getDeclaredConstructor(boolean.class);
		ctor.setAccessible(true);
		panel = (Container) ctor.newInstance(false);

		addMethod = panelClass.getDeclaredMethod("add", NavigationButton.class, boolean.class);
		addMethod.setAccessible(true);

		plugin = new MinimalToolbarPlugin();
	}

	/**
	 * 往面板里塞一个按钮。
	 */
	private NavigationButton addButton(String tooltip, int priority) throws Exception
	{
		BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		NavigationButton button = NavigationButton.builder()
			.icon(icon)
			.tooltip(tooltip)
			.priority(priority)
			.build();
		addMethod.invoke(panel, button, true);
		return button;
	}

	/**
	 * 读回面板里的按钮表。
	 */
	@SuppressWarnings("unchecked")
	private Map<NavigationButton, Component> entries() throws Exception
	{
		Field field = panelClass.getDeclaredField("entries");
		field.setAccessible(true);
		return (Map<NavigationButton, Component>) field.get(panel);
	}

	@Test
	public void keepsInputConsoleAndSidebarButtons() throws Exception
	{
		addButton("Enable Input", 1);
		addButton("Disable Input", 2);
		addButton("Show console", 95);
		addButton("Hide console", 96);
		addButton("Close sidebar", 100);
		addButton("Open sidebar", 101);

		plugin.applyTo(panel);

		assertEquals("白名单里的按钮应该都还在", 6, entries().size());
		assertEquals("白名单里的按钮应该都还在容器里", 6, panel.getComponentCount());
	}

	@Test
	public void hidesOtherToolbarButtons() throws Exception
	{
		addButton("Enable Input", 1);
		addButton("Show console", 95);
		addButton("Close sidebar", 100);
		addButton("Enable 'Semi-Auto' Questing", 2);
		addButton("Report a problem", 90);

		plugin.applyTo(panel);

		Map<NavigationButton, Component> remaining = entries();
		assertEquals("只应该剩下三个白名单按钮", 3, remaining.size());
		assertEquals("被摘掉的按钮也应该从容器里消失", 3, panel.getComponentCount());

		for (NavigationButton button : remaining.keySet())
		{
			assertTrue("剩下的应该是白名单按钮，但混进了 " + button.getTooltip(),
				MinimalToolbarPlugin.shouldKeep(button.getTooltip()));
		}
	}

	@Test
	public void hiddenButtonsAreRemovedFromTheInternalTable() throws Exception
	{
		NavigationButton questHelper = addButton("Enable 'Semi-Auto' Questing", 2);
		addButton("Show console", 95);

		plugin.applyTo(panel);

		assertFalse("被摘掉的按钮必须从内部表里清掉，否则恢复时加不回去",
			entries().containsKey(questHelper));
	}

	@Test
	public void restorePutsTheButtonsBack() throws Exception
	{
		NavigationButton questHelper = addButton("Enable 'Semi-Auto' Questing", 2);
		NavigationButton bugReport = addButton("Report a problem", 90);
		addButton("Show console", 95);

		plugin.applyTo(panel);
		assertEquals(1, entries().size());

		ClientToolbar clientToolbar = mock(ClientToolbar.class);
		setField(plugin, "clientToolbar", clientToolbar);

		plugin.restore();

		verify(clientToolbar).addNavigation(questHelper);
		verify(clientToolbar).addNavigation(bugReport);
	}

	@Test
	public void keepsButtonsWithUnknownTooltipOut()
	{
		assertTrue(MinimalToolbarPlugin.shouldKeep("Enable Input"));
		assertTrue(MinimalToolbarPlugin.shouldKeep("Disable Input"));
		assertTrue(MinimalToolbarPlugin.shouldKeep("Show console"));
		assertTrue(MinimalToolbarPlugin.shouldKeep("Hide console"));
		assertTrue(MinimalToolbarPlugin.shouldKeep("Close sidebar"));
		assertTrue(MinimalToolbarPlugin.shouldKeep("Open sidebar"));

		assertFalse(MinimalToolbarPlugin.shouldKeep("Enable 'Semi-Auto' Questing"));
		assertFalse(MinimalToolbarPlugin.shouldKeep("Report a problem"));
		assertFalse(MinimalToolbarPlugin.shouldKeep("Community Plugins"));
		assertFalse(MinimalToolbarPlugin.shouldKeep(null));
	}

	private static void setField(Object target, String name, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
