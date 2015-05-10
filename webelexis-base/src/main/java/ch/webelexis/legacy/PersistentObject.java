/*******************************************************************************
 * Copyright (c) 2005-2014, G. Weirich and Elexis
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    G. Weirich - initial implementation
 *    MEDEVIT <office@medevit.at>
 *******************************************************************************/

package ch.webelexis.legacy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.datatype.XMLGregorianCalendar;

import ch.rgw.tools.JdbcLink;
import ch.rgw.tools.JdbcLinkSyntaxException;
import ch.rgw.tools.StringTool;
import ch.rgw.tools.net.NetTool;


/**
 * Base class for all objects to be stored in the database. A PersistentObject has an unique ID,
 * which is assigned as the object is created. Every object is accessed "lazily" which means that
 * "loading" an object instantiates only a proxy with the ID of the requested object. Members are
 * read only as needed. The class provides static functions to log into the database, and provides
 * methods for reading and writing of fields for derived classes. The get method uses a cache to
 * reduce the number of costly database operations. Repeated read-requests within a configurable
 * life-time (defaults to 15 seconds) are satisfied from the cache. PersistentObject can log every
 * write-access in a trace-table, as desired. get- and set- methods perform necessary
 * coding/decoding of fields as needed.
 * 
 * Basisklasse für alle Objekte, die in der Datenbank gespeichert werden sollen. Ein
 * PersistentObject hat eine eindeutige ID, welche beim Erstellen des Objekts automatisch vergeben
 * wird. Grundsätzlich wird jedes Objekt "lazy" geladen, indem jede Leseanforderung zunächst nur
 * einen mit der ID des Objekts versehenen Proxy instantiiert und jedes Member-Feld erst auf Anfrage
 * nachlädt. Die Klasse stellt statische Funktionen zur Kontaktaufnahme mit der Datenbank und
 * member-Funktionen zum Lesen und Schreiben von Feldern der Tochterobjekte zur Verfügung. Die
 * get-Methode verwendet einen zeitlich limitierten Cache. um die Zahl teurer Datenbankoperationen
 * zu minimieren: Wiederholte Lesezugriffe innerhalb einer einstellbaren lifetime (Standardmässig 15
 * Sekunden) werden aus dem cache bedient. PersistentObject kann auch alle Schreibvorgänge in einer
 * speziellen Trace-Tabelle dokumentieren. Die get- und set- Methoden kümmern sich selbst um
 * codierung/decodierung der Felder, wenn nötig. Aufeinanderfolgende und streng zusammengehörende
 * Schreibvorgänge können auch in einer Transaktion zusammengefasst werden, welche nur ganz oder gar
 * nicht ausgeführt wird. (begin()). Es ist aber zu beachten, das nicht alle Datenbanken
 * Transaktionen unterstützen. MySQL beispielsweise nur, wenn es mit InnoDB-Tabellen eingerichtet
 * wurde (welche langsamer sind, als die standardmässig verwendeten MyISAM-Tabellen).
 * 
 * @author gerry
 */
public abstract class PersistentObject implements IPersistentObject {
	protected static final String MAPPING_ERROR_MARKER = "**ERROR:";
	
	/** predefined field name for the GUID */
	public static final String FLD_ID = "id";
	/** predefined property to handle a field that is a compressed HashMap */
	public static final String FLD_EXTINFO = "ExtInfo";
	/** predefined property to hande a field that marks the Object as deleted */
	public static final String FLD_DELETED = "deleted";
	/**
	 * predefined property that holds an automatically updated field containing the last update of
	 * this object as long value (milliseconds as in Date())
	 */
	public static final String FLD_LASTUPDATE = "lastupdate";
	/**
	 * predefined property that holds the date of creation of this object in the form YYYYMMDD
	 */
	public static final String FLD_DATE = "Datum";
	
	protected static final String DATE_COMPOUND = "Datum=S:D:Datum";
	
	public static final int CACHE_DEFAULT_LIFETIME = 15;
	public static final int CACHE_MIN_LIFETIME = 5;
	
	// maximum character length of int fields in tables
	private static int MAX_INT_LENGTH = 10;
	
	private static JdbcLink j = null;
	private static JdbcLink testJdbcLink = null;
	protected static Logger log = Logger.getLogger(PersistentObject.class.getName());
	private String id;
	private static Hashtable<String, String> mapping;
	private static IPersistentObjectCache<String> cache;
	private static String username;
	private static String pcname;
	private static String tracetable;
	protected static int default_lifetime;
	private static boolean runningFromScratch = false;
	private static String dbUser;
	private static String dbPw;
	
	
	static {
		mapping = new Hashtable<String, String>();
		
		cache = new SoftCache<String>(3000, 0.7f);
		// cache=new EhBasedCache<String>(null);
		/*
		 * cacheCleaner=new Job("CacheCleaner"){ @Override protected IStatus run(final
		 * IProgressMonitor monitor) { cache.purge(); schedule(60000L); return Status.OK_STATUS; }
		 * }; cacheCleaner.setUser(false); cacheCleaner.setPriority(Job.DECORATE);
		 */
		// cacheCleaner.schedule(300000L);
		log.info("Cache setup: default_lifetime " + default_lifetime);
	}
	
	public static enum FieldType {
		TEXT, LIST, JOINT
	};
	
	/**
	 * the possible states of a tristate checkbox: true/checked, false/unchecked,
	 * undefined/"filled with a square"/"partly selected"
	 * 
	 * @since 3.0.0
	 */
	static public enum TristateBoolean {
		TRUE, FALSE, UNDEF
	};
	
	/**
	 * Return the Object containing the cdecodeonnection. This should only in very specific
	 * conditions be neccessary, if one needs a direkt access to the database. It is strongly
	 * recommended to use this only very carefully, as callers must ensure for themselves that their
	 * code works with different database engines equally.
	 * 
	 * Das Objekt, das die Connection enthält zurückliefern. Sollte nur in Ausnahmefällen nötig
	 * sein, wenn doch mal ein direkter Zugriff auf die Datenbank erforderlich ist.
	 * 
	 * @return den JdbcLink, der die Verbindung zur Datenbank enthält
	 */
	public static JdbcLink getConnection(){
		return j;
	}
	
	/**
	 * Die Zuordnung von Membervariablen zu Datenbankfeldern geschieht über statische mappings: Jede
	 * abgeleitete Klassen muss ihre mappings in folgender Form deklarieren:
	 * addMapping("Tabellenname","Variable=Feld"...); wobei:
	 * <ul>
	 * <li>"Variable=Feld" - Einfache Zuordnung, Variable wird zu Feld</li>
	 * <li>"Variable=S:x:Feld" - Spezielle Abspeicherung<br>
	 * x=D - Datumsfeld, wird automatisch in Standardformat gebracht<br>
	 * x=C - Feld wird vor Abspeicherung komprimiert</li>
	 * X=N - Feld wird als Long interrpetiert
	 * <li>"Variable=JOINT:FremdID:EigeneID:Tabelle[:type]" - n:m - Zuordnungen</li>
	 * <li>"Variable=LIST:EigeneID:Tabelle:orderby[:type]" - 1:n - Zuordnungen</li>
	 * <li>"Variable=EXT:tabelle:feld" - Das Feld ist in der genannten externen Tabelle
	 * </ul>
	 */
	static protected void addMapping(final String prefix, final String... map){
		for (String s : map) {
			String[] def = s.trim().split("[ \t]*=[ \t]*");
			if (def.length != 2) {
				mapping.put(prefix + def[0], def[0]);
			} else {
				mapping.put(prefix + def[0], def[1]);
			}
		}
		mapping.put(prefix + "deleted", "deleted");
		mapping.put(prefix + "lastupdate", "lastupdate");
	}
	
