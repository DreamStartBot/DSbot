/*
 * Copyright (c) 2024 Microbot Contributors
 * All rights reserved.
 */
package net.runelite.client.ui;

import ch.qos.logback.classic.Level;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.border.MatteBorder;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import net.runelite.api.Constants;
import net.runelite.client.config.ConfigManager;

final class LogConsolePanel extends JPanel
{
	private static final int MAX_CHARACTERS = 100_000;
	// Trimming shifts the view, so it is deferred while the user is scrolled up - but not forever.
	private static final int MAX_CHARACTERS_SCROLLED_UP = 400_000;
	private static final int PREFERRED_HEIGHT = 160;
	private static final int BOTTOM_TOLERANCE_PX = 4;
	// enough passes for a starved layout to catch up, few enough to give up on an off-screen console
	private static final int MAX_SNAP_ATTEMPTS = 20;

	// ===================== [MICROBOT 本地改动] 按日志等级着色 =====================
	// 配色：ERROR 红、WARN 黄、INFO 保持原来的终端绿、DEBUG 暗灰。
	// 背景是 DARK_GRAY_COLOR(40,40,40)，所以 DEBUG 的灰不能太暗，
	// 用 (128,128,128) 才看得清，同时比 INFO 的绿低调。
	// TRACE 跟 DEBUG 一样归到暗灰。
	private static final Color ERROR_COLOR = new Color(255, 85, 85);
	private static final Color WARN_COLOR = new Color(240, 200, 60);
	private static final Color INFO_COLOR = new Color(0, 255, 70);
	private static final Color DEBUG_COLOR = new Color(128, 128, 128);
	// ===================== [MICROBOT 本地改动 END] =====================

	// ===================== [MICROBOT 本地改动] 富文本日志框 =====================
	// 要按行着色就得用有样式的组件，JTextArea 只有一个前景色，
	// 换成了 JTextPane + StyledDocument。
	private final JTextPane textPane = new JTextPane();
	private final JScrollPane scrollPane = new JScrollPane(textPane);
	// 每种颜色一个属性集，插入时直接复用，避免每行都新建。
	private final Map<Color, SimpleAttributeSet> styleByColor = new HashMap<>();
	// ===================== [MICROBOT 本地改动 END] =====================

	// ===================== [MICROBOT 本地改动] 待写缓冲带上等级 =====================
	// 原来是一个 StringBuilder，所有来源的文本混在一起。
	// 现在按行存，每行记住自己的等级，写进文档时才能上对色。
	private final PendingBuffer pending = new PendingBuffer();
	private boolean flushScheduled;
	// ===================== [MICROBOT 本地改动 END] =====================

	private boolean followTail = true;
	private boolean snapQueued;
	private int snapAttempts;
	private int tailCaretDot = -1;

	// ===================== [MICROBOT 本地改动] 显示字段开关的持久化 =====================
	// 勾选框的状态要存到配置里，所以要拿到 ConfigManager。
	// 可以是 null —— 那样开关只在本次运行里生效，不落盘（测试用的就是这个构造器）。
	// ===================== [MICROBOT 本地改动 END] =====================
	private final ConfigManager configManager;

	LogConsolePanel()
	{
		this(null);
	}

