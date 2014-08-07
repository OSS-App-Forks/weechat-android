package com.ubergeek42.WeechatAndroid.service;

import com.ubergeek42.weechat.relay.RelayConnection;
import com.ubergeek42.weechat.relay.RelayMessageHandler;
import com.ubergeek42.weechat.relay.protocol.Array;
import com.ubergeek42.weechat.relay.protocol.Hashtable;
import com.ubergeek42.weechat.relay.protocol.Hdata;
import com.ubergeek42.weechat.relay.protocol.HdataEntry;
import com.ubergeek42.weechat.relay.protocol.RelayObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;

/**
 * Created by sq on 25/07/2014.
 */
public class BufferList {
    private static Logger logger = LoggerFactory.getLogger("BufferList");
    final private static boolean DEBUG = true;

    // preferences
    public static boolean SORT_BUFFERS = false;
    public static boolean SHOW_TITLE = true;
    public static boolean FILTER_NONHUMAN_BUFFERS = false;
    public static String  FILTER = null;                                                                // TODO race condition?

    final static public LinkedHashSet<String> synced_buffers_full_names = new LinkedHashSet<String>();  // TODO race condition?

    final RelayServiceBackbone relay;
    final private RelayConnection connection;
    final private ArrayList<Buffer> buffers = new ArrayList<Buffer>();

    private BufferListEye buffers_eye;

