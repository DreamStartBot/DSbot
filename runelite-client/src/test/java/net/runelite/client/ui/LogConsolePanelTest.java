package net.runelite.client.ui;

import ch.qos.logback.classic.Level;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.plaf.basic.BasicTextPaneUI;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import javax.swing.text.Position;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LogConsolePanelTest
{
	private static final int DOCUMENT_CAP = 100_000;

	private LogConsolePanel panel;
	private JScrollPane scrollPane;
	private JTextPane textPane;

	@Before
	public void setUp() throws Exception
	{
		onEdt(() ->
		{
			panel = new LogConsolePanel();
			scrollPane = (JScrollPane) panel.getComponent(0);
			textPane = (JTextPane) scrollPane.getViewport().getView();
			panel.setSize(400, 160);
			layOut();
		});
		panel.append(lines(0, 200));
		settle();
		assertTrue(isAtBottom());
	}

	@Test
	public void eachLevelGetsItsOwnColor() throws Exception
	{
		assertEquals(LogConsolePanel.colorFor(Level.ERROR), LogConsolePanel.colorFor(Level.ERROR));
		assertNotEquals(LogConsolePanel.colorFor(Level.ERROR), LogConsolePanel.colorFor(Level.WARN));
		assertNotEquals(LogConsolePanel.colorFor(Level.WARN), LogConsolePanel.colorFor(Level.INFO));
		assertNotEquals(LogConsolePanel.colorFor(Level.INFO), LogConsolePanel.colorFor(Level.DEBUG));
		// 没有等级的输出（脚本里直接 println）跟 INFO 一个颜色
		assertEquals(LogConsolePanel.colorFor(Level.INFO), LogConsolePanel.colorFor(null));
	}

	@Test
	public void linesAreColoredByTheirLevel() throws Exception
	{
		// 用一个干净的 panel，避开 setUp 里已经填进去的普通行
		AtomicReference<LogConsolePanel> fresh = new AtomicReference<>();
		onEdt(() -> fresh.set(new LogConsolePanel()));
		LogConsolePanel clean = fresh.get();

		clean.append(Level.ERROR, "boom\n");
		clean.append(Level.WARN, "careful\n");
		clean.append(Level.INFO, "hello\n");
		clean.append(Level.DEBUG, "details\n");
		settle();

		java.awt.Color[] expected = onEdtGet(() ->
		{
			JTextPane pane = (JTextPane) ((JScrollPane) clean.getComponent(0)).getViewport().getView();
			StyledDocument document = (StyledDocument) pane.getDocument();
			// 按行首偏移量取颜色：样式相同的连续字符合并成一个 element，
			// 按元素序号取会一直落在同一段里。
			int[] starts = {0, 5, 13, 19};
			java.awt.Color[] colors = new java.awt.Color[4];
			for (int i = 0; i < 4; i++)
			{
				colors[i] = StyleConstants.getForeground(document.getCharacterElement(starts[i]).getAttributes());
			}
			return colors;
		});

		assertEquals(LogConsolePanel.colorFor(Level.ERROR), expected[0]);
		assertEquals(LogConsolePanel.colorFor(Level.WARN), expected[1]);
		assertEquals(LogConsolePanel.colorFor(Level.INFO), expected[2]);
		assertEquals(LogConsolePanel.colorFor(Level.DEBUG), expected[3]);
	}

	@Test
	public void bufferCutStartsAtTheNextWholeLine()
	{
		assertEquals(0, LogConsolePanel.lineStartAtOrAfter("one\ntwo\n", 0));
		assertEquals(4, LogConsolePanel.lineStartAtOrAfter("one\ntwo\n", 2));
		assertEquals(4, LogConsolePanel.lineStartAtOrAfter("one\ntwo\n", 4));
		assertEquals(2, LogConsolePanel.lineStartAtOrAfter("one two", 2));
	}

	@Test
	public void rightClickMenuOffersCopyOnlyForASelection() throws Exception
	{
		onEdt(() ->
		{
			JPopupMenu menu = textPane.getComponentPopupMenu();
			assertEquals(Arrays.asList("Copy", "Copy all", "Select all", "Clear", "Show log level", "Show logger name"),
				Arrays.stream(menu.getComponents())
				.filter(c -> c instanceof JMenuItem)
				.map(c -> ((JMenuItem) c).getText())
				.collect(Collectors.toList()));
			JMenuItem copy = (JMenuItem) menu.getComponent(0);

			textPane.select(0, 0);
			fireWillBecomeVisible(menu);
			assertFalse(copy.isEnabled());

			textPane.select(0, 5);
			fireWillBecomeVisible(menu);
			assertTrue(copy.isEnabled());
		});
	}

	private static void fireWillBecomeVisible(JPopupMenu menu)
	{
		PopupMenuEvent event = new PopupMenuEvent(menu);
		for (PopupMenuListener listener : menu.getPopupMenuListeners())
		{
			listener.popupMenuWillBecomeVisible(event);
		}
	}

	@Test
	public void wheelUpWinsOverAnAlreadyQueuedSnap() throws Exception
	{
		onEdt(() ->
		{
			panel.append(lines(200, 50));
			SwingUtilities.invokeLater(() ->
			{
				layOut();
				MouseWheelEvent wheelUp = new MouseWheelEvent(scrollPane, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(),
					0, 10, 10, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -1);
				for (MouseWheelListener listener : scrollPane.getMouseWheelListeners())
				{
					listener.mouseWheelMoved(wheelUp);
				}
			});
		});
		settle();
		assertFalse(isAtBottom());

		panel.append(lines(250, 50));
		settle();
		assertFalse(isAtBottom());
	}

	@Test
	public void pageUpStopsFollowing() throws Exception
	{
		press(KeyEvent.VK_PAGE_UP, 0);
		settle();
		assertFalse(isAtBottom());

		panel.append(lines(200, 50));
		settle();
		assertFalse(isAtBottom());
	}

	@Test
	public void ctrlEndAfterPageUpResumesFollowing() throws Exception
	{
		press(KeyEvent.VK_PAGE_UP, 0);
		settle();
		panel.append(lines(200, 50));
		settle();
		assertFalse(isAtBottom());

		press(KeyEvent.VK_END, InputEvent.CTRL_DOWN_MASK);
		settle();
		panel.append(lines(250, 50));
		settle();
		assertTrue(isAtBottom());
	}

	@Test
	public void dragSelectionSurvivesTheNextBatch() throws Exception
	{
		AtomicReference<Point> start = new AtomicReference<>();
		onEdt(() ->
		{
			Point view = scrollPane.getViewport().getViewPosition();
			start.set(new Point(5, view.y + 20));
			textPane.dispatchEvent(mouse(MouseEvent.MOUSE_PRESSED, start.get().x, start.get().y));
			textPane.dispatchEvent(mouse(MouseEvent.MOUSE_DRAGGED, start.get().x + 60, start.get().y + 20));
			textPane.dispatchEvent(mouse(MouseEvent.MOUSE_RELEASED, start.get().x + 60, start.get().y + 20));
		});
		settle();
		String selected = onEdtGet(textPane::getSelectedText);
		assertTrue(selected != null && !selected.isEmpty());

		panel.append(lines(200, 50));
		settle();
		assertEquals(selected, onEdtGet(textPane::getSelectedText));
	}

	@Test
	public void wheelDownToTheBottomResumesFollowing() throws Exception
	{
		onEdt(() ->
		{
			JScrollBar vertical = scrollPane.getVerticalScrollBar();
			vertical.setValue(vertical.getMaximum() / 2);
		});
		wheel(-3);
		settle();
		assertFalse(isAtBottom());

		onEdt(() ->
		{
			JScrollBar vertical = scrollPane.getVerticalScrollBar();
			vertical.setValue(vertical.getMaximum());
		});
		wheel(3);
		settle();
		panel.append(lines(200, 50));
		settle();
		assertTrue(isAtBottom());
	}

	@Test
	public void starvedEdtInsertsOnlyWhatAFollowerKeeps() throws Exception
	{
		List<Integer> inserts = new ArrayList<>();
		onEdt(() -> textPane.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent ev)
			{
				inserts.add(ev.getLength());
			}

			@Override
			public void removeUpdate(DocumentEvent ev)
			{
			}

			@Override
			public void changedUpdate(DocumentEvent ev)
			{
			}
		}));

		CountDownLatch blocked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicBoolean released = new AtomicBoolean();
		SwingUtilities.invokeLater(() ->
		{
			blocked.countDown();
			try
			{
				released.set(release.await(30, TimeUnit.SECONDS));
			}
			catch (InterruptedException ex)
			{
				Thread.currentThread().interrupt();
			}
		});
		assertTrue(blocked.await(30, TimeUnit.SECONDS));

		int next = 1_000;
		String last = null;
		while (next < 1_000 + 80_000)
		{
			panel.append(lines(next, 1_000));
			next += 1_000;
			last = "line " + (next - 1) + "\n";
		}
		release.countDown();
		settle();
		assertTrue(released.get());

		int largest = inserts.stream().mapToInt(Integer::intValue).max().orElse(0);
		assertTrue("largest insert " + largest, largest <= DOCUMENT_CAP);
		String text = onEdtGet(() ->
		{
			try
			{
				return textPane.getDocument().getText(0, textPane.getDocument().getLength());
			}
			catch (BadLocationException ex)
			{
				throw new IllegalStateException(ex);
			}
		});
		assertTrue(text.length() <= DOCUMENT_CAP);
		assertTrue(text.endsWith(last));
		assertNotEquals(-1, text.indexOf("line " + (next - 2) + "\n"));
	}

	@Test
	public void layoutFailureWhileSnappingStaysOffTheEdt() throws Exception
	{
		AtomicReference<Throwable> uncaught = new AtomicReference<>();
		Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> uncaught.set(ex));
		try
		{
			onEdt(() -> textPane.setUI(new BasicTextPaneUI()
			{
				@Override
				public Rectangle2D modelToView2D(JTextComponent component, int pos, Position.Bias bias)
				{
					throw new IllegalArgumentException("TextHitInfo is out of range");
				}
			}));
			panel.scrollToBottom();
			settle();
		}
		finally
		{
			Thread.setDefaultUncaughtExceptionHandler(previous);
		}
		assertNull(uncaught.get());
	}

	@Test
	public void trimmingDropsWholeLines() throws Exception
	{
		int length = onEdtGet(() -> textPane.getDocument().getLength());
		panel.append("y".repeat(DOCUMENT_CAP - length + 2) + "\n");
		settle();
		String text = onEdtGet(() ->
		{
			try
			{
				return textPane.getDocument().getText(0, textPane.getDocument().getLength());
			}
			catch (BadLocationException ex)
			{
				throw new IllegalStateException(ex);
			}
		});
		assertTrue(text.length() <= DOCUMENT_CAP);
		assertTrue(text.startsWith("line 1\n"));
		assertTrue(text.endsWith("y\n"));
	}

	private void press(int keyCode, int modifiers) throws Exception
	{
		onEdt(() ->
		{
			KeyEvent key = new KeyEvent(textPane, KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
				modifiers, keyCode, KeyEvent.CHAR_UNDEFINED);
			for (KeyListener listener : textPane.getKeyListeners())
			{
				listener.keyPressed(key);
			}
			Object binding = textPane.getInputMap().get(KeyStroke.getKeyStrokeForEvent(key));
			textPane.getActionMap().get(binding).actionPerformed(new ActionEvent(textPane, ActionEvent.ACTION_PERFORMED, null));
		});
	}

	private void wheel(int rotation) throws Exception
	{
		onEdt(() ->
		{
			MouseWheelEvent event = new MouseWheelEvent(scrollPane, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(),
				0, 10, 10, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation);
			for (MouseWheelListener listener : scrollPane.getMouseWheelListeners())
			{
				listener.mouseWheelMoved(event);
			}
		});
	}

	private MouseEvent mouse(int id, int x, int y)
	{
		int modifiers = id == MouseEvent.MOUSE_RELEASED ? 0 : InputEvent.BUTTON1_DOWN_MASK;
		return new MouseEvent(textPane, id, System.currentTimeMillis(), modifiers, x, y, 1, false, MouseEvent.BUTTON1);
	}

	private void layOut()
	{
		for (int i = 0; i < 2; i++)
		{
			panel.doLayout();
			scrollPane.doLayout();
			scrollPane.getViewport().doLayout();
		}
	}

	private void settle() throws Exception
	{
		for (int i = 0; i < 40; i++)
		{
			onEdt(this::layOut);
		}
	}

	private boolean isAtBottom() throws Exception
	{
		return onEdtGet(() ->
		{
			JScrollBar vertical = scrollPane.getVerticalScrollBar();
			return vertical.getValue() + vertical.getVisibleAmount() >= vertical.getMaximum() - 4;
		});
	}

	private static String lines(int from, int count)
	{
		StringBuilder text = new StringBuilder();
		for (int i = from; i < from + count; i++)
		{
			text.append("line ").append(i).append('\n');
		}
		return text.toString();
	}

	private static void onEdt(Runnable runnable) throws Exception
	{
		SwingUtilities.invokeAndWait(runnable);
	}

	private static <T> T onEdtGet(Supplier<T> supplier) throws Exception
	{
		AtomicReference<T> result = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> result.set(supplier.get()));
		return result.get();
	}
}
