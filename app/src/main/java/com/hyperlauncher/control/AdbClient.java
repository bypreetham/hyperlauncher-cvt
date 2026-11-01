package com.hyperlauncher.control;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Minimal ADB client that talks to this TV's own adbd on 127.0.0.1 (plaintext "Network debugging", default
 * port 5555). It gives the Control Center the shell rights it needs for {@code pm disable-user},
 * {@code pm uninstall --user 0}, {@code top} and friends, with no PC and no root. The first connection makes
 * the TV show its "Allow USB debugging?" dialog; tick "Always allow" and the saved key is accepted from then on.
 */
public final class AdbClient {
    private static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f,
            A_OKAY = 0x59414b4f, A_CLSE = 0x45534c43, A_WRTE = 0x45545257;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    private static final int MAX_DATA = 4096;
    /** DER DigestInfo prefix for SHA-1; adbd checks the 20-byte token as if it were already a SHA-1 digest. */
    private static final byte[] SHA1_PREFIX = hex("3021300906052b0e03021a05000414");

    private static AdbClient instance;

    private final File keyDir;
    private final Object lock = new Object();
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private int nextLocalId = 1;
    private KeyPair keys;
    private String host = "127.0.0.1";
    private int port = 5555;

    private AdbClient(Context context) {
        keyDir = context.getFilesDir();
    }

    public static synchronized AdbClient get(Context context) {
        if (instance == null) instance = new AdbClient(context.getApplicationContext());
        return instance;
    }

    public void setTarget(String host, int port) {
        synchronized (lock) {
            if (!host.equals(this.host) || port != this.port) close();
            this.host = host;
            this.port = port;
        }
    }

    public boolean isConnected() {
        synchronized (lock) {
            return socket != null && socket.isConnected() && !socket.isClosed();
        }
    }

    /** Opens the connection (blocking, so off the main thread). Waits up to 30 s for the on-TV approval. */
    public void connect() throws IOException {
        synchronized (lock) {
            if (isConnected()) return;
            close();
            try {
                loadKeys();
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 3000);
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(30000);
                in = socket.getInputStream();
                out = socket.getOutputStream();
                send(A_CNXN, 0x01000000, MAX_DATA, "host::HyperLauncher\0".getBytes(StandardCharsets.UTF_8));
                boolean sentSignature = false;
                for (int i = 0; i < 6; i++) {
                    Message m = read();
                    if (m.cmd == A_CNXN) {
                        socket.setSoTimeout(15000);
                        return;
                    }
                    if (m.cmd != A_AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("Unexpected ADB reply");
                    if (!sentSignature) {
                        send(A_AUTH, AUTH_SIGNATURE, 0, sign(m.data));
                        sentSignature = true;
                    } else {
                        send(A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBlob());
                    }
                }
                throw new IOException("TV did not accept the connection");
            } catch (IOException | RuntimeException e) {
                close();
                throw e instanceof IOException ? (IOException) e : new IOException(e);
            }
        }
    }

    public void close() {
        synchronized (lock) {
            try {
                if (socket != null) socket.close();
            } catch (IOException ignored) {
            }
            socket = null;
            in = null;
            out = null;
        }
    }

    /** Runs one shell command and returns its output. Reconnects once if the link went stale. */
    public String shell(String command) throws IOException {
        synchronized (lock) {
            try {
                connect();
                return shellOnce(command);
            } catch (IOException first) {
                close();
                connect();
                return shellOnce(command);
            }
        }
    }

    private String shellOnce(String command) throws IOException {
        int local = nextLocalId++;
        send(A_OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int remote = 0;
        while (true) {
            Message m = read();
            if (m.arg1 != local) continue;
            if (m.cmd == A_OKAY) {
                remote = m.arg0;
            } else if (m.cmd == A_WRTE) {
                remote = m.arg0;
                result.write(m.data, 0, m.data.length);
                send(A_OKAY, local, remote, new byte[0]);
            } else if (m.cmd == A_CLSE) {
                if (remote == 0) throw new IOException("ADB refused: " + command);
                return result.toString("UTF-8");
            }
        }
    }

    // ---- wire format ----

    private static final class Message {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        ByteBuffer header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(cmd ^ 0xffffffff);
        out.write(header.array());
        if (data.length > 0) out.write(data);
        out.flush();
    }

    private Message read() throws IOException {
        byte[] raw = new byte[24];
        new DataInputStream(in).readFully(raw);
        ByteBuffer header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.cmd = header.getInt();
        m.arg0 = header.getInt();
        m.arg1 = header.getInt();
        int length = header.getInt();
        if (length < 0 || length > 1 << 20) throw new IOException("Bad ADB packet");
        m.data = new byte[length];
        if (length > 0) new DataInputStream(in).readFully(m.data);
        return m;
    }

    // ---- keys ----

    private void loadKeys() throws IOException {
        if (keys != null) return;
        File priv = new File(keyDir, "adbkey.pk8");
        File pub = new File(keyDir, "adbkey.x509");
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            if (priv.exists() && pub.exists()) {
                keys = new KeyPair(
                        factory.generatePublic(new X509EncodedKeySpec(readAll(pub))),
                        factory.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv))));
                return;
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            keys = generator.generateKeyPair();
            try (FileOutputStream o = new FileOutputStream(priv)) { o.write(keys.getPrivate().getEncoded()); }
            try (FileOutputStream o = new FileOutputStream(pub)) { o.write(keys.getPublic().getEncoded()); }
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private static byte[] readAll(File f) throws IOException {
        byte[] b = new byte[(int) f.length()];
        try (DataInputStream i = new DataInputStream(new FileInputStream(f))) { i.readFully(b); }
        return b;
    }

    private byte[] sign(byte[] token) throws IOException {
        try {
            Signature s = Signature.getInstance("NONEwithRSA");
            s.initSign(keys.getPrivate());
            s.update(SHA1_PREFIX);
            s.update(token);
            return s.sign();
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    /** The public key in Android's own layout (adb's RSAPublicKey struct), base64, NUL-terminated. */
    private byte[] publicKeyBlob() {
        RSAPublicKey key = (RSAPublicKey) keys.getPublic();
        BigInteger n = key.getModulus();
        final int words = 64;
        BigInteger two32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = two32.subtract(n.mod(two32).modInverse(two32));
        BigInteger rr = BigInteger.ONE.shiftLeft(2048).pow(2).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 4 * words + 4 * words + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt(n0inv.intValue());
        putWords(b, n, words);
        putWords(b, rr, words);
        b.putInt(key.getPublicExponent().intValue());
        byte[] encoded = Base64.encode(b.array(), Base64.NO_WRAP);
        byte[] suffix = " HyperLauncher@tv\0".getBytes(StandardCharsets.UTF_8);
        byte[] blob = new byte[encoded.length + suffix.length];
        System.arraycopy(encoded, 0, blob, 0, encoded.length);
        System.arraycopy(suffix, 0, blob, encoded.length, suffix.length);
        return blob;
    }

    private static void putWords(ByteBuffer b, BigInteger v, int words) {
        BigInteger mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        for (int i = 0; i < words; i++) b.putInt(v.shiftRight(32 * i).and(mask).intValue());
    }

    private static byte[] hex(String s) {
        byte[] r = new byte[s.length() / 2];
        for (int i = 0; i < r.length; i++) r[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return r;
    }
}
