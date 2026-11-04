package net.runelite.client.plugins.microbot.toolbar;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.PluginNames;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.Text;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 启动脚本的弹窗。
 *
 * <p>列出所有从 jar 包加载的脚本（{@code isExternal = true} 的插件），
 * 选中一个点「启动」就把它跑起来。
 *
 * <p>列表里每一项是一张文字卡片：脚本名、描述、版本/作者/标签。
 * 这些信息都取自脚本自己的 {@code @PluginDescriptor} 注解，
 * 哪个没填就不显示那一部分。
 *
 * <p>弹窗启动后不关闭，底部会显示启动结果，方便看清成功还是失败。
 */
@Slf4j
public final class ScriptLauncherDialog
{
	private static final int LIST_HEIGHT = 260;
	private static final int DIALOG_WIDTH = 520;

	/**
	 * 卡片的高度。
	 *
	 * <p>固定高度是为了让列表里每一项对齐。列表项的渲染器是同一个组件反复复用，
	 * 高度不定的话 Swing 会按最大的那个算，导致有的行多出空隙。
	 */
	private static final int CARD_HEIGHT = 58;

	/**
	 * 描述最多显示多少个宽度单位。中文等全角字符算两个单位。
	 */
	private static final int DESCRIPTION_WIDTH = 64;

	private ScriptLauncherDialog()
	{
	}

	/**
	 * 打开弹窗。
	 *
	 * <p>必须在 EDT 上调用。
	 *
	 * @param pluginManager 插件管理器
	 */
	public static void show(PluginManager pluginManager)
	{
		show(pluginManager, null);
	}

	/**
	 * 打开弹窗，脚本启动成功后回调一下。
	 *
	 * <p>必须在 EDT 上调用。
	 *
	 * @param pluginManager 插件管理器
	 * @param onScriptStarted 脚本启动成功后要做的事，会带上启动的那个脚本，为 null 表示不管
	 */
	public static void show(PluginManager pluginManager, Consumer<Plugin> onScriptStarted)
	{
		List<Plugin> scripts = externalScripts(pluginManager);

		Window parent = ClientUI.getFrame();
		JDialog dialog = new JDialog(parent, "启动脚本", Dialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		dialog.setIconImages(Arrays.asList(ClientUI.ICON_128, ClientUI.ICON_16));

		DefaultListModel<Plugin> model = new DefaultListModel<>();
		for (Plugin script : scripts)
		{
			model.addElement(script);
		}

		JList<Plugin> list = new JList<>(model);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer(new ScriptCardRenderer());
		list.setFixedCellHeight(CARD_HEIGHT);
		if (!scripts.isEmpty())
		{
			list.setSelectedIndex(0);
		}

		JLabel status = new JLabel(scripts.isEmpty() ? "没有找到已加载的脚本" : " ");
		status.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

		JButton startButton = new JButton("启动");
		JButton closeButton = new JButton("关闭");
		startButton.setEnabled(!scripts.isEmpty());
		closeButton.addActionListener(e -> dialog.dispose());

		startButton.addActionListener(e -> startSelected(list, status, dialog, onScriptStarted));

		// 回车也能启动，键盘操作不用切到鼠标
		list.registerKeyboardAction(e -> startSelected(list, status, dialog, onScriptStarted),
			KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), JComponent.WHEN_FOCUSED);
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.add(startButton);
		buttons.add(closeButton);

		JPanel south = new JPanel(new BorderLayout(8, 0));
		south.add(status, BorderLayout.CENTER);
		south.add(buttons, BorderLayout.EAST);

		JPanel body = new JPanel(new BorderLayout(0, 8));
		body.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		body.add(new JLabel("选择要启动的脚本："), BorderLayout.NORTH);
		body.add(new JScrollPane(list), BorderLayout.CENTER);
		body.add(south, BorderLayout.SOUTH);

		dialog.setContentPane(body);
		dialog.setSize(DIALOG_WIDTH, LIST_HEIGHT + 180);
		dialog.setLocationRelativeTo(parent);
		dialog.setVisible(true);
	}

