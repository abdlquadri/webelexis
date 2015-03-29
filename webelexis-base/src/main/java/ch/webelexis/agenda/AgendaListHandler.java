/**
 * This file is part of Webelexis
 * (c) 2015 by G. Weirich
 */

package ch.webelexis.agenda;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.EventBus;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonArray;
import org.vertx.java.core.json.JsonElement;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.core.json.impl.Json;
import org.vertx.java.core.logging.Logger;

import com.fasterxml.jackson.core.JsonParser;

/**
 * A handler for list requests to the agenda. Since we won't allow random access
 * to the database, we translate external requests to internal messages here.
 * 
 * @author gerry
 * 
 */
public class AgendaListHandler implements Handler<Message<JsonObject>> {
	EventBus eb;
	static final String ELEXISDATE = "20[0-9]{6,6}";
	static final String NAME = "[0-9a-zA-Z ]+";
	static final int FLD_DAY = 0;
	static final int FLD_BEGIN = 1;
	static final int FLD_DURATION = 2;
	static final int FLD_RESOURCE = 3;
	static final int FLD_TYPE = 4;
	static final int FLD_TERMIN_ID = 5;
	Logger log = Server.log;
	JsonObject cfg;

	AgendaListHandler(EventBus eb, JsonObject cfg) {
		this.eb = eb;
		this.cfg = cfg;
	}

	/*
	 * this is, what mod_mysql expects { "action" : "prepared", "statement" :
	 * "SELECT * FROM some_test WHERE name=? AND money > ?", "values" :
	 * ["Mr. Test", 15] }
	 * 
	 * and this is, what we expect from the client: { "begin": "yyyymmdd",
	 * "end": "yyyymmdd", "token": auth-token }
	 */
	@Override
	public void handle(final Message<JsonObject> externalRequest) {
		final JsonObject request = externalRequest.body();
		String token = request.getString("token");
		if (token == null) {
			handlePublic(externalRequest, request);
		} else {
			eb.send("ch.webelexis.auth.authorise",
					new JsonObject().putString("sessionID", token),
					new Handler<Message<JsonObject>>() {

						@Override
						public void handle(Message<JsonObject> localAnswer) {
							if (localAnswer.body().getString("status")
									.equalsIgnoreCase("ok")) {
								handleAuthorized(externalRequest, request);

							} else {
								handlePublic(externalRequest, request);
							}

						}
					});

			Server.log.info("received request");
		}
	}

	/**
	 * This is what an unauthorized client gets: A simplified appointment list
	 * with only times and free/occupied information No Patient information is
	 * transmitted
	 * 
	 * @param event
	 * @param request
	 */
	private void handlePublic(final Message<JsonObject> externalRequest,
			JsonObject request) {
		Cleaner cl = new Cleaner(request);
		log.info("public agenda handler");
		final String resource = cfg.getString("resource") == null ? "" : cfg
				.getString("resource");
		JsonObject bridge = new JsonObject()
				.putString("action", "prepared")
				.putString(
						"statement",
						"SELECT Tag,Beginn,Dauer,Bereich, TerminTyp, ID from AGNTERMINE where Tag>=? and Tag <=? and Bereich=? and deleted='0'")
				.putArray(
						"values",
						new JsonArray(new String[] {
								cl.get("begin", ELEXISDATE),
								cl.get("begin", ELEXISDATE), resource }));
		log.debug("sending message: "+bridge.encodePrettily());
		eb.send("ch.webelexis.sql", bridge, new Handler<Message<JsonObject>>() {

			@Override
			public void handle(Message<JsonObject> returnvalue) {
				JsonObject res = returnvalue.body();
				if (res.getString("status").equals("ok")) {

					externalRequest.reply(fillBlanks(res.getArray("results")
							.toArray(), null));

				} else {
					System.out.println(Json.encodePrettily(res));
					externalRequest.reply(new JsonObject().putString("status",
							"failure"));
				}
			}
		});
	}

