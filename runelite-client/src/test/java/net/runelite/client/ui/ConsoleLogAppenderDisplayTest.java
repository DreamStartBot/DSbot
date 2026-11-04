package net.runelite.client.ui;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 日志框的显示字段开关。
 *
 * <p>默认只显示「时间 + 消息」，日志等级和类名是可选的。
 */
public class ConsoleLogAppenderDisplayTest
{
	private LoggerContext context;
	private StringBuilder console;
	private ConsoleLogAppender appender;

	@Before
	public void setUp()
	{
		context = new LoggerContext();
		console = new StringBuilder();
		appender = new ConsoleLogAppender((level, line) -> console.append(line));
		appender.setContext(context);
		appender.start();
		context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
	}

	@After
	public void tearDown()
	{
		// 开关是静态的，不重置的话会漏到别的测试里。
		ConsoleLogAppender.setShowLevel(false);
		ConsoleLogAppender.setShowLogger(false);
		context.stop();
	}

	@Test
	public void byDefaultOnlyTheTimeAndTheMessageAreShown()
	{
		log("hello");

		String line = console.toString();
		assertTrue("行里应该有消息：" + line, line.contains("hello"));
		assertFalse("不该有日志等级：" + line, line.contains("WARN"));
		assertFalse("不该有类名：" + line, line.contains("com.example.Thing"));
	}

	@Test
	public void theLevelCanBeTurnedOn()
	{
		ConsoleLogAppender.setShowLevel(true);
		log("hello");

		String line = console.toString();
		assertTrue("行里应该有日志等级：" + line, line.contains("WARN"));
		assertTrue("行里应该有消息：" + line, line.contains("hello"));
		assertFalse("不该有类名：" + line, line.contains("com.example.Thing"));
	}

	@Test
	public void theLoggerNameCanBeTurnedOn()
	{
		ConsoleLogAppender.setShowLogger(true);
		log("hello");

		String line = console.toString();
		assertTrue("行里应该有类名：" + line, line.contains("com.example.Thing"));
		assertTrue("行里应该有消息：" + line, line.contains("hello"));
		assertFalse("不该有日志等级：" + line, line.contains("WARN"));
	}

	@Test
	public void bothCanBeTurnedOnAtOnce()
	{
		ConsoleLogAppender.setShowLevel(true);
		ConsoleLogAppender.setShowLogger(true);
		log("hello");

		String line = console.toString();
		assertTrue("行里应该有日志等级：" + line, line.contains("WARN"));
		assertTrue("行里应该有类名：" + line, line.contains("com.example.Thing"));
		assertTrue("行里应该有消息：" + line, line.contains("hello"));
	}

	/**
	 * 关掉之后要能改回去 —— 开关是双向的。
	 */
	@Test
	public void theSwitchesCanBeTurnedBackOff()
	{
		ConsoleLogAppender.setShowLevel(true);
		ConsoleLogAppender.setShowLogger(true);
		ConsoleLogAppender.setShowLevel(false);
		ConsoleLogAppender.setShowLogger(false);

		log("hello");

		String line = console.toString();
		assertFalse("不该有日志等级：" + line, line.contains("WARN"));
		assertFalse("不该有类名：" + line, line.contains("com.example.Thing"));
		assertTrue("行里应该有消息：" + line, line.contains("hello"));
	}

	/**
	 * 改开关只影响之后的行，之前已经写进去的不受影响。
	 */
	@Test
	public void theSwitchOnlyAffectsLaterLines()
	{
		log("first");
		ConsoleLogAppender.setShowLevel(true);
		log("second");

		String[] lines = console.toString().split("\n");
		assertTrue("至少要写进去两行，实际 " + lines.length, lines.length >= 2);
		assertFalse("第一行不该有等级：" + lines[0], lines[0].contains("WARN"));
		assertTrue("第二行该有等级：" + lines[1], lines[1].contains("WARN"));
	}

	private void log(String message)
	{
		Logger logger = context.getLogger("com.example.Thing");
		logger.warn(message);
	}
}
