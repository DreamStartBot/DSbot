package net.runelite.client.plugins.microbot.toolbar;

import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.NavigationButton;

import javax.swing.JFrame;
import javax.swing.JMenuBar;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.util.Map;

/**
 * 访问窗口右上角的工具栏面板。
 *
 * <p>右上角的按钮都放在 {@code net.runelite.client.ui.ClientToolbarPanel} 里。
 * 这个类是包私有的，它自己的 {@code add} / {@code remove} / {@code get} 方法也是包私有的，
 * 而插件必须放在 {@code net.runelite.client.plugins} 包下才能被 {@code PluginManager} 扫描到，
 * 所以只能反射。
 *
 * <p>本类把反射的细节集中在这里，插件本身就不用再各写一份。
 */
final class ToolbarAccess
{
	/**
	 * 工具栏面板的类名。它是包私有的，只能按名字拿。
	 */
	private static final String PANEL_CLASS_NAME = "net.runelite.client.ui.ClientToolbarPanel";

	/**
	 * 工具栏面板里存放按钮的表：{@code NavigationButton -> 组件}。
	 */
	private static final String ENTRIES_FIELD_NAME = "entries";

	/**
	 * 工具栏面板的类。拿不到就是 null，说明客户端结构变了。
	 */
	private static final Class<?> PANEL_CLASS = loadPanelClass();

	private ToolbarAccess()
	{
	}

	private static Class<?> loadPanelClass()
	{
		try
		{
			return Class.forName(PANEL_CLASS_NAME);
		}
		catch (ClassNotFoundException e)
		{
			return null;
		}
	}

	/**
	 * 工具栏面板的类。
	 *
	 * @return 拿不到时返回 null
	 */
	static Class<?> panelClass()
	{
		return PANEL_CLASS;
	}

	/**
	 * 找右上角的工具栏面板。
	 *
	 * @return 找不到时返回 null
	 */
	static Container findPanel()
	{
		if (PANEL_CLASS == null)
		{
			return null;
		}

		Component frame = ClientUI.getFrame();
		if (frame == null)
		{
			return null;
		}

		// 开了自定义标题栏（Windows 上 enableCustomChrome() 默认返回 true）时，
		// 工具栏就在菜单栏里。
		if (frame instanceof JFrame)
		{
			JMenuBar menuBar = ((JFrame) frame).getJMenuBar();
			if (menuBar != null)
			{
				for (Component component : menuBar.getComponents())
				{
					if (PANEL_CLASS.isInstance(component))
					{
						return (Container) component;
					}
				}
			}
		}

		// 没开自定义标题栏时，工具栏在侧边栏底部，只能整个组件树里找。
		return findInTree(frame, PANEL_CLASS);
	}

	/**
	 * 读出工具栏面板里的按钮表。
	 *
	 * @param panel 工具栏面板
	 * @return 按钮表；读不到时返回 null
	 */
	@SuppressWarnings("unchecked")
	static Map<NavigationButton, Component> entries(Container panel)
	{
		if (PANEL_CLASS == null || panel == null)
		{
			return null;
		}

		try
		{
			Field field = PANEL_CLASS.getDeclaredField(ENTRIES_FIELD_NAME);
			field.setAccessible(true);
			Object raw = field.get(panel);
			return raw instanceof Map ? (Map<NavigationButton, Component>) raw : null;
		}
		catch (Exception e)
		{
			return null;
		}
	}

	/**
	 * 在组件树里递归找工具栏面板。
	 */
	private static Container findInTree(Component root, Class<?> panelClass)
	{
		if (panelClass.isInstance(root))
		{
			return (Container) root;
		}

		if (root instanceof Container)
		{
			for (Component child : ((Container) root).getComponents())
			{
				Container found = findInTree(child, panelClass);
				if (found != null)
				{
					return found;
				}
			}
		}

		return null;
	}
}