	/**
	 * fill empty periods of time with "free" appointments
	 * 
	 * @param set
	 */
	private JsonObject fillBlanks(Object[] appointments, JsonArray mixin) {
		TreeSet<JsonArray> orderedList = new TreeSet<JsonArray>(
				new Comparator<JsonArray>() {
					@Override
					public int compare(JsonArray o1, JsonArray o2) {
						String day1 = o1.get(FLD_DAY);
						String day2 = o2.get(FLD_DAY);
						if (day1.equals(day2)) {
							int start1 = Integer.parseInt(((String) o1
									.get(FLD_BEGIN)).trim());
							int start2 = Integer.parseInt(((String) o2
									.get(FLD_BEGIN)).trim());
							return start1 - start2;
						}
						return day1.compareTo(day2);
					}
				});

		for (Object li : appointments) {
			@SuppressWarnings("unchecked")
			List<Object> line = (ArrayList<Object>) li;
			line.set(FLD_TYPE, "occupied");
			orderedList.add(new JsonArray(line));
		}
		if (mixin != null) {
			@SuppressWarnings("rawtypes")
			Iterator it = mixin.iterator();
			while (it.hasNext()) {

				orderedList.add((JsonArray) it.next());
			}
		}

		int endTime = 0;
		Iterator<JsonArray> lines = orderedList.iterator();
		JsonArray arr = new JsonArray();
		int slot = 30;
		if (cfg != null) {
			slot = cfg.getInteger("timeSlot") == null ? 30 : cfg
					.getInteger("timeSlot");

		}

		// Fill in "available" spaces between appointments. Avalailables have
		// the length "slot" as defined in the config
		while (lines.hasNext()) {
			JsonArray aNext = (JsonArray) lines.next();
			int startTime = Integer.parseInt(((String) aNext.get(FLD_BEGIN)).trim());
			while ((startTime - endTime) >= slot) {
				String[] free = new String[aNext.size()];
				free[FLD_DAY] = aNext.get(FLD_DAY);
				free[FLD_BEGIN] = Integer.toString(endTime);
				free[FLD_DURATION] = Integer.toString(slot); // slotInteger.toString(startTime
																// - endTime);
				free[FLD_RESOURCE] = aNext.get(FLD_RESOURCE);
				free[FLD_TYPE] = "available";
				arr.addArray(new JsonArray(free));
				endTime += slot;
				// System.out.println("created "+free[FLD_BEGIN]+","+free[FLD_DURATION]);
			}
			if ((startTime - endTime) > 0) {
				String[] free = new String[aNext.size()];
				free[FLD_DAY] = aNext.get(FLD_DAY);
				free[FLD_BEGIN] = Integer.toString(endTime);
				free[FLD_DURATION] = Integer.toString(startTime - endTime);
				free[FLD_RESOURCE] = aNext.get(FLD_RESOURCE);
				free[FLD_TYPE] = "occupied";
				// System.out.println("rest "+free[FLD_BEGIN]+","+free[FLD_DURATION]);
				arr.addArray(new JsonArray(free));
			}
			endTime = startTime
					+ Integer.parseInt(((String) aNext.get(FLD_DURATION)).trim());
			arr.addArray(aNext);
		}

		JsonObject ores = new JsonObject().putString("status", "ok")
				.putString("type", "basic").putArray("appointments", arr);
		return ores;

	}

	/**
	 * This is, what an authorized user gets. Due to an ill-designed database
	 * layout, a single join does not return all appointments, since the "PatID"
	 * field is dual use: either it's a patient id (which would be covered by
	 * the join in the first place) or it is just a manually entered description
	 * or a name for the appointment (and such appointments are lost with the
	 * join) Therefore we must make 2 database calls. One for the joint and one
	 * for all appointments of the given date. (Oh, there would be an SQL join
	 * to cover that case with a single call indeed, but -the heck- this does
	 * not work with the mysql-jdbc-driver.)
	 * 
	 * The original implementation of elexis does this even worse: It makes a
	 * separate database call for every single entry. This would be very
	 * inefficient over slow internet connections.
	 * 
	 * @param event
	 * @param request
	 */
	private void handleAuthorized(final Message<JsonObject> externalRequest,
			final JsonObject request) {
		// first call: get all Appointments with valid PatientID
		log.info("authorized agenda handler");
		final Cleaner cl = new Cleaner(request);
		JsonObject bridge = new JsonObject()
				.putString("action", "prepared")
				.putString(
						"statement",
						"SELECT A.Tag,A.Beginn,A.Dauer, A.Bereich, A.TerminTyp, A.ID, A.PatID,A.TerminStatus,A.Grund,K.Bezeichnung1,K.Bezeichnung2 from AGNTERMINE as A, KONTAKT as K where K.id=A.PatID and A.Tag>=? and A.Tag <=? and A.Bereich=? and A.deleted='0'")
				.putArray(
						"values",
						new JsonArray(new String[] {
								cl.get("begin", ELEXISDATE),
								cl.get("end", ELEXISDATE), cl.get("resource", NAME) }));
		System.out.println(bridge.toString());
		eb.send("ch.webelexis.sql", bridge, new Handler<Message<JsonObject>>() {

			@Override
			public void handle(Message<JsonObject> returnvalue) {
				JsonObject res = returnvalue.body();
				if (res.getString("status").equals("ok")) {

					final JsonArray appts = res.getArray("results");
					log.debug("first level okay with " + appts.size()
							+ " results");

					JsonObject bridge = new JsonObject()
							.putString("action", "prepared")
							.putString(
									"statement",
									"SELECT Tag,Beginn,Dauer,Bereich, TerminTyp, ID, PatID, TerminStatus, Grund from AGNTERMINE where Tag>=? and Tag <=? and Bereich=? and deleted='0'")
							.putArray(
									"values",
									new JsonArray(
											new String[] {
													cl.get("begin", ELEXISDATE),
													cl.get("end", ELEXISDATE),
													cl.get("resource", NAME) }));
					eb.send("ch.webelexis.sql", bridge,
							new Handler<Message<JsonObject>>() {

								@Override
								public void handle(Message<JsonObject> second) {
									if (second.body().getString("status")
											.equals("ok")) {
										log.debug("second level okay");
										JsonObject ores = fillBlanks(appts
												.toArray(), second.body()
												.getArray("results"));
										ores.putString("type", "full");
										externalRequest.reply(ores);
									} else {
										log.info("second level failed");
										System.out.println(Json.encodePrettily(second.body()));
										externalRequest.reply(new JsonObject()
												.putString("status", "failure")
												.putString("reason", second.body().getString("status")));
									}
								}
							});
				} else {
					log.info("first level failed "
							+ returnvalue.body().getString("message"));
					System.out.println(Json.encodePrettily(res));
					externalRequest.reply(new JsonObject().putString("status",
							"failure"));
				}
			}
		});

	}

}