	/**
	 * Trace (protokollieren aller Schreibvorgänge) ein- und ausschalten. Die Trace-Tabelle muss
	 * folgende Spalten haben: logtime (long), Workstation (VARCHAR), Username(Varchar), action
	 * (Text/Longvarchar)
	 * 
	 * @param Tablename
	 *            Name der Trace-tabelle oder null: Trace aus.
	 */
	public static void setTrace(String Tablename){
		if ((Tablename != null) && (Tablename.equals("none") || Tablename.equals(""))) {
			Tablename = null;
		}
		tracetable = Tablename;
		username = JdbcLink.wrap(System.getProperty("user.name"));
		pcname = JdbcLink.wrap(NetTool.hostname);
	}
	
	/**
	 * Einschränkende Bedingungen für Suche nach diesem Objekt definieren
	 * 
	 * @return ein Constraint für eine Select-Abfrage
	 */
	protected String getConstraint(){
		return "";
	}
	
	/**
	 * Bedingungen für dieses Objekt setzen
	 */
	protected void setConstraint(){
		/* Standardimplementation ist leer */
	}
	
	/**
	 * Jede abgeleitete Klasse muss deklarieren, in welcher Tabelle sie gespeichert werden will.
	 * 
	 * @return Der Name einer bereits existierenden Tabelle der Datenbank
	 */
	abstract protected String getTableName();
	
	/**
	 * Angeben, ob dieses Objekt gültig ist.
	 * 
	 * @return true wenn die Daten gültig (nicht notwendigerweise korrekt) sind
	 */
	public boolean isValid(){
		if (state() < EXISTS) {
			return false;
		}
		return true;
	}
	
	/**
	 * Die eindeutige Identifikation dieses Objektes/Datensatzes liefern. Diese ID wird jeweils
	 * automatisch beim Anlegen eines Objekts dieser oder einer abgeleiteten Klasse erstellt und
	 * bleibt dann unveränderlich.
	 * 
	 * @return die ID.
	 */
	public String getId(){
		return id;
	}
	
	/** Der Konstruktor erstellt die ID */
	protected PersistentObject(){
		id = StringTool.unique("prso");
	}
	
	/**
	 * Konstruktor mit vorgegebener ID (zum Deserialisieren) Wird nur von xx::load gebraucht.
	 */
	protected PersistentObject(final String id){
		this.id = id;
	}
	
	/**
	 * Objekt in einen String serialisieren. Diese Standardimplementation macht eine "cheap copy":
	 * Es wird eine Textrepräsentation des Objektes erstellt, mit deren Hilfe das Objekt später
	 * wieder aus der Datenbank erstellt werden kann. Dies funktioniert nur innerhalb derselben
	 * Datenbank.
	 * 
	 * @return der code-String, aus dem mit {@link PersistentObjectFactory} .createFromString wieder
	 *         das Objekt erstellt werden kann
	 */
	public String storeToString(){
		StringBuilder sb = new StringBuilder();
		sb.append(getClass().getName()).append("::").append(getId());
		return sb.toString();
	}
	
	/** An object with this ID does not exist */
	public static final int INEXISTENT = 0;
	/** This id is not valid */
	public static final int INVALID_ID = 1;
	/** An object with this ID exists but is marked deleted */
	public static final int DELETED = 2;
	/** This is an existing object */
	public static final int EXISTS = 3;
	
	/**
	 * Check the state of an object with this ID Note: This method accesses the database and
	 * therefore is much more costly than the simple instantaniation of a PersistentObject
	 * 
	 * @return a value between INEXISTENT and EXISTS
	 */
	
	public int state(){
		if (StringTool.isNothing(getId())) {
			return INVALID_ID;
		}
		
		StringBuilder sb = new StringBuilder("SELECT ID FROM ");
		sb.append(getTableName()).append(" WHERE ID='").append(getId()).append("'");
		try {
			String obj = j.queryString(sb.toString());
			
			if (id.equalsIgnoreCase(obj)) {
				String deleted = get("deleted");
				if (deleted == null) { // if we cant't find the column called
					// 'deleted', the object exists anyway
					return EXISTS;
				}
				return deleted.equals("1") ? DELETED : EXISTS;
				
			} else {
				return INEXISTENT;
			}
		} catch (JdbcLinkSyntaxException ex) {
			return INEXISTENT;
		}
	}
	
	/**
	 * Feststellen, ob ein PersistentObject bereits in der Datenbank existiert
	 * 
	 * @return true wenn es existiert, false wenn es nicht existiert oder gelöscht wurde
	 */
	
	public boolean exists(){
		return state() == EXISTS;
	}
	
	/**
	 * Check whether the object exists in the database. This is the case for all objects in the
	 * database for which state() returns neither INVALID_ID nor INEXISTENT. Note: objects marked as
	 * deleted will also return true!
	 * 
	 * @return true, if the object is available in the database, false otherwise
	 */
	public boolean isAvailable(){
		return (state() >= DELETED);
	}
	
	/**
	 * Feststellen, ob ein PersistentObject als gelöscht markiert wurde
	 * 
	 * @return true wenn es gelöscht ist
	 */
	public boolean isDeleted(){
		return get("deleted").equals("1");
	}
	
	/**
	 * Darf dieses Objekt mit Drag&Drop verschoben werden?
	 * 
	 * @return true wenn ja.
	 */
	public boolean isDragOK(){
		return false;
	}
	
	/**
	 * Aus einem Feldnamen das dazugehörige Datenbankfeld ermitteln
	 * 
	 * @param f
	 *            Der Feldname
	 * @return Das Datenbankfeld oder **ERROR**, wenn kein mapping für das angegebene Feld
	 *         existiert.
	 */
	public String map(final String f){
		if (f.equals("ID")) {
			return f;
		}
		String prefix = getTableName();
		String res = mapping.get(prefix + f);
		if (res == null) {
			log.info("field is not mapped " + f);
			return MAPPING_ERROR_MARKER + f + "**";
		}
		return res;
	}
	
	public FieldType getFieldType(final String f){
		String mapped = map(f);
		if (mapped.startsWith("LIST:")) {
			return FieldType.LIST;
		} else if (mapped.startsWith("JOINT:")) {
			return FieldType.JOINT;
		} else {
			return FieldType.TEXT;
		}
	}
	