	/**
	 * 启动列表里选中的脚本。
	 *
	 * <p>启动成功就把弹窗关掉 —— 脚本已经在跑了，弹窗留着也没用。
	 * 启动失败时保留弹窗，好让用户看到原因。
	 *
	 * @param onScriptStarted 启动成功后的回调，会带上启动的那个脚本，为 null 表示不管
	 */
	private static void startSelected(JList<Plugin> list, JLabel status, JDialog dialog, Consumer<Plugin> onScriptStarted)
	{
		Plugin script = list.getSelectedValue();
		if (script == null)
		{
			status.setText("请先选择一个脚本");
			return;
		}

		String name = displayName(script);

		try
		{
			// Microbot.startPlugin 会先 setPluginEnabled 再 startPlugin，
			// 在 EDT 上是同步执行的。
			boolean started = Microbot.startPlugin(script);
			if (started)
			{
				status.setText("已启动 " + name);
				dialog.dispose();
				// 主动通知一下，让工具栏按钮立刻切到「脚本在跑」的样子。
				// 不依赖这个也能刷新（插件监听 PluginChanged 事件，还有定时兜底），
				// 但这里是最确定的一条路径，直接接上省得等。
				if (onScriptStarted != null)
				{
					onScriptStarted.accept(script);
				}
			}
			else
			{
				// 已经在跑的脚本，startPlugin 会直接返回 false
				status.setText("启动失败：" + name + "（可能已经在运行）");
			}
		}
		catch (Exception e)
		{
			log.warn("ToolbarButtons: 启动脚本失败 " + name, e);
			status.setText("启动失败：" + name + "（" + e.getMessage() + "）");
		}
	}

	/**
	 * 挑出所有从 jar 包加载的脚本。
	 *
	 * @param pluginManager 插件管理器
	 * @return 脚本列表
	 */
	public static List<Plugin> externalScripts(PluginManager pluginManager)
	{
		List<Plugin> scripts = new ArrayList<>();
		if (pluginManager == null)
		{
			return scripts;
		}

		for (Plugin plugin : pluginManager.getPlugins())
		{
			PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
			if (descriptor == null || !descriptor.isExternal() || descriptor.hidden())
			{
				continue;
			}
			scripts.add(plugin);
		}

		return scripts;
	}

	/**
	 * 脚本的显示名。
	 *
	 * <p>去掉插件名里的标签和前缀方括号里的内容；没有名字就用类名。
	 *
	 * @param plugin 插件
	 * @return 显示名
	 */
	public static String displayName(Plugin plugin)
	{
		return PluginNames.displayName(plugin);
	}

	/**
	 * 脚本的描述。
	 *
	 * <p>取自 {@code @PluginDescriptor} 的 description。没写就返回空串。
	 *
	 * @param plugin 插件
	 * @return 描述，可能为空
	 */
	public static String description(Plugin plugin)
	{
		PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
		if (descriptor == null)
		{
			return "";
		}

		String description = Text.removeTags(descriptor.description() == null ? "" : descriptor.description())
			// 描述里的换行在单行卡片里没法显示，压成空格
			.replaceAll("\\s+", " ")
			.trim();
		return truncate(description, DESCRIPTION_WIDTH);
	}

	/**
	 * 脚本的版本号。
	 *
	 * @param plugin 插件
	 * @return 版本号，可能为空
	 */
	public static String version(Plugin plugin)
	{
		PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
		if (descriptor == null || descriptor.version() == null)
		{
			return "";
		}
		return descriptor.version().trim();
	}

	/**
	 * 脚本的作者。
	 *
	 * <p>注解里 authors 的默认值是 {@code {"Unknown"}}，那等于没填，不显示。
	 *
	 * @param plugin 插件
	 * @return 作者，可能为空
	 */
	public static String authors(Plugin plugin)
	{
		PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
		if (descriptor == null || descriptor.authors() == null)
		{
			return "";
		}

		return Arrays.stream(descriptor.authors())
			.filter(author -> author != null && !author.trim().isEmpty())
			.map(String::trim)
			.filter(author -> !"Unknown".equalsIgnoreCase(author))
			.collect(Collectors.joining(", "));
	}

