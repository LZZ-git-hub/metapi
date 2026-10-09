package com.nexacheckin;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;

final class Vault {
    private final AtomicFile file;
    private static final String ALIAS = "nexa-accounts-v1";
    Vault(Context context) { this(context, "accounts.enc"); }
    Vault(Context context, String filename) { file = new AtomicFile(new File(context.getNoBackupFilesDir(), filename)); }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            return generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
    List<Account> load() throws Exception {
        List<Account> accounts = new ArrayList<>();
        JSONArray values = loadValues();
        boolean[] slots = new boolean[10];
        for (int i = 0; i < values.length(); i++) {
            Account account = Account.from(values.getJSONObject(i));
            if (slots[account.slot]) throw new Exception("Duplicate slot");
            slots[account.slot] = true; accounts.add(account);
        }
        return accounts;
    }
    void save(List<Account> accounts) throws Exception {
        JSONArray values = new JSONArray();
        for (Account account : accounts) values.put(account.json());
        saveValues(values);
    }
    JSONArray loadValues() throws Exception {
        byte[] blob;
        try { blob = file.readFully(); }
        catch (java.io.FileNotFoundException e) {
            if (file.getBaseFile().exists() || new File(file.getBaseFile().getPath() + ".bak").exists()) throw e;
            return new JSONArray();
        }
        if (blob.length < 29 || blob[0] != 1) throw new Exception("Invalid vault");
        byte[] iv = new byte[12]; System.arraycopy(blob, 1, iv, 0, 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return new JSONArray(new String(cipher.doFinal(blob, 13, blob.length - 13), StandardCharsets.UTF_8));
    }
    void saveValues(JSONArray values) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(values.toString().getBytes(StandardCharsets.UTF_8));
        byte[] blob = ByteBuffer.allocate(13 + encrypted.length).put((byte) 1).put(cipher.getIV()).put(encrypted).array();
        FileOutputStream stream = null;
        try { stream = file.startWrite(); stream.write(blob); file.finishWrite(stream); }
        catch (Exception e) { if (stream != null) file.failWrite(stream); throw e; }
    }
}