	/**
	 * Ein Feld aus der Datenbank auslesen. Die Tabelle wird über getTableName() erfragt. Das Feld
	 * wird beim ersten Aufruf in jedem Fall aus der Datenbank gelesen. Dann werden weitere
	 * Lesezugriffe während der <i>lifetime</i> aus dem cache bedient, um die Zahl der
	 * Datenbankzugriffe zu minimieren. Nach Ablauf der lifetime erfolgt wieder ein Zugriff auf die
	 * Datenbank, wobei auch der cache wieder erneuert wird. Wenn das Feld nicht als Tabellenfeld
	 * existiert, wird es in EXTINFO gesucht. Wenn es auch dort nicht gefunden wird, wird eine
	 * Methode namens getFeldname gesucht.
	 * 
	 * @param field
	 *            Name des Felds
	 * @return Der Inhalt des Felds (kann auch null sein), oder **ERROR**, wenn versucht werden
	 *         sollte, ein nicht existierendes Feld auszulesen
	 */
	public String get(final String field){
		String key = getKey(field);
		Object ret = cache.get(key);
		if (ret instanceof String) {
			return (String) ret;
		}
		boolean decrypt = false;
		StringBuffer sql = new StringBuffer();
		String mapped = map(field);
		String table = getTableName();
		if (mapped.startsWith("EXT:")) {
			int ix = mapped.indexOf(':', 5);
			if (ix == -1) {
				log.severe("Fehlerhaftes Mapping bei " + field);
				return MAPPING_ERROR_MARKER + " " + field + "**";
			}
			table = mapped.substring(4, ix);
			mapped = mapped.substring(ix + 1);
		} else if (mapped.startsWith("S:")) {
			mapped = mapped.substring(4);
			decrypt = true;
		} else if (mapped.startsWith("JOINT:")) {
			String[] dwf = mapped.split(":");
			if (dwf.length > 4) {
				String objdef = dwf[4] + "::";
				StringBuilder sb = new StringBuilder();
				List<String[]> list = getList(field, new String[0]);
				PersistentObjectFactory fac = new PersistentObjectFactory();
				for (String[] s : list) {
					PersistentObject po = fac.createFromString(objdef + s[0]);
					sb.append(po.getLabel()).append("\n");
				}
				return sb.toString();
			}
			
		} else if (mapped.startsWith("LIST:")) {
			String[] dwf = mapped.split(":");
			if (dwf.length > 4) {
				String objdef = dwf[4] + "::";
				StringBuilder sb = new StringBuilder();
				List<String> list = getList(field, false);
				PersistentObjectFactory fac = new PersistentObjectFactory();
				for (String s : list) {
					PersistentObject po = fac.createFromString(objdef + s);
					sb.append(po.getLabel()).append("\n");
				}
				return sb.toString();
			}
		} else if (mapped.startsWith(MAPPING_ERROR_MARKER)) { // If the field
			// could not be
			// mapped
			String exi = map(FLD_EXTINFO); // Try to find it in ExtInfo
			if (!exi.startsWith(MAPPING_ERROR_MARKER)) {
				Map ht = getMap(FLD_EXTINFO);
				Object res = ht.get(field);
				if (res instanceof String) {
					return (String) res;
				}
			}
			// try to find an XID with that name
			String xid = getXid(field);
			if (xid.length() > 0) {
				return xid;
			}
			// or try to find a "getter" Method
			// for the field
			String method = "get" + field;
			try {
				Method mx = getClass().getMethod(method, new Class[0]);
				Object ro = mx.invoke(this, new Object[0]);
				if (ro == null) {
					return "";
				} else if (ro instanceof String) {
					return (String) ro;
				} else if (ro instanceof Integer) {
					return Integer.toString((Integer) ro);
				} else if (ro instanceof PersistentObject) {
					return ((PersistentObject) ro).getLabel();
				} else {
					return "?invalid field? " + mapped;
				}
			} catch (NoSuchMethodException nmex) {
				log.warn("Fehler bei Felddefinition " + field);
				ElexisStatus status =
					new ElexisStatus(ElexisStatus.WARNING, CoreHub.PLUGIN_ID,
						ElexisStatus.CODE_NOFEEDBACK, "Fehler bei Felddefinition", nmex);
				ElexisEventDispatcher.fireElexisStatusEvent(status);
				return mapped;
			} catch (Exception ex) {
				// ignore the exceptions calling functions look for
				// MAPPING_ERROR_MARKER
				ExHandler.handle(ex);
				return mapped;
			}
		}
		sql.append("SELECT ").append(mapped).append(" FROM ").append(table).append(" WHERE ID='")
			.append(id).append("'");
		
		Stm stm = getConnection().getStatement();
		ResultSet rs = executeSqlQuery(sql.toString(), stm);
		String res = null;
		try {
			if ((rs != null) && (rs.next() == true)) {
				if (decrypt) {
					res = decode(field, rs);
				} else {
					res = rs.getString(mapped);
				}
				if (res == null) {
					res = "";
				}
				cache.put(key, res, getCacheTime());
			}
		} catch (SQLException ex) {
			ExHandler.handle(ex);
		} finally {
			try {
				rs.close();
				stm.delete();
			} catch (SQLException e) {
				// ignore
			}
		}
		return res;
	}
	
	public byte[] getBinary(final String field){
		String key = getKey(field);
		Object o = cache.get(key);
		if (o instanceof byte[]) {
			return (byte[]) o;
		}
		byte[] ret = getBinaryRaw(field);
		cache.put(key, ret, getCacheTime());
		return ret;
	}
	
	private byte[] getBinaryRaw(final String field){
		StringBuilder sql = new StringBuilder();
		String mapped = (field);
		String table = getTableName();
		sql.append("SELECT ").append(mapped).append(" FROM ").append(table).append(" WHERE ID='")
			.append(id).append("'");
		
		Stm stm = getConnection().getStatement();
		ResultSet rs = executeSqlQuery(sql.toString(), stm);
		try {
			if ((rs != null) && (rs.next() == true)) {
				return rs.getBytes(mapped);
			}
		} catch (Exception ex) {
			ExHandler.handle(ex);
		} finally {
			try {
				rs.close();
				stm.delete();
			} catch (SQLException e) {
				// ignore
			}
		}
		return null;
	}
	
	protected VersionedResource getVersionedResource(final String field, final boolean flushCache){
		String key = getKey(field);
		if (flushCache == false) {
			Object o = cache.get(key);
			if (o instanceof VersionedResource) {
				return (VersionedResource) o;
			}
		}
		byte[] blob = getBinaryRaw(field);
		VersionedResource ret = VersionedResource.load(blob);
		cache.put(key, ret, getCacheTime());
		return ret;
	}
	
	/**
	 * Eine Hashtable auslesen
	 * 
	 * @param field
	 *            Feldname der Hashtable
	 * @return eine Hashtable (ggf. leer). Nie null.
	 */
	@SuppressWarnings({
		"rawtypes", "unchecked"
	})
	public Map getMap(final String field){
		String key = getKey(field);
		Object o = cache.get(key);
		if (o instanceof Hashtable) {
			return (Hashtable) o;
		}
		byte[] blob = getBinaryRaw(field);
		if (blob == null) {
			return new Hashtable();
		}
		Hashtable<Object, Object> ret = fold(blob);
		if (ret == null) {
			return new Hashtable();
		}
		cache.put(key, ret, getCacheTime());
		return ret;
	}
	
	/**
	 * Bequemlichkeitsmethode zum lesen eines Integer.
	 * 
	 * @param field
	 * @return einen Integer. 0 bei 0 oder unlesbar
	 */
	public int getInt(final String field){
		return checkZero(get(field));
	}
	
	/**
	 * returns the selected TristateBoolean value (for a tristate checkbox)
	 * 
	 * @param field
	 *            the name of the field to be tested
	 * @return the current tristate selection state, one of TristateBoolean (TRUE/FALSE/UNDEF)
	 * @author H. Marlovits
	 * @since 3.0.0
	 */
	public TristateBoolean getTriStateBoolean(final String field){
		String value = get(field);
		if (value == null)
			return TristateBoolean.UNDEF;
		if (value.equalsIgnoreCase(StringConstants.ONE))
			return TristateBoolean.TRUE;
		else if (value.equalsIgnoreCase(StringConstants.ZERO))
			return TristateBoolean.FALSE;
		else
			return TristateBoolean.UNDEF;
	}
	
	/**
	 * save the selected TristateBoolean value (of a tristate checkbox)
	 * 
	 * @param field
	 *            the name of the field to be set
	 * @param newVal
	 *            the new state to save to the cb, one of TristateBoolean (TRUE/FALSE/UNDEF)
	 * @author H. Marlovits
	 * @since 3.0.0
	 */
	public void setTriStateBoolean(final String field, TristateBoolean newVal)
		throws IllegalArgumentException, PersistenceException{
		if (newVal == null)
			throw new IllegalArgumentException(
				"PersistentObject.setTriStateBoolean(): param newVal == null");
		String saveVal = "";
		if (newVal == TristateBoolean.TRUE)
			saveVal = StringConstants.ONE;
		if (newVal == TristateBoolean.FALSE)
			saveVal = StringConstants.ZERO;
		if (newVal == TristateBoolean.UNDEF)
			saveVal = StringConstants.EMPTY;
		boolean result = set(field, saveVal);
		if (!result) {
			throw new PersistenceException(new ElexisStatus(Status.ERROR, CoreHub.PLUGIN_ID,
				ElexisStatus.CODE_NONE,
				"PersistentObject.setTriStateBoolean(): Error on saving value " + newVal
					+ " to field " + field, null));
		}
	}
	