	/**
	 * 脚本的标签。
	 *
	 * @param plugin 插件
	 * @return 标签，可能为空
	 */
	public static String tags(Plugin plugin)
	{
		PluginDescriptor descriptor = plugin.getClass().getAnnotation(PluginDescriptor.class);
		if (descriptor == null || descriptor.tags() == null)
		{
			return "";
		}

		return Arrays.stream(descriptor.tags())
			.filter(tag -> tag != null && !tag.trim().isEmpty())
			.map(String::trim)
			.collect(Collectors.joining(", "));
	}

	/**
	 * 卡片底部那一行：版本、作者、标签。
	 *
	 * <p>没填的项不出现，三个都没填就是空串。
	 *
	 * @param plugin 插件
	 * @return 元信息行，可能为空
	 */
	public static String metaLine(Plugin plugin)
	{
		List<String> parts = new ArrayList<>();

		String version = version(plugin);
		if (!version.isEmpty())
		{
			parts.add("v" + version);
		}

		String authors = authors(plugin);
		if (!authors.isEmpty())
		{
			parts.add("by " + authors);
		}

		String tags = tags(plugin);
		if (!tags.isEmpty())
		{
			parts.add(tags);
		}

		return String.join("  ·  ", parts);
	}

	/**
	 * 按显示宽度截断文本，中文等全角字符算两个宽度。
	 *
	 * @param text     原文
	 * @param maxWidth 最大显示宽度
	 * @return 截断后的文本
	 */
	public static String truncate(String text, int maxWidth)
	{
		if (text == null || text.isEmpty())
		{
			return "";
		}

		int width = 0;
		for (int i = 0; i < text.length(); i++)
		{
			width += isWideChar(text.charAt(i)) ? 2 : 1;
			if (width > maxWidth)
			{
				return text.substring(0, i) + "…";
			}
		}
		return text;
	}

	/**
	 * 判断是不是全角字符（中日韩文字、全角标点等）。
	 */
	private static boolean isWideChar(char c)
	{
		return c >= 0x1100 && (c <= 0x115F
			|| c == 0x2329 || c == 0x232A
			|| (c >= 0x2E80 && c <= 0xA4CF && c != 0x303F)
			|| (c >= 0xAC00 && c <= 0xD7A3)
			|| (c >= 0xF900 && c <= 0xFAFF)
			|| (c >= 0xFE30 && c <= 0xFE6F)
			|| (c >= 0xFF00 && c <= 0xFF60)
			|| (c >= 0xFFE0 && c <= 0xFFE6));
	}

	/**
	 * 列表里每一项的卡片渲染器。
	 *
	 * <p>三行：脚本名、描述、版本/作者/标签。哪行没内容就留空，行高不变。
	 */
	private static class ScriptCardRenderer extends JPanel implements ListCellRenderer<Plugin>
	{
		private final JLabel nameLabel = new JLabel();
		private final JLabel descriptionLabel = new JLabel();
		private final JLabel metaLabel = new JLabel();

		ScriptCardRenderer()
		{
			setOpaque(true);
			setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS));

			nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 13f));
			nameLabel.setForeground(Color.WHITE);

			descriptionLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			descriptionLabel.setFont(descriptionLabel.getFont().deriveFont(12f));

			metaLabel.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
			metaLabel.setFont(metaLabel.getFont().deriveFont(11f));

			for (JLabel label : Arrays.asList(nameLabel, descriptionLabel, metaLabel))
			{
				label.setAlignmentX(LEFT_ALIGNMENT);
				add(label);
			}

			setPreferredSize(new Dimension(0, CARD_HEIGHT));
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends Plugin> list, Plugin value, int index,
			boolean isSelected, boolean cellHasFocus)
		{
			if (value != null)
			{
				nameLabel.setText(displayName(value));
				descriptionLabel.setText(description(value));
				metaLabel.setText(metaLine(value));
			}
			else
			{
				nameLabel.setText("");
				descriptionLabel.setText("");
				metaLabel.setText("");
			}

			if (isSelected)
			{
				setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
				// 左边一条品牌色的竖条表示选中
				setBorder(new MatteBorder(0, 3, 0, 0, ColorScheme.BRAND_ORANGE));
			}
			else
			{
				setBackground(list.getBackground());
				setBorder(new EmptyBorder(0, 3, 0, 0));
			}

			return this;
		}
	}
}
