package com.forgeide.app.git;

import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Device-bound AES-GCM key in Android Keystore. Never saved to projects, terminal environments or logs. */
public final class CredentialVault {
    private static final String ALIAS="forge.github.v1";private final Context context;
    public CredentialVault(Context context){this.context=context.getApplicationContext();}
    private SecretKey key()throws Exception{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(store.containsAlias(ALIAS))return (SecretKey)store.getKey(ALIAS,null);KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());return generator.generateKey();}
    public synchronized void store(String token)throws Exception{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());byte[] encrypted=c.doFinal(token.getBytes(StandardCharsets.UTF_8));String value=Base64.encodeToString(c.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(encrypted,Base64.NO_WRAP);if(!context.getSharedPreferences("credential-vault",Context.MODE_PRIVATE).edit().putString("github",value).commit())throw new java.io.IOException("Credential could not be stored");}
    public synchronized String read()throws Exception{String value=context.getSharedPreferences("credential-vault",0).getString("github","");if(value.isEmpty())return "";String[] parts=value.split(":",2);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));return new String(c.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);}
    public boolean connected(){return context.getSharedPreferences("credential-vault",0).contains("github");}
    public synchronized void clear()throws Exception{context.getSharedPreferences("credential-vault",0).edit().clear().commit();KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(store.containsAlias(ALIAS))store.deleteEntry(ALIAS);}
}
