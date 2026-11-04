/*
 * Copyright (c) 2024 Microbot Contributors
 * All rights reserved.
 */
package net.runelite.client.ui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.joran.spi.ConsoleTarget;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.function.BiConsumer;
import net.runelite.client.util.ResetResistantAttachment;

final class ConsoleLogAppender extends AppenderBase<ILoggingEvent>
{
	static final int MAX_REBIND_ATTEMPTS = 1000;

	// ===================== [MICROBOT 本地改动] 按等级着色 =====================
	// 消费方要拿到日志等级才能给每行上色，所以回调从 Consumer<String>
	// 变成 BiConsumer<Level, String>：第一个参数是等级，第二个是格式化后的整行。
	// ===================== [MICROBOT 本地改动 END] =====================
	private final BiConsumer<Level, String> logConsumer;
	private final OutputStream stdout;
	private volatile PatternLayout layout;
	private ResetResistantAttachment attachment;
	private int rebindAttemptsLeft;

	// ===================== [MICROBOT 本地改动] 日志显示字段开关 =====================
	// 本地新增：日志框里默认只显示「时间 + 消息」，日志等级和类名做成可勾选的开关。
	//
	// 上游这里是把格式写死成 "%d{HH:mm:ss} %-5level %logger{36} - %msg%n"，
	// 每行都带着 INFO 和一长串缩写类名，看着很吵。
	//
	// 开关是 static 的：appender 挂在 root logger 上，只有一个实例，
	// 而勾选框在 LogConsolePanel 里，两者不在同一个对象图上。
	// activeInstance 指向当前装好的那个，改开关时用它去重建格式。
	//
	// 冲突时如何覆盖：保留这两个开关字段和下面几个读写方法。
	// ===================== [MICROBOT 本地改动 END] =====================
	private static volatile ConsoleLogAppender activeInstance;
	private static volatile boolean showLevel;
	private static volatile boolean showLogger;

	ConsoleLogAppender(BiConsumer<Level, String> logConsumer)
	{
		this(logConsumer, null);
	}

	private ConsoleLogAppender(BiConsumer<Level, String> logConsumer, OutputStream stdout)
	{
		this.logConsumer = logConsumer;
		this.stdout = stdout;
	}

	/**
	 * 日志等级（INFO、WARN 之类）要不要显示在每行前面。
	 *
	 * @param show true 显示
	 */
	static void setShowLevel(boolean show)
	{
		showLevel = show;
		rebuildActiveLayout();
	}

	/**
	 * 日志等级当前是否显示。
	 *
	 * @return true 表示显示
	 */
	static boolean isShowLevel()
	{
		return showLevel;
	}

	/**
	 * 类名（比如 n.r.c.p.m.t.ToolbarButtonsPlugin）要不要显示在每行前面。
	 *
	 * @param show true 显示
	 */
	static void setShowLogger(boolean show)
	{
		showLogger = show;
		rebuildActiveLayout();
	}

	/**
	 * 类名当前是否显示。
	 *
	 * @return true 表示显示
	 */
	static boolean isShowLogger()
	{
		return showLogger;
	}

	/**
	 * 按当前开关拼出 logback 的 pattern。
	 *
	 * <p>两个开关都不开时只剩「时间 + 消息」，也就是：
	 * {@code %d{HH:mm:ss} %msg%n}
	 *
	 * <p>开一个的时候用 {@code -} 把前缀和消息分开，免得粘在一起：
	 * {@code %d{HH:mm:ss} %-5level - %msg%n}
	 *
	 * <p>两个都开就是上游原来的格式。
	 *
	 * @return pattern 字符串
	 */
	private static String buildPattern()
	{
		StringBuilder pattern = new StringBuilder("%d{HH:mm:ss}");

		if (showLevel)
		{
			pattern.append(" %-5level");
		}

		if (showLogger)
		{
			pattern.append(" %logger{36}");
		}

		if (showLevel || showLogger)
		{
			pattern.append(" -");
		}

		return pattern.append(" %msg%n").toString();
	}

