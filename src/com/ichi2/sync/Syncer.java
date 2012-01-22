/***************************************************************************************
 * Copyright (c) 2009 Edu Zamora <edu.zasu@gmail.com>                                   *
 * Copyright (c) 2011 Norbert Nagold <norbert.nagold@gmail.com>                         *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/
//
package com.ichi2.sync;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import org.apache.http.HttpResponse;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.database.Cursor;
import android.database.SQLException;

import com.ichi2.async.Connection;
import com.ichi2.libanki.Collection;
import com.ichi2.libanki.Sched;
import com.ichi2.libanki.Utils;
import com.ichi2.utils.ConvUtils;

public class Syncer {

	Collection mCol;
	HttpSyncer mServer;
	long mRMod;
	long mRScm;
	int mMaxUsn;
	int mMediaUsn;
	long mLMod;
	long mLScm;
	int mMinUsn;
	boolean mLNewer;
	JSONObject mRChg;

	private LinkedList<String> mTablesLeft;
	private Cursor mCursor;

    public Syncer (Collection col, HttpSyncer server) {
    	mCol = col;
    	mServer = server;
    }

    /** Returns 'noChanges', 'fullSync', or 'success'. */
    public String sync(Connection con) {
    	// if the deck has any pending changes, flush them first and bump mod time
    	mCol.save();
    	// step 1: login & metadata
    	HttpResponse ret = mServer.meta();
    	int returntype = ret.getStatusLine().getStatusCode();
    	if (ret == null || returntype == 403){
    		return "badAuth";
    	} else if (returntype == 503) {
    		return "serverNotAvailable";
    	} else if (returntype != 200) {
    		return "error";
    	}
    	long rts;
    	long lts;
    	try {
        	JSONArray ra = HttpSyncer.getDataJSONArray(ret);
			mRMod = ra.getLong(0);
	    	mRScm = ra.getLong(1);
	    	mMaxUsn = ra.getInt(2);
	    	rts = ra.getLong(3);
	    	mMediaUsn = ra.getInt(4);

	    	JSONArray la = meta();
			mLMod = la.getLong(0);
	    	mLScm = la.getLong(1);
	    	mMinUsn = la.getInt(2);
	    	lts = la.getLong(3);
	    	if (Math.abs(rts - lts) > 300) {
	    		return "clockOff";
	    	}
	    	if (mLMod == mRMod) {
//	    		return "noChanges";
	    	} else if (mLScm != mRScm) {
	    		return "fullSync";
	    	}
	    	mLNewer = mLMod > mRMod;
	    	// step 2: deletions
//	    	con.publishProgress(R.string.);
	    	JSONObject lrem = removed();
	    	JSONObject o = new JSONObject();
	    	o.put("minUsn", mMinUsn);
	    	o.put("lnewer", mLNewer);
	    	o.put("graves", lrem);
	    	JSONObject rrem = mServer.start(o);
	    	if (rrem == null) {
	    		return "error";
	    	}
	    	remove(rrem);
	    	// ... and small objects
	    	JSONObject lchg = changes();
	    	JSONObject sch = new JSONObject();
	    	sch.put("changes", lchg);
	    	JSONObject rchg = mServer.applyChanges(sch);
	    	if (rchg == null) {
	    		return "error";
	    	}
	    	mergeChanges(lchg, rchg);
	    	// step 3: stream large tables from server
	    	while (true) {
	    		JSONObject chunk = mServer.chunk();
	    		if (chunk == null) {
		    		return "error";
		    	}
	    		JSONObject pch = new JSONObject();
	    		pch.put("chunk", chunk);
	    		applyChunk(pch);
	    		if (chunk.getBoolean("done")) {
	    			break;
	    		}
	    	}
	    	// step 4: stream to server
	    	while (true) {
	    		JSONObject chunk = chunk();
	    		JSONObject sech = new JSONObject();
	    		sech.put("chunk", chunk);
	    		mServer.applyChunk(sech);
	    		if (chunk.getBoolean("done")) {
	    			break;
	    		}
	    	}
		} catch (JSONException e) {
			throw new RuntimeException(e);
		} catch (IllegalStateException e) {
			throw new RuntimeException(e);
		}
    	// finalize
    	long mod = mServer.finish();
//    	if (mod == 0) {
//    		return "error";
//    	}
    	finish(mod);
    	return "success";
    }

	private JSONArray meta() {
    	JSONArray o = new JSONArray();
    	o.put(mCol.getMod());
    	o.put(mCol.getScm());
    	o.put(mCol.getUsn());
    	o.put(Utils.intNow());
    	return o;
    }

    /** Bundle up small objects. */
    private JSONObject changes() {
    	JSONObject o = new JSONObject();
    	try {
			o.put("models",getModels());
	    	o.put("decks", getDecks());
	    	o.put("tags", getTags());
	    	if (mLNewer) {
	    		o.put("conf", getConf());
	    	}
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
    	return o;
    }

    private JSONObject applyChanges(JSONObject changes) {
    	mRChg = changes;
    	JSONObject lchg = changes();
    	// merge our side before returning
    	mergeChanges(lchg, mRChg);
    	return lchg;
    }

    private void mergeChanges(JSONObject lchg, JSONObject rchg) {
    	try {
        	// then the other objects
			mergeModels(rchg.getJSONArray("models"));
	    	mergeDecks(rchg.getJSONArray("decks"));
	    	mergeTags(rchg.getJSONArray("tags"));
	    	if (rchg.has("conf")) {
	    		mergeConf(rchg.getJSONObject("conf"));
	    	}
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
    	prepareToChunk();
    }

    private String usnLim() {
    	if (mCol.getServer()) {
    		return "usn >= " + mMinUsn;
    	} else {
    		return "usn = -1";
    	}
    }

    private long finish() {
    	return finish(0);
    }
    private long finish(long mod) {
    	if (mod == 0) {
    		// server side; we decide new mod time
    		mod = Utils.intNow(1000);
    	}
    	mCol.setLs(mod);
    	mCol.setUsn(mMaxUsn + 1);
    	mCol.save(null, mod);
    	return mod;
    }

    /** Chunked syncing
     * ********************************************************************
     */

    private void prepareToChunk() {
    	mTablesLeft = new LinkedList<String>();
    	mTablesLeft.add("revlog");
    	mTablesLeft.add("cards");
    	mTablesLeft.add("notes");
    	mCursor = null;
    }

    private Cursor cursorForTable(String table) {
    	String lim = usnLim();
    	if (table.equals("revlog")) {
    		return mCol.getDb().getDatabase().rawQuery(String.format("SELECT id, cid, %d, ease, ivl, lastIvl, factor, time, type FROM revlog WHERE %s", mMaxUsn, lim), null);
    	} else if (table.equals("cards")) {
    		return mCol.getDb().getDatabase().rawQuery(String.format("SELECT id, nid, did, ord, mod, %d, type, queue, due, ivl, factor, reps, lapses, left, edue, flags, data FROM cards WHERE %s", mMaxUsn, lim), null);
    	} else {
    		return mCol.getDb().getDatabase().rawQuery(String.format("SELECT id, guid, mid, did, mod, %d, tags, flds, '', '', flags, data FROM notes WHERE %s", mMaxUsn, lim), null);
    	}
    }

    private JSONObject chunk() {
    	JSONObject buf = new JSONObject();
    	try {
    		buf.put("done", false);
        	int lim = 2500;
        	while (!mTablesLeft.isEmpty() && lim > 0) {
        		String curTable = mTablesLeft.getFirst();
        		if (mCursor == null) {
        			mCursor = cursorForTable(curTable);
        		}
        		JSONArray rows = new JSONArray();
        		while (mCursor.moveToNext()) {
        			JSONArray r = new JSONArray();
        			int count = mCursor.getColumnCount(); 
        			for (int i = 0; i < count; i++) {
        				int type = mCursor.getType(i);
        				switch (type) {
        				case Cursor.FIELD_TYPE_STRING:
            				r.put(mCursor.getString(i));
            				break;
        				case Cursor.FIELD_TYPE_FLOAT:
            				r.put(mCursor.getDouble(i));
            				break;
        				case Cursor.FIELD_TYPE_INTEGER:
            				r.put(mCursor.getLong(i));
            				break;
        				}
        			}
        			rows.put(r);
        		}
        		int fetched = rows.length();
        		if (fetched != lim) {
    				// table is empty
        			mTablesLeft.removeFirst();
        			mCursor.close();
        			mCursor = null;
        			// if we're the client, mark the objects as having been sent
        			if (!mCol.getServer()) {
        				mCol.getDb().getDatabase().execSQL("UPDATE " + curTable + " SET usn=" + mMaxUsn + " WHERE usn=-1");
        			}
        		}
        		buf.put(curTable, rows);
        		lim -= fetched;
        	}
        	if (mTablesLeft.isEmpty()) {
    			buf.put("done", true);
    		}
    	} catch (JSONException e) {
    		throw new RuntimeException(e);
    	}
		return buf;
   	}

    private void applyChunk(JSONObject chunk) {
		try {
	    	if (chunk.has("revlog")) {
				mergeRevlog(chunk.getJSONArray("revlog"));
	    	}
	    	if (chunk.has("cards")) {
	    		mergeCards(chunk.getJSONArray("cards"));
	    	}
	    	if (chunk.has("notes")) {
	    		mergeNotes(chunk.getJSONArray("notes"));
	    	}
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
    }

    /** Deletions
     * ********************************************************************
     */

    private JSONObject removed() {
    	JSONArray cards = new JSONArray();
    	JSONArray notes = new JSONArray();
    	JSONArray decks = new JSONArray();
    	Cursor cur = null;
    	try {
    		cur = mCol.getDb().getDatabase().rawQuery("SELECT oid, type FROM graves WHERE usn" + (mCol.getServer() ? (" >= " + mMinUsn) : (" = -1")), null);
    		while (cur.moveToNext()) {
    			int type = cur.getInt(1);
    			switch (type) {
    			case Sched.REM_CARD:
    				cards.put(cur.getLong(0));
    				break;
    			case Sched.REM_NOTE:
    				notes.put(cur.getLong(0));
    				break;
    			case Sched.REM_DECK:
    				decks.put(cur.getLong(0));
    				break;
    			}
    		}
    	} finally {
    		if (cur != null && !cur.isClosed()) {
    			cur.close();
    		}
    	}
    	if (!mCol.getServer()) {
    		mCol.getDb().getDatabase().execSQL("UPDATE graves SET usn=" + mMaxUsn + " WHERE usn=-1");
    	}
    	JSONObject o = new JSONObject();
    	try {
			o.put("cards", cards);
	    	o.put("notes", notes);
	    	o.put("decks", decks);
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
    	return o;
    }

    private JSONObject start(int minUsn, boolean lnewer, JSONObject graves) {
	mMaxUsn = mCol.getUsn();
	mMinUsn = minUsn;
	mLNewer = !lnewer;
	JSONObject lgraves = removed();
	remove(graves);
	return lgraves;
    }

    private void remove(JSONObject graves) {
    	// pretend to be the server so we don't set usn = -1
    	boolean wasServer = mCol.getServer();
    	mCol.setServer(true);
    	try {
        	// notes first, so we don't end up with duplicate graves
			mCol._remNotes(Utils.jsonArrayToLongArray(graves.getJSONArray("notes")));
			// then cards
			mCol.remCards(Utils.jsonArrayToLongArray(graves.getJSONArray("cards")));
			// and deck
			JSONArray decks = graves.getJSONArray("decks");
			for (int i = 0; i < decks.length(); i++) {
				mCol.getDecks().rem(decks.getLong(i));
			}
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
    	mCol.setServer(wasServer);
    }

    /** Models
     * ********************************************************************
     */

    private JSONArray getModels() {
    	JSONArray result = new JSONArray();
		try {
	    	if (mCol.getServer()) {
	    		for (JSONObject m : mCol.getModels().all()) {
					if (m.getInt("usn") >= mMinUsn) {
						result.put(m);
					}
				}
	    	} else {
	    		for (JSONObject m : mCol.getModels().all()) {
	    			if (m.getInt("usn") == -1) {
	    				m.put("usn", mMaxUsn);
	    				result.put(m);
	    			}
	    		}
	    		mCol.getModels().save();
	    	}
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
		return result;
    }

    private void mergeModels(JSONArray rchg) {
    	for (int i = 0; i < rchg.length(); i++) {
			try {
	    		JSONObject r = rchg.getJSONObject(i);
	    		JSONObject l;
				l = mCol.getModels().get(r.getLong("id"));
	    		// if missing locally or server is newer, update
	    		if (l == null || r.getLong("mod") > l.getLong("mod")) {
	    			mCol.getModels().update(r);
	    		}
			} catch (JSONException e) {
				throw new RuntimeException(e);
			}
    	}
    }


    /** Decks
     * ********************************************************************
     */

    private JSONArray getDecks() {
    	JSONArray result = new JSONArray();
    	try {
        	if (mCol.getServer()) {
    			JSONArray decks = new JSONArray();
    			for (JSONObject g : mCol.getDecks().all()) {
    				if (g.getInt("usn") >= mMinUsn) {
    					decks.put(g);
    				}
    			}
    			JSONArray dconfs = new JSONArray();
    			for (JSONObject g : mCol.getDecks().allConf()) {
    				if (g.getInt("usn") >= mMinUsn) {
    					dconfs.put(g);
    				}
    			}
    			result.put(decks);
    			result.put(dconfs);
    		} else {
    			JSONArray decks = new JSONArray();
    			for (JSONObject g : mCol.getDecks().all()) {
    				if (g.getInt("usn") == -1) {
    					g.put("usn", mMaxUsn);
    					decks.put(g);
    				}
    			}
    			JSONArray dconfs = new JSONArray();
    			for (JSONObject g : mCol.getDecks().allConf()) {
    				if (g.getInt("usn") == -1) {
    					g.put("usn", mMaxUsn);
    					dconfs.put(g);
    				}
    			}
    			mCol.getDecks().save();
    			result.put(decks);
    			result.put(dconfs);
    		}
    	} catch (JSONException e) {
			throw new RuntimeException(e);
    	}
    	return result;	
    }

    private void mergeDecks(JSONArray rchg) {
    	try {
    		JSONArray decks = rchg.getJSONArray(0);
        	for (int i = 0; i < decks.length(); i++) {
        		JSONObject r = decks.getJSONObject(i);
        		JSONObject l = mCol.getDecks().get(r.getLong("id"), false);
        		// if missing locally or server is newer, update
        		if (l == null || r.getLong("mod") > l.getLong("mod")) {
        			mCol.getDecks().update(r);
        		}
        	} 
    		JSONArray confs = rchg.getJSONArray(1);
        	for (int i = 0; i < confs.length(); i++) {
        		JSONObject r = confs.getJSONObject(i);
        		JSONObject l = mCol.getDecks().getConf(r.getLong("id"));
        		// if missing locally or server is newer, update
        		if (l == null || r.getLong("mod") > l.getLong("mod")) {
        			mCol.getDecks().updateConf(r);
        		}
        	} 
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
    }
    
    /** Tags
     * ********************************************************************
     */

    private JSONArray getTags() {
    	JSONArray result = new JSONArray();
    	if (mCol.getServer()) {
			for (Map.Entry<String, Integer> t : mCol.getTags().allItems().entrySet()) {
				if (t.getValue() >= mMinUsn) {
					JSONArray ta = new JSONArray();
					ta.put(t.getKey());
					ta.put(t.getValue());
					result.put(ta);
				}
			}
    	} else {
    		for (Map.Entry<String, Integer> t : mCol.getTags().allItems().entrySet()) {
    			if (t.getValue() == -1) {
    				ArrayList<String> tag = new ArrayList<String>();
					tag.add(t.getKey());
					mCol.getTags().register(tag, mMaxUsn);
					JSONArray ta = new JSONArray();
					ta.put(t.getKey());
					ta.put(t.getValue());
					result.put(ta);
				}
			}
			mCol.getTags().save();
		}
		return result;
    }

    private void mergeTags(JSONArray tags) {
    	ArrayList<String> list = new ArrayList<String>();
    	for (int i = 0; i < tags.length(); i++) {
    		try {
				list.add(tags.getString(i));
			} catch (JSONException e) {
				throw new RuntimeException(e);
			}
    	}
    	mCol.getTags().register(list, mMaxUsn);
    }
    
    /** Cards/notes/revlog
     * ********************************************************************
     */

    private void mergeRevlog(JSONArray logs) {
    	for (int i = 0; i < logs.length(); i++) {
    		try {
				mCol.getDb().getDatabase().execSQL("INSERT OR IGNORE INTO revlog VALUES (?,?,?,?,?,?,?,?,?)", ConvUtils.jsonArray2Objects(logs.getJSONArray(i)));    				
			} catch (SQLException e) {
				throw new RuntimeException(e);
			} catch (JSONException e) {
				throw new RuntimeException(e);
			}
    	}
    	
    }
   
    private ArrayList<Object[]> newerRows(JSONArray data, String table, int modIdx) {
    	long[] ids = new long[data.length()];
		try {
	    	for (int i = 0; i < data.length(); i++) {
				ids[i] = data.getJSONArray(i).getLong(0);
	    	}
	    	HashMap<Long, Long> lmods = new HashMap<Long, Long>();
	    	Cursor cur = null;
	    	try {
	    		cur = mCol.getDb().getDatabase().rawQuery("SELECT id, mod FROM " + table + "WHERE id IN " + Utils.ids2str(ids) + " AND " + usnLim(), null);
	    		while (cur.moveToNext()) {
	    			lmods.put(cur.getLong(0), cur.getLong(1));
	    		}
	    	} finally {
	    		if (cur != null && !cur.isClosed()) {
	    			cur.close();
	    		}
	    	}
	    	ArrayList<Object[]> update = new ArrayList<Object[]>();
	    	for (int i = 0; i < data.length(); i++) {
	    		JSONArray r = data.getJSONArray(i);
	    		if (!lmods.containsKey(r.getLong(0)) || lmods.get(i) < r.getLong(modIdx)) {
	    			update.add(ConvUtils.jsonArray2Objects(r));
	    		}
	    	}
	    	return update;
		} catch (JSONException e) {
			throw new RuntimeException(e);
		}
    }
   
    private void mergeCards(JSONArray cards) {
    	for (Object[] r : newerRows(cards, "cards", 4)) {
    		mCol.getDb().getDatabase().execSQL("INSERT OR REPLACE INTO cards VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", r);
    	}
    }
   
    private void mergeNotes(JSONArray notes) {
    	for (Object[] n : newerRows(notes, "notes", 4)) {
    		mCol.getDb().getDatabase().execSQL("INSERT OR REPLACE INTO notes VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", n);
    		mCol.updateFieldCache(new long[]{(Long) n[0]});
    	}
    }
   
    /** Col config
     * ********************************************************************
     */

    private JSONObject getConf() {
    	return mCol.getConf();
    }

    private void mergeConf(JSONObject conf) {
    	mCol.setConf(conf);
    }
    
//    private void updateModels(JSONArray models) throws JSONException {
//        ArrayList<String> insertedModelsIds = new ArrayList<String>();
//        AnkiDb ankiDB = mDeck.getDB();
//
//        String sql = "INSERT OR REPLACE INTO models"
//                    + " (id, deckId, created, modified, tags, name, description, features, spacing, initialSpacing, source)"
//                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?)";
//        SQLiteStatement statement = ankiDB.getDatabase().compileStatement(sql);
//        int len = models.length();
//        for (int i = 0; i < len; i++) {
//            JSONObject model = models.getJSONObject(i);
//
//            // id
//            String id = model.getString("id");
//            statement.bindString(1, id);
//            // deckId
//            statement.bindLong(2, model.getLong("deckId"));
//            // created
//            statement.bindDouble(3, model.getDouble("created"));
//            // modified
//            statement.bindDouble(4, model.getDouble("modified"));
//            // tags
//            statement.bindString(5, model.getString("tags"));
//            // name
//            statement.bindString(6, model.getString("name"));
//            // description
//            statement.bindString(7, model.getString("name"));
//            // features
//            statement.bindString(8, model.getString("features"));
//            // spacing
//            statement.bindDouble(9, model.getDouble("spacing"));
//            // initialSpacing
//            statement.bindDouble(10, model.getDouble("initialSpacing"));
//            // source
//            statement.bindLong(11, model.getLong("source"));
//
//            statement.execute();
//
//            insertedModelsIds.add(id);
//
//            mergeFieldModels(id, model.getJSONArray("fieldModels"));
//            mergeCardModels(id, model.getJSONArray("cardModels"));
//        }
//        statement.close();
//
//        // Delete inserted models from modelsDeleted
//        ankiDB.getDatabase().execSQL("DELETE FROM modelsDeleted WHERE modelId IN " + Utils.ids2str(insertedModelsIds));
//    }
//

//
//    public static HashMap<String, String> fullSyncFromServer(String password, String username, String deckName, String deckPath) {
//        HashMap<String, String> result = new HashMap<String, String>();
//        Throwable exc = null;
//        try {
//            String data = "p=" + URLEncoder.encode(password, "UTF-8") + "&u=" + URLEncoder.encode(username, "UTF-8")
//                    + "&d=" + URLEncoder.encode(deckName, "UTF-8");
//
//            // Log.i(AnkiDroidApp.TAG, "Data json = " + data);
//            HttpPost httpPost = new HttpPost(AnkiDroidProxy.SYNC_URL + "fulldown");
//            StringEntity entity = new StringEntity(data);
//            httpPost.setEntity(entity);
//            httpPost.setHeader("Content-type", "application/x-www-form-urlencoded");
//            DefaultHttpClient httpClient = new DefaultHttpClient();
//            HttpResponse response = httpClient.execute(httpPost);
//            HttpEntity entityResponse = response.getEntity();
//            InputStream content = entityResponse.getContent();
//            int responseCode = response.getStatusLine().getStatusCode();
//            String tempDeckPath = deckPath + ".tmp";
//            if (responseCode == 200) {
//                Utils.writeToFile(new InflaterInputStream(content), tempDeckPath);
//                File newFile = new File(tempDeckPath);
//                //File oldFile = new File(deckPath);
//                if (newFile.renameTo(new File(deckPath))) {
//                    result.put("code", "200");
//                } else {
//                    result.put("code", "PermissionError");
//                    result.put("message", "Can't overwrite old deck with downloaded from server");
//                }
//            } else {
//                result.put("code", String.valueOf(responseCode));
//                result.put("message", Utils.convertStreamToString(content));
//            }
//        } catch (UnsupportedEncodingException e) {
//            Log.e(AnkiDroidApp.TAG, Log.getStackTraceString(e));
//            result.put("code", "UnsupportedEncodingException");
//            exc = e;
//        } catch (ClientProtocolException e) {
//            Log.e(AnkiDroidApp.TAG, Log.getStackTraceString(e));
//            result.put("code", "ClientProtocolException");
//            exc = e;
//        } catch (IOException e) {
//            Log.e(AnkiDroidApp.TAG, Log.getStackTraceString(e));
//            result.put("code", "IOException");
//            exc = e;
//        }
//
//        if (exc != null) {
//            // Sometimes the exception has null message and we have to get it from its cause
//            while (exc.getMessage() == null && exc.getCause() != null) {
//                exc = exc.getCause();
//            }
//            result.put("message", exc.getMessage());
//        }
//        return result;
//    }
//
}
