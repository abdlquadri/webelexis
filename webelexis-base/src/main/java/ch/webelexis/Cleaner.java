/**
 * This file is part of Webelexis
 * (c) 2015 by G. Weirich
 */
package ch.webelexis;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.DecodeException;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonObject;

/**
 * Simple helper to return cleaned String entries from a JsonObject (return ""
 * for null and make sure, the result matches a given pattern)
 * 
 * @author gerry
 * 
 */
public class Cleaner {
	// /^\d{1,2}\.\d{1,2}\.(?:\d{4}|\d{2})$/
	public static final String ELEXISDATE = "[12][09][0-9]{6,6}";
	public static final String NAME = "[0-9a-zA-Z \\.-]+";
	public static final String WORD = "[a-zA-Z]+";
	public static final String NOTEMPTY = ".+";
	public static final String DATE = "[0-3]?[0-9]\\.[01]?[0-9]\\.[0-9]{2,4}";
	public static final String PHONE = "\\+?[0-9  -/]{7,20}";
	public static final String MAIL = ".+@[a-zA-Z_0-9\\.]*[a-zA-Z_0-9]{2,}\\.[a-zA-Z]{2,3}";
	public static final String TIME = "[0-2][0-9]:[0-5][0-9]";
	public static final String TEXT = "[A_Za-z \\.,-]";
	public static final String IP = "[0-2]?[0-9]?[0-9]\\.[0-2]?[0-9]?[0-9]\\.[0-2]?[0-9]?[0-9]\\.[0-2]?[0-9]?[0-9]";
	public static final String ZIP = "[A-Za-z 0-9]{4,8}";
	public static final String UID = "[a-zA-Z0-9-]{8,}";

	Message<JsonObject> jo;

	public Cleaner(Message<JsonObject> raw) {
		jo = raw;
	}

	public String get(String field, String pattern, boolean emptyok) throws ParametersException {
		String raw = jo.body().getString(field);
		if (raw == null || raw.length() == 0) {
			if (emptyok) {
				return raw;
			} else {
				throw new ParametersException("field " + field + " was not set.");
			}
		}
		if ((raw != null) && raw.matches(pattern)) {
			return raw;
		} else {
			jo.reply(new JsonObject().putString("status", "bad or missing field value for " + field));
			throw new ParametersException("value of " + field + " does not match expected criteria");
		}
	}

	public String getOptional(String field, String defaultValue) {
		String raw = jo.body().getString(field);
		if (raw != null) {
			return raw;
		} else {
			return defaultValue;
		}
	}

	public JsonArray getArray(String name, JsonArray defaultValue) {
		JsonArray ret = jo.body().getArray(name);
		return ret == null ? defaultValue : ret;
	}

	public void reply(JsonObject result) {
		jo.reply(result);
	}

	public void replyError() {
		jo.reply(new JsonObject().putString("status", "error"));
	}

	public void replyError(String msg) {
		JsonObject result = new JsonObject().putString("status", "error").putString("message", msg);
		jo.reply(result);
	}

	public void replyOk() {
		jo.reply(new JsonObject().putString("status", "ok"));
	}

	public void replyOk(String msg) {
		JsonObject result = new JsonObject().putString("status", "ok").putString("message", msg);
		jo.reply(result);
	}

	public void replyStatus(String msg) {
		jo.reply(new JsonObject().putString("status", msg));
	}

	@Override
	public String toString() {
		return jo.body().encodePrettily();
	}
	
	/**
	 * Create a JsonObject from a file. // Comments are stripped before parsing
	 * @param fpath filename (with relative or absolute path)
	 * @return a JsonObject created from that file
	 * @throws IOException if the file was not found or could not be read
	 * @throws DecodeException if the file was not a valid Json Object
	 */
	public static JsonObject createFromFile(String fpath) throws IOException, DecodeException{
		File file = new File(fpath);
		if (!file.exists()) {
			System.out.println(file.getAbsolutePath());
			throw new IOException("File not found "+file.getAbsolutePath());
		}
		char[] buffer = new char[(int) file.length()];
		FileReader fr = new FileReader(file);
		fr.read(buffer);
		fr.close();
		String conf = new String(buffer).replaceAll("//\\s+.+\\r?\\n+\\r?", "");
		JsonObject ret=new JsonObject(conf);
		return ret;
	}
}