	/**
	 * Eine 1:n Verknüpfung aus der Datenbank auslesen.
	 * 
	 * Does not include elements marked as deleted.
	 * 
	 * @param field
	 *            das Feld, wie in der mapping-Deklaration angegeben
	 * @param reverse
	 *            wenn true wird rückwärts sortiert
	 * @return eine Liste mit den IDs (String!) der verknüpften Datensätze oder null, wenn das Feld
	 *         keine 1:n-Verknüofung ist
	 */
	@SuppressWarnings("unchecked")
	public List<String> getList(final String field, final boolean reverse){
		StringBuffer sql = new StringBuffer();
		String mapped = map(field);
		if (mapped.startsWith("LIST:")) {
			String[] m = mapped.split(":");
			if (m.length > 2) {
				// String order=null;
				
				sql.append("SELECT ID FROM ").append(m[2]).append(" WHERE ");
				
				sql.append("deleted=").append(JdbcLink.wrap("0")).append(" AND ");
				
				sql.append(m[1]).append("=").append(getWrappedId());
				if (m.length > 3) {
					sql.append(" ORDER by ").append(m[3]);
					if (reverse) {
						sql.append(" DESC");
					}
				}
				Stm stm = getConnection().getStatement();
				List<String> ret = stm.queryList(sql.toString(), new String[] {
					"ID"
				});
				getConnection().releaseStatement(stm);
				return ret;
			}
		} else {
			log.error("Fehlerhaftes Mapping " + mapped);
		}
		return null;
	}
	
	/**
	 * Eine n:m - Verknüpfung auslesen
	 * 
	 * @param field
	 *            Das Feld, für das ein entsprechendes mapping existiert
	 * @param extra
	 *            Extrafelder, die aus der joint-Tabelle ausgelesen werden sollen
	 * @return eine Liste aus String-Arrays, welche jeweils die ID des gefundenen Objekts und den
	 *         Inhalt der Extra-Felder enthalten. Null bei Mapping-Fehler
	 */
	public List<String[]> getList(final String field, String[] extra){
		if (extra == null) {
			extra = new String[0];
		}
		StringBuffer sql = new StringBuffer();
		String mapped = map(field);
		if (mapped.startsWith("JOINT:")) {
			String[] abfr = mapped.split(":");
			sql.append("SELECT ").append(abfr[1]);
			for (String ex : extra) {
				sql.append(",").append(ex);
			}
			sql.append(" FROM ").append(abfr[3]).append(" WHERE ").append(abfr[2]).append("=")
				.append(getWrappedId());
			
			Stm stm = getConnection().getStatement();
			ResultSet rs = executeSqlQuery(sql.toString(), stm);
			LinkedList<String[]> list = new LinkedList<String[]>();
			try {
				while ((rs != null) && rs.next()) {
					String[] line = new String[extra.length + 1];
					line[0] = rs.getString(abfr[1]);
					for (int i = 1; i < extra.length + 1; i++) {
						line[i] = rs.getString(extra[i - 1]);
					}
					list.add(line);
				}
				rs.close();
				return list;
				
			} catch (Exception ex) {
				ElexisStatus status =
					new ElexisStatus(ElexisStatus.ERROR, CoreHub.PLUGIN_ID, ElexisStatus.CODE_NONE,
						"Fehler beim Lesen der Liste ", ex, ElexisStatus.LOG_ERRORS);
				// This is not an exception but a misconfiguration. No need to
				// stop program flow.
				// Just return null
				// as the documentation of the method states.
				// throw new PersistenceException(status);
				return null;
			} finally {
				try {
					rs.close();
					stm.delete();
				} catch (SQLException e) {
					// ignore
				}
			}
		} else {
			log.error("Fehlerhaftes Mapping " + mapped);
		}
		return null;
		
	}
	
	/**
	 * Ein Feld in die Datenbank übertragen. Gleichzeitig Cache-update Die Tabelle wird über
	 * getTableName() erfragt.
	 * 
	 * @param field
	 *            Name des Feldes
	 * @param value
	 *            Einzusetzender Wert (der vorherige Wert wird überschrieben)
	 * @return true bei Erfolg
	 */
	public boolean set(final String field, String value){
		String mapped = map(field);
		String table = getTableName();
		String key = getKey(field);
		StringBuilder sql = new StringBuilder();
		long ts = System.currentTimeMillis();
		
		if (value == null) {
			cache.remove(key);
			sql.append("UPDATE ").append(table).append(" SET ").append(mapped)
				.append("=NULL, lastupdate=" + Long.toString(ts) + " WHERE ID=")
				.append(getWrappedId());
			getConnection().exec(sql.toString());
			return true;
		}
		Object oldval = cache.get(key);
		cache.put(key, value, getCacheTime()); // refresh cache
		if (value.equals(oldval)) {
			return true; // no need to write data if it ws already in cache
		}
		
		if (mapped.startsWith("EXT:")) {
			int ix = mapped.indexOf(':', 5);
			if (ix == -1) {
				log.error("Fehlerhaftes Mapping bei " + field);
				return false;
			}
			table = mapped.substring(4, ix);
			mapped = mapped.substring(ix + 1);
			sql.append("UPDATE ").append(table).append(" SET ").append(mapped);
		} else {
			sql.append("UPDATE ").append(table).append(" SET ");
			if (mapped.startsWith("S:")) {
				sql.append(mapped.substring(4));
			} else {
				sql.append(mapped);
			}
		}
		sql.append("=?, lastupdate=? WHERE ID=").append(getWrappedId());
		String cmd = sql.toString();
		PreparedStatement pst = getConnection().prepareStatement(cmd);
		
		encode(1, pst, field, value);
		if (tracetable != null) {
			StringBuffer params = new StringBuffer();
			params.append("[");
			params.append(value);
			params.append("]");
			doTrace(cmd + " " + params);
		}
		try {
			pst.setLong(2, ts);
			pst.executeUpdate();
			// ElexisEventDispatcher.getInstance().fire(new
			// ElexisEvent(this,this.getClass(),ElexisEvent.EVENT_UPDATE));
			return true;
		} catch (Exception ex) {
			ElexisStatus status =
				new ElexisStatus(ElexisStatus.ERROR, CoreHub.PLUGIN_ID, ElexisStatus.CODE_NONE,
					"Fehler bei: " + cmd + "(" + field + "=" + value + ")", ex,
					ElexisStatus.LOG_ERRORS);
			throw new PersistenceException(status); // See api doc. check this
													// whether it breaks
			// existing code.
			// return false; // See api doc. Return false on errors.
		} finally {
			try {
				pst.close();
			} catch (SQLException e) {}
		}
		
	}
	
	/**
	 * Eine Hashtable speichern. Diese wird zunächst in ein byte[] geplättet, und so gespeichert.
	 * 
	 * @param field
	 * @param map
	 * @return 0 bei Fehler
	 */
	@SuppressWarnings("rawtypes")
	public void setMap(final String field, final Map<Object, Object> map){
		if (map == null) {
			throw new PersistenceException(new ElexisStatus(Status.ERROR, CoreHub.PLUGIN_ID,
				ElexisStatus.CODE_NONE, "Attempt to store Null map", null));
		}
		byte[] bin = flatten((Hashtable) map);
		cache.put(getKey(field), map, getCacheTime());
		setBinary(field, bin);
	}
	
	/**
	 * Eine VersionedResource zurückschreiben. Um Datenverlust durch gleichzeitigen Zugriff zu
	 * vermeiden, wird zunächst die aktuelle Version in der Datenbank gelesen und mit der neuen
	 * Version überlagert.
	 */
	protected void setVersionedResource(final String field, final String entry){
		String lockid = lock("VersionedResource", true);
		VersionedResource old = getVersionedResource(field, true);
		if (old.update(entry, CoreHub.actUser.getLabel()) == true) {
			cache.put(getKey(field), old, getCacheTime());
			setBinary(field, old.serialize());
		}
		unlock("VersionedResource", lockid);
	}
	
	public void setBinary(final String field, final byte[] value){
		String key = getKey(field);
		cache.put(key, value, getCacheTime());
		setBinaryRaw(field, value);
	}
	
