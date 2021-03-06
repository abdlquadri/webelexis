/*
 * This file is part of Webelexis. Copyright (c) 2015 by G. Weirich
 */

package ch.rgw.vertx;

import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;

/**
 * Created by gerry on 25.06.15.
 */
public class Config {
  final JsonObject cfg;

  public Config(JsonObject cfg) {
    this.cfg = cfg;
  }

  public String getMandatoryString(String field, final Message<JsonObject> msg) {
    if (cfg.containsKey(field)) {
      return cfg.getString(field);
    } else {
      msg.reply(new JsonObject().put("status", "error").put("message", field + " not found"));
    }
    return field;
  }


  public String getOptionalString(String field, String defaultValue) {
    if (cfg.containsKey(field)) {
      return cfg.getString(field);
    } else {
      return defaultValue;
    }
  }

  public JsonObject getMandatoryObject(String field, final Message<JsonObject> msg) {
    if (cfg.containsKey(field)) {
      return cfg.getJsonObject(field);
    } else {
      msg.reply(new JsonObject().put("status", "error").put("message", field + " not found"));
      return new JsonObject();
    }
  }

  public Long getOptionalLong(String field, long defaultValue) {
    if (cfg.containsKey(field)) {
      return cfg.getLong(field);
    } else {
      return defaultValue;
    }
  }



}
