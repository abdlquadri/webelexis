/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ch.rgw.vertx;

import ch.webelexis.Cleaner;
import ch.webelexis.ParametersException;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.AsyncResultHandler;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SessionManager extends AbstractVerticle {
  private static final long DEFAULT_TIMEOUT = 10 * 60 * 1000;

  private String persistorAddress;
  private String usersCollection;
  private final Map<String, Session> sessions = new HashMap<>();
  private long TIMEOUT;
  private final Logger log = Logger.getLogger("SessionManager");
  private EventBus eb;

  public void start() {
    Config cfg = new Config(config().getJsonObject("auth"));
    String basicAddress = cfg.getOptionalString("address", "ch.rgw.sessions");
    persistorAddress = cfg.getOptionalString("persistor_address", "ch.rgw.nosql");
    usersCollection = cfg.getOptionalString("users_collection", "users");
    TIMEOUT = cfg.getOptionalLong("session_timeout", DEFAULT_TIMEOUT);
    eb = getVertx().eventBus();

    eb.consumer(basicAddress + ".create", createHandler);
    eb.consumer(basicAddress + ".login", loginHandler);
    eb.consumer(basicAddress + ".logout", logoutHandler);
    eb.consumer(basicAddress + ".authorize", authorizeHandler);
    eb.consumer(basicAddress + ".admin", adminHandler);
    eb.consumer(basicAddress + ".put", putHandler);
    eb.consumer(basicAddress + ".get", getHandler);

  }

  /*
   * create a new Session
   */
  private final Handler<Message<JsonObject>> createHandler = msg -> {
    Session session = new Session();
    if (msg.body() != null) {
      session.store.put("params", msg.body());
    }
    sessions.put(session.id, session);
    msg.reply(new JsonObject().put("sessionID", session.id).put("status", "ok"));
    log.info(new Date().toString() + " - Created session: " + session.id);
  };

  private final Handler<Message<JsonObject>> loginHandler = externalRequest -> {
    final Session session = checkSession(externalRequest);
    final Cleaner cl = new Cleaner(externalRequest);
    log.log(Level.FINEST, "try login " + externalRequest.body().encodePrettily());
    if (session != null) {
      if (session.failTime != 0) {
        long timeSinceLastAttempt = System.currentTimeMillis() - session.failTime;
        if (timeSinceLastAttempt < (session.loginFailures * 5000)) {
          Number sec = ((session.loginFailures * 5000) - timeSinceLastAttempt) / 1000;
          log.info(new Date().toString() + " - SessionMgr denied login. Wait for " + sec
            + " seconds.");
          externalRequest.reply(new JsonObject().put("status", "wait").put("seconds",
            sec));
          return;
        }
      }
      try {
        final String username = cl.get("username", Cleaner.MAIL, false);
        final String mode = cl.get("mode", "(local|google)", false);
        findUser(username, findResult -> {
          log.info("try logging in user " + username);
          JsonObject user = findResult.result().body().getJsonObject("result");
          if (Util.getParm("status", findResult).equals("ok")) {
            if (user == null) {
              fail("unknown user", session, externalRequest);
            } else {
              if (user.getBoolean("verified", true)) {
                if (mode.equals("local")) {
                  log.info("try to log in local user");
                  if (checkPwd(user, externalRequest.body().getString("password", ""))) {
                    session.login(user, externalRequest.body().getString("feedback-address"));
                    externalRequest.reply(new JsonObject().put("status", "ok").put(
                      "user", user));
                  } else {
                    log.info("login deinied for " + user.getString("username"));
                    fail("denied", session, externalRequest);
                  }
                } else if (mode.equals("google")) {
                  // TODO check id_user
                  log.info("try to log in google user " + username);
                  verifyGoogleId(externalRequest, session, user);

                } else {
                  log.severe("unsupported login mode " + mode);
                  Util.sendError(externalRequest, "unsupported login mode");
                }
              } else {
                log.info("user account not yet verified");
                fail("not verified", session, externalRequest);
              }
            }
          } else {
            log.severe(findResult.result().body().encodePrettily());
            Util.sendError(externalRequest, "db error 1");
          }
        });
      } catch (ParametersException pex) {
        Util.sendError(externalRequest, "Parameter error");
      }

    }

  };

  private void fail(String msg, Session session, Message<JsonObject> req) {
    Util.sendStatus(req, msg);
    session.loginFailures++;
    session.failTime = System.currentTimeMillis();

  }

  /*
   * Verify, if an id_token is valid and issued by Google
   */
  private void verifyGoogleId(final Message<JsonObject> externalRequest, final Session session,
                              final JsonObject user) {

    String id = Util.getParm("id_token", externalRequest);
    HttpClientOptions hco = new HttpClientOptions().setSsl(true).setDefaultHost("www.googleapis.com").setDefaultPort(443).setTrustAll(true);
    HttpClient htc = vertx.createHttpClient(hco);
    htc.getNow("/oauth2/v1/tokeninfo?id_token=" + id, resp -> {
      if (resp.statusCode() == 200) {
        resp.bodyHandler(buffer -> {
          log.finest(buffer.toString());
          try {
            JsonObject jwt = new JsonObject(buffer.toString());
            JsonObject params = session.store.get("params");
            String clientID = null;
            String state = null;
            if (params != null) {
              clientID = params.getString("clientID");
              state = params.getString("state");
            }
            if ((clientID != null) && (state != null)
              && jwt.getString("audience").equals(clientID)
              && (jwt.getString("issuer").endsWith("accounts.google.com"))
              && jwt.getInteger("expires_in") > 0
              && jwt.getString("email").equals(user.getString("username"))) {
              session.login(user, externalRequest.body().getString("feedback-address"));
              externalRequest.reply(new JsonObject().put("status", "ok").put(
                "user", user));
            } else {
              log.severe("bad credentials " + clientID);
              fail("denied", session, externalRequest);
            }

          } catch (Throwable ex) {
            log.warning("invalid token for user " + user.getString("username"));
            fail("invalid token", session, externalRequest);
          }
        });
      } else {
        externalRequest.reply(new JsonObject().put("status", "google server failure"));
      }

    });
  }

  private final Handler<Message<JsonObject>> logoutHandler = msg -> {
    Session session = checkSession(msg);
    if (session != null) {
      session.logout();
      Util.sendOK(msg);
    }
  };
  private final Handler<Message<JsonObject>> authorizeHandler = msg -> {
    Session session = checkSession(msg);
    if (session != null) {
      if (isAuthorized(session, Util.getParm("role", msg))) {
        msg.reply(new JsonObject().put("status", "ok").put("authorized_user",
          session.user));
      } else {
        log.info("authorization denied for role " + msg.body().getString("role"));
        Util.sendStatus(msg, "denied");
      }
    }

  };

  private final Handler<Message<JsonObject>> adminHandler = msg -> {
    Session session = checkSession(msg);
    if (session != null) {
      if (!session.getRoles().contains("admin")) {
        Util.sendStatus(msg, "denied");
      } else {
        String cmd = Util.getParm("command", msg);
        if (Objects.equals(cmd, "adduser")) {
          addUser(msg);
        } else if (cmd.equals("removeuser")) {
          removeUser(msg);
        }
      }
    }

  };

  private final Handler<Message<JsonObject>> putHandler = msg -> {
    Session session = checkSession(msg);
    if (session != null) {
      String key = Util.getParm("key", msg);
      JsonObject value = Util.getObject("value", msg);
      session.store.put(key, value);
      Util.sendOK(msg);
    }
  };

  private final Handler<Message<JsonObject>> getHandler = msg -> {
    Session session = checkSession(msg);
    if (session != null) {
      String key = Util.getParm("key", msg);
      JsonObject value = session.store.get(key);
      if (value == null) {
        Util.sendStatus(msg, "not found");
      } else {
        msg.reply(new JsonObject().put("status", "ok").put("result", value));
      }
    }
  };

  /*
   * check whether a valid session exists and is contained as an argument. if
   * not, send an error message to the original caller and return null.
   * Otherwise, refresh the session (i.e. set the expire-timer to its original
   * value) and return it.
   */
  private Session checkSession(Message<JsonObject> msg) {
    Session session = sessions.get(Util.getParm("sessionID", msg));
    if (session == null) {
      Util.sendError(msg, "no session");
      log.warning("no session");
      return null;
    }
    session.refresh();
    return session;
  }

  /*
   * check whether a Session may act as "role". If a session is not logged in,
   * it may only behave as "guest". If it is logged-in, the rights depend on the
   * roles of the user the session belongs (If their "roles" field contains a
   * matching role)
   */
  private boolean isAuthorized(Session session, String role) {

    if ((role == null) || role.equalsIgnoreCase("guest")) {
      return true;
    }
    JsonArray givenRoles = session.getRoles();
    return givenRoles != null && givenRoles.contains(role);
  }

  /*
   * find an entry for a username in the database
   */
  private void findUser(String username, AsyncResultHandler<Message<JsonObject>> callback) {
    JsonObject op = new JsonObject().put("action", "findone").put("collection",
      usersCollection).put("matcher", new JsonObject().put("username", username));
    eb.send(persistorAddress, op, callback);
  }

  /*
   * Add a new user to the database. The user is a JsonObject with the following
   * minimal contents: username: <unique name> password: <string>
   *
   * The user object may contain optional fields, such as roles: <Array of
   * Strings>
   *
   * And any additional free fields
   */
  private void addUser(final Message<JsonObject> msg) {
    final JsonObject user = Util.getObject("user", msg);
    if (user != null) {
      findUser(user.getString("username"), dbReply -> {
        log.finest("mongo result: " + dbReply.result().body().encodePrettily());

        if (Util.getParm("status", dbReply.result()).equals("ok")) {
          if (Util.getParm("result", dbReply) != null) {
            Util.sendError(msg, "user " + user.getString("username") + " already exists");
          } else {
            JsonObject dbUser = makePwd(user);
            if (dbUser != null) {
              JsonArray roles = user.getJsonArray("roles");
              if (roles == null) {
                roles = Util.asJsonArray("user");
              }
              dbUser.put("roles", roles);
              JsonObject op = new JsonObject().put("action", "save").put(
                "collection", usersCollection).put("document", dbUser);
              eb.send(persistorAddress, op, new AsyncResultHandler<Message<JsonObject>>() {

                @Override
                public void handle(AsyncResult<Message<JsonObject>> reply) {
                  msg.reply(reply.result());
                }
              });
            } else {
              Util.sendError(msg, "bad request");
            }

          }
        } else {
          Util.sendError(msg, "database error");
        }
      });
    } else {
      Util.sendError(msg, "no user supplied");

    }

  }

  private void removeUser(final Message<JsonObject> msg) {
    JsonObject op = new JsonObject().put("action", "delete").put("collection",
      usersCollection).put("matcher",
      new JsonObject().put("username", Util.getParm("username", msg)));
    eb.send(persistorAddress, op);
  }

  /*
   * convert the supplied password to a hash, so that no cleartext-passworts are
   * stored in the database .
   */
  private JsonObject makePwd(JsonObject user) {
    byte[] pwbytes = makeHash(user.getString("username"), user.getString("password"));
    if (pwbytes == null) {
      return null;
    }
    user.remove("password");
    user.put("pwhash", pwbytes);
    return user;
  }

  /*
   * Check a cleartext password against a hashed database password
   */
  private boolean checkPwd(JsonObject user, String pwdToCheck) {
    if (user.getBinary("pwhash") == null) {
      makePwd(user);
      JsonObject criteria = new JsonObject().put("_id", user.getValue("_id"));
      eb.send(persistorAddress, new JsonObject().put("action", "update").put(
        "collection", usersCollection).put("criteria", criteria)
        .put("objNew", user).put("upsert", false).put("multi", false));
    }
    byte[] checkBytes = makeHash(user.getString("username"), pwdToCheck);
    log.finest("comparing given " + new String(checkBytes) + " with users "
      + new String(user.getBinary("pwhash")));
    if (Arrays.equals(user.getBinary("pwhash"), checkBytes)) {
      log.finest("login successful.");
      return true;
    }
    return false;
  }

  private byte[] makeHash(String username, String password) {
    try {

      MessageDigest md = MessageDigest.getInstance("MD5");
      md.update(username.getBytes("utf-8"));
      return md.digest(password.getBytes("utf-8"));

    } catch (NoSuchAlgorithmException e) {
      log.severe("could not create password hash MD5");
      e.printStackTrace();
    } catch (UnsupportedEncodingException e) {
      log.severe("don't know how to handle utf-8");
      e.printStackTrace();
    }
    return null;
  }

  private class Session {
    Session() {
      id = UUID.randomUUID().toString();
    }

    void login(JsonObject user, String feedbackAddress) {
      this.user = user;
      this.feedbackAddress = feedbackAddress;
      loggedIn = true;
      loginFailures = 0;
      failTime = 0;
      refresh();
    }

    void logout() {
      log.info("logging out " + user.encodePrettily());
      this.user = new JsonObject();
      if (timerID != 0L) {
        vertx.cancelTimer(timerID);
      }
      loggedIn = false;
    }

    JsonArray getRoles() {
      if (!loggedIn || user == null) {
        return Util.asJsonArray("guest");
      } else {
        return user.getJsonArray("roles");
      }
    }

    void refresh() {
      if (timerID != 0L) {
        vertx.cancelTimer(timerID);
      }
      long myTimeout = TIMEOUT;
      if (store.get("params") != null) {
        JsonObject params = store.get("params");
        myTimeout = params.getLong("timeout", TIMEOUT);
      }
      log.finest("session refresh " + id + ". Setting timeout to " + myTimeout);

      timerID = vertx.setTimer(myTimeout, arg0 -> {
        log.finest(new Date().toString() + " - Session timed out " + id);
        if (loggedIn) {
          log.info("timout session " + id + ", user: " + user.encodePrettily());
          if (feedbackAddress != null) {
            log.info("feedBack-Address: " + feedbackAddress);
            eb.send(feedbackAddress, new JsonObject().put("message", "logged out")
              .put("reason", "timeout"));
          }
          logout();
        } else {
          log.finest("user is not logged in");
                      /* If session is idle for more than an hour -> kill */
          if ((System.currentTimeMillis() - lastAccess) > 3600000) {
            log.info(new Date().toString() + " - Session was idle for more than an hour -> kill "
              + id);
            sessions.remove(id);
          }
        }
        refresh();
      });
      lastAccess = System.currentTimeMillis();
    }

    final String id;
    String feedbackAddress;
    int loginFailures = 0;
    JsonObject user = new JsonObject();
    boolean loggedIn;
    long timerID;
    long failTime = 0;
    long lastAccess = 0;
    final Map<String, JsonObject> store = new HashMap<>();
  }
}
