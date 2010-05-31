
/*
* Tigase Jabber/XMPP Server
* Copyright (C) 2004-2010 "Artur Hefczyc" <artur.hefczyc@tigase.org>
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, version 3 of the License.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program. Look for COPYING file in the top folder.
* If not, see http://www.gnu.org/licenses/.
*
* $Rev$
* Last modified by $Author$
* $Date$
 */
package tigase.server.amp;

//~--- non-JDK imports --------------------------------------------------------

import tigase.db.MsgRepositoryIfc;
import tigase.db.UserNotFoundException;

import tigase.util.JDBCAbstract;
import tigase.util.SimpleCache;

import tigase.xml.DomBuilderHandler;
import tigase.xml.Element;
import tigase.xml.SimpleParser;
import tigase.xml.SingletonFactory;

import tigase.xmpp.BareJID;
import tigase.xmpp.JID;

//~--- JDK imports ------------------------------------------------------------

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;

import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

//~--- classes ----------------------------------------------------------------

/**
 * Created: May 3, 2010 5:28:02 PM
 *
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
public class MsgRepository extends JDBCAbstract implements MsgRepositoryIfc {
	private static final Logger log = Logger.getLogger(MsgRepository.class.getName());
	private static final String MSG_TABLE = "msg_history";
	private static final String MSG_ID_COLUMN = "msg_id";
	private static final String MSG_TIMESTAMP_COLUMN = "ts";
	private static final String MSG_EXPIRED_COLUMN = "expired";
	private static final String MSG_FROM_UID_COLUMN = "sender_uid";
	private static final String MSG_TO_UID_COLUMN = "receiver_uid";
	private static final String MSG_BODY_COLUMN = "message";
	private static final String CREATE_MSG_TABLE = "create table " + MSG_TABLE + " ( " + "  "
		+ MSG_ID_COLUMN + " serial," + "  " + MSG_TIMESTAMP_COLUMN
		+ " TIMESTAMP DEFAULT CURRENT_TIMESTAMP," + "  " + MSG_EXPIRED_COLUMN + " DATETIME,"
		+ "  " + MSG_FROM_UID_COLUMN + " bigint unsigned," + "  " + MSG_TO_UID_COLUMN
		+ " bigint unsigned NOT NULL," + "  " + MSG_BODY_COLUMN + " varchar(4096) NOT NULL,"
		+ " key (" + MSG_EXPIRED_COLUMN + "), " + " key (" + MSG_FROM_UID_COLUMN + ", "
		+ MSG_TO_UID_COLUMN + ")," + " key (" + MSG_TO_UID_COLUMN + ", " + MSG_FROM_UID_COLUMN
		+ "))";
	private static final String MSG_INSERT_QUERY = "insert into " + MSG_TABLE + " ( "
		+ MSG_EXPIRED_COLUMN + ", " + MSG_FROM_UID_COLUMN + ", " + MSG_TO_UID_COLUMN + ", "
		+ MSG_BODY_COLUMN + ") values (?, ?, ?, ?)";
	private static final String MSG_SELECT_TO_JID_QUERY = "select * from " + MSG_TABLE
		+ " where " + MSG_TO_UID_COLUMN + " = ?";
	private static final String MSG_DELETE_TO_JID_QUERY = "delete from " + MSG_TABLE + " where "
		+ MSG_TO_UID_COLUMN + " = ?";
	private static final String MSG_DELETE_ID_QUERY = "delete from " + MSG_TABLE + " where "
		+ MSG_ID_COLUMN + " = ?";
	private static final String MSG_SELECT_EXPIRED_QUERY = "select * from " + MSG_TABLE
		+ " where expired is not null order by expired";
	private static final String MSG_SELECT_EXPIRED_BEFORE_QUERY = "select * from " + MSG_TABLE
		+ " where expired is not null and expired <= ? order by expired";
	private static final String GET_USER_UID_PROP_KEY = "user-uid-query";
	private static final String GET_USER_UID_DEF_QUERY = "{ call TigGetUserDBUid(?) }";
	private static final int MAX_UID_CACHE_SIZE = 100000;
	private static final long MAX_UID_CACHE_TIME = 3600000;
	private static final Map<String, MsgRepository> repos = new ConcurrentSkipListMap<String,
		MsgRepository>();
	private static final int MAX_QUEUE_SIZE = 1000;

	//~--- fields ---------------------------------------------------------------

	private PreparedStatement delete_id_st = null;
	private PreparedStatement delete_to_jid_st = null;
	private long earliestOffline = Long.MAX_VALUE;
	private PreparedStatement insert_msg_st = null;
	private PreparedStatement select_expired_before_st = null;
	private PreparedStatement select_expired_st = null;
	private SimpleParser parser = SingletonFactory.getParserInstance();
	private PreparedStatement select_to_jid_st = null;
	private String uid_query = GET_USER_UID_DEF_QUERY;
	private PreparedStatement uid_st = null;
	private boolean initialized = false;
	private Map<BareJID, Long> uids_cache = Collections.synchronizedMap(new SimpleCache<BareJID,
		Long>(MAX_UID_CACHE_SIZE, MAX_UID_CACHE_TIME));
	private DelayQueue<MsgDBItem> expiredQueue = new DelayQueue<MsgDBItem>();

	//~--- get methods ----------------------------------------------------------

	/**
	 * Method description
	 *
	 *
	 * @param id_string
	 *
	 * @return
	 */
	public static MsgRepository getInstance(String id_string) {
		MsgRepository result = repos.get(id_string);

		if (result == null) {
			result = new MsgRepository();
			repos.put(id_string, result);
		}

		return result;
	}

	/**
	 * Method description
	 *
	 *
	 * @param time
	 * @param delete
	 *
	 * @return
	 */
	@Override
	public Element getMessageExpired(long time, boolean delete) {
		if (expiredQueue.size() == 0) {

			// If the queue is empty load it with some elements
			loadExpiredQueue(MAX_QUEUE_SIZE);
		} else {

			// If the queue is not empty, check whether recently saved offline message
			// is due to expire sonner then the head of the queue.
			MsgDBItem item = expiredQueue.peek();

			if ((item != null) && (earliestOffline < item.expired.getTime())) {

				// There is in fact offline message due to expire sooner then the head of the
				// queue. Load all offline message due to expire sonner then the first element
				// in the queue.
				loadExpiredQueue(item.expired);
			}
		}

		MsgDBItem item = null;

		while (item == null) {
			try {
				item = expiredQueue.take();
			} catch (InterruptedException ex) {}
		}

		if (delete) {
			deleteMessage(item.db_id);
		}

		return item.msg;
	}

	//~--- methods --------------------------------------------------------------

	/**
	 * Method description
	 *
	 *
	 * @param conn_str
	 * @param map
	 *
	 * @throws SQLException
	 */
	@Override
	public void initRepository(String conn_str, Map<String, String> map) throws SQLException {
		if (initialized) {
			return;
		}

		initialized = true;
		log.info("Initializing dbAccess for db connection url: " + conn_str);

		if (map != null) {
			String query = map.get(GET_USER_UID_PROP_KEY);

			if (query != null) {
				uid_query = query;
			}
		}

		setResourceUri(conn_str);

		try {

			// This may fail if not required tables have been created yet.
			checkConnection();
		} catch (Exception e) {

			// Ignore for now....
		} finally {

			// Check if DB is correctly setup and contains all required tables.
			checkDB();
		}
	}

	/**
	 * Method description
	 *
	 *
	 * @param to
	 * @param delete
	 *
	 * @return
	 * @throws UserNotFoundException
	 */
	@Override
	public Queue<Element> loadMessagesToJID(JID to, boolean delete)
			throws UserNotFoundException {
		Queue<Element> result = null;
		ResultSet rs = null;

		try {
			checkConnection();

			long to_uid = getUserUID(to.getBareJID());

			synchronized (select_to_jid_st) {
				select_to_jid_st.setLong(1, to_uid);
				rs = select_to_jid_st.executeQuery();

				StringBuilder sb = new StringBuilder();

				while (rs.next()) {
					sb.append(rs.getString(MSG_BODY_COLUMN));
				}

				if (sb.length() > 0) {
					DomBuilderHandler domHandler = new DomBuilderHandler();

					parser.parse(domHandler, sb.toString().toCharArray(), 0, sb.length());
					result = domHandler.getParsedElements();
				}
			}

			if (delete) {
				synchronized (delete_to_jid_st) {
					delete_to_jid_st.setLong(1, to_uid);
					delete_to_jid_st.executeUpdate();
				}
			}
		} catch (SQLException e) {
			log.log(Level.WARNING, "Problem getting offline messages for user: " + to, e);
		} finally {
			release(null, rs);
		}

		return result;
	}

	/**
	 * Method description
	 *
	 *
	 * @param from
	 * @param to
	 * @param expired
	 * @param msg
	 * @throws UserNotFoundException
	 */
	@Override
	public void storeMessage(JID from, JID to, Date expired, Element msg)
			throws UserNotFoundException {
		if (log.isLoggable(Level.FINEST)) {
			log.finest("Storring expired: " + expired + " message: " + msg);
		}

		try {
			checkConnection();

			long from_uid = -1;

//    try {
//
//      // This user may not exist in our DB as this might be a user from
//      // a remote server/different domain
//      from_uid = getUserUID(from.getBareJID());
//    } catch (UserNotFoundException e) {
//      from_uid = -1;
//    }
			long to_uid = getUserUID(to.getBareJID());

			synchronized (insert_msg_st) {
				if (expired == null) {
					insert_msg_st.setNull(1, Types.TIMESTAMP);
				} else {
					Timestamp time = new Timestamp(expired.getTime());

					insert_msg_st.setTimestamp(1, time);
				}

				if (from_uid <= 0) {
					insert_msg_st.setNull(2, Types.BIGINT);
				} else {
					insert_msg_st.setLong(2, from_uid);
				}

				insert_msg_st.setLong(3, to_uid);
				insert_msg_st.setString(4, msg.toString());
				insert_msg_st.executeUpdate();
			}

			if (expired != null) {
				if (expired.getTime() < earliestOffline) {
					earliestOffline = expired.getTime();
				}

				if (expiredQueue.size() == 0) {
					loadExpiredQueue(1);
				}
			}
		} catch (SQLException e) {
			log.log(Level.WARNING, "Problem adding new entry to DB: ", e);
		}
	}

	@Override
	protected void initPreparedStatements() throws SQLException {
		super.initPreparedStatements();
		uid_st = prepareQuery(uid_query);
		insert_msg_st = prepareQuery(MSG_INSERT_QUERY);
		select_to_jid_st = prepareQuery(MSG_SELECT_TO_JID_QUERY);
		delete_to_jid_st = prepareQuery(MSG_DELETE_TO_JID_QUERY);
		delete_id_st = prepareQuery(MSG_DELETE_ID_QUERY);
		select_expired_st = prepareQuery(MSG_SELECT_EXPIRED_QUERY);
		select_expired_before_st = prepareQuery(MSG_SELECT_EXPIRED_BEFORE_QUERY);
	}

	private void checkDB() throws SQLException {
		ResultSet rs = null;

		try {
			String CHECK_TABLE_QUERY = "select count(*) from ";
			PreparedStatement checkTableSt = prepareStatement(CHECK_TABLE_QUERY + MSG_TABLE);

			rs = checkTableSt.executeQuery();

			if (rs.next()) {
				long count = rs.getLong(1);

				log.info("DB table " + MSG_TABLE + " OK, items: " + count);
			}
		} catch (Exception e) {
			PreparedStatement createTable = prepareStatement(CREATE_MSG_TABLE);

			createTable.executeUpdate();
		} finally {
			release(null, rs);
			rs = null;
		}
	}

	private void deleteMessage(long msg_id) {
		try {
			checkConnection();

			synchronized (delete_id_st) {
				delete_id_st.setLong(1, msg_id);
				delete_id_st.executeUpdate();
			}
		} catch (SQLException e) {
			log.log(Level.WARNING, "Problem removing entry from DB: ", e);
		}
	}

	//~--- get methods ----------------------------------------------------------

	private long getUserUID(BareJID user_id) throws SQLException, UserNotFoundException {
		Long cache_res = uids_cache.get(user_id);

		if (cache_res != null) {
			return cache_res.longValue();
		}    // end of if (result != null)

		ResultSet rs = null;
		long result = -1;

		try {
			synchronized (uid_st) {
				uid_st.setString(1, user_id.toString());
				rs = uid_st.executeQuery();

				if (rs.next()) {
					result = rs.getLong(1);
				}
			}

			if (result <= 0) {
				throw new UserNotFoundException("User does not exist: " + user_id);
			}    // end of if (isnext) else
		} finally {
			release(null, rs);
		}

		uids_cache.put(user_id, result);

		return result;
	}

	//~--- methods --------------------------------------------------------------

	private void loadExpiredQueue(int min_elements) {
		ResultSet rs = null;

		try {
			checkConnection();

			synchronized (select_expired_st) {
				rs = select_expired_st.executeQuery();

				DomBuilderHandler domHandler = new DomBuilderHandler();
				int counter = 0;

				while (rs.next()
						&& ((expiredQueue.size() < MAX_QUEUE_SIZE) || (counter++ < min_elements))) {
					String msg_str = rs.getString(MSG_BODY_COLUMN);

					parser.parse(domHandler, msg_str.toCharArray(), 0, msg_str.length());

					Queue<Element> elems = domHandler.getParsedElements();
					Element msg = elems.poll();

					if (msg == null) {
						log.info("Something wrong, loaded offline message from DB but parsed no "
								+ "XML elements: " + msg_str);
					} else {
						Timestamp ts = rs.getTimestamp(MSG_EXPIRED_COLUMN);
						MsgDBItem item = new MsgDBItem(rs.getLong(MSG_ID_COLUMN), msg, ts);

						expiredQueue.offer(item);
					}
				}
			}
		} catch (SQLException e) {
			log.log(Level.WARNING, "Problem getting offline messages from db: ", e);
		} finally {
			release(null, rs);
		}

		earliestOffline = Long.MAX_VALUE;
	}

	private void loadExpiredQueue(Date expired) {
		ResultSet rs = null;

		try {
			if (expiredQueue.size() > 100 * MAX_QUEUE_SIZE) {
				expiredQueue.clear();
			}

			checkConnection();

			synchronized (select_expired_before_st) {
				select_expired_before_st.setTimestamp(1, new Timestamp(expired.getTime()));
				rs = select_expired_before_st.executeQuery();

				DomBuilderHandler domHandler = new DomBuilderHandler();
				int counter = 0;

				while (rs.next() && (counter++ < MAX_QUEUE_SIZE)) {
					String msg_str = rs.getString(MSG_BODY_COLUMN);

					parser.parse(domHandler, msg_str.toCharArray(), 0, msg_str.length());

					Queue<Element> elems = domHandler.getParsedElements();
					Element msg = elems.poll();

					if (msg == null) {
						log.info("Something wrong, loaded offline message from DB but parsed no "
								+ "XML elements: " + msg_str);
					} else {
						Timestamp ts = rs.getTimestamp(MSG_EXPIRED_COLUMN);
						MsgDBItem item = new MsgDBItem(rs.getLong(MSG_ID_COLUMN), msg, ts);

						expiredQueue.offer(item);
					}
				}
			}
		} catch (SQLException e) {
			log.log(Level.WARNING, "Problem getting offline messages from db: ", e);
		} finally {
			release(null, rs);
		}

		earliestOffline = Long.MAX_VALUE;
	}

	//~--- inner classes --------------------------------------------------------

	private class MsgDBItem implements Delayed {
		private long db_id = -1;
		private Date expired = null;
		private Element msg = null;

		//~--- constructors -------------------------------------------------------

		/**
		 * Constructs ...
		 *
		 *
		 * @param db_id
		 * @param msg
		 * @param expired
		 */
		public MsgDBItem(long db_id, Element msg, Date expired) {
			this.db_id = db_id;
			this.msg = msg;
			this.expired = expired;
		}

		//~--- methods ------------------------------------------------------------

		/**
		 * Method description
		 *
		 *
		 * @param o
		 *
		 * @return
		 */
		@Override
		public int compareTo(Delayed o) {
			return (int) (getDelay(TimeUnit.NANOSECONDS) - o.getDelay(TimeUnit.NANOSECONDS));
		}

		//~--- get methods --------------------------------------------------------

		/**
		 * Method description
		 *
		 *
		 * @param unit
		 *
		 * @return
		 */
		@Override
		public long getDelay(TimeUnit unit) {
			return unit.convert(expired.getTime() - System.currentTimeMillis(),
					TimeUnit.MILLISECONDS);
		}
	}
}


//~ Formatted in Sun Code Convention


//~ Formatted by Jindent --- http://www.jindent.com