	private void setBinaryRaw(final String field, final byte[] value){
		StringBuilder sql = new StringBuilder(1000);
		sql.append("UPDATE ").append(getTableName()).append(" SET ").append(
		/* map */(field)).append("=?, lastupdate=?").append(" WHERE ID=").append(getWrappedId());
		String cmd = sql.toString();
		if (tracetable != null) {
			doTrace(cmd);
		}
		PreparedStatement stm = getConnection().prepareStatement(cmd);
		try {
			stm.setBytes(1, value);
			stm.setLong(2, System.currentTimeMillis());
			stm.executeUpdate();
		} catch (Exception ex) {
			log.error("Fehler beim Ausführen der Abfrage " + cmd, ex);
			throw new PersistenceException(new ElexisStatus(Status.ERROR, CoreHub.PLUGIN_ID,
				ElexisStatus.CODE_NONE, "setBytes: Es trat ein Fehler beim Schreiben auf. "
					+ ex.getMessage(), ex, Log.ERRORS));
		} finally {
			try {
				stm.close();
			} catch (SQLException e) {
				ExHandler.handle(e);
				throw new PersistenceException("Could not close statement " + e.getMessage());
			}
		}
	}
	
	/**
	 * Set a value of type int.
	 * 
	 * @param field
	 *            a table field of numeric type
	 * @param value
	 *            the value to be set
	 * @return true on success, false else
	 */
	public boolean setInt(final String field, final int value){
		String stringValue = new Integer(value).toString();
		if (stringValue.length() <= MAX_INT_LENGTH) {
			return set(field, stringValue);
		} else {
			return false;
		}
	}
	
	private void doTrace(final String sql){
		StringBuffer tracer = new StringBuffer();
		tracer.append("INSERT INTO ").append(tracetable);
		tracer.append(" (logtime,Workstation,Username,action) VALUES (");
		tracer.append(System.currentTimeMillis()).append(",");
		tracer.append(pcname).append(",");
		tracer.append(username).append(",");
		tracer.append(JdbcLink.wrap(sql.replace('\'', '/'))).append(")");
		getConnection().exec(tracer.toString());
	}
	
	/**
	 * Eine Element einer n:m Verknüpfung eintragen. Zur Tabellendefinition wird das mapping
	 * verwendet.
	 * 
	 * @param field
	 *            Das n:m Feld, für das ein neuer Eintrag erstellt werden soll.
	 * @param oID
	 *            ID des Zielobjekts, auf das der Eintrag zeigen soll
	 * @param extra
	 *            Definition der zusätzlichen Felder der Joint-Tabelle. Jeder Eintrag in der Form
	 *            Feldname=Wert
	 * @return 0 bei Fehler
	 */
	public int addToList(final String field, final String oID, final String... extra){
		String mapped = map(field);
		if (mapped.startsWith("JOINT:")) {
			String[] m = mapped.split(":");// m[1] FremdID, m[2] eigene ID, m[3]
			// Name Joint
			if (m.length > 3) {
				StringBuffer head = new StringBuffer(100);
				StringBuffer tail = new StringBuffer(100);
				head.append("INSERT INTO ").append(m[3]).append("(ID,").append(m[2]).append(",")
					.append(m[1]);
				tail.append(") VALUES (").append(JdbcLink.wrap(StringTool.unique("aij")))
					.append(",").append(getWrappedId()).append(",").append(JdbcLink.wrap(oID));
				if (extra != null) {
					for (String s : extra) {
						String[] def = s.split("=");
						if (def.length != 2) {
							log.error("Fehlerhafter Aufruf addToList " + s);
							return 0;
						}
						head.append(",").append(def[0]);
						tail.append(",").append(JdbcLink.wrap(def[1]));
					}
				}
				head.append(tail).append(")");
				if (tracetable != null) {
					String sql = head.toString();
					doTrace(sql);
					return getConnection().exec(sql);
				}
				return getConnection().exec(head.toString());
			}
		}
		log.error("Fehlerhaftes Mapping: " + mapped);
		return 0;
	}
	
	/**
	 * Remove all relations to this object from link
	 * 
	 * @param field
	 */
	public void removeFromList(String field){
		String mapped = map(field);
		if (mapped.startsWith("JOINT:")) {
			String[] m = mapped.split(":");// m[1] FremdID, m[2] eigene ID, m[3]
			// Name Joint
			if (m.length > 3) {
				StringBuilder sql = new StringBuilder(200);
				sql.append("DELETE FROM ").append(m[3]).append(" WHERE ").append(m[2]).append("=")
					.append(getWrappedId());
				if (tracetable != null) {
					String sq = sql.toString();
					doTrace(sq);
				}
				getConnection().exec(sql.toString());
				return;
			}
		}
		log.error("Fehlerhaftes Mapping: " + mapped);
	}
	
	/**
	 * Remove a relation to this object from link
	 * 
	 * @param field
	 * @param oID
	 */
	public void removeFromList(String field, String oID){
		String mapped = map(field);
		if (mapped.startsWith("JOINT:")) {
			String[] m = mapped.split(":");// m[1] FremdID, m[2] eigene ID, m[3]
			// Name Joint
			if (m.length > 3) {
				StringBuilder sql = new StringBuilder(200);
				sql.append("DELETE FROM ").append(m[3]).append(" WHERE ").append(m[2]).append("=")
					.append(getWrappedId()).append(" AND ").append(m[1]).append("=")
					.append(JdbcLink.wrap(oID));
				if (tracetable != null) {
					String sq = sql.toString();
					doTrace(sq);
				}
				getConnection().exec(sql.toString());
				return;
			}
		}
		log.error("Fehlerhaftes Mapping: " + mapped);
	}
	
	/**
	 * Ein neues Objekt erstellen und in die Datenbank eintragen
	 * 
	 * @param customID
	 *            Wenn eine ID (muss eindeutig sein!) vorgegeben werden soll. Bei null wird eine
	 *            generiert.
	 * @return true bei Erfolg
	 */
	protected boolean create(final String customID){
		// String pattern=this.getClass().getSimpleName();
		if (customID != null) {
			id = customID;
		}
		StringBuffer sql = new StringBuffer(300);
		sql.append("INSERT INTO ").append(getTableName()).append("(ID) VALUES (")
			.append(getWrappedId()).append(")");
		if (getConnection().exec(sql.toString()) != 0) {
			setConstraint();
			ElexisEventDispatcher.getInstance().fire(
				new ElexisEvent(this, getClass(), ElexisEvent.EVENT_CREATE));
			return true;
		}
		return false;
	}
	
	/**
	 * Ein Objekt und ggf. dessen XID's aus der Datenbank löschen the object is not deleted but
	 * rather marked as deleted. A purge must be applied to remove the object really
	 * 
	 * @return true on success
	 */
	public boolean delete(){
		if (set("deleted", "1")) {
			List<Xid> xids = new Query<Xid>(Xid.class, Xid.FLD_OBJECT, getId()).execute();
			for (Xid xid : xids) {
				xid.delete();
			}
			new DBLog(this, DBLog.TYP.DELETE);
			IPersistentObject sel = ElexisEventDispatcher.getSelected(this.getClass());
			if ((sel != null) && sel.equals(this)) {
				ElexisEventDispatcher.clearSelection(this.getClass());
			}
			ElexisEventDispatcher.getInstance().fire(
				new ElexisEvent(this, getClass(), ElexisEvent.EVENT_DELETE));
			return true;
		}
		return false;
	}
	
	/**
	 * Alle Bezüge aus einer n:m-Verknüpfung zu diesem Objekt löschen
	 * 
	 * @param field
	 *            Feldname, der die Liste definiert
	 * @return
	 */
	public boolean deleteList(final String field){
		String mapped = map(field);
		if (!mapped.startsWith("JOINT:")) {
			ElexisStatus status =
				new ElexisStatus(ElexisStatus.ERROR, CoreHub.PLUGIN_ID, ElexisStatus.CODE_NONE,
					"Feld " + field + " ist keine n:m Verknüpfung", null, ElexisStatus.LOG_ERRORS);
			ElexisEventDispatcher.fireElexisStatusEvent(status);
			return false;
		}
		String[] m = mapped.split(":");// m[1] FremdID, m[2] eigene ID, m[3]
		// Name Joint
		getConnection().exec("DELETE FROM " + m[3] + " WHERE " + m[2] + "=" + getWrappedId());
		return true;
	}
	
