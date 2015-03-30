/*
 * Copyright 2011-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 ** This file was originally published at http://github.com/vertx-x/mod-auth-mgr under the
 ** license mentioned above.
 ** Modifications for role based authentication, and refreshing timeout timer on every successful authotize request
 ** (c) 2015 by G. Weirich
 */

package ch.rgw.vertx;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import org.vertx.java.busmods.BusModBase;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

/**
 * Basic Authentication Manager Bus Module
 * <p>
 * Please see the busmods manual for a full description
 * <p>
 *
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class AuthManager extends BusModBase {

	private Handler<Message<JsonObject>> loginHandler;
	private Handler<Message<JsonObject>> logoutHandler;
	private Handler<Message<JsonObject>> authoriseHandler;

	protected final Map<String, String> sessions = new HashMap<>();
	protected final Map<String, LoginInfo> logins = new HashMap<>();

	private static final long DEFAULT_SESSION_TIMEOUT = 30 * 60 * 1000;

	private String address;
	private String userCollection;
	private String persistorAddress;
	private long sessionTimeout;

	private static final class LoginInfo {
		long timerID;
		final String sessionID;
		final JsonArray roles;

		private LoginInfo(long timerID, String sessionID, JsonArray roles) {
			this.timerID = timerID;
			this.sessionID = sessionID;
			this.roles = roles;
		}
	}

	/**
	 * Start the busmod
	 */
	public void start() {
		super.start();

		this.address = getOptionalStringConfig("address",
				"vertx.basicauthmanager");
		this.userCollection = getOptionalStringConfig("user_collection",
				"users");
		this.persistorAddress = getOptionalStringConfig("persistor_address",
				"vertx.mongopersistor");
		Number timeout = config.getNumber("session_timeout");
		if (timeout != null) {
			if (timeout instanceof Long) {
				this.sessionTimeout = (Long) timeout;
			} else if (timeout instanceof Integer) {
				this.sessionTimeout = (Integer) timeout;
			}
		} else {
			this.sessionTimeout = DEFAULT_SESSION_TIMEOUT;
		}

		loginHandler = new Handler<Message<JsonObject>>() {
			public void handle(Message<JsonObject> message) {
				doLogin(message);
			}
		};
		eb.registerHandler(address + ".login", loginHandler);
		logoutHandler = new Handler<Message<JsonObject>>() {
			public void handle(Message<JsonObject> message) {
				doLogout(message);
			}
		};
		eb.registerHandler(address + ".logout", logoutHandler);
		authoriseHandler = new Handler<Message<JsonObject>>() {
			public void handle(Message<JsonObject> message) {
				doAuthorise(message);
			}
		};
		eb.registerHandler(address + ".authorise", authoriseHandler);
	}

	private void doLogin(final Message<JsonObject> message) {

		final String username = getMandatoryString("username", message);
		if (username == null) {
			return;
		}
		String password = getMandatoryString("password", message);
		if (password == null) {
			return;
		}

		JsonObject findMsg = new JsonObject().putString("action", "findone")
				.putString("collection", userCollection);
		JsonObject matcher = new JsonObject().putString("username", username)
				.putString("password", password);
		findMsg.putObject("matcher", matcher);

		eb.send(persistorAddress, findMsg, new Handler<Message<JsonObject>>() {
			public void handle(Message<JsonObject> reply) {

				if (reply.body().getString("status").equals("ok")) {
					JsonObject result = reply.body().getObject("result");
					if (result != null) {

						// Check if already logged in, if so logout of the old
						// session
						LoginInfo info = logins.get(username);
						if (info != null) {
							logout(info.sessionID);
						}

						// Found
						final String sessionID = UUID.randomUUID().toString();
						long timerID = setTimerFor(username, sessionID);
						sessions.put(sessionID, username);
						JsonArray roles = result.getArray("roles");
						if (roles == null) {
							roles = new JsonArray();
						}
						logins.put(username, new LoginInfo(timerID, sessionID,
								roles));
						JsonObject jsonReply = new JsonObject().putString(
								"sessionID", sessionID)
								.putArray("roles", roles);
						sendOK(message, jsonReply);
					} else {
						// Not found
						sendStatus("denied", message);
					}
				} else {
					logger.error("Failed to execute login query: "
							+ reply.body().getString("message"));
					sendError(message, "Failed to excecute login");
				}
			}

		});
	}

	private long setTimerFor(final String username, final String sessionID) {
		long timerID = vertx.setTimer(sessionTimeout, new Handler<Long>() {
			public void handle(Long timerID) {
				sessions.remove(sessionID);
				logins.remove(username);
			}
		});
		return timerID;
	}

	protected void doLogout(final Message<JsonObject> message) {
		final String sessionID = getMandatoryString("sessionID", message);
		if (sessionID != null) {
			if (logout(sessionID)) {
				sendOK(message);
			} else {
				super.sendError(message, "Not logged in");
			}
		}
	}

	protected boolean logout(String sessionID) {
		String username = sessions.remove(sessionID);
		if (username != null) {
			LoginInfo info = logins.remove(username);
			vertx.cancelTimer(info.timerID);
			return true;
		} else {
			return false;
		}
	}

	protected void doAuthorise(Message<JsonObject> message) {
		String sessionID = getMandatoryString("sessionID", message);
		if (sessionID != null) {
			String username = sessions.get(sessionID);

			if (username != null) {
				LoginInfo li = logins.get(username);

				vertx.cancelTimer(li.timerID);
				li.timerID = setTimerFor(username, li.sessionID);
				JsonArray neededRoles = message.body().getArray("roles");
				boolean bOk = true;
				if (neededRoles != null) {
					bOk = checkRoles(li.roles, neededRoles);
				}
				if (bOk) {
					JsonObject reply = new JsonObject().putString("username",
							username).putArray("roles", li.roles);
					sendOK(message, reply);
				}
			}
		}
		sendStatus("denied", message);
	}

	private boolean checkRoles(JsonArray givenRoles, JsonArray neededRoles) {
		Iterator<Object> it = givenRoles.iterator();
		while (it.hasNext()) {
			String role = (String) it.next();
			Iterator<Object> nit = neededRoles.iterator();
			while (nit.hasNext()) {
				if (role.equalsIgnoreCase((String) nit.next())) {
					return true;
				}
			}
		}
		return false;
	}
}