	/**
	 * 用当前开关重建格式。
	 */
	private static void rebuildActiveLayout()
	{
		ConsoleLogAppender active = activeInstance;
		if (active != null)
		{
			active.rebuildLayout();
		}
	}

	/**
	 * 重建格式。
	 *
	 * <p>换一个新的 PatternLayout 再整个替换掉，而不是改旧的 ——
	 * 旧的那个可能正在被日志线程读，改一半会拼出半截的行。
	 */
	private void rebuildLayout()
	{
		PatternLayout newLayout = new PatternLayout();
		newLayout.setContext(getContext());
		newLayout.setPattern(buildPattern());
		newLayout.start();
		layout = newLayout;
	}

	static ConsoleLogAppender install(LoggerContext context, OutputStream stdout, BiConsumer<Level, String> logConsumer)
	{
		ConsoleLogAppender appender = new ConsoleLogAppender(logConsumer, stdout);
		appender.setContext(context);
		bindStdoutAppenders(context, stdout);
		appender.start();
		appender.attachment = ResetResistantAttachment.attach(context, appender, appender::restartAfterReset);
		// ===================== [MICROBOT 本地改动] 记住当前实例 =====================
		// 勾选框改开关时靠它去重建格式。
		// ===================== [MICROBOT 本地改动 END] =====================
		activeInstance = appender;
		return appender;
	}

	void uninstall()
	{
		attachment.remove();
		// ===================== [MICROBOT 本地改动] 清掉实例引用 =====================
		if (activeInstance == this)
		{
			activeInstance = null;
		}
		// ===================== [MICROBOT 本地改动 END] =====================
	}

	static boolean bindStdoutAppenders(LoggerContext context, OutputStream stdout)
	{
		OutputStream shielded = new CloseShieldOutputStream(stdout);
		boolean bound = false;
		for (Logger logger : context.getLoggerList())
		{
			Iterator<Appender<ILoggingEvent>> appenders = logger.iteratorForAppenders();
			while (appenders.hasNext())
			{
				Appender<ILoggingEvent> appender = appenders.next();
				if (appender instanceof ConsoleAppender
					&& ConsoleTarget.SystemOut.getName().equals(((ConsoleAppender<ILoggingEvent>) appender).getTarget()))
				{
					((ConsoleAppender<ILoggingEvent>) appender).setOutputStream(shielded);
					bound = true;
				}
			}
		}
		return bound;
	}

	private synchronized void restartAfterReset()
	{
		rebindAttemptsLeft = stdout != null ? MAX_REBIND_ATTEMPTS : 0;
		start();
	}

	synchronized boolean isRebindPending()
	{
		return rebindAttemptsLeft > 0;
	}

	@Override
	public void start()
	{
		rebuildLayout();
		// ===================== [MICROBOT 本地改动] 记住当前实例 =====================
		// 勾选框改开关时靠它去重建格式。
		// 生产里 appender 只装一个；测试里直接 new 出来的也算当前实例，
		// 不然改开关对它们不生效。
		// ===================== [MICROBOT 本地改动 END] =====================
		activeInstance = this;
		super.start();
	}

	@Override
	protected void append(ILoggingEvent eventObject)
	{
		if (!isStarted())
		{
			return;
		}
		if (rebindAttemptsLeft > 0)
		{
			rebindAttemptsLeft = bindStdoutAppenders((LoggerContext) getContext(), stdout) ? 0 : rebindAttemptsLeft - 1;
		}
		String formatted = layout.doLayout(eventObject);
		// ===================== [MICROBOT 本地改动] 把等级一起传出去 =====================
		// 日志框要按等级给每行上色，所以等级得跟着行一起送过去。
		// ===================== [MICROBOT 本地改动 END] =====================
		logConsumer.accept(eventObject.getLevel(), formatted);
	}

	private static final class CloseShieldOutputStream extends FilterOutputStream
	{
		private CloseShieldOutputStream(OutputStream out)
		{
			super(out);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException
		{
			out.write(b, off, len);
		}

		@Override
		public void close() throws IOException
		{
			flush();
		}
	}
}