	/**
	 * We can undelete any object by simply clearing the deleted-flag and reanimate dependend XID's
	 * 
	 * @return true on success
	 */
	public boolean undelete(){
		if (set("deleted", "0")) {
			Query<Xid> qbe = new Query<Xid>(Xid.class);
			qbe.clear(true);
			qbe.add(Xid.FLD_OBJECT, Query.EQUALS, getId());
			List<Xid> xids = qbe.execute();
			for (Xid xid : xids) {
				xid.undelete();
			}
			new DBLog(this, DBLog.TYP.UNDELETE);
			ElexisEventDispatcher.getInstance().fire(
				new ElexisEvent(this, getClass(), ElexisEvent.EVENT_CREATE));
			return true;
		}
		return false;
	}
	
	/**
	 * Mehrere Felder auf einmal setzen (Effizienter als einzelnes set)
	 * 
	 * @param fields
	 *            die Feldnamen
	 * @param values
	 *            die Werte
	 * @return false bei Fehler
	 */
	public boolean set(final String[] fields, final String... values){
		if ((fields == null) || (values == null) || (fields.length != values.length)) {
			log.error("Falsche Felddefinition für set");
			return false;
		}
		StringBuffer sql = new StringBuffer(200);
		sql.append("UPDATE ").append(getTableName()).append(" SET ");
		for (int i = 0; i < fields.length; i++) {
			String mapped = map(fields[i]);
			if (mapped.startsWith("S:")) {
				sql.append(mapped.substring(4));
			} else {
				sql.append(mapped);
			}
			sql.append("=?,");
			cache.put(getKey(fields[i]), values[i], getCacheTime());
		}
		sql.append("lastupdate=?");
		// sql.delete(sql.length() - 1, 100000);
		sql.append(" WHERE ID=").append(getWrappedId());
		String cmd = sql.toString();
		PreparedStatement pst = getConnection().prepareStatement(cmd);
		for (int i = 0; i < fields.length; i++) {
			encode(i + 1, pst, fields[i], values[i]);
		}
		if (tracetable != null) {
			StringBuffer params = new StringBuffer();
			params.append("[");
			params.append(StringTool.join(values, ", "));
			params.append("]");
			doTrace(cmd + " " + params);
		}
		try {
			pst.setLong(fields.length + 1, System.currentTimeMillis());
			pst.executeUpdate();
			ElexisEventDispatcher.getInstance().fire(
				new ElexisEvent(this, this.getClass(), ElexisEvent.EVENT_UPDATE));
			return true;
		} catch (Exception ex) {
			ExHandler.handle(ex);
			StringBuilder sb = new StringBuilder();
			sb.append("Fehler bei ").append(cmd).append("\nFelder:\n");
			for (int i = 0; i < fields.length; i++) {
				sb.append(fields[i]).append("=").append(values[i]).append("\n");
			}
			ElexisStatus status =
				new ElexisStatus(ElexisStatus.ERROR, CoreHub.PLUGIN_ID, ElexisStatus.CODE_NONE,
					sb.toString(), ex, ElexisStatus.LOG_ERRORS);
			// DONT Throw an Exception. The API doc states: return false on
			// errors!!
			// throw new PersistenceException(status);
			return false;
		} finally {
			try {
				pst.close();
			} catch (SQLException e) {}
		}
	}
	
	/**
	 * Mehrere Felder auf einmal auslesen
	 * 
	 * @param fields
	 *            die Felder
	 * @param values
	 *            String Array für die gelesenen Werte
	 * @return true ok, values wurden gesetzt
	 */
	public boolean get(final String[] fields, final String[] values){
		if ((fields == null) || (values == null) || (fields.length != values.length)) {
			log.error("Falscher Aufruf von get(String[],String[]");
			return false;
		}
		StringBuffer sql = new StringBuffer(200);
		sql.append("SELECT ");
		boolean[] decode = new boolean[fields.length];
		for (int i = 0; i < fields.length; i++) {
			String key = getKey(fields[i]);
			Object ret = cache.get(key);
			if (ret instanceof String) {
				values[i] = (String) ret;
			} else {
				String f1 = map(fields[i]);
				if (f1.startsWith("S:")) {
					sql.append(f1.substring(4));
					decode[i] = true;
				} else {
					sql.append(f1);
				}
				sql.append(",");
			}
		}
		if (sql.length() < 8) {
			return true;
		}
		sql.delete(sql.length() - 1, 1000);
		sql.append(" FROM ").append(getTableName()).append(" WHERE ID=").append(getWrappedId());
		
		Stm stm = getConnection().getStatement();
		ResultSet rs = executeSqlQuery(sql.toString(), stm);
		try {
			if ((rs != null) && rs.next()) {
				for (int i = 0; i < values.length; i++) {
					if (values[i] == null) {
						if (decode[i] == true) {
							values[i] = decode(fields[i], rs);
						} else {
							values[i] = checkNull(rs.getString(map(fields[i])));
						}
						cache.put(getKey(fields[i]), values[i], getCacheTime());
					}
				}
				
			}
			return true;
		} catch (Exception ex) {
			ExHandler.handle(ex);
			return false;
		} finally {
			try {
				rs.close();
				stm.delete();
			} catch (SQLException e) {
				// ignore
			}
		}
	}
	
	/**
	 * Apply some magic to the input parameters, and return a decoded string object. TODO describe
	 * magic
	 * 
	 * @param field
	 * @param rs
	 * @return decoded string or null if decode was not possible
	 */
	private String decode(final String field, final ResultSet rs){
		
		try {
			String mapped = map(field);
			if (mapped.startsWith("S:")) {
				char mode = mapped.charAt(2);
				switch (mode) {
				case 'D':
					String dat = rs.getString(mapped.substring(4));
					if (dat == null) {
						return "";
					}
					TimeTool t = new TimeTool();
					if (t.set(dat) == true) {
						return t.toString(TimeTool.DATE_GER);
					} else {
						return "";
					}
				case 'N':
					int val = rs.getInt(mapped.substring(4));
					return Integer.toString(val);
				case 'C':
					InputStream is = rs.getBinaryStream(mapped.substring(4));
					if (is == null) {
						return "";
					}
					byte[] exp = CompEx.expand(is);
					return StringTool.createString(exp);
					
				case 'V':
					byte[] in = rs.getBytes(mapped.substring(4));
					VersionedResource vr = VersionedResource.load(in);
					return vr.getHead();
				}
			}
		} catch (Exception ex) {
			log.error("Fehler bei decode ", ex);
			
			// Dont throw an exception. Null is an acceptable (and normally
			// testes) return value if something went wrong.
			// throw new PersistenceException(status);
			
		}
		return null;
	}
	
	private String encode(final int num, final PreparedStatement pst, final String field,
		final String value){
		String mapped = map(field);
		String ret = value;
		try {
			if (mapped.startsWith("S:")) {
				String typ = mapped.substring(2, 3);
				mapped = mapped.substring(4);
				byte[] enc;
				
				if (typ.startsWith("D")) { // datum
					TimeTool t = new TimeTool();
					if ((!StringTool.isNothing(value)) && (t.set(value) == true)) {
						ret = t.toString(TimeTool.DATE_COMPACT);
						pst.setString(num, ret);
					} else {
						ret = "";
						pst.setString(num, "");
					}
					
				} else if (typ.startsWith("C")) { // string enocding
					enc = CompEx.Compress(value, CompEx.ZIP);
					pst.setBytes(num, enc);
				} else if (typ.startsWith("N")) { // Number encoding
					pst.setInt(num, Integer.parseInt(value));
				} else {
					log.error("Unbekannter encode code " + typ);
				}
			} else {
				pst.setString(num, value);
			}
		} catch (Exception ex) {
			ElexisStatus status =
				new ElexisStatus(ElexisStatus.ERROR, CoreHub.PLUGIN_ID, ElexisStatus.CODE_NONE,
					"Fehler beim String encoder", ex, ElexisStatus.LOG_ERRORS);
			// Dont throw an exeption. returning the original value is an
			// acceptable way if encoding
			// is not possible. Frequently it's just
			// a configuration problem, so just log it and let the user decide
			// if they want to fix
			// it later.
			// DONT throw new PersistenceException(status);
			log.error("Fehler beim String encoder: " + ex.getMessage());
			
		}
		return ret;
	}
	