    BufferList(RelayService relay) {
        this.relay = relay;
        this.connection = this.relay.connection;
        Buffer.buffer_list = this;

        // Handle us getting a listing of the this
        connection.addHandler("listbuffers", buffer_list_watcher);

        // Handle weechat event messages regarding this
        connection.addHandler("_buffer_opened", buffer_list_watcher);
        connection.addHandler("_buffer_renamed", buffer_list_watcher);
        connection.addHandler("_buffer_title_changed", buffer_list_watcher);
        connection.addHandler("_buffer_localvar_added", buffer_list_watcher);
        connection.addHandler("_buffer_localvar_changed", buffer_list_watcher);
        connection.addHandler("_buffer_localvar_removed", buffer_list_watcher);
        connection.addHandler("_buffer_closing", buffer_list_watcher);
        connection.addHandler("_buffer_moved", buffer_list_watcher);
        connection.addHandler("_buffer_merged", buffer_list_watcher);
        connection.addHandler("hotlist", hotlist_watcher);

        connection.addHandler("_buffer_line_added", buffer_line_watcher);
        connection.addHandler("listlines_reverse", buffer_line_watcher);

        // get a list of buffers current open, along with some information about them
        // also nicklist
        connection.sendMsg("listbuffers", "hdata", "buffer:gui_buffers(*) number,full_name,short_name,type,title,nicklist,local_variables,notify");
        connection.sendMsg("hotlist", "hdata", "hotlist:gui_hotlist(*) buffer,count");
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////// called from
    ////////////////////////////////////////////////////////////////////////////////////////////////

    synchronized public ArrayList<Buffer> getBufferListCopy() {
        ArrayList<Buffer> new_bufs = new ArrayList<Buffer>();
        for (Buffer buffer : buffers) {
            if (FILTER_NONHUMAN_BUFFERS && buffer.type == Buffer.OTHER) continue;
            if (FILTER != null && !buffer.full_name.toLowerCase().contains(FILTER)) continue;
            new_bufs.add(buffer);
        }
        return new_bufs;
    }

    synchronized public Buffer findByFullName(String full_name) {
        if (full_name == null) return null;
        for (Buffer buffer : buffers) if (buffer.full_name.equals(full_name)) return buffer;
        return null;
    }

    public void requestLinesForBufferByPointer(int pointer) {
        if (DEBUG) logger.error("requestLinesForBufferByPointer({})", pointer);
        connection.sendMsg("listlines_reverse", "hdata", String.format(
                "buffer:0x%x/own_lines/last_line(-%d)/data date,displayed,prefix,message,highlight,notify,tags_array",
                pointer, Buffer.MAX_LINES));
    }

    synchronized public void setBufferListEye(BufferListEye buffers_eye) {this.buffers_eye = buffers_eye;}

    ////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////

    synchronized private void notifyBuffersChanged() {
        sortBuffers();
        if (buffers_eye != null) buffers_eye.onBuffersChanged();
    }

    synchronized public void notifyBuffersSlightlyChanged() {
        if (buffers_eye != null) buffers_eye.onBuffersSlightlyChanged();
    }

    synchronized public void notifyBufferPropertiesChanged(Buffer buffer) {
        sortBuffers();
        buffer.onPropertiesChanged();
        if (buffers_eye != null) buffers_eye.onBuffersChanged();
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////

    synchronized public boolean isSynced(String full_name) {
        return synced_buffers_full_names.contains(full_name);
    }

    synchronized public void syncBuffer(String full_name) {
        if (DEBUG) logger.error("syncBuffer({})", full_name);
        BufferList.synced_buffers_full_names.add(full_name);
        relay.connection.sendMsg("sync " + full_name);
    }

    synchronized public void desyncBuffer(String full_name) {
        if (DEBUG) logger.error("desyncBuffer({})", full_name);
        BufferList.synced_buffers_full_names.remove(full_name);
        relay.connection.sendMsg("desync " + full_name);
    }

    synchronized static public String getSyncedBuffersAsString() {
        if (DEBUG) logger.error("getSyncedBuffersAsString() -> ...");
        StringBuilder sb = new StringBuilder();
        for (String full_name : BufferList.synced_buffers_full_names)
            sb.append(full_name).append("\0");
        return sb.toString();
    }

    synchronized static public void setSyncedBuffersFromString(String synced_buffers) {
        if (DEBUG) logger.error("setSyncedBuffersFromString({})", synced_buffers);
        StringTokenizer st = new StringTokenizer(synced_buffers, "\0");
        while (true) {
            try {
                synced_buffers_full_names.add(st.nextToken());
            } catch (NoSuchElementException e) {
                return;
            }
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////

    synchronized private Buffer findByPointer(int pointer) {
        for (Buffer buffer : buffers) if (buffer.pointer == pointer) return buffer;
        return null;
    }

    synchronized private void sortBuffers() {
        Collections.sort(buffers, sortByNumberComparator);
    }

    RelayMessageHandler buffer_list_watcher = new RelayMessageHandler() {
        @Override
        public void handleMessage(RelayObject obj, String id) {
            if (DEBUG) logger.warn("handleMessage(..., {}) (hdata size = {})", id, ((Hdata) obj).getCount());
            Hdata data = (Hdata) obj;

            for (int i = 0, size = data.getCount(); i < size; i++) {
                HdataEntry entry = data.getItem(i);

                if (id.equals("listbuffers") || id.equals("_buffer_opened")) {
                    RelayObject r;
                    Buffer buffer = new Buffer(entry.getPointerInt(),
                            entry.getItem("number").asInt(),
                            entry.getItem("full_name").asString(),
                            entry.getItem("short_name").asString(),
                            entry.getItem("title").asString(),
                            ((r = entry.getItem("notify")) != null) ? r.asInt() : 1,            // TODO request notify level afterwards???
                            (Hashtable) entry.getItem("local_variables"));                      // TODO because _buffer_opened doesn't provide notify level
                    synchronized (BufferList.this) {buffers.add(buffer);}
                    notifyBuffersChanged();
                } else {
                    Buffer buffer = findByPointer(entry.getPointerInt(0));
                    if (buffer == null) {
                        logger.error("handleMessage(..., {}): buffer is not present!", id);
                    } else {
                        if (id.equals("_buffer_renamed")) {
                            buffer.full_name = entry.getItem("full_name").asString();
                            buffer.short_name = entry.getItem("short_name").asString();
                            buffer.local_vars = (Hashtable) entry.getItem("local_variables");
                            notifyBufferPropertiesChanged(buffer);
                        } else if (id.equals("_buffer_title_changed")) {
                            buffer.title = entry.getItem("title").asString();
                            notifyBufferPropertiesChanged(buffer);
                        } else if (id.startsWith("_buffer_localvar_")) {
                            buffer.local_vars = (Hashtable) entry.getItem("local_variables");
                            notifyBufferPropertiesChanged(buffer);
                        } else if (id.equals("_buffer_moved") || id.equals("_buffer_merged")) {
                            buffer.number = entry.getItem("number").asInt();
                            notifyBufferPropertiesChanged(buffer);
                        } else if (id.equals("_buffer_closing")) {
                            buffer.onBufferClosed();
                            synchronized (BufferList.this) {buffers.remove(buffer);}
                            notifyBuffersChanged();
                        } else {
                            if (DEBUG) logger.warn("Unknown message ID: '{}'", id);
                        }
                    }
                }
            }
        }
    };

    // only tackles "hotlist" id
    RelayMessageHandler hotlist_watcher = new RelayMessageHandler() {
        @Override
        public void handleMessage(RelayObject obj, String id) {
            Hdata data = (Hdata) obj;
            for (int i = 0, size = data.getCount(); i < size; i++) {
                HdataEntry entry = data.getItem(i);
                Integer pointer = entry.getItem("buffer").asPointerInt();
                Buffer buffer = findByPointer(pointer);
                if (buffer != null) {
                    if (DEBUG) logger.warn("hotlist: buffer {}, count {}", buffer.short_name, entry.getItem("count").asArray());
                    Array count = entry.getItem("count").asArray();
                    buffer.unreads = count.get(1).asInt() + count.get(2).asInt();   // chat messages & private messages
                    buffer.highlights = count.get(3).asInt();                       // highlights
                }
            }
            notifyBuffersSlightlyChanged();
        }
    };

    ////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////

    RelayMessageHandler buffer_line_watcher = new RelayMessageHandler() {
        @Override
        public void handleMessage(RelayObject obj, String id) {
            Hdata data = (Hdata) obj;

            for (int i = 0, size = data.getCount(); i < size; i++) {

                boolean is_bottom = id.equals("_buffer_line_added");

                HdataEntry entry = data.getItem(i);
                int buffer_pointer = (is_bottom) ? entry.getItem("buffer").asPointerInt() :  entry.getPointerInt(0);
                Buffer buffer = findByPointer(buffer_pointer);
                if (buffer == null) {
                    if (DEBUG) logger.warn("no buffer to update!");
                    continue;
                }
                String message = entry.getItem("message").asString();
                String prefix =  entry.getItem("prefix").asString();
                boolean displayed = ( entry.getItem("displayed").asChar() == 0x01);
                Date time =  entry.getItem("date").asTime();
                RelayObject high = entry.getItem("highlight");
                boolean highlight = (high != null && high.asChar() == 0x01);
                RelayObject tagsobj = entry.getItem("tags_array");

                String[] tags = (tagsobj != null && tagsobj.getType() == RelayObject.WType.ARR) ?
                        tagsobj.asArray().asStringArray() : null;

                Buffer.Line line = new Buffer.Line(entry.getPointerInt(), time, prefix, message, displayed, highlight, tags);
                buffer.addLine(line, is_bottom);

                if (!is_bottom) buffer.holds_all_lines_it_is_supposed_to_hold = true;
            }
        }
    };

    private final Comparator<Buffer> sortByNumberComparator = new Comparator<Buffer>() {
        @Override
        public int compare(Buffer b1, Buffer b2) {
            return b1.number - b2.number;
        }
    };
}
