	LogConsolePanel(ConfigManager configManager)
	{
		super(new BorderLayout());
		this.configManager = configManager;
		setPreferredSize(new Dimension(Constants.GAME_FIXED_SIZE.width, PREFERRED_HEIGHT));
		setBorder(new MatteBorder(1, 0, 0, 0, ColorScheme.DARKER_GRAY_COLOR));
		setBackground(ColorScheme.DARKER_GRAY_COLOR);

		textPane.setEditable(false);
		textPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		textPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		// 这是没有样式时的兜底颜色，每行的实际颜色由文档里的属性集决定。
		textPane.setForeground(INFO_COLOR);
		textPane.setBorder(null);

		DefaultCaret caret = new DefaultCaret()
		{
			@Override
			protected void adjustVisibility(Rectangle nloc)
			{
				if (getDot() != tailCaretDot || getMark() != tailCaretDot)
				{
					super.adjustVisibility(nloc);
				}
			}
		};
		caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
		textPane.setCaret(caret);

		scrollPane.setBorder(null);
		add(scrollPane, BorderLayout.CENTER);

		// Following is decided by what the user does, never by measuring the view: under a flood the
		// scroll position trails the document, which reads as "the user scrolled up".
		scrollPane.addMouseWheelListener(ev ->
		{
			if (ev.getPreciseWheelRotation() < 0)
			{
				followTail = false;
			}
			reconsiderFollowingTail();
		});

		textPane.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent ev)
			{
				followTail = false;
				SwingUtilities.invokeLater(LogConsolePanel.this::reconsiderFollowingTail);
			}
		});
		textPane.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent ev)
			{
				followTail = false;
			}

			@Override
			public void mouseReleased(MouseEvent ev)
			{
				reconsiderFollowingTail();
			}
		});
		textPane.addMouseMotionListener(new MouseMotionAdapter()
		{
			@Override
			public void mouseDragged(MouseEvent ev)
			{
				followTail = false;
			}
		});

		JScrollBar vertical = scrollPane.getVerticalScrollBar();
		vertical.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent ev)
			{
				followTail = false;
			}

			@Override
			public void mouseReleased(MouseEvent ev)
			{
				reconsiderFollowingTail();
			}
		});
		vertical.addMouseMotionListener(new MouseMotionAdapter()
		{
			@Override
			public void mouseDragged(MouseEvent ev)
			{
				followTail = false;
			}
		});

		textPane.setComponentPopupMenu(buildContextMenu());

		// opening the console lands on the newest line, wherever the view was left
		addHierarchyListener(ev ->
		{
			if ((ev.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing())
			{
				scrollToBottom();
			}
		});
	}

	void scrollToBottom()
	{
		followTail = true;
		snapAttempts = 0;
		queueSnapToBottom();
	}

	// Snapping waits for the next pass: until the insert's revalidation has run the viewport still
	// reports the pre-insert view size and would snap a line short of the newest one.
	private void queueSnapToBottom()
	{
		if (snapQueued)
		{
			return;
		}

		snapQueued = true;
		SwingUtilities.invokeLater(() ->
		{
			snapQueued = false;
			if (followTail)
			{
				snapToBottom();
			}
		});
	}

	private void snapToBottom()
	{
		int end = textPane.getDocument().getLength();
		moveCaretToTail();

		Rectangle2D newestLine;
		try
		{
			// the viewport's laid-out size trails the document, so ask the text view, which reads the
			// newest line's position from the document itself
			newestLine = textPane.modelToView2D(end);
		}
		catch (BadLocationException | IllegalArgumentException ex)
		{
			return;
		}

		if (newestLine == null)
		{
			return;
		}

		textPane.scrollRectToVisible(newestLine.getBounds());

		JViewport viewport = scrollPane.getViewport();

		// The scroll is clamped to the size the text area was last laid out at, so under load it can
		// land short, and nothing else would correct that - keep trying while the layout catches up.
		boolean reachedNewestLine = newestLine.getMaxY()
			<= viewport.getViewPosition().y + viewport.getExtentSize().height + 1;
		if (reachedNewestLine || ++snapAttempts > MAX_SNAP_ATTEMPTS)
		{
			snapAttempts = 0;
			return;
		}

		queueSnapToBottom();
	}

	// One batch per event queue pass. A line per pass is what breaks the console under load: each
	// insert schedules its own layout, and a busy script logs faster than the queue can run them.
	void append(String text)
	{
		append(null, text);
	}

	// ===================== [MICROBOT 本地改动] 按等级着色 =====================
	/**
	 * 追加一段文本，并给每一行上色。
	 *
	 * @param level 这一段的日志等级。传 null 表示没有等级 ——
	 *              脚本里直接用 System.out.println 打出来的内容就是这种，
	 *              它们用默认的绿色显示。
	 * @param text  文本，可能包含多行
	 */
	void append(Level level, String text)
	{
		if (text == null || text.isEmpty())
		{
			return;
		}

		synchronized (pending)
		{
			pending.add(level, text);
			pending.trimTo(MAX_CHARACTERS_SCROLLED_UP);

			if (flushScheduled)
			{
				return;
			}
			flushScheduled = true;
		}

		SwingUtilities.invokeLater(this::flushPending);
	}

	/**
	 * 丢掉最前面几行，直到剩下的字符数不超过上限。
	 *
	 * <p>按整行丢，不能丢半行 —— 半行会拼出乱七八糟的内容。
	 */
	// ===================== [MICROBOT 本地改动 END] =====================

	// 待写缓冲改成按行存之后，这个方法在生产代码里暂时没人用了，
	// 但它是上游留下来的行边界工具，留着以免跟上游同步时多一处冲突。
	static int lineStartAtOrAfter(CharSequence text, int from)
	{
		if (from <= 0)
		{
			return 0;
		}
		if (text.charAt(from - 1) == '\n')
		{
			return from;
		}
		for (int i = from; i < text.length(); i++)
		{
			if (text.charAt(i) == '\n')
			{
				return i + 1;
			}
		}
		return from;
	}

	private void flushPending()
	{
		List<PendingLine> batch;
		synchronized (pending)
		{
			flushScheduled = false;
			if (pending.isEmpty())
			{
				return;
			}

			int cap = followTail ? MAX_CHARACTERS : MAX_CHARACTERS_SCROLLED_UP;
			batch = pending.drain(cap);
		}

		appendOnEdt(batch);
	}

	private JPopupMenu buildContextMenu()
	{
		JPopupMenu menu = new JPopupMenu();
		JMenuItem copy = new JMenuItem("Copy");
		copy.addActionListener(ev -> textPane.copy());
		JMenuItem copyAll = new JMenuItem("Copy all");
		copyAll.addActionListener(ev -> copyToClipboard(textPane.getText()));
		JMenuItem selectAll = new JMenuItem("Select all");
		selectAll.addActionListener(ev ->
		{
			followTail = false;
			textPane.requestFocusInWindow();
			textPane.selectAll();
		});
		JMenuItem clear = new JMenuItem("Clear");
		clear.addActionListener(ev -> clear());
		menu.add(copy);
		menu.add(copyAll);
		menu.add(selectAll);
		menu.addSeparator();
		menu.add(clear);

		// ===================== [MICROBOT 本地改动] 显示字段开关 =====================
		// 日志等级和类名做成可勾选的，默认都不显示，每行只留时间和消息。
		JCheckBoxMenuItem showLevel = new JCheckBoxMenuItem("Show log level");
		showLevel.addActionListener(ev ->
		{
			boolean selected = showLevel.isSelected();
			ConsoleLogAppender.setShowLevel(selected);
			persist(ClientUI.CONFIG_CLIENT_CONSOLE_SHOW_LEVEL, selected);
		});
		JCheckBoxMenuItem showLogger = new JCheckBoxMenuItem("Show logger name");
		showLogger.addActionListener(ev ->
		{
			boolean selected = showLogger.isSelected();
			ConsoleLogAppender.setShowLogger(selected);
			persist(ClientUI.CONFIG_CLIENT_CONSOLE_SHOW_LOGGER, selected);
		});
		menu.addSeparator();
		menu.add(showLevel);
		menu.add(showLogger);
		// ===================== [MICROBOT 本地改动 END] =====================

		menu.addPopupMenuListener(new PopupMenuListener()
		{
			@Override
			public void popupMenuWillBecomeVisible(PopupMenuEvent ev)
			{
				copy.setEnabled(textPane.getSelectionStart() != textPane.getSelectionEnd());
				boolean hasText = textPane.getDocument().getLength() > 0;
				copyAll.setEnabled(hasText);
				selectAll.setEnabled(hasText);
				clear.setEnabled(hasText);
				// ===================== [MICROBOT 本地改动] 同步勾选状态 =====================
				// 开关可能已经被别处改过了，弹出时按当前实际状态显示。
				showLevel.setSelected(ConsoleLogAppender.isShowLevel());
				showLogger.setSelected(ConsoleLogAppender.isShowLogger());
				// ===================== [MICROBOT 本地改动 END] =====================
			}

			@Override
			public void popupMenuWillBecomeInvisible(PopupMenuEvent ev)
			{
			}

			@Override
			public void popupMenuCanceled(PopupMenuEvent ev)
			{
			}
		});
		return menu;
	}

	// ===================== [MICROBOT 本地改动] 显示字段开关的持久化 =====================
	/**
	 * 把开关状态存进配置。
	 *
	 * @param key 配置的键
	 * @param value 开关状态
	 */
	private void persist(String key, boolean value)
	{
		if (configManager == null)
		{
			return;
		}

		configManager.setConfiguration(ClientUI.CONFIG_GROUP, key, value);
	}
	// ===================== [MICROBOT 本地改动 END] =====================

	private static void copyToClipboard(String text)
	{
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
	}

	void clear()
	{
		synchronized (pending)
		{
			pending.clear();
		}

		if (SwingUtilities.isEventDispatchThread())
		{
			textPane.setText("");
		}
		else
		{
			SwingUtilities.invokeLater(() -> textPane.setText(""));
		}
	}

	OutputStream createOutputStream()
	{
		return new ConsoleOutputStream();
	}

	// ===================== [MICROBOT 本地改动] 按等级着色 =====================
	/**
	 * 把一批带等级的行走进文档。
	 *
	 * <p>连续的同等级行合并成一次插入，文档改动次数少了性能才撑得住高频日志。
	 */
	private void appendOnEdt(List<PendingLine> batch)
	{
		Document document = textPane.getDocument();
		try
		{
			boolean atBottom = followTail;
			int length = document.getLength();

			int i = 0;
			while (i < batch.size())
			{
				Level level = batch.get(i).level;
				StringBuilder chunk = new StringBuilder();
				while (i < batch.size() && batch.get(i).level == level)
				{
					chunk.append(batch.get(i).text);
					i++;
				}

				String text = chunk.toString().replace("\r", "");
				document.insertString(length, text, attributesFor(level));
				length += text.length();
			}

			trimIfNecessary(atBottom);
			if (atBottom)
			{
				moveCaretToTail();
				// NEVER_UPDATE stops the caret dragging the view, so move it ourselves; the attempt
				// budget is per batch, or a flood that outruns the layout would exhaust it for good.
				snapAttempts = 0;
				queueSnapToBottom();
			}
		}
		catch (BadLocationException ex)
		{
			// Ignore append failures to avoid recursive logging.
		}
	}

	/**
	 * 取某个等级对应的文字样式。样式按颜色缓存，每种颜色只建一次。
	 */
	private SimpleAttributeSet attributesFor(Level level)
	{
		Color color = colorFor(level);
		return styleByColor.computeIfAbsent(color, c ->
		{
			SimpleAttributeSet attributes = new SimpleAttributeSet();
			StyleConstants.setForeground(attributes, c);
			// 字体跟着组件走，免得富文本组件用默认字体把等宽的排版打乱。
			Font font = textPane.getFont();
			if (font != null)
			{
				StyleConstants.setFontFamily(attributes, font.getFamily());
				StyleConstants.setFontSize(attributes, font.getSize());
			}
			return attributes;
		});
	}

	/**
	 * 等级到颜色的映射。
	 *
	 * <p>ERROR 红、WARN 黄、INFO 绿、DEBUG（和 TRACE）暗灰。
	 * 没有等级的输出按 INFO 的绿色处理。
	 */
	static Color colorFor(Level level)
	{
		if (level == null)
		{
			return INFO_COLOR;
		}

		switch (level.toInt())
		{
			case Level.ERROR_INT:
				return ERROR_COLOR;
			case Level.WARN_INT:
				return WARN_COLOR;
			case Level.INFO_INT:
				return INFO_COLOR;
			default:
				return DEBUG_COLOR;
		}
	}
	// ===================== [MICROBOT 本地改动 END] =====================

	/**
	 * 待写缓冲里的一行，连着它的日志等级。
	 */
	private static final class PendingLine
	{
		private final Level level;
		private final String text;

		private PendingLine(Level level, String text)
		{
			this.level = level;
			this.text = text;
		}
	}

	/**
	 * 待写缓冲：按行存，每行记住自己的等级。
	 *
	 * <p>顺手记着总字符数，这样判断要不要裁剪不用每次都遍历一遍 ——
	 * 高频日志下每来一行都遍历整个缓冲会变成平方级的开销。
	 */
	private static final class PendingBuffer
	{
		private final List<PendingLine> lines = new ArrayList<>();
		private int chars;

		/**
		 * 把文本按换行拆成一行一行加进来，每行记住自己的等级。
		 * 换行符留在行尾，拼回去还是原来的样子。
		 */
		void add(Level level, String text)
		{
			int start = 0;
			int length = text.length();
			while (start < length)
			{
				int newline = text.indexOf('\n', start);
				String line = newline < 0
					? text.substring(start)
					: text.substring(start, newline + 1);
				lines.add(new PendingLine(level, line));
				chars += line.length();
				if (newline < 0)
				{
					return;
				}
				start = newline + 1;
			}
		}

		/**
		 * 丢掉最前面几行，直到剩下的字符数不超过上限。
		 *
		 * <p>按整行丢，不能丢半行 —— 半行会拼出乱七八糟的内容。
		 */
		void trimTo(int cap)
		{
			if (chars <= cap)
			{
				return;
			}

			int excess = chars - cap;
			int dropped = 0;
			while (dropped < lines.size() && excess > 0)
			{
				excess -= lines.get(dropped).text.length();
				chars -= lines.get(dropped).text.length();
				dropped++;
			}
			lines.subList(0, dropped).clear();
		}

		boolean isEmpty()
		{
			return lines.isEmpty();
		}

		List<PendingLine> drain(int cap)
		{
			trimTo(cap);
			List<PendingLine> batch = new ArrayList<>(lines);
			lines.clear();
			chars = 0;
			return batch;
		}

		void clear()
		{
			lines.clear();
			chars = 0;
		}
	}

	private void moveCaretToTail()
	{
		tailCaretDot = textPane.getDocument().getLength();
		textPane.setCaretPosition(tailCaretDot);
	}

	// Deferred so the user's gesture has reached the scrollbar before it is read.
	private void reconsiderFollowingTail()
	{
		SwingUtilities.invokeLater(() ->
		{
			followTail = isScrolledToBottom() && textPane.getSelectionStart() == textPane.getSelectionEnd();
			if (followTail)
			{
				snapAttempts = 0;
				queueSnapToBottom();
			}
		});
	}

	private boolean isScrolledToBottom()
	{
		JScrollBar vertical = scrollPane.getVerticalScrollBar();
		return vertical.getValue() + vertical.getVisibleAmount() >= vertical.getMaximum() - BOTTOM_TOLERANCE_PX;
	}

	private void trimIfNecessary(boolean atBottom)
	{
		Document document = textPane.getDocument();
		int excess = document.getLength() - (atBottom ? MAX_CHARACTERS : MAX_CHARACTERS_SCROLLED_UP);
		if (excess <= 0)
		{
			return;
		}

		Element lines = document.getDefaultRootElement();
		int lineEnd = lines.getElement(lines.getElementIndex(excess - 1)).getEndOffset();
		int cut = lineEnd <= document.getLength() ? lineEnd : excess;

		try
		{
			document.remove(0, cut);
		}
		catch (BadLocationException ex)
		{
			// Ignore trim failures to avoid recursive logging.
		}
	}

	private final class ConsoleOutputStream extends OutputStream
	{
		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

		@Override
		public synchronized void write(int b) throws IOException
		{
			if (b == '\r')
			{
				return;
			}

			buffer.write(b);
			if (b == '\n')
			{
				flushBuffer();
			}
		}

		@Override
		public synchronized void write(byte[] b, int off, int len) throws IOException
		{
			for (int i = 0; i < len; i++)
			{
				write(b[off + i]);
			}
		}

		@Override
		public synchronized void flush() throws IOException
		{
			flushBuffer();
		}

		@Override
		public void close() throws IOException
		{
			flush();
		}

		private void flushBuffer() throws IOException
		{
			if (buffer.size() == 0)
			{
				return;
			}

			String value = buffer.toString(StandardCharsets.UTF_8);
			buffer.reset();
			append(value);
		}
	}
}