	/**
	 * Get a unique key for a value, suitable for identifying a key in a cache. The current
	 * implementation uses the table name, the id of the PersistentObject and the field name.
	 * 
	 * @param field
	 *            the field to get a key for
	 * @return a unique key
	 */
	private String getKey(final String field){
		StringBuffer key = new StringBuffer();
		
		key.append(getTableName());
		key.append(".");
		key.append(getId());
		key.append("#");
		key.append(field);
		
		return key.toString();
	}
	
	/**
	 * Verbindung zur Datenbank trennen
	 * 
	 */
	public static void disconnect(){
		if (getConnection() != null) {
			if (getConnection().DBFlavor.startsWith("hsqldb")) {
				getConnection().exec("SHUTDOWN COMPACT");
			}
			getConnection().disconnect();
			j = null;
			log.info("Verbindung zur Datenbank getrennt.");
			cache.stat();
		}
	}
	
	@Override
	public boolean equals(final Object arg0){
		if (arg0 instanceof PersistentObject) {
			return getId().equals(((PersistentObject) arg0).getId());
		}
		return false;
	}
	
	/**
	 * Return a String field making sure that it will never be null
	 * 
	 * @param in
	 *            name of the field to retrieve
	 * @return the field contents or "" if it was null
	 */
	public static String checkNull(final Object in){
		if (in == null) {
			return "";
		}
		if (!(in instanceof String)) {
			return "";
		}
		return (String) in;
	}
	
	/**
	 * return a numeric field making sure the call will not fail on illegal values
	 * 
	 * @param in
	 *            name of the field
	 * @return the value of the field as integer or 0 if it was null or not nomeric.
	 */
	public static int checkZero(final Object in){
		if (StringTool.isNothing(in)) {
			return 0;
		}
		try {
			return Integer.parseInt(((String) in).trim()); // We're sure in is a
															// String at this
			// point
		} catch (NumberFormatException ex) {
			ExHandler.handle(ex);
			return 0;
		}
	}
	
	/**
	 * return a numeric field making sure the call will not fail on illegal values
	 * 
	 * @param in
	 *            name of the field
	 * @return the value of the field as double or 0.0 if it was null or not a Double.
	 */
	public static double checkZeroDouble(final String in){
		if (StringTool.isNothing(in)) {
			return 0.0;
		}
		try {
			return Double.parseDouble(in.trim());
		} catch (NumberFormatException ex) {
			ExHandler.handle(ex);
			return 0.0;
		}
	}
	
	/**
	 * return the time of the last update of this object
	 * 
	 * @return the time (as given in System.currentTimeMillis()) of the last write operation on this
	 *         object or 0 if there was no valid lastupdate time
	 */
	public long getLastUpdate(){
		try {
			return Long.parseLong(get("lastupdate"));
		} catch (Exception ex) {
			// ExHandler.handle(ex);
			return 0L;
		}
	}
	
	@Override
	public int hashCode(){
		return getId().hashCode();
	}
	
	public static void clearCache(){
		synchronized (cache) {
			cache.clear();
		}
	}
	
	public static void resetCache(){
		synchronized (cache) {
			cache.reset();
		}
	}
	
	/**
	 * Return time-to-live in cache for this object
	 * 
	 * @return the time in seconds
	 */
	public int getCacheTime(){
		return default_lifetime;
	}
	
	public static void setDefaultCacheLifetime(int seconds){
		default_lifetime = seconds;
	}
	
	public static int getDefaultCacheLifetime(){
		return default_lifetime;
	}
	
	/**
	 * public helper to execute an sql script iven as file path. SQL Errors will be
	 * handeld/displayed by SqlWithUiRunner
	 * 
	 * @param filepath
	 *            where the script is
	 * @param plugin
	 *            name of the originating plugin
	 * @throws IOException
	 *             file not found or not readable
	 */
	public static void executeSQLScript(String filepath, String plugin) throws IOException{
		FileInputStream is = new FileInputStream(filepath);
		InputStreamReader isr = new InputStreamReader(is);
		char[] buf = new char[4096];
		int l = 0;
		StringBuilder sb = new StringBuilder();
		while ((l = isr.read(buf)) > 0) {
			sb.append(buf, 0, l);
		}
		new SqlRunner(new String[] {
			sb.toString()
		}, plugin).runSql();
		
	}
	
	/*
	 * protected static void createOrModifyTable(final String sqlScript) { try {
	 * PlatformUI.getWorkbench().getProgressService() .busyCursorWhile(new IRunnableWithProgress() {
	 * public void run(IProgressMonitor moni) { moni.beginTask("Führe Datenbankmodifikation aus",
	 * IProgressMonitor.UNKNOWN); try { final ByteArrayInputStream bais; bais = new
	 * ByteArrayInputStream(sqlScript .getBytes("UTF-8")); if (getConnection().execScript(bais,
	 * true, false) == false) { SWTHelper .showError("Datenbank-Fehler",
	 * "Konnte Datenbank-Script nicht ausführen"); log.log("Cannot execute db script: " + sqlScript,
	 * Log.WARNINGS); } moni.done(); } catch (UnsupportedEncodingException e) { // should really
	 * never happen e.printStackTrace(); } } }); } catch (Exception e) {
	 * SWTHelper.showError("Interner-Fehler", "Konnte Datenbank-Script nicht ausführen"); log.log(e,
	 * "Cannot execute db script: " + sqlScript, Log.ERRORS); } }
	 */
	protected static boolean executeScript(final String pathname){
		Stm stm = getConnection().getStatement();
		try {
			FileInputStream is = new FileInputStream(pathname);
			return stm.execScript(is, true, true);
		} catch (Exception ex) {
			ExHandler.handle(ex);
			return false;
		} finally {
			getConnection().releaseStatement(stm);
		}
	}
	
	/**
	 * Utility function to remove a table and all objects defined therein consistentliy To make sure
	 * dependent data are deleted as well, we call each object's delete operator individually before
	 * dropping the table
	 * 
	 * @param name
	 *            the name of the table
	 */
	@SuppressWarnings({
		"unchecked", "rawtypes"
	})
	protected static void removeTable(final String name, final Class oclas){
		Query qbe = new Query(oclas);
		for (Object o : qbe.execute()) {
			((PersistentObject) o).delete();
		}
		getConnection().exec("DROP TABLE " + name);
	}
	
	/**
	 * Convert a Hashtable into a compressed byte array. Note: the resulting array is java-specific,
	 * but stable through jre Versions (serialVersionUID: 1421746759512286392L)
	 * 
	 * @param hash
	 *            the hashtable to store
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public static byte[] flatten(final Hashtable hash){
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream(hash.size() * 30);
			ZipOutputStream zos = new ZipOutputStream(baos);
			zos.putNextEntry(new ZipEntry("hash"));
			ObjectOutputStream oos = new ObjectOutputStream(zos);
			oos.writeObject(hash);
			zos.close();
			baos.close();
			return baos.toByteArray();
		} catch (Exception ex) {
			ExHandler.handle(ex);
			return null;
		}
	}
	
	/**
	 * Recreate a Hashtable from a byte array as created by flatten()
	 * 
	 * @param flat
	 *            the byte array
	 * @return the original Hashtable or null if no Hashtable could be created from the array
	 */
	@SuppressWarnings("unchecked")
	public static Hashtable<Object, Object> fold(final byte[] flat){
		try {
			ByteArrayInputStream bais = new ByteArrayInputStream(flat);
			ZipInputStream zis = new ZipInputStream(bais);
			zis.getNextEntry();
			ObjectInputStream ois = new ObjectInputStream(zis);
			Hashtable<Object, Object> res = (Hashtable<Object, Object>) ois.readObject();
			ois.close();
			bais.close();
			return res;
		} catch (Exception ex) {
			log.error("Error unfolding object", ex);
			return null;
		}
	}
	
