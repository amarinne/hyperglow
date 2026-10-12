package com.eza.hyperglow.diagnostics;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Process;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.DefaultHandler2;

/** Fixed read-only setup probe. Only status tokens leave this root process. */
public final class SpicySetupProbe {
    private static final String DATABASE = "/data/adb/lspd/config/modules_config.db";
    private static final String PUBLISHING_KEY = "lyrics_hyper_aod_lyrics_enabled";
    private static final int MAX_PREFERENCE_BYTES = 512 * 1024;
    private static final String PER_USER_MODULE_QUERY =
            "SELECT state.enabled, EXISTS(SELECT 1 FROM scope "
                    + "WHERE scope.module_pkg_name=modules.module_pkg_name "
                    + "AND app_pkg_name=? AND user_id=?) FROM modules "
                    + "LEFT JOIN modules_state AS state ON state.module_pkg_name=modules.module_pkg_name "
                    + "AND state.user_id=? WHERE modules.module_pkg_name=? LIMIT 2";
    private static final String LEGACY_MODULE_QUERY =
            "SELECT enabled, EXISTS(SELECT 1 FROM scope WHERE scope.mid=modules.mid "
                    + "AND app_pkg_name=? AND user_id=?) FROM modules "
                    + "WHERE module_pkg_name=? LIMIT 2";

    private SpicySetupProbe() {}

    public static void main(String[] args) {
        if (Process.myUid() != 0 || args.length != 1 || !args[0].matches("[0-9]{1,5}")) {
            System.exit(1);
            return;
        }
        try {
            int userId = Integer.parseInt(args[0]);
            String[] module = readModuleConfiguration(userId);
            String publishing = readPublishingConfiguration(userId);
            System.out.println("spicy_setup_v1 user=" + userId + " module=" + module[0]
                    + " scope=" + module[1] + " publishing=" + publishing);
        } catch (Throwable unavailable) {
            System.exit(2);
        }
    }

    private static String[] readModuleConfiguration(int userId) {
        // The default Android corruption handler can delete even a read-only database as root.
        // Never repair, migrate, copy, or open a backup of the framework's live configuration.
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(DATABASE, null,
                SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                corrupted -> { /* Leave LSPosed's configuration untouched. */ })) {
            boolean perUserState;
            try (Cursor schema = db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name='modules_state'", null)) {
                perUserState = schema.moveToFirst();
            }
            String user = String.valueOf(userId);
            String[] arguments = perUserState
                    ? new String[]{"com.spotify.music", user, user, "com.eza.spicyex"}
                    : new String[]{"com.spotify.music", user, "com.eza.spicyex"};
            try (Cursor cursor = db.rawQuery(
                    perUserState ? PER_USER_MODULE_QUERY : LEGACY_MODULE_QUERY, arguments)) {
                int count = cursor.getCount();
                if (count == 0) return moduleStatuses(0, null, 0);
                if (count != 1 || !cursor.moveToFirst()) return new String[]{"unknown", "unknown"};
                Integer enabled = cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER ? cursor.getInt(0) : null;
                return moduleStatuses(count, enabled, cursor.getInt(1));
            }
        } catch (Exception unavailable) {
            return new String[]{"unknown", "unknown"};
        }
    }

    static String[] moduleStatuses(int rowCount, Integer enabled, int scope) {
        if (rowCount == 0) return new String[]{"not_registered", "not_selected"};
        if (rowCount != 1) return new String[]{"unknown", "unknown"};
        // A missing per-user state does not establish the framework's default enabled state.
        return new String[]{Integer.valueOf(1).equals(enabled) ? "enabled"
                : Integer.valueOf(0).equals(enabled) ? "disabled" : "unknown",
                scope == 1 ? "selected" : scope == 0 ? "not_selected" : "unknown"};
    }

    private static String readPublishingConfiguration(int userId) {
        // An app's mount namespace can hide other packages. Absence there is not a default value.
        String[] roots = {"/data/user/", "/data_mirror/data_ce/null/"};
        for (String root : roots) {
            File directory = new File(root + userId + "/com.spotify.music");
            if (!directory.isDirectory() || !directory.canRead()) continue;
            File preference = new File(directory, "shared_prefs/SpotifyPlus.xml");
            try {
                if (!preference.getCanonicalPath().equals(directory.getCanonicalPath()
                        + "/shared_prefs/SpotifyPlus.xml")) return "unknown";
                // SharedPreferences can restore this backup. The main file is not authoritative
                // while a write or recovery is pending, and this probe must never perform recovery.
                if (new File(preference.getPath() + ".bak").exists()) return "unknown";
                // An absent file cannot establish the in-memory default for a running producer.
                if (!preference.isFile() || !preference.canRead()) continue;
                long modifiedAt = preference.lastModified();
                long length = preference.length();
                try (FileInputStream input = new FileInputStream(preference)) {
                    byte[] bytes = input.readNBytes(MAX_PREFERENCE_BYTES + 1);
                    if (bytes.length == 0 || bytes.length > MAX_PREFERENCE_BYTES
                            || bytes.length != length || modifiedAt != preference.lastModified()
                            || length != preference.length()
                            || new File(preference.getPath() + ".bak").exists()) return "unknown";
                    return parsePublishingPreference(new ByteArrayInputStream(bytes));
                }
            } catch (Exception unavailable) {
                return "unknown";
            }
        }
        return "unknown";
    }

    static String parsePublishingPreference(InputStream input) {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            PublishingHandler handler = new PublishingHandler();
            reader.setContentHandler(handler);
            reader.setEntityResolver(handler);
            reader.setErrorHandler(handler);
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
            reader.parse(new InputSource(input));
            return handler.validMap && handler.depth == 0 ? handler.status : "unknown";
        } catch (Exception malformed) {
            return "unknown";
        }
    }

    private static final class PublishingHandler extends DefaultHandler2 {
        int depth;
        boolean validMap;
        boolean found;
        boolean readingPublishing;
        String status = "default_disabled";

        @Override
        public void startDTD(String name, String publicId, String systemId) throws SAXException {
            throw new SAXException("DTD is not supported");
        }

        @Override
        public void startElement(String uri, String localName, String name, Attributes attributes)
                throws SAXException {
            depth++;
            if (depth == 1) {
                if (!"map".equals(name)) throw new SAXException("Expected a preference map");
                validMap = true;
            }
            if (!PUBLISHING_KEY.equals(attributes.getValue("name"))) return;
            if (depth != 2 || found || !"boolean".equals(name)) {
                throw new SAXException("Invalid publishing preference");
            }
            found = true;
            readingPublishing = true;
            String value = attributes.getValue("value");
            if (!"true".equals(value) && !"false".equals(value)) {
                throw new SAXException("Invalid boolean");
            }
            status = "true".equals(value) ? "enabled" : "disabled";
        }

        @Override
        public void endElement(String uri, String localName, String name) {
            if (depth == 2) readingPublishing = false;
            depth--;
        }

        @Override
        public void characters(char[] text, int start, int length) throws SAXException {
            if (depth != 1 && !readingPublishing) return;
            for (int index = start; index < start + length; index++) {
                if (!Character.isWhitespace(text[index])) {
                    throw new SAXException("Unexpected preference text");
                }
            }
        }
    }
}
