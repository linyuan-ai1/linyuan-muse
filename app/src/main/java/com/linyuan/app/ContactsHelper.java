package com.linyuan.app;

import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public final class ContactsHelper {

    private ContactsHelper() { }

    public static final class Hit {
        public long contactId;
        public String name;
        public String number;
        public int type;
    }

    public static boolean canRead(Context c) {
        try { return c.checkSelfPermission("android.permission.READ_CONTACTS") == PackageManager.PERMISSION_GRANTED; }
        catch (Throwable t) { return false; }
    }

    public static boolean canWrite(Context c) {
        try { return c.checkSelfPermission("android.permission.WRITE_CONTACTS") == PackageManager.PERMISSION_GRANTED; }
        catch (Throwable t) { return false; }
    }

    public static boolean canCall(Context c) {
        try { return c.checkSelfPermission("android.permission.CALL_PHONE") == PackageManager.PERMISSION_GRANTED; }
        catch (Throwable t) { return false; }
    }

    public static int count(Context c) {
        if (!canRead(c)) return -1;
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(ContactsContract.Contacts.CONTENT_URI,
                    new String[]{"_id"}, null, null, null);
            return cur == null ? -1 : cur.getCount();
        } catch (Throwable t) {
            return -1;
        } finally {
            if (cur != null) try { cur.close(); } catch (Throwable ignored) { }
        }
    }

    public static List<Hit> search(Context c, String q, int limit) {
        List<Hit> out = new ArrayList<>();
        if (!canRead(c) || TextUtils.isEmpty(q)) return out;
        Cursor cur = null;
        try {
            String like = "%" + q.trim() + "%";
            cur = c.getContentResolver().query(Phone.CONTENT_URI,
                    new String[]{Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE},
                    Phone.DISPLAY_NAME + " LIKE ? OR " + Phone.NUMBER + " LIKE ?",
                    new String[]{like, like}, Phone.DISPLAY_NAME + " ASC");
            if (cur == null) return out;
            while (cur.moveToNext() && out.size() < limit) {
                Hit h = new Hit();
                h.contactId = cur.getLong(0);
                h.name = cur.getString(1);
                h.number = cur.getString(2);
                h.type = cur.getInt(3);
                out.add(h);
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) try { cur.close(); } catch (Throwable ignored) { }
        }
        return out;
    }

    public static String searchText(Context c, String q, int limit) {
        if (!canRead(c)) return "No permission to read contacts (设置 → 手机感知 → 联系人权限).";
        List<Hit> hits = search(c, q, limit);
        if (hits.isEmpty()) return "No contact matches \"" + q + "\".";
        StringBuilder sb = new StringBuilder();
        for (Hit h : hits) {
            sb.append("· ").append(TextUtils.isEmpty(h.name) ? "(no name)" : h.name)
                    .append(": ").append(h.number)
                    .append(" (").append(typeText(h.type)).append(")\n");
        }
        sb.append(hits.size()).append(" entries total. To place a call use contacts(action=call); to edit details use action=update.");
        return sb.toString();
    }

    public static String firstNumber(Context c, String name) {
        List<Hit> hits = search(c, name, 10);
        if (hits.isEmpty()) return null;

        for (Hit h : hits) if (name.equals(h.name)) return h.number;
        return hits.get(0).number;
    }

    public static String add(Context c, String name, String number) {
        if (!canWrite(c)) return "No permission to write contacts (设置 → 手机感知 → 联系人权限).";
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(number)) return "Both name and number are required.";
        try {
            ArrayList<ContentProviderOperation> ops = new ArrayList<>();
            ops.add(ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                    .withValue(RawContacts.ACCOUNT_TYPE, null)
                    .withValue(RawContacts.ACCOUNT_NAME, null)
                    .build());
            ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(StructuredName.DISPLAY_NAME, name)
                    .build());
            ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                    .withValue(Phone.NUMBER, number)
                    .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                    .build());
            c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
            return "Contact saved: " + name + " " + number;
        } catch (Throwable t) {
            return "Save failed: " + t.getMessage();
        }
    }

    public static String update(Context c, String name, String newName, String newNumber) {
        if (!canWrite(c)) return "No permission to write contacts (设置 → 手机感知 → 联系人权限).";
        if (TextUtils.isEmpty(name)) return "Specify which contact to update via name first.";
        List<Hit> hits = search(c, name, 10);
        if (hits.isEmpty()) return "No contact named \"" + name + "\" found.";
        long id = hits.get(0).contactId;
        StringBuilder did = new StringBuilder();
        ContentResolver cr = c.getContentResolver();
        try {
            if (!TextUtils.isEmpty(newName)) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(StructuredName.DISPLAY_NAME, newName);
                int n = cr.update(Data.CONTENT_URI, v,
                        Data.CONTACT_ID + "=? AND " + Data.MIMETYPE + "=?",
                        new String[]{String.valueOf(id), StructuredName.CONTENT_ITEM_TYPE});
                did.append(n > 0 ? ("name changed to " + newName) : "(the original record has no name field; rename skipped)");
            }
            if (!TextUtils.isEmpty(newNumber)) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(Phone.NUMBER, newNumber);
                int n = cr.update(Data.CONTENT_URI, v,
                        Data.CONTACT_ID + "=? AND " + Data.MIMETYPE + "=?",
                        new String[]{String.valueOf(id), Phone.CONTENT_ITEM_TYPE});
                if (n == 0) {

                    long rawId = rawContactIdOf(c, id);
                    ArrayList<ContentProviderOperation> ops = new ArrayList<>();
                    ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                            .withValue(Data.RAW_CONTACT_ID, rawId)
                            .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                            .withValue(Phone.NUMBER, newNumber)
                            .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                            .build());
                    cr.applyBatch(ContactsContract.AUTHORITY, ops);
                    did.append("added number ").append(newNumber);
                } else {
                    did.append(did.length() > 0 ? "; " : "").append("number changed to ").append(newNumber);
                }
            }
            if (did.length() == 0) return "Nothing to change was given (at least one of new_name / new_number).";
            return "Updated \"" + name + "\": " + did;
        } catch (Throwable t) {
            return "Update failed: " + t.getMessage();
        }
    }

    public static String delete(Context c, String name) {
        if (!canWrite(c)) return "No permission to write contacts (设置 → 手机感知 → 联系人权限).";
        if (TextUtils.isEmpty(name)) return "Specify which contact to delete via name first.";
        List<Hit> hits = search(c, name, 10);
        if (hits.isEmpty()) return "No contact named \"" + name + "\" found.";
        long id = hits.get(0).contactId;
        try {
            Uri uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id);
            int n = c.getContentResolver().delete(uri, null, null);
            return n > 0 ? ("Deleted \"" + name + "\"") : "Delete had no effect (the system refused)";
        } catch (Throwable t) {
            return "Delete failed: " + t.getMessage();
        }
    }

    public static String call(Context c, String who) {
        if (TextUtils.isEmpty(who)) return "Call whom? Give me a name or a number.";
        String number = who;
        String shown = who;

        if (!who.matches("[+0-9\\-\\s*#]+")) {
            String n = canRead(c) ? firstNumber(c, who) : null;
            if (TextUtils.isEmpty(n)) return "No \"" + who + "\" in contacts; you can give me the number directly.";
            number = n;
        }
        String tel = "tel:" + number.replace(" ", "").replace("-", "");
        try {
            if (canCall(c)) {
                Intent i = new Intent(Intent.ACTION_CALL, Uri.parse(tel));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                return "Calling " + shown + " (" + number + ").";
            }
            Intent i = new Intent(Intent.ACTION_DIAL, Uri.parse(tel));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return "No direct-call permission; the dialer has been opened with " + shown + " (" + number + ") filled in - just press the call button.";
        } catch (Throwable t) {

            return "Could not start the call (" + t.getMessage() + "). If the app happens to be in the background, bring it to the foreground and try again.";
        }
    }

    private static long rawContactIdOf(Context c, long contactId) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(RawContacts.CONTENT_URI,
                    new String[]{RawContacts._ID}, RawContacts.CONTACT_ID + "=?",
                    new String[]{String.valueOf(contactId)}, null);
            if (cur != null && cur.moveToFirst()) return cur.getLong(0);
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) try { cur.close(); } catch (Throwable ignored) { }
        }
        return -1;
    }

    private static String typeText(int type) {
        switch (type) {
            case Phone.TYPE_MOBILE: return "mobile";
            case Phone.TYPE_HOME: return "home";
            case Phone.TYPE_WORK: return "work";
            default: return "other";
        }
    }

    public static LinkedHashMap<String, Integer> snapshot(Context c, int limit) {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        if (!canRead(c)) return out;
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(Phone.CONTENT_URI,
                    new String[]{Phone.DISPLAY_NAME}, null, null, Phone.DISPLAY_NAME + " ASC");
            if (cur == null) return out;
            while (cur.moveToNext() && out.size() < limit) {
                String n = cur.getString(0);
                if (TextUtils.isEmpty(n)) n = "(无名)";
                Integer v = out.get(n);
                out.put(n, v == null ? 1 : v + 1);
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) try { cur.close(); } catch (Throwable ignored) { }
        }
        return out;
    }
}