	/**
	 * Returns array of field names of the database fields.<br>
	 * Used for export functionality
	 */
	protected String[] getExportFields(){
		try {
			ResultSet res =
				getConnection().getStatement().query("Select count(id) from " + getTableName());
			ResultSetMetaData rmd = res.getMetaData();
			String[] ret = new String[rmd.getColumnCount()];
			for (int i = 0; i < ret.length; i++) {
				ret[i] = rmd.getColumnName(i + 1);
			}
			return ret;
		} catch (Exception ex) {
			ExHandler.handle(ex);
			return null;
		}
		/*
		 * throw new IllegalArgumentException("No export fields for " + getClass().getSimpleName() +
		 * " available");
		 */
	}
	
	/**
	 * Returns uid value. The uid should be world wide universal.<br>
	 * If this code changes, then the method getExportUIDVersion has to be overwritten<br>
	 * and the returned value incremented.
	 * 
	 */
	protected String getExportUIDValue(){
		throw new IllegalArgumentException("No export uid value for " + getClass().getSimpleName()
			+ " available");
	}
	
	/**
	 * Checks the version of the export functionality. If the method<br>
	 * getExportUIDValue() changes, this method should return a new number.<br>
	 */
	protected String getExportUIDVersion(){
		return "1";
	}
	
	/**
	 * Exports a persistentobject to an xml string
	 * 
	 * @return
	 */
	public String exportData(){
		return XML2Database.exportData(this);
	}
	
	/**
	 * Execute the sql string and handle exceptions appropriately.
	 * <p>
	 * <b>ATTENTION:</b> JdbcLinkResourceException will trigger a restart of Elexis in
	 * at.medevit.medelexis.ui.statushandler.
	 * </p>
	 * 
	 * @param sql
	 * @return
	 */
	private ResultSet executeSqlQuery(String sql, Stm stm){
		ResultSet res = null;
		try {
			res = stm.query(sql);
		} catch (JdbcLinkException je) {
			ElexisStatus status = translateJdbcException(je);
			// trigger restart for severe communication error
			if (je instanceof JdbcLinkResourceException) {
				status.setCode(ElexisStatus.CODE_RESTART | ElexisStatus.CODE_NOFEEDBACK);
				status.setMessage(status.getMessage() + "\nACHTUNG: Elexis wird neu gestarted!\n");
				status.setLogLevel(ElexisStatus.LOG_FATALS);
				// TODO throw PersistenceException to UI code ...
				// calling StatusManager directly here was not intended,
				// but throwing the exception without handling it apropreately
				// in the UI code makes it impossible for the status handler
				// to display a blocking error dialog
				// (this is executed in a Runnable where Exception handling is
				// not blocking UI
				// thread)
				ElexisEventDispatcher.fireElexisStatusEvent(status);
			} else {
				status.setLogLevel(ElexisStatus.LOG_FATALS);
				throw new PersistenceException(status);
			}
		}
		return res;
	}
	
	private static ElexisStatus translateJdbcException(JdbcLinkException jdbc){
		if (jdbc instanceof JdbcLinkSyntaxException) {
			return new ElexisStatus(ElexisStatus.ERROR, CoreHub.PLUGIN_ID, ElexisStatus.CODE_NONE,
				"Fehler in der Datenbanksyntax.", jdbc, ElexisStatus.LOG_ERRORS);
		} else if (jdbc instanceof JdbcLinkConcurrencyException) {
			return new ElexisStatus(ElexisStatus.ERROR, CoreHub.PLUGIN_ID, ElexisStatus.CODE_NONE,
				"Fehler bei einer Datenbanktransaktion.", jdbc, ElexisStatus.LOG_ERRORS);
		} else if (jdbc instanceof JdbcLinkResourceException) {
			return new ElexisStatus(ElexisStatus.ERROR, CoreHub.PLUGIN_ID, ElexisStatus.CODE_NONE,
				"Fehler bei der Datenbankkommunikation.", jdbc, ElexisStatus.LOG_ERRORS);
		} else {
			return new ElexisStatus(ElexisStatus.ERROR, CoreHub.PLUGIN_ID, ElexisStatus.CODE_NONE,
				"Fehler in der Datenbankschnittstelle.", jdbc, ElexisStatus.LOG_ERRORS);
		}
	}
	
	/**
	 * Utility procedure for unit tests which need to start with a clean database
	 */
	public static boolean deleteAllTables(){
		int nrTables = 0;
		String tableName = "none";
		DatabaseMetaData dmd;
		Connection conn = null;
		try {
			conn = j.getConnection();
			dmd = conn.getMetaData();
			String[] onlyTables = {
				"TABLE"
			};
			ResultSet rs = dmd.getTables(null, null, "%", onlyTables);
			if (rs != null) {
				while (rs.next()) {
					// DatabaseMetaData#getTables() specifies TABLE_NAME is in
					// column 3
					tableName = rs.getString(3);
					getConnection().exec("DROP TABLE " + tableName);
					nrTables++;
				}
			}
		} catch (SQLException e1) {
			log.error("Error deleting table " + tableName);
			return false;
		} finally {
			try {
				if (conn != null) {
					conn.close();
				}
			} catch (SQLException e) {
				log.error("Error closing connection" + e);
			}
		}
		log.info("Deleted " + nrTables + " tables");
		return true;
	}
	
	/**
	 * Utility procedure
	 * 
	 * @param tableName
	 *            name of the table to check existence for
	 */
	public static boolean tableExists(String tableName){
		int nrFounds = 0;
		// Vergleich schaut nicht auf Gross/Klein-Schreibung, da thomas
		// schon H2-DB gesehen hat, wo entweder alles gross oder alles klein war
		Connection conn = null;
		try {
			conn = j.getConnection();
			DatabaseMetaData dmd = conn.getMetaData();
			String[] onlyTables = {
				"TABLE"
			};
			ResultSet rs = dmd.getTables(null, null, "%", onlyTables);
			if (rs != null) {
				while (rs.next()) {
					// DatabaseMetaData#getTables() specifies TABLE_NAME is in
					// column 3
					if (rs.getString(3).equalsIgnoreCase(tableName))
						nrFounds++;
				}
			}
		} catch (SQLException je) {
			log.error("Fehler beim abrufen der Datenbank Tabellen Information.", je);
		} finally {
			try {
				if (conn != null) {
					conn.close();
				}
			} catch (SQLException e) {
				log.error("Error closing connection " + e);
			}
		}
		if (nrFounds > 1) {
			// Dies kann vorkommen, wenn man eine MySQL-datenbank von Windows ->
			// Linuz kopiert
			// und dort nicht die System-Variable lower_case_table_names nicht
			// gesetzt ist
			// Anmerkung von Niklaus Giger
			log.error("Tabelle " + tableName + " " + nrFounds + "-mal gefunden!!");
		}
		return nrFounds == 1;
	}
	
	/**
	 * Convert an arbitrary value into the database format
	 * 
	 * @author Marco Descher
	 * @since 2.1.6
	 * @param in
	 *            {@link Object}
	 * @return String representing the value in database storage conform format
	 */
	public static String ts(Object in){
		if (in == null)
			return "";
		if (in instanceof String)
			return (String) in;
		if (in instanceof Boolean) {
			return ((Boolean) in) ? "1" : "0";
		}
		if (in instanceof Long)
			return Long.toString((Long) in);
		if (in instanceof Integer)
			return Integer.toString((Integer) in);
		if (in instanceof Double)
			return Double.toString((Double) in);
		if (in instanceof Date) {
			return new SimpleDateFormat("dd.MM.yyyy").format((Date) in);
		}
		if (in instanceof XMLGregorianCalendar) {
			XMLGregorianCalendar dt = (XMLGregorianCalendar) in;
			return new SimpleDateFormat("dd.MM.yyyy").format(dt.toGregorianCalendar().getTime());
		}
		return "";
	}
	
	public void addChangeListener(IChangeListener listener, String fieldToObserve){
		
	}
	
	public void removeChangeListener(IChangeListener listener, String fieldObserved){
		
	}
	
}
