package com.zqnt.sdk.client.testing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;

/**
 * A throwaway self-signed certificate for {@code localhost}, made with the JDK's keytool at test
 * time and written as the three PEM files an installation mounts ({@code tls.crt}, {@code tls.key}
 * in PKCS#8, {@code ca.crt}): a server that is its own CA. Nothing is committed to the repository.
 */
public record TestPki(Path dir, Path cert, Path key, Path ca) {

	private static final String STORE_PASSWORD = "changeit";

	public static TestPki generate(Path dir) throws Exception {
		Path keystore = dir.resolve("server.p12");
		String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
		Process process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "server", "-keyalg", "EC",
				"-groupname", "secp256r1", "-sigalg", "SHA256withECDSA", "-dname", "CN=localhost", "-validity", "1",
				"-ext", "SAN=dns:localhost,ip:127.0.0.1", "-ext", "BC=ca:true", "-ext", "KU=digitalSignature,keyCertSign",
				"-ext", "EKU=serverAuth,clientAuth", "-keystore", keystore.toString(), "-storetype", "PKCS12",
				"-storepass", STORE_PASSWORD).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes());
		if (process.waitFor() != 0) {
			throw new IllegalStateException("keytool failed: " + output);
		}
		KeyStore store = KeyStore.getInstance("PKCS12");
		try (InputStream in = Files.newInputStream(keystore)) {
			store.load(in, STORE_PASSWORD.toCharArray());
		}
		Certificate certificate = store.getCertificate("server");
		Key privateKey = store.getKey("server", STORE_PASSWORD.toCharArray());
		Path cert = write(dir.resolve("tls.crt"), "CERTIFICATE", certificate.getEncoded());
		Path key = write(dir.resolve("tls.key"), "PRIVATE KEY", privateKey.getEncoded());
		Path ca = write(dir.resolve("ca.crt"), "CERTIFICATE", certificate.getEncoded());
		return new TestPki(dir, cert, key, ca);
	}

	private static Path write(Path file, String type, byte[] der) throws IOException {
		String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
		Files.writeString(file, "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n");
		return file;
	}
}
