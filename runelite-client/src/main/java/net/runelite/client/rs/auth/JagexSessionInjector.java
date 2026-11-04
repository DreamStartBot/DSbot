/*
 * Copyright (c) 2026, Microbot
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.rs.auth;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 把 Jagex 的 session id 和 character id 注入游戏客户端。
 *
 * <p>游戏客户端就是用这两个值组装 Jagex 登录包的，所以不需要 Jagex Launcher、
 * OAuth 流程或交互式角色选择就能登录。注入之后，登录界面会直接出现该角色的登录按钮。
 *
 * <p>客户端还需要角色名（displayName），获取顺序如下：
 * <ol>
 *     <li>调用方已经知道角色名 —— 直接使用，不产生任何网络请求；</li>
 *     <li>否则用 session id 请求
 *     {@code https://auth.jagex.com/game-session/v1/accounts} 拉取角色列表，
 *     再按 character id 匹配出角色名。</li>
 * </ol>
 *
 * <p>session id 是短时效凭据。microbot 不保存 refresh token，
 * 因此这里无法为过期的 session id 续期，过期后必须重新获取。
 *
 * <p>【MICROBOT 本地改动】本文件是为 {@code --session-id} / {@code --character-id}
 * / {@code --display-name} 这三个启动参数新增的，不属于上游代码。
 * 上游日后若也在 {@code net.runelite.client.rs.auth} 下加入功能相近的类，
 * 两者可以共存；若出现同名类冲突，以保留本类、把上游实现改名或合并的方式处理。
 */
@Slf4j
@Singleton
@AllArgsConstructor(onConstructor_ = @Inject)
public class JagexSessionInjector
{
	private final JagexAuthService jagexAuthService;
	private final JagexAuthClient jagexAuthClient;
	private final ScheduledExecutorService executor;

	/**
	 * 把给定的凭据注入游戏客户端。
	 *
	 * @param sessionId   Jagex 的 session id
	 * @param characterId 角色 id，账号下只有一个角色时可以为 null
	 * @param displayName 角色名，为 null 时会去查询
	 */
	public void inject(String sessionId, String characterId, String displayName)
	{
		if (sessionId == null || sessionId.isBlank())
		{
			return;
		}

		if (displayName != null && !displayName.isBlank())
		{
			apply(sessionId, characterId, displayName.trim());
			return;
		}

		// 拉取角色列表是网络请求，不能占用客户端线程，放到后台线程执行。
		executor.execute(() -> resolveAndApply(sessionId, characterId));
	}

	private void resolveAndApply(String sessionId, String characterId)
	{
		final Account[] accounts;
		try
		{
			accounts = jagexAuthClient.getAccounts(sessionId);
		}
		catch (IOException e)
		{
			log.error("Unable to read the Jagex characters for the given session id (it may have expired)", e);
			return;
		}

		if (accounts == null || accounts.length == 0)
		{
			log.error("The given Jagex session id returned no characters");
			return;
		}

		if (characterId != null && !characterId.isBlank())
		{
			String wanted = characterId.trim();
			for (Account account : accounts)
			{
				if (Long.toString(account.getAccountId()).equals(wanted))
				{
					apply(sessionId, wanted, account.getDisplayName());
					return;
				}
			}
			log.error("Character id {} was not found in the Jagex account. Available characters: {}",
				wanted, describe(accounts));
			return;
		}

		if (accounts.length == 1)
		{
			Account account = accounts[0];
			apply(sessionId, Long.toString(account.getAccountId()), account.getDisplayName());
			return;
		}

		log.error("The Jagex account has {} characters, so a character id is required. Available characters: {}",
			accounts.length, describe(accounts));
	}

	private void apply(String sessionId, String characterId, String displayName)
	{
		log.info("Injecting the Jagex character {} from the given session id", displayName);
		jagexAuthService.switchJagexAccount(sessionId, characterId, displayName);
	}

	private static String describe(Account[] accounts)
	{
		return Arrays.stream(accounts)
			.map(account -> account.getDisplayName() + " (" + account.getAccountId() + ")")
			.collect(Collectors.joining(", "));
	}
}
